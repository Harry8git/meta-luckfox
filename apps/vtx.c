/*
 * Dedicated Low-Latency H.265 V4L2 -> MPP Encoder -> USB CDC Streamer (VTX)
 * Open-Source Rockchip MPP + V4L2 DMA-BUF Zero-Copy Pipeline
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <linux/videodev2.h>

#include <rockchip/rk_mpi.h>
#include <rockchip/rk_mpi_cmd.h>
#include <rockchip/rk_venc_cmd.h>
#include <rockchip/rk_venc_rc.h>
#include <rockchip/rk_venc_cfg.h>
#include <rockchip/mpp_buffer.h>
#include <rockchip/mpp_frame.h>
#include <rockchip/mpp_packet.h>
#include <rockchip/rk_mpp_cfg.h>

#define MAX_V4L2_BUFFERS   4
#define MPP_ALIGN(x, a)    (((x) + (a) - 1) & ~((a) - 1))

typedef struct {
    char        v4l2_dev[64];
    char        cdc_dev[64];
    uint32_t    width;
    uint32_t    height;
    uint32_t    fps;
    uint32_t    bitrate_kbps;
    uint32_t    gop;
} VtxConfig;

typedef struct {
    int         dma_fd;
    size_t      length;
    MppBuffer   mpp_buf;
} CamBuffer;

typedef struct {
    VtxConfig   cfg;
    int         v4l2_fd;
    uint32_t    buf_type;
    CamBuffer   buffers[MAX_V4L2_BUFFERS];
    uint32_t    buf_count;

    MppCtx      mpp_ctx;
    MppApi     *mpi;
    MppEncCfg   enc_cfg;

    uint8_t     hdr_buf[512];
    size_t      hdr_len;

    int         cdc_fd;
    pthread_t   tx_thd;
} VtxContext;

static volatile bool quit = false;

static void sigterm_handler(int sig) {
    (void)sig;
    quit = true;
}

static int set_serial_raw(int fd) {
    struct termios tty;
    if (tcgetattr(fd, &tty) != 0) {
        return 0; /* Not a tty (e.g. regular file) */
    }
    cfmakeraw(&tty);
    tty.c_iflag &= ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON | IXOFF);
    tty.c_oflag &= ~(OPOST | ONLCR | OCRNL | ONOCR | ONLRET);
    tty.c_lflag &= ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
    tty.c_cflag |= (CS8 | CLOCAL | CREAD);
    tty.c_cflag &= ~(PARENB | CSTOPB | CRTSCTS);
    tcflush(fd, TCIOFLUSH);
    return tcsetattr(fd, TCSANOW, &tty);
}

static inline int cdc_write_all(int fd, const uint8_t *buf, size_t len) {
    size_t total_written = 0;
    struct pollfd pfd = { .fd = fd, .events = POLLOUT };

    while (total_written < len && !quit) {
        int ret = poll(&pfd, 1, 20);
        if (ret < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (ret == 0) continue;

        ssize_t n = write(fd, buf + total_written, len - total_written);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue;
            return -1;
        }
        total_written += (size_t)n;
    }
    return (int)total_written;
}

static int xioctl(int fh, int request, void *arg) {
    int r;
    do {
        r = ioctl(fh, request, arg);
    } while (r == -1 && errno == EINTR);
    return r;
}

