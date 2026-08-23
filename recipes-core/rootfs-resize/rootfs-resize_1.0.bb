SUMMARY = "Grow the root partition and filesystem to fill the disk on boot"
DESCRIPTION = "Luckfox images are built with wic and shipped as a fixed-size \
GPT image, so the root partition is only as big as the files it contains. \
When that image is written to SD/eMMC media larger than the image itself, \
the remaining space is otherwise wasted. This installs a oneshot systemd \
service, rootfs-resize.service, that runs on every boot and, provided the \
root partition is the last partition on its disk, first repairs the GPT \
headers to match the disk's real size (sgdisk -e, needed after the image \
has been dd'd to bigger media), then grows the GPT partition entry \
(parted resizepart), and finally grows the ext2/3/4 filesystem inside it \
(resize2fs) to use all the space available on the underlying storage \
device. The service settles into a no-op via a state marker once nothing \
more can be done, so it stays safe to leave enabled permanently, \
including across re-flashes to differently sized media."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://rootfs-resize.sh \
           file://rootfs-resize.service \
           "

S = "${UNPACKDIR}"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

inherit systemd

do_install() {
    install -d ${D}${libexecdir}/rootfs-resize
    install -m 0755 ${UNPACKDIR}/rootfs-resize.sh ${D}${libexecdir}/rootfs-resize/rootfs-resize.sh

    install -d ${D}${systemd_system_unitdir}
    sed -e 's|@LIBEXECDIR@|${libexecdir}|g' \
        ${UNPACKDIR}/rootfs-resize.service > ${D}${systemd_system_unitdir}/rootfs-resize.service
    chmod 0644 ${D}${systemd_system_unitdir}/rootfs-resize.service
}

FILES:${PN} = " \
    ${libexecdir}/rootfs-resize/rootfs-resize.sh \
    ${systemd_system_unitdir}/rootfs-resize.service \
    "

SYSTEMD_SERVICE:${PN} = "rootfs-resize.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

RDEPENDS:${PN} = " \
    parted \
    gptfdisk \
    e2fsprogs-resize2fs \
    util-linux-findmnt \
    util-linux-partx \
    coreutils \
    "
