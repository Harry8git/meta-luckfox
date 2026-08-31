#!/bin/sh
# Load the Rockchip camera capture pipeline kernel modules.
#
# The whole stack (CSI2 D-PHY, CIF, ISP, sensor drivers) is built as loadable
# modules (see enable-camera-subsystem.cfg), so nothing in the media graph
# exists until these are inserted. Unlike i2c/platform hotplug modalias
# autoloading, doing it explicitly here guarantees the pipeline is up *before*
# rkaiq_3A_server / rkisp_demo try to open /dev/mediaX and /dev/videoX
# (see rkaiq_3A_server.service's After=/Wants=rkcamera.service).
#
# This mirrors the vendor SDK's own module-loading script
# (external/luckfox-pico/sysdrv/drv_ko/insmod_ko.sh), which handles the same
# three problems this port also hits:
#
#  1. video_rkisp.ko has no MODULE_DEVICE_TABLE anywhere in it (checked all of
#     isp/{hw,dev,isp_sditf,rkisp_tb_helper}.c), so udev's modalias-triggered
#     autoload can NEVER load it on its own -- it must always be modprobed by
#     name.
#  2. phy-rockchip-csi2-dphy (logical) reads platform_get_drvdata() from
#     phy-rockchip-csi2-dphy-hw (physical) during its own probe and returns a
#     hard -EINVAL (not -EPROBE_DEFER, so no retry) if the hw driver hasn't
#     finished probing yet. Loading them as two independent udev-triggered
#     modprobes races; loading -hw first and letting modprobe finish
#     synchronously before loading the logical module avoids it entirely
#     (confirmed live: rebinding csi2-dphy0 after -hw was already up
#     succeeded immediately).
#  3. Only one sensor is physically populated at a time (sc3336/mis5001 share
#     the mipi_refclk_out0 clock pin -- see the i2c4 node comment in the
#     board dtsi), so cif/isp's v4l2_async_notifier ends up waiting forever
#     for the *other*, physically-absent sensor's endpoint to bind, which
#     keeps the whole media topology "not ready" even though the present
#     sensor's subdev registered fine. clr_unready_dev (module parameter on
#     both video_rkcif and video_rkisp, backed by
#     v4l2_async_notifier_clr_unready_dev()) tells the notifier to give up on
#     missing endpoints and finish with whatever actually bound. The drivers
#     do have a late_initcall()/late_initcall_sync() that calls this
#     automatically -- but late_initcall is a no-op for code built as a
#     loadable module (only runs for built-in code), so it must be triggered
#     manually here, same as the vendor script does.
#
# stop/start the udev exec queue for the whole sequence so udev's own
# modalias-triggered autoload of these same modules (kicked off by the
# platform/i2c devices already being present at boot) can't race with the
# explicit modprobes below.
#
# The queue MUST be resumed no matter what happens in between (a stuck
# stopped queue would silently break all hotplug on the system: USB, network
# interfaces, everything), so resume it from an EXIT trap rather than relying
# on reaching the last line of the script.
udevadm control --stop-exec-queue
trap 'udevadm control --start-exec-queue' EXIT

set -e

# Try every sensor driver this kernel build supports; whichever one is not
# physically present on the I2C/CSI bus just fails its own probe internally
# and the module stays loaded with zero bound devices (cleaned up below).
# NOTE: the IMX462 board DT binds to the imx290 driver via compatible
# "sony,imx462" (see the board dtsi), so the sensor module name is
# "imx290", not "imx462". Without this load, the CIF/ISP async notifier
# never gets a valid sensor endpoint and the camera pipeline remains broken.
for sensor in mis5001 sc3336 imx415 imx327 imx290; do
	modprobe "$sensor" || true
done

modprobe video_rkcif
modprobe video_rkisp

# Must be loaded in this order: -hw first, and modprobe blocks until that
# module's insmod (and therefore its synchronous device probing) is fully
# finished before returning, so by the time the logical dphy module loads,
# the hw module's platform_get_drvdata() is guaranteed to be populated.
modprobe phy-rockchip-csi2-dphy-hw
modprobe phy-rockchip-csi2-dphy

# Drop sensor modules that never bound to any device (3rd lsmod column, the
# refcount, is 0) -- same cleanup the vendor script does.
for sensor in mis5001 sc3336 imx415 imx327 imx290; do
	if lsmod | grep -w "$sensor" | awk '{print $3}' | grep -qw 0; then
		rmmod "$sensor" || true
	fi
done

# Tell the CIF/ISP async notifiers to stop waiting on whichever sensor
# endpoint never bound (the one that isn't physically populated) and
# finalize the media topology with what did bind.
[ -w /sys/module/video_rkcif/parameters/clr_unready_dev ] && \
	echo 1 > /sys/module/video_rkcif/parameters/clr_unready_dev
[ -w /sys/module/video_rkisp/parameters/clr_unready_dev ] && \
	echo 1 > /sys/module/video_rkisp/parameters/clr_unready_dev

exit 0

# Ensure MPP CMA dma-heap symlinks exist for contiguous zero-copy allocation
if [ -e /dev/dma_heap/reserved ]; then
	ln -sf reserved /dev/dma_heap/cma
	ln -sf reserved /dev/dma_heap/cma-uncached
fi
