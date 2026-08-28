/*
 * Dedicated Low-Latency H.265 720p60 USB CDC Streamer (VTX)
 * Pipeline: Sony IMX462 (1080p60) -> Direct VI (720p Scaler) -> VENC (H.265 CBR+GIR) -> USB CDC (/dev/ttyGS0)
 */

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#include "rk_debug.h"
#include "rk_defines.h"
#include "rk_mpi_mb.h"
#include "rk_mpi_sys.h"
#include "rk_mpi_venc.h"
#include "rk_mpi_vi.h"
#include "sample_comm.h"

#define VTX_WIDTH          1280
#define VTX_HEIGHT         720
#define VTX_FPS            60
#define VTX_BITRATE_KBPS   2400        /* 2.4 Mbps CBR */
#define VTX_CDC_DEV        "/dev/ttyGS0"
#define VTX_IQ_DIR         "/etc/iqfiles"
#define MAX_PACK_COUNT     8

static volatile bool quit = false;

static void sigterm_handler(int sig) {
	fprintf(stderr, "\nSignal %d received, stopping VTX...\n", sig);
	quit = true;
}

/* Configure serial port to 100% raw binary mode (No 0x0A -> 0x0D 0x0A corruption) */
static int set_serial_raw(int fd) {
	struct termios tty;
	if (tcgetattr(fd, &tty) != 0) {
		perror("tcgetattr");
		return -1;
	}

	cfmakeraw(&tty);

	/* Explicitly disable all text processing, newlines, and XON/XOFF flow control */
	tty.c_iflag &= ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON | IXOFF);
	tty.c_oflag &= ~(OPOST | ONLCR | OCRNL | ONOCR | ONLRET);
	tty.c_lflag &= ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
	tty.c_cflag |= (CS8 | CLOCAL | CREAD);
	tty.c_cflag &= ~(PARENB | CSTOPB | CRTSCTS);

	tcflush(fd, TCIOFLUSH);
	if (tcsetattr(fd, TCSANOW, &tty) != 0) {
		perror("tcsetattr");
		return -1;
	}
	return 0;
}

/* Fast write with backpressure handling */
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

/* ----------------------------------------------------------------------------
 * Video Transmission Thread
 * ---------------------------------------------------------------------------- */
static void *venc_cdc_stream_thread(void *arg) {
	(void)arg;
	prctl(PR_SET_NAME, "vtx_cdc_tx", 0, 0, 0);

	VENC_STREAM_S stFrame;
	memset(&stFrame, 0, sizeof(VENC_STREAM_S));
	stFrame.pstPack = malloc(sizeof(VENC_PACK_S) * MAX_PACK_COUNT);
	if (!stFrame.pstPack) {
		RK_LOGE("Failed to allocate VENC_PACK_S");
		quit = true;
		return NULL;
	}

	int cdc_fd = open(VTX_CDC_DEV, O_WRONLY | O_NONBLOCK | O_NOCTTY);
	if (cdc_fd < 0) {
		fprintf(stderr, "ERROR: Could not open %s (%s). Check if USB Serial gadget is active.\n",
		        VTX_CDC_DEV, strerror(errno));
		quit = true;
		free(stFrame.pstPack);
		return NULL;
	}

	/* Put /dev/ttyGS0 into raw binary mode */
	if (set_serial_raw(cdc_fd) < 0) {
		RK_LOGW("Failed to set raw mode on %s", VTX_CDC_DEV);
	}

	printf(">>> VTX ACTIVE: Streaming RAW Binary H.265 to %s <<<\n", VTX_CDC_DEV);

	while (!quit) {
		stFrame.u32PackCount = MAX_PACK_COUNT;

		RK_S32 s32Ret = RK_MPI_VENC_GetStream(0, &stFrame, 40);
		if (s32Ret == RK_SUCCESS) {
			for (RK_U32 i = 0; i < stFrame.u32PackCount; i++) {
				void *pData = RK_MPI_MB_Handle2VirAddr(stFrame.pstPack[i].pMbBlk);
				uint32_t len = stFrame.pstPack[i].u32Len;

				if (pData && len > 0) {
					cdc_write_all(cdc_fd, (const uint8_t *)pData, len);
				}
			}
			RK_MPI_VENC_ReleaseStream(0, &stFrame);
		} else {
			usleep(1000);
		}
	}

	close(cdc_fd);
	free(stFrame.pstPack);
	return NULL;
}

