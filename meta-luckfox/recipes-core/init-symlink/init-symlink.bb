SUMMARY = "Create cma-uncached symlink for Rockchip CMA heap"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://fix-cma-heap.sh \
           file://fix-cma-heap.service"


inherit systemd

SYSTEMD_SERVICE:${PN} = "fix-cma-heap.service"

do_install() {
    install -d ${D}${sbindir}
    install -m 0755 ${UNPACKDIR}/fix-cma-heap.sh ${D}${sbindir}/fix-cma-heap.sh

    install -d ${D}${systemd_unitdir}/system
    install -m 0644 ${UNPACKDIR}/fix-cma-heap.service ${D}${systemd_unitdir}/system/fix-cma-heap.service
}

FILES:${PN} += "${sbindir}/fix-cma-heap.sh \
                ${systemd_unitdir}/system/fix-cma-heap.service"
S = "${UNPACKDIR}"