static int v4l2_init(VtxContext *ctx) {
    ctx->v4l2_fd = open(ctx->cfg.v4l2_dev, O_RDWR | O_NONBLOCK, 0);
    if (ctx->v4l2_fd < 0) {
        fprintf(stderr, "ERROR: Cannot open V4L2 device '%s': %s\n", ctx->cfg.v4l2_dev, strerror(errno));
        return -1;
    }

    struct v4l2_capability cap;
    if (xioctl(ctx->v4l2_fd, VIDIOC_QUERYCAP, &cap) < 0) {
        perror("VIDIOC_QUERYCAP");
        return -1;
    }

    if (cap.capabilities & V4L2_CAP_VIDEO_CAPTURE_MPLANE) {
        ctx->buf_type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
    } else {
        ctx->buf_type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    }

    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = ctx->buf_type;

    if (ctx->buf_type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
        fmt.fmt.pix_mp.width = ctx->cfg.width;
        fmt.fmt.pix_mp.height = ctx->cfg.height;
        fmt.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_NV12;
        fmt.fmt.pix_mp.field = V4L2_FIELD_NONE;
        fmt.fmt.pix_mp.num_planes = 1;
    } else {
        fmt.fmt.pix.width = ctx->cfg.width;
        fmt.fmt.pix.height = ctx->cfg.height;
        fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_NV12;
        fmt.fmt.pix.field = V4L2_FIELD_NONE;
    }

    if (xioctl(ctx->v4l2_fd, VIDIOC_S_FMT, &fmt) < 0) {
        fprintf(stderr, "ERROR: VIDIOC_S_FMT failed on %s: %s\n", ctx->cfg.v4l2_dev, strerror(errno));
        return -1;
    }

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = MAX_V4L2_BUFFERS;
    req.type = ctx->buf_type;
    req.memory = V4L2_MEMORY_MMAP;

    if (xioctl(ctx->v4l2_fd, VIDIOC_REQBUFS, &req) < 0 || req.count == 0) {
        fprintf(stderr, "ERROR: VIDIOC_REQBUFS failed: %s\n", strerror(errno));
        return -1;
    }
    ctx->buf_count = req.count;

    for (uint32_t i = 0; i < ctx->buf_count; ++i) {
        struct v4l2_buffer buf;
        struct v4l2_plane planes[1];
        memset(&buf, 0, sizeof(buf));
        memset(planes, 0, sizeof(planes));

        buf.type = ctx->buf_type;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (ctx->buf_type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
            buf.m.planes = planes;
            buf.length = 1;
        }

        if (xioctl(ctx->v4l2_fd, VIDIOC_QUERYBUF, &buf) < 0) {
            fprintf(stderr, "ERROR: VIDIOC_QUERYBUF failed: %s\n", strerror(errno));
            return -1;
        }

        size_t buf_len = (ctx->buf_type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) ? planes[0].length : buf.length;
        ctx->buffers[i].length = buf_len;

        struct v4l2_exportbuffer expbuf;
        memset(&expbuf, 0, sizeof(expbuf));
        expbuf.type = ctx->buf_type;
        expbuf.index = i;
        expbuf.flags = O_CLOEXEC | O_RDWR;
        if (xioctl(ctx->v4l2_fd, VIDIOC_EXPBUF, &expbuf) < 0) {
            perror("VIDIOC_EXPBUF");
            return -1;
        }
        ctx->buffers[i].dma_fd = expbuf.fd;

        MppBufferInfo info;
        memset(&info, 0, sizeof(info));
        info.type = MPP_BUFFER_TYPE_EXT_DMA;
        info.fd = expbuf.fd;
        info.size = buf_len;
        info.index = i;

        MPP_RET ret = mpp_buffer_import(&ctx->buffers[i].mpp_buf, &info);
        if (ret != MPP_OK) {
            fprintf(stderr, "ERROR: mpp_buffer_import failed: %d\n", ret);
            return -1;
        }

        if (xioctl(ctx->v4l2_fd, VIDIOC_QBUF, &buf) < 0) {
            perror("VIDIOC_QBUF");
            return -1;
        }
    }

    enum v4l2_buf_type type = ctx->buf_type;
    if (xioctl(ctx->v4l2_fd, VIDIOC_STREAMON, &type) < 0) {
        perror("VIDIOC_STREAMON");
        return -1;
    }

    return 0;
}

