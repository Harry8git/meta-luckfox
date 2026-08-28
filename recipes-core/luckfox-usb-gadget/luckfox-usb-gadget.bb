SUMMARY = "Luckfox Pico USB Gadget (CDC-NCM Ethernet and Dual CDC-ACM Serial)"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://luckfox-usb-gadget.sh \
    file://luckfox-usb-gadget.service \
"

S = "${UNPACKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "luckfox-usb-gadget.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${S}/luckfox-usb-gadget.sh ${D}${bindir}/luckfox-usb-gadget.sh

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/luckfox-usb-gadget.service ${D}${systemd_system_unitdir}/luckfox-usb-gadget.service
}

FILES:${PN} += " \
    ${bindir}/luckfox-usb-gadget.sh \
    ${systemd_system_unitdir}/luckfox-usb-gadget.service \
"