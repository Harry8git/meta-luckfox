# meta-luckfox with blob-free video pipeline for RV1106

## Contribution welcome
If you able to add more hardware and test it - PR welcome

## WARNING
1. This layer created mostly by AI
2. This layer not tested for production
3. Developer tested only for luckfox pico pro max

## What is it
This layers provides support for Luckfox Pico Pro Max.  
The aim - abbility to load system, capture and encode video without outdated Luckfox SDK.  

## What is inside
1. `u-boot` - `next-dev` branch from rockchip-linux repository;
2. `kernel` - `develop-6.6` branch from rockchip-linux repository;
3. `librga` - `linux-rga-multi` branch from JeffyCN mirrors repository;
4. `mpp` - `develop` branch from rockchip-linux repository;
5. `rkaiq` - `rkaiq-2025_08_11` version from JeffyCN mirrors repository;
6. `gstreamer-rockchip` - from JeffyCN mirrors repository;
7. `dts` - from luckfox SDK
8. `rootfs-resize` to grow the rootfs partition to fill the SD/eMMC on first boot, and misc `systemd` tweaks.

## How it even possible


## How to test
1. Prepare workspace
```sh
mkdir lfbuild
cd lfbuild
git clone https://github.com/buldo/meta-luckfox.git
git clone -b yocto-6.0.2 https://git.openembedded.org/bitbake 
git clone -b yocto-6.0.2 https://git.openembedded.org/openembedded-core
source ./openembedded-core/oe-init-build-env build
bitbake-layers add-layer ./meta-luckfox/
```

2. Edit `build/conf/local.conf`
```
MACHINE = "luckfox-pico-pro-max"
IMAGE_INSTALL:append = " \
    rockchip-mpp \
    rockchip-mpp-demos \
    rockchip-mpp-vpu \
    luckfox-iqfiles \
    rockchip-librga \
    rockchip-rkaiq \
    rockchip-rkaiq-dev \
    rockchip-rkaiq-server \
    rockchip-camera-init \
    gstreamer1.0 \
    gstreamer1.0-plugins-base-videoconvertscale \
    gstreamer1.0-plugins-good-video4linux2 \
    gstreamer1.0-plugins-good-rtp \
    gstreamer1.0-plugins-good-udp \
    gstreamer1.0-plugins-good-isomp4 \
    gstreamer1.0-plugins-bad-videoparsersbad \
    gstreamer1.0-rockchip-rockchipmpp \
    i2c-tools \
    v4l-utils \
    ethtool \
    iproute2 \
    "

# Upstream default PACKAGECONFIGs for gst-plugins-base/good/bad pull in a
# large, mostly-unused dependency tree (flac, gdk-pixbuf, cairo, pango,
# jpeg, lame, mpg123, speex, taglib, theora, vorbis, ogg, ...). None of it
# is needed for a "v4l2src ! mpph264enc ! rtph264pay ! udpsink" pipeline,
# so trim these down to just orc (SIMD JIT) + v4l2.
PACKAGECONFIG:pn-gstreamer1.0-plugins-base = "${GSTREAMER_ORC}"
PACKAGECONFIG:pn-gstreamer1.0-plugins-good = "${GSTREAMER_ORC} v4l2"
PACKAGECONFIG:pn-gstreamer1.0-plugins-bad = "${GSTREAMER_ORC}"

DISTRO_FEATURES:remove = "sysvinit x11 wayland opengl vulkan directfb"
DISTRO_FEATURES:append = " systemd"
VIRTUAL-RUNTIME_init_manager = "systemd"
VIRTUAL-RUNTIME_initscripts = "systemd-compat-units"
VIRTUAL-RUNTIME_login_manager = "shadow-base"

# systemd-resolved is enabled via recipes-core/systemd/systemd_%.bbappend
IMAGE_INSTALL:append = " systemd-networkd systemd-conf systemd-resolved-enable openssh-sftp-server"
``` 

3. Build system


## Adding this layer to your build

```sh

git clone -b yocto-6.0.2 https://git.openembedded.org/openembedded-core 
git clone -b yocto-6.0.2 https://git.yoctoproject.org/meta-yocto 
git clone -b wrynose https://github.com/openembedded/meta-openembedded.git 

source ./external/layers/openembedded-core/oe-init-build-env build

bitbake-layers add-layer /path/to/meta-luckfox
bitbake-layers add-layer /path/to/external/layers/meta-openembedded/meta-oe
```

Then set the machine in `build/conf/local.conf`:



Build:

```sh
bitbake core-image-minimal        # or core-image-minimal-dev
```

### Example `local.conf` additions

A minimal headless capture/encode/stream image (v4l2 capture -> hw encode ->
RTP/UDP) needs roughly the following in `local.conf`:

```

```

## Dev notes

### One-time host setup

AppArmor's unprivileged user namespace restriction breaks some build tasks;
disable it:

```sh
sudo nano /etc/sysctl.d/60-apparmor-namespace.conf
```

Add:

```
kernel.apparmor_restrict_unprivileged_userns=0
```

### Kernel-only rebuild loop

```sh
source ./external/layers/openembedded-core/oe-init-build-env build
bitbake -c cleansstate linux-rockchip
bitbake linux-rockchip
```

### How to test the encoder

```sh
mpi_enc_test -w 1280 -h 720 -t 7 -n 100 -o /tmp/out.h264
```

### How to test the camera (i2c)

```sh
i2cdetect -y -r 4
```

### How it works

RV1106 has the same encoder IP as RK3528. This layer carries patches to the
kernel and `rockchip-mpp` that enable using the RV1106 encoder the same way
it's used on RK3528.

## Patches

Please send patches/pull requests to the repository, or contact the
maintainer directly.

Maintainer: Roman Buldygin <froooks@gmail.com>