static int mpp_enc_setup(VtxContext *ctx) {
    MPP_RET ret = mpp_create(&ctx->mpp_ctx, &ctx->mpi);
    if (ret != MPP_OK) {
        fprintf(stderr, "ERROR: mpp_create failed: %d\n", ret);
        return -1;
    }

    ret = mpp_init(ctx->mpp_ctx, MPP_CTX_ENC, MPP_VIDEO_CodingHEVC);
    if (ret != MPP_OK) {
        fprintf(stderr, "ERROR: mpp_init failed: %d\n", ret);
        return -1;
    }

    ret = mpp_enc_cfg_init(&ctx->enc_cfg);
    if (ret != MPP_OK) {
        fprintf(stderr, "ERROR: mpp_enc_cfg_init failed\n");
        return -1;
    }

    uint32_t width = ctx->cfg.width;
    uint32_t height = ctx->cfg.height;
    uint32_t hor_stride = MPP_ALIGN(width, 16);
    uint32_t ver_stride = MPP_ALIGN(height, 16);

    /* Resolution and Stride */
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "prep:width", width);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "prep:height", height);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "prep:hor_stride", hor_stride);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "prep:ver_stride", ver_stride);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "prep:format", MPP_FMT_YUV420SP);

    /* Rate Control */
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:mode", MPP_ENC_RC_MODE_CBR);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:bps_target", ctx->cfg.bitrate_kbps * 1000);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:bps_max", ctx->cfg.bitrate_kbps * 1000);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:bps_min", ctx->cfg.bitrate_kbps * 1000 * 8 / 10);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:fps_in_flex", 0);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:fps_in_num", ctx->cfg.fps);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:fps_in_denorm", 1);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:fps_out_flex", 0);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:fps_out_num", ctx->cfg.fps);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:fps_out_denorm", 1);
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "rc:gop", ctx->cfg.gop);

    /* Codec Type */
    mpp_enc_cfg_set_s32(ctx->enc_cfg, "codec:type", MPP_VIDEO_CodingHEVC);

    ret = ctx->mpi->control(ctx->mpp_ctx, MPP_ENC_SET_CFG, ctx->enc_cfg);
    if (ret != MPP_OK) {
        fprintf(stderr, "ERROR: MPP_ENC_SET_CFG failed: %d\n", ret);
        return -1;
    }

    /* Extract H.265 Parameter Sets (VPS/SPS/PPS) with MPP_ENC_GET_EXTRA_INFO */
    MppPacket extra_pkt = NULL;
    ret = ctx->mpi->control(ctx->mpp_ctx, MPP_ENC_GET_EXTRA_INFO, &extra_pkt);
    if (ret == MPP_OK && extra_pkt) {
        void *ptr = mpp_packet_get_pos(extra_pkt);
        size_t len = mpp_packet_get_length(extra_pkt);
        if (ptr && len > 0 && len <= sizeof(ctx->hdr_buf)) {
            memcpy(ctx->hdr_buf, ptr, len);
            ctx->hdr_len = len;
            printf(">>> SUCCESS: Extracted H.265 Parameter Headers (VPS/SPS/PPS: %zu bytes) <<<\n", ctx->hdr_len);
        }
        mpp_packet_deinit(&extra_pkt);
    } else {
        fprintf(stderr, "WARNING: MPP_ENC_GET_EXTRA_INFO returned %d\n", ret);
    }

    RK_S32 timeout = 100;
    ctx->mpi->control(ctx->mpp_ctx, MPP_SET_INPUT_TIMEOUT, &timeout);
    ctx->mpi->control(ctx->mpp_ctx, MPP_SET_OUTPUT_TIMEOUT, &timeout);

    return 0;
}

