require conf/machine/include/luckfox-common.inc

DEFAULTTUNE = "cortexa7thf-neon-vfpv4"
require conf/machine/include/arm/armv7a/tune-cortexa7.inc

MACHINEOVERRIDES =. "rv1106:"
UBOOT_MACHINE = "rv1106_defconfig"

SERIAL_CONSOLES = "115200;ttyFIQ0"
RK_SOC_FAMILY = "rv1106"
RK_ISP_VERSION = "3.2"
KBUILD_DEFCONFIG = "luckfox_rv1106_linux_defconfig"