# Copyright (C) 2022, Rockchip Electronics Co., Ltd
# Released under the MIT license (see COPYING.MIT for the terms)

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://NOTICE;md5=9645f39e9db895a4aa6e02cb57294595"

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# Prepend (not append) so these package-specific FILES claims are matched
# before the catch-all FILES:${PN} below; PACKAGES:append would put them
# *after* ${PN} in the package ordering and ${PN}'s greedy ${bindir}/${libdir}
# claims would silently swallow their files first.
PACKAGES =+ "${PN}-server ${PN}-iqfiles"

DEPENDS = "coreutils-native chrpath-replacement-native xxd-native rockchip-librga"
RDEPENDS:${PN}-server = "${PN}"

# librkaiq.so uses legacy 32-bit time APIs (pre-built vendor binary, can't patch).
INSANE_SKIP:${PN} = "32bit-time"

PACKAGE_ARCH = "${MACHINE_ARCH}"

SRCREV = "73a6010fe79f3185471157d48558fefb04c7e1c4"
SRC_URI = "git://github.com/buldo/rkaiq-mirrors.git;protocol=https;branch=JeffyCN/rkaiq-2025_08_11; \
           file://rkaiq_daemons.sh \
           file://rkaiq_3A_server.service \
           file://0001-cmake-drop-Werror-and-hardcoded-march-mthumb-let-OE-.patch \
           file://0002-ipc_server-add-missing-stdio.h-include-in-MessagePar.patch \
           file://0003-rkisp_demo-install-with-empty-RPATH-and-relocatable-.patch \
           "

inherit pkgconfig cmake

RK_ISP_VERSION ?= ""
RK_SOC_FAMILY ?= ""
# RKAIQ_J2S4B_DEV builds j2s4b_dev, the on-device json->bin IQ converter. We ship
# the vendor .json IQ files and rkaiq reads those directly, so the converter is
# dead weight -- it isn't even installed (upstream skips its install rule when
# CMAKE_INSTALL_PREFIX is /usr). It is the only consumer of j2s_generated.h left
# after COMPILE_TEMPLATE, so dropping it also drops a build-time failure mode.
# Note this is not RKAIQ_DISABLE_J2S: j2s *is* the JSON parser and must stay on.
EXTRA_OECMAKE = "     \
    -DARCH=${@bb.utils.contains('TUNE_FEATURES', 'aarch64', 'aarch64', 'arm', d)} \
    -DISP_HW_VERSION=-DISP_HW_V${@d.getVar('RK_ISP_VERSION').replace('.','')} \
    -DRKAIQ_TARGET_SOC=${@d.getVar('RK_SOC_FAMILY').replace('rk3568','rk356x')} \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DRKAIQ_J2S4B_DEV=FALSE \
"

# rkaiq is C11-era code: it relies on the legacy meaning of an empty parameter
# list (int f() == unspecified args). GCC 15 defaults to C23, where () means
# (void), turning those prototypes into "conflicting types" errors. The main
# rkaiq project already forces -std=gnu11; pin the whole recipe to it so the
# subprojects (IspFec, smart_ir, ...) that don't set a C standard build too.
CFLAGS:append = " -std=gnu11"

# librkaiq maps the IQ JSON onto the calibration structs through a j2s
# descriptor table of offsetof()/sizeof() values. By default j2s_utils.c pulls
# that table from j2s_code2bin.bin, a blob baked by a *host* tool: upstream only
# gets target-matching numbers there by building the host tools with
# -m32 -malign-double, i.e. by relying on the x86 32-bit layout happening to
# match arm EABI. That needs 32-bit host libc headers (gcc-multilib), which OE
# build hosts don't have -- and the flags are dropped below for that reason.
# Built plain x86-64 the blob holds 64-bit offsets (e.g. CalibDb_MainScene_t.
# sub_scene at 8 instead of 4), j2s then fails on every IQ file and rkaiq aborts
# with "CamCalibDbProj in invalied!", leaving the ISP unconfigured (black
# frames). COMPILE_TEMPLATE switches j2s_utils.c to .j2s_generated_v2.h, which
# carries the same descriptors as offsetof()/sizeof() expressions evaluated by
# the cross compiler, so they match the target by construction. Cost: the table
# is built at runtime instead of being a ready-made blob (~450K more .text).
CFLAGS:append = " -DCOMPILE_TEMPLATE"

