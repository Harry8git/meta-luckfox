SUMMARY = "Rockchip vendor Linux kernel (develop-6.6 branch)"
SECTION = "kernel"
LICENSE = "GPL-2.0-with-Linux-syscall-note"
LIC_FILES_CHKSUM = "file://COPYING;md5=6bc538ed5bd9a7fc9398086aedcd7e46"

inherit kernel kernel-yocto

# Same common build-time dependencies as linux-yocto.inc
DEPENDS += "xz-native bc-native openssl-native util-linux-native gmp-native libmpc-native"
# mkimage is required by scripts/mkimg when assembling the Rockchip boot.img FIT image.
DEPENDS += "u-boot-tools-native"

LINUX_VERSION = "6.6.89"
LINUX_VERSION_EXTENSION = "-rockchip"

KBRANCH = "develop-6.6"
SRCREV = "1ba51b059f25533c5529b7f68186190b47d6a7b3"

# luckfox_rv1106_linux_defconfig is a *full* defconfig, not a config fragment.
# It deliberately omits symbols that rely on their Kconfig default (e.g.
# ARCH_MULTI_V7=y, which in turn gates ARCH_ROCKCHIP and the whole SoC).  With
# the kernel-yocto default (merge_config.sh -n / allnoconfig base) those
# defaults are forced off, silently dropping ARCH_ROCKCHIP and every driver
# that depends on it.  "alldefconfig" keeps Kconfig defaults for unspecified
# symbols, so the platform is selected correctly.
KCONFIG_MODE = "alldefconfig"
SRC_URI = "git://github.com/rockchip-linux/kernel.git;protocol=https;branch=${KBRANCH} \
           file://dts \
           file://configs \
           file://0001-fiq_debugger-guard-THREAD_INFO-usage-for-CONFIG_THRE.patch \
           file://0002-mm-pgtable-export-__pte_offset_map_lock-for-out-of-t.patch \
           file://0003-video-rockchip-mpp-rkvenc2-add-rv1106-VEPU-540C-supp.patch \
           file://0004-mpp-iommu-reject-non-contiguous-buffers-on-no-iommu.patch \
           file://0005-media-i2c-add-MIS5001-camera-sensor-driver.patch \
           file://0006-media-i2c-sc3336-fix-double-clk_disable_unprepare.patch \
           file://enable-efi-partition.cfg \
           file://enable-camera-subsystem.cfg \
           file://enable-stmmac-ethtool.cfg \
           "

# Rockchip vendor kernel 6.6 was developed against GCC 10-11.  GCC 14+ promotes
# -Wimplicit-function-declaration to an error by default, which breaks several
# vendor drivers (e.g. fiq_debugger).  Suppress it globally for this tree until
# each affected driver is patched individually.
EXTRA_OEMAKE:append = " KCFLAGS=-Wno-implicit-function-declaration"

RK_KERNEL_DTS_BASE ?= ""

# do_kernel_metadata (kernel-yocto) is the first task to check for
# KBUILD_DEFCONFIG in ${S}/arch/arm/configs/ — it runs right after do_unpack
# and before do_patch.  Copy our out-of-tree defconfigs and device trees into
# the shared kernel source tree here so every subsequent task finds them.
#
# NOTE: the defconfig is shipped in a directory named "configs" (plural) on
# purpose.  kernel-yocto reserves the exact name "defconfig" in UNPACKDIR: when
# KBUILD_DEFCONFIG is set it does `cp -f <in-tree defconfig> ${UNPACKDIR}/defconfig`.
# If a SRC_URI entry "file://defconfig" had already unpacked a *directory* named
# defconfig into UNPACKDIR, that cp would drop the file *inside* the directory
# instead of overwriting it, and scc would then be handed a directory path and
# fail with "input file .../sources/defconfig does not exist".
do_kernel_metadata:prepend() {
    for f in "${UNPACKDIR}/configs/"*_defconfig; do
        [ -e "$f" ] && cp "$f" "${S}/arch/${ARCH}/configs/"
    done

    for f in "${UNPACKDIR}/dts/"*.dts "${UNPACKDIR}/dts/"*.dtsi; do
        [ -e "$f" ] && cp "$f" "${S}/arch/arm/boot/dts/rockchip/"
    done
    :
}

# Build the Rockchip boot.img FIT image after the regular kernel compile.
# Mirrors the vendor SDK:
#   make rv1106g-luckfox-pico-pro-max.img BOOT_ITS=${KERNEL_DIR}/boot.its
# The %.img target rebuilds DTB + zImage (no-op: already built), then calls
# scripts/mkimg which runs resource_tool + mkimage to assemble the FIT.
do_compile:append() {
    if [ -n "${RK_KERNEL_DTS_BASE}" ] && [ -f "${S}/boot.its" ]; then
        # scripts/bmpconvert has a bare '#!/usr/bin/env python' shebang;
        # OE native sysroot provides python3 only, so fix it before mkimg runs.
        sed -i '1s|^#!/usr/bin/env python$|#!/usr/bin/env python3|' \
            "${S}/scripts/bmpconvert" || true
        oe_runmake BOOT_ITS="${S}/boot.its" MKIMAGE="mkimage" \
            "${RK_KERNEL_DTS_BASE}.img"
    fi
}

do_deploy:append() {
    if [ -f "${B}/boot.img" ]; then
        install -m 0644 "${B}/boot.img" "${DEPLOYDIR}/boot.img"
    fi
}