/* ----------------------------------------------------------------------------
 * VI / VENC Hardware Setup
 * ---------------------------------------------------------------------------- */
static int vi_init(int channelId, int width, int height) {
	SAMPLE_VI_CTX_S vi_ctx;
	memset(&vi_ctx, 0, sizeof(vi_ctx));

	vi_ctx.u32Width = width;
	vi_ctx.u32Height = height;
	vi_ctx.s32DevId = 0;
	vi_ctx.u32PipeId = 0;
	vi_ctx.s32ChnId = channelId;
	vi_ctx.stChnAttr.stIspOpt.u32BufCount = 2;
	vi_ctx.stChnAttr.stIspOpt.enMemoryType = VI_V4L2_MEMORY_TYPE_DMABUF;
	vi_ctx.stChnAttr.u32Depth = 0;
	vi_ctx.stChnAttr.enPixelFormat = RK_FMT_YUV420SP;
	vi_ctx.stChnAttr.enCompressMode = COMPRESS_MODE_NONE;
	vi_ctx.stChnAttr.stFrameRate.s32SrcFrameRate = -1;
	vi_ctx.stChnAttr.stFrameRate.s32DstFrameRate = -1;

	return SAMPLE_COMM_VI_CreateChn(&vi_ctx);
}

static int venc_init(int chnId, int width, int height) {
	VENC_CHN_ATTR_S stAttr;
	memset(&stAttr, 0, sizeof(VENC_CHN_ATTR_S));

	/* Clean H.265 CBR Rate Control */
	stAttr.stRcAttr.enRcMode = VENC_RC_MODE_H265CBR;
	stAttr.stRcAttr.stH265Cbr.u32BitRate = VTX_BITRATE_KBPS;
	stAttr.stRcAttr.stH265Cbr.u32Gop = VTX_FPS * 5; /* 5s GOP with GIR */
	stAttr.stRcAttr.stH265Cbr.u32SrcFrameRateNum = VTX_FPS;
	stAttr.stRcAttr.stH265Cbr.u32SrcFrameRateDen = 1;
	stAttr.stRcAttr.stH265Cbr.fr32DstFrameRateNum = VTX_FPS;
	stAttr.stRcAttr.stH265Cbr.fr32DstFrameRateDen = 1;

	stAttr.stVencAttr.enType = RK_VIDEO_ID_HEVC;
	stAttr.stVencAttr.enPixelFormat = RK_FMT_YUV420SP;
	stAttr.stVencAttr.u32Profile = 0; /* Main Profile */
	stAttr.stVencAttr.u32PicWidth = width;
	stAttr.stVencAttr.u32PicHeight = height;
	stAttr.stVencAttr.u32VirWidth = width;
	stAttr.stVencAttr.u32VirHeight = height;
	stAttr.stVencAttr.u32StreamBufCnt = 2;
	stAttr.stVencAttr.u32BufSize = width * height * 3 / 2;
	stAttr.stVencAttr.enMirror = MIRROR_NONE;

	stAttr.stGopAttr.enGopMode = VENC_GOPMODE_NORMALP;

	RK_MPI_VENC_CreateChn(chnId, &stAttr);

	/* Flat Transform */
	VENC_H265_TRANS_S pstH265Trans;
	RK_MPI_VENC_GetH265Trans(chnId, &pstH265Trans);
	pstH265Trans.bScalingListEnabled = 0;
	RK_MPI_VENC_SetH265Trans(chnId, &pstH265Trans);

	/* Gradual Intra Refresh (GIR) */
	VENC_INTRA_REFRESH_S stIntraRefresh;
	memset(&stIntraRefresh, 0, sizeof(VENC_INTRA_REFRESH_S));
	stIntraRefresh.bRefreshEnable     = RK_TRUE;
	stIntraRefresh.enIntraRefreshMode = INTRA_REFRESH_ROW;
	stIntraRefresh.u32RefreshNum      = 2; /* 2 CTU rows/frame */
	stIntraRefresh.u32ReqIQp          = 25;
	RK_MPI_VENC_SetIntraRefresh(chnId, &stIntraRefresh);

	/* Standard Rate Control limits (No non-standard delta QP jumps) */
	VENC_RC_PARAM_S stRcParam;
	memset(&stRcParam, 0, sizeof(VENC_RC_PARAM_S));
	stRcParam.s32FirstFrameStartQp = 26;
	stRcParam.stParamH265.u32StepQp = 4;
	stRcParam.stParamH265.u32MinQp = 18;
	stRcParam.stParamH265.u32MaxQp = 42;
	stRcParam.stParamH265.u32MinIQp = 18;
	stRcParam.stParamH265.u32MaxIQp = 38;
	RK_MPI_VENC_SetRcParam(chnId, &stRcParam);

	VENC_RECV_PIC_PARAM_S stRecvParam;
	memset(&stRecvParam, 0, sizeof(VENC_RECV_PIC_PARAM_S));
	stRecvParam.s32RecvPicNum = -1;
	RK_MPI_VENC_StartRecvFrame(chnId, &stRecvParam);

	return 0;
}