do_generate_toolchain_file:append () {
	echo "set( CMAKE_SYSROOT ${STAGING_DIR_HOST} )" >> \
		${UNPACKDIR}/toolchain.cmake
	echo "set( CMAKE_SYSROOT_COMPILE ${STAGING_DIR_HOST} )" >> \
		${UNPACKDIR}/toolchain.cmake

	# The HEADER MACRO PREPROCESS custom_command runs the cross-compiler
	# (${CMAKE_C_COMPILER}) directly, so it never receives CMAKE_C_FLAGS and
	# thus drops the tune flags. Without -mfloat-abi=hard the compiler picks the
	# soft-float ABI and pulls in gnu/stubs-soft.h, which a hardfp sysroot does
	# not ship. Inject the OE tune flags (TUNE_CCARGS, bitbake-expanded) so the
	# preprocessing matches the rest of the build.
	sed -i "s/\( \${CMAKE_C_COMPILER}\)/\1 ${TUNE_CCARGS} -I\${CMAKE_SYSROOT}\/usr\/include/" \
		${S}/rkaiq/iq_parser_v2/CMakeLists.txt

	# The IspFec, smart_ir and media_enquiry sub-CMakeLists overwrite
	# CMAKE_C(XX)_FLAGS with a fixed string (set(... "-Wall ...")) instead of
	# appending to it, which discards the OE tune flags (-mcpu/-mfpu/
	# -mfloat-abi=hard) injected through the toolchain file. The result is a
	# soft-float compile that fails on the missing gnu/stubs-soft.h. Make these
	# subprojects preserve the inherited flags, like rkaiq/rkisp_demo already do.
	for f in IspFec smart_ir media_enquiry; do
		sed -i "s/\(set(CMAKE_C_FLAGS *\)\"-/\1\"\${CMAKE_C_FLAGS} -/" \
			${S}/$f/cmake/CompileOptions.cmake
		sed -i "s/\(set(CMAKE_CXX_FLAGS *\)\"-/\1\"\${CMAKE_CXX_FLAGS} -/" \
			${S}/$f/cmake/CompileOptions.cmake
	done

	# iq_parser_v2 builds parser/code-generator helpers on the build host,
	# but its CMakeLists forces -m32 for ARM targets (-m64 for AArch64).
	# Those flags are host-specific and make an otherwise native build require
	# 32-bit libc headers (e.g. bits/wordsize.h). Upstream only needs them so the
	# host-baked struct offsets match the target ABI; COMPILE_TEMPLATE (see CFLAGS
	# above) makes librkaiq use compiler-evaluated descriptors instead, leaving the
	# helpers to just parse headers and generate source, which they can do at the
	# host's native word size.
	sed -i \
		-e 's/set(J2S_HOST_CFLAGS -m32 -std=gnu99 -malign-double)/set(J2S_HOST_CFLAGS -std=gnu99)/' \
		-e 's/set(J2S_HOST_CFLAGS -m64 -std=gnu99 -malign-double)/set(J2S_HOST_CFLAGS -std=gnu99)/' \
		${S}/rkaiq/iq_parser_v2/CMakeLists.txt

	# Rockchip ships the 3A algorithms only as prebuilt archives (no sources in
	# tree), under algos/<algo>/linux/<soc>/<arch>/{glibc,uclibc}/. rkaiq's cmake
	# forces C_LIBRARY_NAME=glibc, so it links the glibc/*.a. On rv1106 the native
	# vendor toolchain is uclibc, and the glibc archives in this JeffyCN snapshot
	# are a STALE drop that does NOT match the in-tree (freshly compiled) rkaiq
	# framework. This manifests two ways:
	#   * link time: the glibc AE archive predates the newer AE uAPI
	#     (rk_aiq_uapi_ae_{set,get}{FrameHdr,ExpSubWin}Attr) the handlers now call
	#     -> undefined references in librkaiq.so.
	#   * run time: the glibc AF/AWB archives encode an OLDER struct ABI than the
	#     framework compiles, so a config field the algo reads as a pointer is
	#     actually e.g. a float in the current layout -> the algo dereferences a
	#     garbage pointer and SIGSEGVs during rk_aiq_uapi2_sysctl_prepare's
	#     init-time analyzeInternal() pass (seen as AFPrepare / AWB processing
	#     crashes right after "init mipi tx/rx", independent of the IQ file).
	# The sibling uclibc archives are the current build that matches the source
	# snapshot. Overlay uclibc -> glibc for EVERY prebuilt algo so the whole 3A
	# set is ABI-consistent with the compiled framework. (The uclibc archives
	# carry no uclibc-internal symbol deps and link fine into the glibc build,
	# same as the AE case this generalises.)
	algo_arch="${@bb.utils.contains('TUNE_FEATURES', 'aarch64', 'aarch64', 'arm', d)}"
	algo_soc="${@d.getVar('RK_SOC_FAMILY').replace('rk3568', 'rk356x').lower()}"
	for glibc_a in ${S}/rkaiq/algos/*/linux/${algo_soc}/${algo_arch}/glibc/*.a; do
		[ -f "$glibc_a" ] || continue
		uclibc_a="$(echo "$glibc_a" | sed 's,/glibc/,/uclibc/,')"
		if [ -f "$uclibc_a" ]; then
			cp "$uclibc_a" "$glibc_a"
		fi
	done
}

do_install:append () {
	# libdir might not equal /usr/lib which is assumed by rkaiq's cmake (e.g. when using multilib)
	if [ "${libdir}" != "/usr/lib" ]; then
		mkdir -p ${D}${libdir}
		mv ${D}/usr/lib/*.a ${D}${libdir}/ || true
		mv ${D}/usr/lib/*.so ${D}${libdir}/ || true

		# Remove the empty /usr/lib directory to prevent packaging issues
		rmdir --ignore-fail-on-non-empty ${D}/usr/lib
	fi

	# rkaiq installed 3A server to the wrong dir; fix up and remove the stale /usr/usr tree.
	if [ -d ${D}/usr/usr ]; then
		cp -rp ${D}/usr/usr/* ${D}/usr/
		rm -rf ${D}/usr/usr
	fi

	# rkaiq installs init scripts to /usr/etc instead of /etc; relocate them.
	if [ -d ${D}/usr/etc ]; then
		mkdir -p ${D}${sysconfdir}
		cp -rp ${D}/usr/etc/* ${D}${sysconfdir}/
		rm -rf ${D}/usr/etc
	fi

	# rkisp_demo has no cmake install() rule; copy the built binary manually.
	find ${B}/rkisp_demo -name "rkisp_demo" -type f | while read bin; do
		install -m 0755 "$bin" ${D}${bindir}/rkisp_demo
	done

	chrpath -d ${D}${libdir}/libsmartIr.so
	chrpath -d ${D}${bindir}/rkisp_demo

	install -d ${D}${sysconfdir}/iqfiles

	IQFILES_DIR="$(echo isp${RK_ISP_VERSION} | tr 'A-Z' 'a-z' | tr -d '.')"
	# Some ISP generations (e.g. RK_ISP_VERSION="3.0") don't have their own
	# iqfiles directory upstream; their calibration data lives under the
	# shared "isp3x" directory instead. Resolve the alias here rather than
	# symlinking inside ${S} (do_install must not mutate the source tree).
	if [ ! -d "${S}/rkaiq/iqfiles/${IQFILES_DIR}" ] && [ "${IQFILES_DIR}" = "isp30" ]; then
		IQFILES_DIR="isp3x"
	fi
	cd ${S}/rkaiq/iqfiles/${IQFILES_DIR}/
	if [ -d common ]; then
		cd common
	fi

	install -m 0644 *.json ${D}${sysconfdir}/iqfiles/

	if [ "${VIRTUAL-RUNTIME_init_manager}" != "systemd" ]; then
		install -d ${D}${sysconfdir}/init.d
		install -m 0755 ${UNPACKDIR}/rkaiq_daemons.sh ${D}${sysconfdir}/init.d/
	fi

	install -d ${D}${systemd_system_unitdir}
	install -m 0644 ${UNPACKDIR}/rkaiq_3A_server.service ${D}${systemd_system_unitdir}/
}

inherit update-rc.d systemd

INITSCRIPT_PACKAGES = "${PN}-server"
INITSCRIPT_NAME:${PN}-server = "rkaiq_daemons.sh"
INITSCRIPT_PARAMS:${PN}-server = "start 70 5 4 3 2 . stop 30 0 1 6 ."

INHIBIT_UPDATERCD_BBCLASS = "${@oe.utils.conditional('VIRTUAL-RUNTIME_init_manager', 'systemd', '1', '', d)}"

# The unit belongs to ${PN}-server, so systemd.bbclass has to look there instead
# of the default ${PN}; without this no preset is emitted and the service stays
# disabled in the image.
SYSTEMD_PACKAGES = "${PN}-server"
SYSTEMD_SERVICE:${PN}-server = "rkaiq_3A_server.service"

FILES:${PN}-dev = "${includedir}"
FILES:${PN}-server = " \
	${bindir}/rkaiq_3A_server \
	${bindir}/rkaiq_tool_server \
	${systemd_system_unitdir}/rkaiq_3A_server.service \
	${sysconfdir}/init.d/ \
"
FILES:${PN}-iqfiles = "${sysconfdir}/iqfiles/"
FILES:${PN} = " \
	${libdir} \
	${datadir} \
	${bindir} \
"