static void *stream_tx_loop(void *arg) {
    VtxContext *ctx = (VtxContext *)arg;
    prctl(PR_SET_NAME, "vtx_tx", 0, 0, 0);

    ctx->cdc_fd = open(ctx->cfg.cdc_dev, O_WRONLY | O_CREAT | O_TRUNC | O_NONBLOCK | O_NOCTTY, 0666);
    if (ctx->cdc_fd < 0) {
        fprintf(stderr, "ERROR: Cannot open output '%s': %s\n", ctx->cfg.cdc_dev, strerror(errno));
        quit = true;
        return NULL;
    }
    set_serial_raw(ctx->cdc_fd);

    printf(">>> VTX ACTIVE: Streaming Zero-Copy H.265 to %s <<<\n", ctx->cfg.cdc_dev);

    uint32_t frame_cnt = 0;
    while (!quit) {
        struct pollfd pfd = { .fd = ctx->v4l2_fd, .events = POLLIN };
        int poll_ret = poll(&pfd, 1, 40);
        if (poll_ret <= 0) continue;

        struct v4l2_buffer buf;
        struct v4l2_plane planes[1];
        memset(&buf, 0, sizeof(buf));
        memset(planes, 0, sizeof(planes));

        buf.type = ctx->buf_type;
        buf.memory = V4L2_MEMORY_MMAP;
        if (ctx->buf_type == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
            buf.m.planes = planes;
            buf.length = 1;
        }

        if (xioctl(ctx->v4l2_fd, VIDIOC_DQBUF, &buf) < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                perror("VIDIOC_DQBUF");
            }
            usleep(1000);
            continue;
        }

        uint32_t idx = buf.index;
        MppFrame frame = NULL;
        MppPacket packet = NULL;

        mpp_frame_init(&frame);
        mpp_frame_set_width(frame, ctx->cfg.width);
        mpp_frame_set_height(frame, ctx->cfg.height);
        mpp_frame_set_hor_stride(frame, MPP_ALIGN(ctx->cfg.width, 16));
        mpp_frame_set_ver_stride(frame, MPP_ALIGN(ctx->cfg.height, 16));
        mpp_frame_set_fmt(frame, MPP_FMT_YUV420SP);
        mpp_frame_set_buffer(frame, ctx->buffers[idx].mpp_buf);

        /* Encode Frame */
        MPP_RET ret = ctx->mpi->encode_put_frame(ctx->mpp_ctx, frame);
        if (ret == MPP_OK) {
            ret = ctx->mpi->encode_get_packet(ctx->mpp_ctx, &packet);
            if (ret == MPP_OK && packet) {
                void *ptr = mpp_packet_get_pos(packet);
                size_t len = mpp_packet_get_length(packet);

                if (ptr && len > 0) {
                    /* Prepend VPS/SPS/PPS header at the start and on every keyframe (GOP) */
                    if ((frame_cnt++ % ctx->cfg.gop == 0) && ctx->hdr_len > 0) {
                        cdc_write_all(ctx->cdc_fd, ctx->hdr_buf, ctx->hdr_len);
                    }
                    cdc_write_all(ctx->cdc_fd, (const uint8_t *)ptr, len);
                }
                mpp_packet_deinit(&packet);
            } else if (ret != MPP_OK) {
                usleep(1000);
            }
        } else if (ret != MPP_OK) {
            usleep(1000);
        }

        if (frame) mpp_frame_deinit(&frame);

        /* Return buffer to V4L2 capture queue */
        if (xioctl(ctx->v4l2_fd, VIDIOC_QBUF, &buf) < 0) {
            perror("VIDIOC_QBUF");
            usleep(1000);
        }
    }

    close(ctx->cdc_fd);
    return NULL;
}

