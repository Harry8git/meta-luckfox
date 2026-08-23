# v4l2src on the Rockchip ISP exposes both contiguous (NV12/NV16) and
# non-contiguous (NV12M/NV16M) variants of the same pixel format.  Upstream
# gst-plugins-good unconditionally prefers the multi-plane variant, which
# produces buffers made of several GstMemory objects.  Rockchip MPP and RGA
# need a single dmabuf per buffer for zero-copy import; on headless no-IOMMU
# SoCs (RV1106/RV1103) the hardware cannot walk the resulting scatter-list at
# all.  This bbappend inverts the default so contiguous formats are tried
# first, with an environment variable to restore upstream behaviour.
FILESEXTRAPATHS:prepend := "${THISDIR}/${PN}:"
SRC_URI += "file://0001-v4l2-prefer-contiguous-formats-over-non-contiguous-v.patch"
