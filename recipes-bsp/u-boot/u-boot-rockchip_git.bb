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

# u-boot source (primary) + rkbin (co-fetched with destsuffix=rkbin so it lands
# at ${UNPACKDIR}/rkbin, which is ../rkbin relative to S – the sibling-directory
# layout expected by make.sh, fit-core.sh, and the other Rockchip pack scripts).
SRC_URI = " \
    git://github.com/rockchip-linux/u-boot.git;protocol=https;branch=next-dev;name=uboot \
    git://github.com/rockchip-linux/rkbin.git;protocol=https;branch=master;name=rkbin;destsuffix=rkbin \
"

SRCREV_uboot  = "aeec6f2bfd5ce0cfcdfe0ffc7f84d9d143683856"
SRCREV_rkbin  = "ecb4fcbe954edf38b3ae037d5de6d9f5bccf81f4"
SRCREV_FORMAT = "uboot_rkbin"

PV = "1.0+git"

# S is set automatically by bitbake.conf to ${UNPACKDIR}/git for git fetches.
# rkbin unpacks to ${UNPACKDIR}/rkbin (destsuffix=rkbin), resolving as
# ../rkbin relative to S – the sibling-directory layout expected by make.sh.

inherit deploy

# U-Boot is bare-metal; no target libc linkage needed.
# python3-pyelftools-native is required by make_fit_atf.py during FIT packing.
DEPENDS += "bc-native dtc-native python3-native python3-pyelftools-native flex-native bison-native"

# Do not strip or split debug info from the produced boot images.
INHIBIT_PACKAGE_STRIP     = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"

# sstate entries are machine-specific.
PACKAGE_ARCH = "${MACHINE_ARCH}"

COMPATIBLE_MACHINE = "rv1106"

# Config fragment for the boot storage controller.  Must be set in the machine
# conf.  Typical values:
#   rk-emmc.config  – SD card or eMMC (SDHCI)
#   rk-sfc.config   – SPI NAND / SPI NOR
UBOOT_RK_FRAGMENT ?= "rk-emmc.config"

# Base U-Boot defconfig.  Overridable from the machine conf via UBOOT_MACHINE so
# the board, not the recipe, owns this choice.
UBOOT_MACHINE ?= ""

# ENVF (Rockchip env-file) location on the SD/eMMC device, in bytes.  ENVF
# reads at these fixed byte offsets directly (env/envf.c) -- it does NOT look
# up a GPT partition by name -- so these must stay in lockstep with the "env"
# GPT entry's --offset in the board wks file. Primary + redundant copies are
# packed back-to-back into one 256K GPT partition (see do_compile/wks).
UBOOT_ENVF_SIZE ?= "0x8000"
UBOOT_ENVF_OFFSET ?= "0x3C0000"
UBOOT_ENVF_OFFSET_REDUND ?= "0x3C8000"

