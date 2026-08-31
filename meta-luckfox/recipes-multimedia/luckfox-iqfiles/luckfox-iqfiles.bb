SUMMARY = "Luckfox vendor ISP IQ calibration files for RV1106"
DESCRIPTION = "Sensor IQ JSON files and CAC binary calibration data for \
cameras supported by Luckfox Pico boards (sc4336, sc3336, mis5001, imx462/imx327). \
These files are taken directly from the Luckfox vendor SDK."

LICENSE = "Proprietary"
LIC_FILES_CHKSUM = "file://NOTICE;md5=afe667c79b10e173904da1ed65460a49"

PACKAGE_ARCH = "${MACHINE_ARCH}"

S = "${UNPACKDIR}"

SRC_URI = " \
    file://NOTICE \
    file://sc4336_OT01_40IRC_F16.json \
    file://sc3336_CMK-OT2119-PC1_30IRC-F16.json \
    file://mis5001_CMK-OT2115-PC1_30IRC-F16.json \
    file://imx462_imx462_default.json \
    file://CAC_sc4336_OT01_40IRC_F16/cac_map_hw_2560x1440.bin \
"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -d ${D}${sysconfdir}/iqfiles
    install -m 0644 ${UNPACKDIR}/sc4336_OT01_40IRC_F16.json ${D}${sysconfdir}/iqfiles/
    install -m 0644 ${UNPACKDIR}/sc3336_CMK-OT2119-PC1_30IRC-F16.json ${D}${sysconfdir}/iqfiles/
    install -m 0644 ${UNPACKDIR}/mis5001_CMK-OT2115-PC1_30IRC-F16.json ${D}${sysconfdir}/iqfiles/
    install -m 0644 ${UNPACKDIR}/imx462_imx462_default.json ${D}${sysconfdir}/iqfiles/

    install -d ${D}${sysconfdir}/iqfiles/CAC_sc4336_OT01_40IRC_F16
    install -m 0644 ${UNPACKDIR}/CAC_sc4336_OT01_40IRC_F16/cac_map_hw_2560x1440.bin \
        ${D}${sysconfdir}/iqfiles/CAC_sc4336_OT01_40IRC_F16/
}

FILES:${PN} = "${sysconfdir}/iqfiles/"
