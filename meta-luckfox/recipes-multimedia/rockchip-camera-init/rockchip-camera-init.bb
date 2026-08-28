# Loads the Rockchip camera capture pipeline kernel modules (CSI2-DPHY, CIF,
# ISP, sensors) at boot, before rkaiq_3A_server / rkisp_demo try to use them.
#
# The camera stack is built entirely as loadable modules (see
# linux-rockchip-6.6.89/enable-camera-subsystem.cfg) and nothing else in the
# image inserts them: platform/i2c hotplug modalias autoloading via udev is
# not ordered against rkaiq_3A_server.service, so without this unit the 3A
# server can (and does) start before the modules are loaded, leaving every
# /dev/mediaX with an empty topology.
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI = " \
    file://rkcamera-load-modules.sh \
    file://rkcamera.service \
    "

S = "${UNPACKDIR}"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

inherit systemd

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${UNPACKDIR}/rkcamera-load-modules.sh ${D}${bindir}/rkcamera-load-modules.sh

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${UNPACKDIR}/rkcamera.service ${D}${systemd_system_unitdir}/
}

FILES:${PN} = " \
    ${bindir}/rkcamera-load-modules.sh \
    ${systemd_system_unitdir}/rkcamera.service \
    "

SYSTEMD_SERVICE:${PN} = "rkcamera.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

# Needs the camera kernel modules present on the rootfs. kernel-modules is
# already pulled in for every image via MACHINE_ESSENTIAL_EXTRA_RRECOMMENDS,
# but depend on it explicitly here too since this package is meaningless
# without it.
RDEPENDS:${PN} = "kernel-modules"