do_configure() {
    cd "${S}"
    # Pass HOSTCC on the command line so it overrides U-Boot's Makefile default.
    oe_runmake HOSTCC="${BUILD_CC}" ${UBOOT_MACHINE}
    if [ -n "${UBOOT_RK_FRAGMENT}" ]; then
        ./scripts/kconfig/merge_config.sh -m -r .config "configs/${UBOOT_RK_FRAGMENT}"
    fi

    # Ensure on-disk GPT partition reading is available so part_get_info_by_name()
    # can locate the 'boot' partition by GPT label on SD/eMMC images.
    # Also enable the Rockchip vendor-storage partition (raw sectors reserved
    # at LBA 7168, see the "vnvm" GPT entry in the board wic file). With this
    # on, U-Boot's generic arch/arm/mach-rockchip/board.c:rockchip_set_ethaddr()
    # reads a persistent LAN MAC from vendor storage on every boot; if none is
    # present yet it generates one and WRITES it back (persisted from then on),
    # then sets the "ethaddr" env var. The common bootm/FIT boot path already
    # calls fdt_fixup_ethernet() unconditionally, which copies "ethaddr" into
    # the kernel FDT's ethernet alias node as "local-mac-address" - no kernel
    # driver or DT wiring is needed on the Linux side for this to work.
    # Append the flags then run olddefconfig, which sets every newly-introduced
    # dependent config symbol (EFI_PARTITION_ENTRIES_NUMBERS, _ENTRIES_OFF, …)
    # to its Kconfig default – no interactive prompts, no cascading failures.
    printf 'CONFIG_EFI_PARTITION=y\nCONFIG_ROCKCHIP_VENDOR_PARTITION=y\n' >> .config
    oe_runmake HOSTCC="${BUILD_CC}" olddefconfig

    # SD/eMMC-specific fixups (only when building for the SDHCI controller).
    # The default fixed-sector fallback for u-boot is 0x4000 (8 MiB) – the
    # standard Rockchip layout – but our compact wic layout places uboot.img at
    # 544 KiB (sector 0x440).  Without a valid ENVF env block on the SD card
    # (sector 0 holds the GPT protective MBR instead), the SPL skips env-based
    # partition lookup and falls through to this fixed sector.
    # NAND (rk-sfc.config) keeps the original sector value.
    if [ "${UBOOT_RK_FRAGMENT}" = "rk-emmc.config" ]; then
        sed -i 's/CONFIG_SYS_MMCSD_RAW_MODE_U_BOOT_SECTOR=.*/CONFIG_SYS_MMCSD_RAW_MODE_U_BOOT_SECTOR=0x440/' .config

        # Point ENVF at the "env" GPT partition (see wks file) instead of the
        # unset default of offset 0 (the GPT protective MBR), which produced
        # "ENVF: !bad CRC @ 0x0" on every boot.
        sed -i \
            -e 's/CONFIG_ENV_OFFSET=.*/CONFIG_ENV_OFFSET=${UBOOT_ENVF_OFFSET}/' \
            -e 's/CONFIG_ENV_OFFSET_REDUND=.*/CONFIG_ENV_OFFSET_REDUND=${UBOOT_ENVF_OFFSET_REDUND}/' \
            -e 's/CONFIG_ENV_SIZE=.*/CONFIG_ENV_SIZE=${UBOOT_ENVF_SIZE}/' \
            .config
        oe_runmake HOSTCC="${BUILD_CC}" olddefconfig
    fi

    # Adapt make.sh for the OE build environment (inspired by radxa/meta-rockchip).
    # 1. Neutralise the 'which python2' availability check – OE only has python3-native.
    sed -i '/which python2/{n;n;s/exit 1/true/}' make.sh
    # 2. Fix shebangs in all python scripts so they run under python3.
    for f in $(grep -rIl python scripts/ arch/arm/mach-rockchip/ 2>/dev/null); do
        sed -i '1s|^#!.*python[23]*|#!/usr/bin/env python3|' "$f"
    done
    # 3. Strip the top-level 'make … all' compilation line from make.sh so the
    #    script becomes packing-only; OE drives compilation in do_compile with
    #    the correct HOSTCC and PYTHON.
    sed -i '/^make /d' make.sh
    # 4. clean_files() removes u-boot.dtb before pack_images() runs, but the
    #    FIT ITS references it directly (via /incbin/("./u-boot.dtb")).
    #    mkimage exits 255 when it can't open the file.  Keep u-boot.dtb.
    sed -i 's/rm spl\/u-boot-spl\.dtb tpl\/u-boot-tpl\.dtb u-boot\.dtb -f/rm spl\/u-boot-spl.dtb tpl\/u-boot-tpl.dtb -f/' make.sh
}

# make.sh (patched in do_configure to be packing-only) auto-selects INI files
# for RV1106 from ../rkbin:
#   RKBOOT/RV1106MINIALL.ini  (SPL + DDR blobs)
#   RKTRUST/RV1106TOS.ini     (TF-A / OPTEE blobs)
do_compile() {
    cd "${S}"
    # Compile U-Boot with OE-supplied host and cross toolchains.
    # KCFLAGS=-Wno-error: this 2017.09-era codebase produces warnings
    # (-Wmaybe-uninitialized, -Wenum-int-mismatch, …) that GCC 14/15 promotes
    # to errors; suppress the promotion rather than patching each call-site.
    oe_runmake HOSTCC="${BUILD_CC}" CROSS_COMPILE="${TARGET_PREFIX}" PYTHON=python3 \
        KCFLAGS="-Wno-error" all
    # Pack FIT + loader images via the now packing-only make.sh.
    # --spl-new: use the freshly built spl/u-boot-spl.bin instead of the
    # pre-built blob from rkbin (handled by fit-core.sh, not make.sh directly).
    ./make.sh --spl-new CROSS_COMPILE="${TARGET_PREFIX}"

    # Build a valid (empty) ENVF image so the on-disk CRC check passes instead
    # of failing at offset 0. Padded with 0x00 (not mkenvimage's 0xff default)
    # to match what a freshly-zeroed "env" GPT partition would already contain.
    # Primary + redundant copies are packed back-to-back so the single 256K
    # "env" GPT partition holds both (redundant offset = primary + size).
    : > envf-empty.txt
    ./tools/mkenvimage -s "${UBOOT_ENVF_SIZE}" -p 0x0 -o envf-single.img envf-empty.txt
    cat envf-single.img envf-single.img > env.img
}

# Nothing goes into the target rootfs; all artefacts are deployed.
do_install[noexec] = "1"

do_deploy() {
    install -d "${DEPLOYDIR}"

    # FIT uboot image (u-boot + TF-A/TEE + MCU firmware).
    install -m 0644 "${S}/uboot.img" "${DEPLOYDIR}/uboot.img"

    # Standalone trust image (only present in non-FIT TOS configurations).
    if [ -f "${S}/trust.img" ]; then
        install -m 0644 "${S}/trust.img" "${DEPLOYDIR}/trust.img"
    fi

    # ENVF environment image (primary + redundant copies), only produced for
    # the SD/eMMC (rk-emmc.config) fragment.
    if [ -f "${S}/env.img" ]; then
        install -m 0644 "${S}/env.img" "${DEPLOYDIR}/env.img"
    fi

    # idbloader (versioned filename → stable deploy name).
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
