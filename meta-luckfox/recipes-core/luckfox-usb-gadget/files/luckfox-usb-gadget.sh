#!/bin/sh
mountpoint -q /sys/kernel/config || mount -t configfs none /sys/kernel/config 2>/dev/null || true
mkdir -p /sys/kernel/config/usb_gadget/g1
cd /sys/kernel/config/usb_gadget/g1 || exit 1
echo 0x2207 > idVendor
echo 0x0011 > idProduct
mkdir -p strings/0x409
SERIAL=$(grep Serial /proc/cpuinfo 2>/dev/null | awk '{print $3}')
[ -z "$SERIAL" ] && SERIAL=0123456789
echo "$SERIAL" > strings/0x409/serialnumber
echo "Luckfox" > strings/0x409/manufacturer
echo "Pico Zero" > strings/0x409/product
mkdir -p functions/ncm.usb0
mkdir -p functions/acm.GS0
mkdir -p configs/c.1/strings/0x409
echo "CDC-NCM + ACM" > configs/c.1/strings/0x409/configuration
ln -sf functions/ncm.usb0 configs/c.1/
ln -sf functions/acm.GS0 configs/c.1/
echo ffb00000.usb > UDC 2>/dev/null || true
for i in 1 2 3 4 5 6 7 8 9 10; do
    ip link show usb0 >/dev/null 2>&1 && break
    sleep 0.1
done

ip link set usb0 up
ip addr add 169.254.100.1/16 dev usb0