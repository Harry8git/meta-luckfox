# Copyright (C) 2016 - 2017 Randy Li <ayaka@soulik.info>
# Copyright (C) 2019, Fuzhou Rockchip Electronics Co., Ltd
# Released under the MIT license (see COPYING.MIT for the terms)

SUMMARY = "Rockchip Media Process Platform (MPP)"
DESCRIPTION = "Unified media hardware-acceleration library for Rockchip SoCs."
HOMEPAGE = "https://github.com/rockchip-linux/mpp"
BUGTRACKER = "https://github.com/rockchip-linux/mpp/issues"
SECTION = "multimedia"

LICENSE = "Apache-2.0 & MIT"
LIC_FILES_CHKSUM = " \
    file://LICENSES/Apache-2.0;md5=7f43e699e0a26fae98c2938092f008d2 \
    file://LICENSES/MIT;md5=e8f57dd048e186199433be2c41bd3d6d"

PV = "1.1.0+git"
SRCREV = "0986d01294d5c2449c14cf13af9b740368c33967"
UPSTREAM_CHECK_COMMITS = "1"

SRC_URI = "git://github.com/rockchip-linux/mpp;protocol=https;branch=develop \
           file://0001-mpp_soc-hal-add-rv1106-and-rv1103-encoder-only-SoC-s.patch \
           file://0004-osal-allocator-dma_heap-force-CMA-heap-no-IOMMU-SoCs.patch \
           "

inherit pkgconfig cmake

HAVE_DRM = "ON"
HAVE_DRM:rv1106 = "OFF"
HAVE_DRM:rv1103 = "OFF"

DEPENDS += "${@bb.utils.contains('HAVE_DRM', 'ON', 'libdrm', '', d)}"

EXTRA_OECMAKE = " \
    -DRKPLATFORM=ON \
    -DHAVE_DRM=${HAVE_DRM} \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_BUILD_TYPE=Release \
"

CFLAGS:append = " -Wno-error=incompatible-pointer-types"

CFLAGS:append:arm = " -fno-tree-vectorize -mno-unaligned-access"
CXXFLAGS:append:arm = " -fno-tree-vectorize -mno-unaligned-access"
OECMAKE_C_FLAGS_RELEASE:append:arm = " -g -fno-tree-vectorize -mno-unaligned-access"
OECMAKE_CXX_FLAGS_RELEASE:append:arm = " -g -fno-tree-vectorize -mno-unaligned-access"

PACKAGES = "${PN}-demos ${PN}-dbg ${PN}-staticdev ${PN}-dev ${PN} ${PN}-vpu"
FILES:${PN}-vpu = "${libdir}/lib*vpu${SOLIBS}"
FILES:${PN} = "${libdir}/lib*mpp${SOLIBS}"
FILES:${PN}-dev = "${libdir}/lib*${SOLIBSDEV} ${includedir} ${libdir}/pkgconfig"
FILES:${PN}-demos = "${bindir}/*"
SECTION:${PN}-dev = "devel"
FILES:${PN}-staticdev = "${libdir}/*.a"
SECTION:${PN}-staticdev = "devel"
