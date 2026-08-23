# Copyright (C) 2016 - 2017 Randy Li <ayaka@soulik.info>
# Copyright (C) 2019, Fuzhou Rockchip Electronics Co., Ltd
# Released under the LGPL-2.1-or-later license (see COPYING for the terms)

SUMMARY = "GStreamer 1.0 plugins for Rockchip hardware codecs (MPP/RGA)"
DESCRIPTION = "GStreamer elements built on top of Rockchip's MPP hardware \
video encoder/decoder (mpph264enc, mpph265enc, mppjpegenc, mppvp8enc, \
mppvideodec, mppjpegdec) and the RGA 2D scaler/converter (rgaconvert). \
Combined with the standard GStreamer v4l2/RTP elements (v4l2src, rtph264pay, \
udpsink, ...) from gstreamer1.0-plugins-good, this is what a hardware \
camera-capture -> encode -> RTP streaming pipeline needs on RV1106/RV1103, \
e.g.: \
  v4l2src ! mpph264enc ! rtph264pay ! udpsink"
HOMEPAGE = "https://github.com/JeffyCN/mirrors/tree/gstreamer-rockchip"
SECTION = "multimedia"

LICENSE = "LGPL-2.1-or-later"
LIC_FILES_CHKSUM = "file://COPYING;md5=4fbd65380cdd255951079008b364516c"

# This mirror branch carries no release tags, so pin an exact commit and let
# upstream-version tooling (devtool check-upgrade-status / AUH) track by commit.
PV = "1.0+git"
SRCREV = "dcbcd6454ef892e385b3a782600369eb6c0719db"
UPSTREAM_CHECK_COMMITS = "1"

SRC_URI = "git://github.com/JeffyCN/mirrors.git;protocol=https;branch=gstreamer-rockchip"
SRC_URI:append = " \
    file://0001-mppenc-do-not-require-aligned-vstride-from-upstream-for-H.264-H.265.patch \
    file://0002-mpp-remember-when-RGA-cannot-use-virtual-address-input.patch \
"

# JeffyCN/mirrors is a single repo used to host several unrelated BSP trees as
# branches (kernel, rga, gstreamer-rockchip, ...); the gstreamer-rockchip
# branch's history is hundreds of thousands of commits deep (it shares the
# ancestry of the "kernel" branch), so a normal full/mirror clone downloads
# the equivalent of the whole Linux kernel history. Force a depth-1 shallow
# clone of just SRCREV instead.
BB_GIT_SHALLOW = "1"
BB_GIT_SHALLOW_DEPTH = "1"

DEPENDS += "gstreamer1.0-plugins-base"

inherit meson pkgconfig

require recipes-multimedia/gstreamer/gstreamer1.0-plugins-packaging.inc

# Only the Rockchip MPP hardware codec and RGA 2D converter plugins are
# wired up: luckfox-pico-pro-max (RV1106) is a headless IPC SoC with no
# DRM/KMS driver and no X11 in DISTRO_FEATURES, so the upstream rkximage
# (X11/KMS sink) and kmssrc plugins would build but never be usable here.
PACKAGECONFIG ??= "mpp rga"
PACKAGECONFIG[mpp] = "-Drockchipmpp=enabled,-Drockchipmpp=disabled,rockchip-mpp"
PACKAGECONFIG[rga] = "-Drga=enabled,-Drga=disabled,rockchip-librga"
