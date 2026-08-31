SUMMARY = "Rockchip vendor Linux kernel (develop-6.6 branch)"
SECTION = "kernel"
LICENSE = "GPL-2.0-with-Linux-syscall-note"
LIC_FILES_CHKSUM = "file://COPYING;md5=6bc538ed5bd9a7fc9398086aedcd7e46"

inherit kernel kernel-yocto

DEPENDS += "lz4-native xz-native bc-native openssl-native util-linux-native gmp-native libmpc-native"
DEPENDS += "u-boot-tools-native"

LINUX_VERSION = "6.6.89"
LINUX_VERSION_EXTENSION = "-rockchip"

KBRANCH = "develop-6.6"
SRCREV = "1ba51b059f25533c5529b7f68186190b47d6a7b3"

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
           file://0007-rknpu-fix-arm32-cache-flush-and-format-specifier.patch \
           file://0008-rga2-fix-get-user-pages-remote.patch \
           file://enable-efi-partition.cfg \
           file://enable-camera-subsystem.cfg \
           file://enable-stmmac-ethtool.cfg \
           file://enable-usb-gadget.cfg \
           file://custom-features.cfg \
           file://imx290.c \
           "

EXTRA_OEMAKE:append = " KCFLAGS='-Wno-implicit-function-declaration -Wno-error -Wno-format'"

RK_KERNEL_DTS_BASE ?= ""

do_kernel_metadata:prepend() {
    for f in "${UNPACKDIR}/configs/"*_defconfig; do
        [ -e "$f" ] && cp "$f" "${S}/arch/${ARCH}/configs/"
    done

    for f in "${UNPACKDIR}/dts/"*.dts "${UNPACKDIR}/dts/"*.dtsi; do
        [ -e "$f" ] && cp "$f" "${S}/arch/arm/boot/dts/rockchip/"
    done

    if [ -f "${UNPACKDIR}/imx290.c" ]; then
        cp -f "${UNPACKDIR}/imx290.c" "${S}/drivers/media/i2c/imx290.c"
    fi
}

do_compile:append() {
    if [ -n "${RK_KERNEL_DTS_BASE}" ] && [ -f "${S}/boot.its" ]; then
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
