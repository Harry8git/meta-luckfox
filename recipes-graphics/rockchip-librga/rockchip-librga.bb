# Copyright (C) 2019, Fuzhou Rockchip Electronics Co., Ltd
# Released under the MIT license (see COPYING.MIT for the terms)

DESCRIPTION = "Rockchip RGA 2D graphics acceleration library"
SECTION = "libs"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://COPYING;md5=89aea4e17d99a7cacdbeed46a0096b10"

# DRM support. RV1106/RV1103 are headless IPC SoCs without a DRM/KMS driver
# (they use the dma-heap/ION allocator), so libdrm is disabled there. Other
# Rockchip SoCs (e.g. RK3588) keep it enabled.
HAVE_DRM = "true"
HAVE_DRM:rv1106 = "false"
HAVE_DRM:rv1103 = "false"

DEPENDS = "${@bb.utils.contains('HAVE_DRM', 'true', 'libdrm', '', d)}"

PACKAGE_ARCH = "${MACHINE_ARCH}"

SRC_URI = " \
	git://github.com/buldo/rga-mirrors.git;protocol=https;branch=JeffyCN/linux-rga-multi; \
"

# Original:
# SRCREV = "c6105b06ade0e5dc7f16924c7f0f5e9dcdb198bc"

# Latest:
SRCREV = "57a1067a246c71fa6c9a355d1668884fda155dd5"

inherit meson pkgconfig

EXTRA_OEMESON = "-Dlibdrm=${HAVE_DRM}"