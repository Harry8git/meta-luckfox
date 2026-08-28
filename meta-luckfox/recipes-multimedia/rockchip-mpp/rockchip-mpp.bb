# Copyright (C) 2016 - 2017 Randy Li <ayaka@soulik.info>
# Copyright (C) 2019, Fuzhou Rockchip Electronics Co., Ltd
# Released under the MIT license (see COPYING.MIT for the terms)

SUMMARY = "Rockchip Media Process Platform (MPP)"
DESCRIPTION = "Unified media hardware-acceleration library for Rockchip SoCs. \
It exposes a common MPI for hardware video decoding and encoding (H.264, H.265, \
VP8/VP9, JPEG, AV1, ...) through the on-chip VPU/VEPU/VDPU codec blocks."
HOMEPAGE = "https://github.com/rockchip-linux/mpp"
BUGTRACKER = "https://github.com/rockchip-linux/mpp/issues"
SECTION = "multimedia"

LICENSE = "Apache-2.0 & MIT"
LIC_FILES_CHKSUM = " \
    file://LICENSES/Apache-2.0;md5=7f43e699e0a26fae98c2938092f008d2 \
    file://LICENSES/MIT;md5=e8f57dd048e186199433be2c41bd3d6d"

# The develop branch carries no release tags, so pin an exact commit and let
# upstream-version tooling (devtool check-upgrade-status / AUH) track by commit.
PV = "1.0+git"
SRCREV = "bf7c3be28d43f71544845d45400bd84de827b6bf"
UPSTREAM_CHECK_COMMITS = "1"

SRC_URI = "git://github.com/rockchip-linux/mpp;protocol=https;branch=develop \
           file://0001-mpp_soc-hal-add-rv1106-and-rv1103-encoder-only-SoC-s.patch \
           file://0002-cmake-fix-Release-g-strip-corrupting-OE-ffile-prefix-map.patch \
           file://0003-hal-h264e-vepu540c-read-back-int_sta-hw-status.patch \
           file://0004-osal-allocator-dma_heap-force-CMA-heap-no-IOMMU-SoCs.patch \
           "

inherit pkgconfig cmake

# DRM allocator support. RV1106/RV1103 are headless IPC SoCs without a
# DRM/KMS driver (they use the dma-heap/ION allocator), so DRM is disabled
# there. Other Rockchip SoCs (e.g. RK3588) keep it enabled.
HAVE_DRM = "ON"
HAVE_DRM:rv1106 = "OFF"
HAVE_DRM:rv1103 = "OFF"

DEPENDS += "${@bb.utils.contains('HAVE_DRM', 'ON', 'libdrm', '', d)}"

# Rockchip / CMake build knobs:
#  * RKPLATFORM=ON                - enable the Rockchip hardware codec paths.
#  * HAVE_DRM                     - libdrm allocator; off on headless RV1106/RV1103.
#  * CMAKE_POLICY_VERSION_MINIMUM - let CMake 4.x configure MPP, whose
#    cmake_minimum_required() still requests the 3.5-era policy set.
#  * CMAKE_BUILD_TYPE=Release     - build an optimised production library
#    (-O3, -DNDEBUG). OE's cmake.bbclass never sets a build type, so without
#    this MPP falls back to its internal "Debug" default and never defines
#    -DNDEBUG, leaving asserts and debug-only code paths compiled in.
#    EXTRA_OECMAKE is appended last on the cmake command line, so this reliably
#    wins. See the OECMAKE_*_FLAGS_RELEASE overrides below for how the
#    alignment-safety flags stay effective under the -O3 that Release enables.
EXTRA_OECMAKE = " \
    -DRKPLATFORM=ON \
    -DHAVE_DRM=${HAVE_DRM} \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_BUILD_TYPE=Release \
"

# NOTE: intentionally NOT overriding CMAKE_SYSTEM_PROCESSOR to "armv7l" here.
# MPP's CMake only uses that value to (a) compile hand-written NEON assembly -
# of which this develop branch has none (zero .S files) - and (b) inject
# "-march=armv7-a -mfpu=neon", which would clobber the more specific
# cortexa7 / neon-vfpv4 tune OE already passes. OE's generated toolchain file
# also hard-sets "CMAKE_SYSTEM_PROCESSOR arm", which shadows any -D override on
# the command line anyway (MPP just logs "CMAKE_SYSTEM_PROCESSOR value `arm` is
# unknown" and carries on with OE's flags). Letting MPP see "arm" is correct.

# GCC 14+ promotes -Wincompatible-pointer-types to a hard error by default.
# MPP's osal/test code is pre-GCC-14 era and passes K&R-style thread routines
# (e.g. "void *wait_thread()") to pthread_create. Demote it back to a warning
# so this older library builds with the wrynose GCC 15 toolchain.
CFLAGS:append = " -Wno-error=incompatible-pointer-types"

# On the Cortex-A7 (armv7) RV1106/RV1103 SoCs, GCC 12+ auto-vectorises (at both
# -O2 and -O3) and emits NEON load/store instructions with :64/:128 alignment
# qualifiers (e.g. "vst1.64 {d22-d23}, [ip:64]"). MPP frequently accesses
# 64-bit and 128-bit data (kmpp_obj fields, cdf tables, shared-memory structs)
# that is only 4-byte aligned at run time, so these strictly-aligned NEON
# accesses raise an alignment exception (0x801) the kernel cannot fix up,
# killing the process with SIGBUS ("Bus error") - e.g. mpi_enc_test crashes
# during the MppEncArgs object dump before encoding starts.
#
# -fno-tree-vectorize stops the loop/SLP vectoriser from generating those
# aligned NEON accesses, and -mno-unaligned-access makes the block-move
# (memcpy) expansion fall back to strictly-aligned-safe copies. Together they
# remove all alignment-qualified NEON codegen. There is no hand-written NEON
# assembly in MPP (zero .S files) and the encoder runs on the hardware VEPU, so
# the only effect is dropping compiler auto-vectorisation. Scoped to 32-bit arm
# only; aarch64 SoCs (RK3588 etc.) are naturally 8-byte aligned and keep full
# vectorisation.
#
# The flags are set in two places on purpose:
#  * base CFLAGS/CXXFLAGS - protects every build configuration.
#  * OECMAKE_*_FLAGS_RELEASE - MPP's own CMakeLists appends "-O3" to
#    CMAKE_C_FLAGS in a Release build, i.e. AFTER the base CFLAGS, which would
#    re-enable the vectoriser and bring the crash back. CMAKE_<LANG>_FLAGS_RELEASE
#    is emitted after CMAKE_<LANG>_FLAGS on the compile line, so placing the
#    flags there guarantees they win over that -O3. The "-g" restored here
#    counteracts MPP's Release branch stripping OE's -g, so debug info still
#    splits into the -dbg package.
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