int main(int argc, char **argv) {
    VtxContext ctx;
    memset(&ctx, 0, sizeof(ctx));

    /* Default Configuration */
    strncpy(ctx.cfg.v4l2_dev, "/dev/video11", sizeof(ctx.cfg.v4l2_dev) - 1);
    strncpy(ctx.cfg.cdc_dev, "/dev/ttyGS0", sizeof(ctx.cfg.cdc_dev) - 1);
    ctx.cfg.width = 1280;
    ctx.cfg.height = 720;
    ctx.cfg.fps = 60;
    ctx.cfg.bitrate_kbps = 2400;
    ctx.cfg.gop = 60;

    static struct option long_opts[] = {
        {"device",  required_argument, 0, 'd'},
        {"output",  required_argument, 0, 'o'},
        {"width",   required_argument, 0, 'w'},
        {"height",  required_argument, 0, 'h'},
        {"fps",     required_argument, 0, 'f'},
        {"bitrate", required_argument, 0, 'b'},
        {"gop",     required_argument, 0, 'g'},
        {"help",    no_argument,       0, '?'},
        {0, 0, 0, 0}
    };

    int opt;
    while ((opt = getopt_long(argc, argv, "d:o:w:h:f:b:g:?", long_opts, NULL)) != -1) {
        switch (opt) {
            case 'd': strncpy(ctx.cfg.v4l2_dev, optarg, sizeof(ctx.cfg.v4l2_dev) - 1); break;
            case 'o': strncpy(ctx.cfg.cdc_dev, optarg, sizeof(ctx.cfg.cdc_dev) - 1); break;
            case 'w': ctx.cfg.width = (uint32_t)atoi(optarg); break;
            case 'h': ctx.cfg.height = (uint32_t)atoi(optarg); break;
            case 'f': ctx.cfg.fps = (uint32_t)atoi(optarg); break;
            case 'b': ctx.cfg.bitrate_kbps = (uint32_t)atoi(optarg); break;
            case 'g': ctx.cfg.gop = (uint32_t)atoi(optarg); break;
            default:
                printf("Usage: %s [-d /dev/video11] [-o /dev/ttyGS0] [-w 1280] [-h 720] [-f 60] [-b 2400] [-g 60]\n", argv[0]);
                return 0;
        }
    }

    signal(SIGINT, sigterm_handler);
    signal(SIGTERM, sigterm_handler);

    printf("\n=======================================================\n");
    printf(" Luckfox Pico - Open MPP Zero-Copy H.265 VTX\n");
    printf(" Ingest       : %s (%ux%u @ %ufps)\n", ctx.cfg.v4l2_dev, ctx.cfg.width, ctx.cfg.height, ctx.cfg.fps);
    printf(" Video Codec  : H.265 CBR (%u Kbps) [GOP %u]\n", ctx.cfg.bitrate_kbps, ctx.cfg.gop);
    printf(" Output Node  : %s\n", ctx.cfg.cdc_dev);
    printf("=======================================================\n\n");

    if (v4l2_init(&ctx) < 0) {
        fprintf(stderr, "Failed to initialize V4L2 ingest\n");
        return -1;
    }

    if (mpp_enc_setup(&ctx) < 0) {
        fprintf(stderr, "Failed to setup MPP encoder\n");
        return -1;
    }

    pthread_create(&ctx.tx_thd, NULL, stream_tx_loop, &ctx);

    while (!quit) {
        sleep(1);
    }

    pthread_join(ctx.tx_thd, NULL);

    /* Teardown */
    enum v4l2_buf_type type = ctx.buf_type;
    xioctl(ctx.v4l2_fd, VIDIOC_STREAMOFF, &type);

    for (uint32_t i = 0; i < ctx.buf_count; ++i) {
        if (ctx.buffers[i].mpp_buf) mpp_buffer_put(ctx.buffers[i].mpp_buf);
        if (ctx.buffers[i].dma_fd >= 0) close(ctx.buffers[i].dma_fd);
    }
    close(ctx.v4l2_fd);

    if (ctx.enc_cfg) mpp_enc_cfg_deinit(ctx.enc_cfg);
    if (ctx.mpp_ctx) mpp_destroy(ctx.mpp_ctx);

    printf("VTX cleanly stopped.\n");
    return 0;
}
