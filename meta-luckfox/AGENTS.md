# AGENTS.md

# AGENTS.md

Yocto/BitBake layer (`meta-luckfox`, collection name `meta-luckfox`) providing BSP and multimedia support for the Luckfox Pico Pro/Max board (Rockchip RV1106), targeting the `wrynose` release series. This repo IS the layer (conf/, recipes-*, files/ live at the repo root). See [README.md](README.md) for full setup instructions and dev notes.

## Build and test

This repo does not ship a build directory or the external OE layers — set those up per [README.md](README.md#adding-this-layer-to-your-build) (clone `bitbake`/`openembedded-core`/`meta-yocto` at `yocto-6.0.2` and `meta-openembedded` at `wrynose`, then add this layer + `meta-openembedded/meta-oe`).

Kernel-only rebuild loop and hardware test commands (encoder, camera i2c) are in [README.md](README.md#dev-notes).

## Architecture notes

- Machine confs use a `conf/machine/<machine>.conf` + `conf/machine/include/*.inc` split: `luckfox-pico-pro-max.conf` requires `luckfox-rv1106.inc`, which requires `luckfox-common.inc`. Follow this layering if more Luckfox/RV1106-family boards are added.
- RV1106/RV1103 are **headless, no-IOMMU** SoCs: `rockchip-librga` and `rockchip-mpp` both explicitly disable DRM and use dma-heap/CMA instead.
- The kernel (`recipes-kernel/linux/linux-rockchip_*.bb`) and `rockchip-mpp` recipes carry out-of-tree patches enabling the RV1106 VEPU540C encoder path (mirrors the RK3528 encoder); see the numbered patches in each recipe's patch directory before touching encoder-related kernel/MPP code.
- `luckfox-iqfiles` ships vendor ISP calibration data under a **proprietary** license — don't relicense or redistribute assumptions when touching that recipe.
- Several recipes pull from forked mirrors (`github.com/buldo/rga-mirrors`, `github.com/buldo/rkaiq-mirrors`) pinned by `SRCREV`, not upstream — bump these deliberately and update `SRCREV` together with any patch changes.

## Conventions

- New recipes/patches go under `recipes-<category>/<recipe>/`, matching the existing `recipes-bsp`, `recipes-core`, `recipes-graphics`, `recipes-kernel`, `recipes-multimedia` categories.
