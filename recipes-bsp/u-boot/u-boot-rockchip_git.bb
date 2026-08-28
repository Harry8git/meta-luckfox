SUMMARY = "Rockchip U-Boot for RV1106 boards"
DESCRIPTION = "U-Boot bootloader built from rockchip-linux/u-boot (next-dev branch), \
with Rockchip rkbin firmware blobs co-fetched as a sibling directory for FIT image packing."
HOMEPAGE = "https://github.com/rockchip-linux/u-boot"
SECTION = "bootloaders"

LICENSE = "GPL-2.0-or-later & Rockchip-Binary"
LIC_FILES_CHKSUM = " \
    file://Licenses/gpl-2.0.txt;md5=b234ee4d69f5fce4486a80fdaf4a4263 \
    file://${UNPACKDIR}/rkbin/LICENSE;md5=11e3673115959bf596feaaa6ea7ce9a5 \
"

PROVIDES += "virtual/bootloader"

SRC_URI = " \
    git://github.com/rockchip-linux/u-boot.git;protocol=https;branch=next-dev;name=uboot \
    git://github.com/rockchip-linux/rkbin.git;protocol=https;branch=master;name=rkbin;destsuffix=rkbin \
"

SRCREV_uboot  = "aeec6f2bfd5ce0cfcdfe0ffc7f84d9d143683856"
SRCREV_rkbin  = "ecb4fcbe954edf38b3ae037d5de6d9f5bccf81f4"
SRCREV_FORMAT = "uboot_rkbin"

PV = "1.0+git"

inherit deploy

DEPENDS += "bc-native dtc-native python3-native python3-pyelftools-native flex-native bison-native"

INHIBIT_PACKAGE_STRIP     = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"

PACKAGE_ARCH = "${MACHINE_ARCH}"
COMPATIBLE_MACHINE = "rv1106"

UBOOT_RK_FRAGMENT ?= "rk-emmc.config"
UBOOT_MACHINE ?= ""

UBOOT_ENVF_SIZE ?= "0x8000"
UBOOT_ENVF_OFFSET ?= "0x3C0000"
UBOOT_ENVF_OFFSET_REDUND ?= "0x3C8000"

do_configure:prepend() {
    python3 -c "
f = '${S}/arch/arm/dts/rv1106-u-boot.dtsi'
with open(f, 'r') as fp:
    c = fp.read()
c = c.replace('u-boot,spl-boot-order = &sdmmc, &spi_nor, &spi_nand, &emmc;',
              'u-boot,spl-boot-order = &emmc;')
c = c.replace('&sdmmc {\n\tu-boot,dm-spl;\n\tpwr-en-gpios = <&gpio0 RK_PA1 GPIO_ACTIVE_LOW>;\n\tstatus = \"okay\";\n};',
              '&sdmmc {\n\tstatus = \"disabled\";\n};')
c = c.replace('mmc-hs200-1_8v;',
              'cap-mmc-highspeed;\n\tnon-removable;\n\tno-sdio;\n\tno-sd;')
with open(f, 'w') as fp:
    fp.write(c)
"
}

do_configure() {
    cd "${S}"

    oe_runmake HOSTCC="${BUILD_CC}" ${UBOOT_MACHINE}
    if [ -n "${UBOOT_RK_FRAGMENT}" ]; then
        ./scripts/kconfig/merge_config.sh -m -r .config "configs/${UBOOT_RK_FRAGMENT}"
    fi

    printf 'CONFIG_EFI_PARTITION=y\nCONFIG_ROCKCHIP_VENDOR_PARTITION=y\n' >> .config
    oe_runmake HOSTCC="${BUILD_CC}" olddefconfig

    if [ "${UBOOT_RK_FRAGMENT}" = "rk-emmc.config" ]; then
        sed -i 's/CONFIG_SYS_MMCSD_RAW_MODE_U_BOOT_SECTOR=.*/CONFIG_SYS_MMCSD_RAW_MODE_U_BOOT_SECTOR=0x440/' .config
        sed -i \
            -e 's/CONFIG_ENV_OFFSET=.*/CONFIG_ENV_OFFSET=${UBOOT_ENVF_OFFSET}/' \
            -e 's/CONFIG_ENV_OFFSET_REDUND=.*/CONFIG_ENV_OFFSET_REDUND=${UBOOT_ENVF_OFFSET_REDUND}/' \
            -e 's/CONFIG_ENV_SIZE=.*/CONFIG_ENV_SIZE=${UBOOT_ENVF_SIZE}/' \
            .config
        oe_runmake HOSTCC="${BUILD_CC}" olddefconfig
    fi

    sed -i '/which python2/{n;n;s/exit 1/true/}' make.sh
    for f in $(grep -rIl python scripts/ arch/arm/mach-rockchip/ 2>/dev/null); do
        sed -i '1s|^#!.*python[23]*|#!/usr/bin/env python3|' "$f"
    done
    sed -i '/^make /d' make.sh
    sed -i 's/rm spl\/u-boot-spl\.dtb tpl\/u-boot-tpl\.dtb u-boot\.dtb -f/rm spl\/u-boot-spl.dtb tpl\/u-boot-tpl.dtb -f/' make.sh
}

do_compile() {
    cd "${S}"
    oe_runmake HOSTCC="${BUILD_CC}" CROSS_COMPILE="${TARGET_PREFIX}" PYTHON=python3 \
        KCFLAGS="-Wno-error" all

    ./make.sh --spl-new CROSS_COMPILE="${TARGET_PREFIX}"

    : > envf-empty.txt
    ./tools/mkenvimage -s "${UBOOT_ENVF_SIZE}" -p 0x0 -o envf-single.img envf-empty.txt
    cat envf-single.img envf-single.img > env.img
}

do_install[noexec] = "1"

do_deploy() {
    install -d "${DEPLOYDIR}"

    install -m 0644 "${S}/uboot.img" "${DEPLOYDIR}/uboot.img"

    if [ -f "${S}/trust.img" ]; then
        install -m 0644 "${S}/trust.img" "${DEPLOYDIR}/trust.img"
    fi

    if [ -f "${S}/env.img" ]; then
        install -m 0644 "${S}/env.img" "${DEPLOYDIR}/env.img"
    fi

    for f in "${S}"/*_download_v*.bin; do
        [ -f "$f" ] || continue
        install -m 0644 "$f" "${DEPLOYDIR}/download.bin"
        break
    done

    for f in "${S}"/*_idblock_v*.img; do
        [ -f "$f" ] || continue
        install -m 0644 "$f" "${DEPLOYDIR}/idblock.img"
        break
    done
}

addtask do_deploy after do_compile before do_build