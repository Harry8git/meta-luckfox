#!/bin/sh
#
# Grow the root partition and its filesystem to fill the underlying block
# device (SD card / eMMC). The wic image ships a rootfs partition sized to
# fit only the files it contains; when that image is written to bigger
# media, the rest of the disk is wasted unless something grows the
# partition and filesystem afterwards.
#
# The actual resize only ever needs to happen once: as soon as this script
# reaches a "settled" state (grown the disk, or determined there is
# nothing it can/should do for this build), it touches a marker file.
# rootfs-resize.service checks that marker via ConditionPathExists= before
# even starting this script, so on every later boot systemd just does a
# stat() and skips the unit - parted/partx/resize2fs never get forked
# again. Flashing a fresh image wipes /var/lib on that device, which is
# exactly what "resets" the check for the new install.
#
# Only the common case is handled on purpose: an ext2/3/4 root filesystem
# whose partition is the *last* partition on a GPT/MBR disk. Growing a
# partition that has another partition after it would require relocating
# that partition first, which this script deliberately does not attempt.

set -eu

MARKER_DIR="${STATE_DIRECTORY:-/var/lib/rootfs-resize}"
MARKER="$MARKER_DIR/done"

log() {
    printf 'rootfs-resize: %s\n' "$*"
}

# Use for states that will never change for this build (wrong fstype,
# partition topology) or once the disk has actually been grown: skip every
# later boot for free via the unit's ConditionPathExists=.
settled() {
    log "$*"
    mkdir -p "$MARKER_DIR"
    : >"$MARKER"
    exit 0
}

# Use for states that might just be transient (device not ready yet, races
# at boot): skip this boot but keep retrying on subsequent boots instead of
# silently disabling the check forever.
skip_for_now() {
    log "$*"
    exit 0
}

ROOT_SRC=$(findmnt -no SOURCE /) || skip_for_now "cannot determine the root device, will retry next boot"
ROOT_DEV=$(readlink -f "$ROOT_SRC")
ROOT_FSTYPE=$(findmnt -no FSTYPE /)

case "$ROOT_FSTYPE" in
ext2 | ext3 | ext4) ;;
*)
    settled "root filesystem is '$ROOT_FSTYPE', not ext2/3/4 - nothing to do"
    ;;
esac

PART_NAME=$(basename "$ROOT_DEV")
PART_SYSFS="/sys/class/block/$PART_NAME"

if [ ! -e "$PART_SYSFS/partition" ]; then
    settled "$ROOT_DEV does not look like a partition"
fi

PART_NUM=$(cat "$PART_SYSFS/partition")
PARENT_NAME=$(basename "$(readlink -f "$PART_SYSFS/..")")
PARENT_DEV="/dev/$PARENT_NAME"

if [ ! -b "$PARENT_DEV" ]; then
    skip_for_now "parent device $PARENT_DEV not found, will retry next boot"
fi

# Only grow when the root partition is the last one on its disk.
MAX_PART_NUM=0
for p in "/sys/class/block/$PARENT_NAME"/*/partition; do
    [ -e "$p" ] || continue
    n=$(cat "$p")
    if [ "$n" -gt "$MAX_PART_NUM" ]; then
        MAX_PART_NUM=$n
    fi
done

if [ "$PART_NUM" -ne "$MAX_PART_NUM" ]; then
    settled "partition $PART_NUM is not the last partition on $PARENT_DEV (last is $MAX_PART_NUM)"
fi

# After the image was dd'd to bigger media, the primary GPT header still
# describes the disk's old (smaller) size and the backup header sits right
# after the old last partition instead of at the real end of the disk.
# parted notices the mismatch and asks interactively whether to "fix" the
# GPT to the real disk size; in --script mode it can't answer that prompt
# and just fails with "Unable to satisfy all constraints on the partition".
# sgdisk -e rewrites the GPT headers to match the disk's actual size first
# (the same fix growpart's GPT backend applies), which is what actually
# makes the extra space visible to parted below. It is a no-op if the
# headers already match the real disk size.
sgdisk -e "$PARENT_DEV"

# Sizes in 512-byte sectors, read straight from sysfs (no extra tools
# needed). Logged before/after so `journalctl -u rootfs-resize.service -b`
# shows unambiguous evidence of what actually happened, since --script mode
# otherwise prints nothing on success.
PART_SECTORS_BEFORE=$(cat "$PART_SYSFS/size")
log "partition $PART_NUM size before: $((PART_SECTORS_BEFORE / 2048)) MiB - growing to fill $PARENT_DEV"
parted --script --align optimal "$PARENT_DEV" resizepart "$PART_NUM" 100%

# Tell the running kernel about the new partition size. This only touches
# the partition being grown (the last one), so it is safe to do while it is
# mounted as the root filesystem.
partx --update --nr "$PART_NUM" "$PARENT_DEV"

PART_SECTORS_AFTER=$(cat "$PART_SYSFS/size")
log "partition $PART_NUM size after: $((PART_SECTORS_AFTER / 2048)) MiB"

log "growing filesystem on $ROOT_DEV"
resize2fs "$ROOT_DEV"

settled "grown $PARENT_DEV partition $PART_NUM ($((PART_SECTORS_BEFORE / 2048)) MiB -> $((PART_SECTORS_AFTER / 2048)) MiB) and the filesystem on $ROOT_DEV"

