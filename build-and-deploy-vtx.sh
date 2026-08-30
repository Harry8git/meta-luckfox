#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

NATIVE_SYSROOT="${SCRIPT_DIR}/build/tmp/work/luckfox_pico_zero-oe-linux-gnueabi/rockchip-rkaiq/1.0/recipe-sysroot-native"
TARGET_SYSROOT="${SCRIPT_DIR}/build/tmp/work/luckfox_pico_zero-oe-linux-gnueabi/rockchip-rkaiq/1.0/recipe-sysroot"
CC="${NATIVE_SYSROOT}/usr/bin/arm-oe-linux-gnueabi/arm-oe-linux-gnueabi-gcc"
MPP_INC="${SCRIPT_DIR}/build/tmp/sysroots-components/cortexa7t2hf-neon-vfpv4/rockchip-mpp/usr/include"
MPP_LIB="${SCRIPT_DIR}/build/tmp/sysroots-components/cortexa7t2hf-neon-vfpv4/rockchip-mpp/usr/lib"

export PATH="${NATIVE_SYSROOT}/usr/bin/arm-oe-linux-gnueabi:${NATIVE_SYSROOT}/usr/bin:${PATH}"

if [ ! -f "$CC" ]; then
    echo "ERROR: Cross compiler not found at $CC"
    exit 1
fi

echo "==> Compiling apps/vtx.c ..."
$CC -O2 \
    -mcpu=cortex-a7 -mfpu=neon-vfpv4 -mfloat-abi=hard \
    -fno-tree-vectorize -mno-unaligned-access \
    --sysroot="$TARGET_SYSROOT" \
    -I"$MPP_INC" \
    -L"$MPP_LIB" \
    ./apps/vtx.c \
    -lrockchip_mpp -lpthread \
    -o ./apps/vtx

echo "==> Built successfully: ./apps/vtx"
ls -lh ./apps/vtx

if [ "$1" = "--deploy" ] || [ "$1" = "-d" ]; then
    TARGET_IP="${2:-169.254.100.1}"
    echo "==> Deploying to root@${TARGET_IP}:/usr/bin/vtx ..."
    scp ./apps/vtx root@${TARGET_IP}:/usr/bin/vtx
    echo "==> Deployed successfully to target."
fi