/* ----------------------------------------------------------------------------
 * Main Function
 * ---------------------------------------------------------------------------- */
int main(int argc, char *argv[]) {
	(void)argc;
	(void)argv;

	signal(SIGINT, sigterm_handler);
	signal(SIGTERM, sigterm_handler);

	printf("\n=======================================================\n");
	printf(" Luckfox Pico - Low-Latency Direct USB CDC H.265 VTX\n");
	printf(" Ingest       : Sony IMX462 (ISP 720p60 Scaling)\n");
	printf(" Video Codec  : H.265 CBR (%u Kbps) + GIR Active\n", VTX_BITRATE_KBPS);
	printf(" Output Node  : %s (USB Serial)\n", VTX_CDC_DEV);
	printf("=======================================================\n\n");

	SAMPLE_COMM_ISP_Init(0, RK_AIQ_WORKING_MODE_NORMAL, RK_FALSE, VTX_IQ_DIR);
	SAMPLE_COMM_ISP_Run(0);

	if (RK_MPI_SYS_Init() != RK_SUCCESS) {
		RK_LOGE("RK_MPI_SYS_Init failed");
		return -1;
	}

	vi_init(0, VTX_WIDTH, VTX_HEIGHT);
	venc_init(0, VTX_WIDTH, VTX_HEIGHT);

	MPP_CHN_S stSrcChn  = { .enModId = RK_ID_VI,   .s32DevId = 0, .s32ChnId = 0 };
	MPP_CHN_S stDestChn = { .enModId = RK_ID_VENC, .s32DevId = 0, .s32ChnId = 0 };
	RK_MPI_SYS_Bind(&stSrcChn, &stDestChn);

	pthread_t cdc_thread;
	pthread_create(&cdc_thread, NULL, venc_cdc_stream_thread, NULL);

	while (!quit) {
		sleep(1);
	}

	pthread_join(cdc_thread, NULL);

	/* Clean Teardown */
	RK_MPI_SYS_UnBind(&stSrcChn, &stDestChn);
	SAMPLE_VI_CTX_S vi_ctx = { .s32DevId = 0, .s32ChnId = 0 };
	SAMPLE_COMM_VI_DestroyChn(&vi_ctx);

	RK_MPI_VENC_StopRecvFrame(0);
	RK_MPI_VENC_DestroyChn(0);
	RK_MPI_VI_DisableDev(0);
	RK_MPI_SYS_Exit();

	SAMPLE_COMM_ISP_Stop(0);

	printf("VTX cleanly terminated.\n");
	return 0;
}


/*

cat /dev/cu.usbmodem103 | mpv - \
  --demuxer-lavf-format=hevc \
  --demuxer-lavf-o=probesize=32,analyzeduration=0,fflags=nobuffer \
  --no-correct-pts \
  --container-fps-override=60 \
  --profile=low-latency \
  --no-cache \
  --untimed \
  --hwdec=no \
  --video-sync=display-desync \
  --opengl-glfinish=yes \
  --framedrop=vo


cat /dev/cu.usbmodem103 | mpv - \
  --demuxer-lavf-format=hevc \
  --demuxer-lavf-o=probesize=32768,analyzeduration=0 \
  --no-correct-pts \
  --container-fps-override=60 \
  --profile=low-latency \
  --no-cache \
  --untimed \
  --framedrop=vo

# Find your device (e.g., /dev/cu.usbmodem1101)
DEVICE=$(ls /dev/cu.usbmodem* | head -n 1)

# Configure true raw binary mode
stty -f $DEVICE raw -echo -onlcr -ocrnl -opost -isig -icanon -ixon -ixoff cs8

cat $DEVICE | ffplay -fflags nobuffer -flags low_delay -f hevc -framerate 60 -i -

*/