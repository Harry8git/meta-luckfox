SUMMARY = "Enable systemd-resolved.service via a systemd preset drop-in"
DESCRIPTION = "Ships a preset that enables systemd-resolved so that DNS obtained \
via DHCP (systemd-networkd) is applied. Kept as a standalone recipe so the \
systemd recipe itself is not rebuilt."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://10-enable-resolved.preset"

S = "${UNPACKDIR}"

inherit allarch

do_install() {
    install -d ${D}${systemd_unitdir}/system-preset
    install -m 0644 ${UNPACKDIR}/10-enable-resolved.preset \
        ${D}${systemd_unitdir}/system-preset/10-enable-resolved.preset
}

FILES:${PN} = "${systemd_unitdir}/system-preset/10-enable-resolved.preset"

RDEPENDS:${PN} = "systemd"
