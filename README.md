# Xiaomi Warsaw device configuration for LineageOS 24

Device configuration for `warsaw` (Redmi M332BF, Qualcomm sun), targeting
Android 17 / SDK 37 and the `lineage_warsaw` userdebug product.

This is a private development snapshot, not an official LineageOS release
or a claim of device acceptance. See [status and remaining gaps](docs/STATUS.md).

## Repository layout

| Repository | Checkout location | Role |
| --- | --- | --- |
| [Device](https://github.com/lrqtech/android_device_xiaomi_warsaw_lineage24) | `device/xiaomi/warsaw` | Product, board, SELinux, VINTF, overlays and device integration |
| [Vendor](https://github.com/lrqtech/android_vendor_xiaomi_warsaw_lineage24) | `vendor/xiaomi/warsaw` | Proprietary files, firmware and matched boot/module inputs; Git LFS required |
| [Kernel](https://github.com/lrqtech/android_kernel_xiaomi_warsaw_lineage24) | `common` in a separate Kleaf workspace | Full pinned GKI source and the Warsaw no-KUnit target |

Branch: `lineage-24.0`. Snapshot tag: `warsaw-20261015`.
Each repository begins with a new root commit; upstream attribution and
licensing notices remain in the source files.

## Build and integration

Follow [BUILD.md](docs/BUILD.md) with the pinned platform manifest. The
accompanying 28 platform patches are required by this snapshot and must not
be applied to arbitrary platform revisions. The portable patch helper checks
all projects before changing any of them.

The default kernel input is the source-built 4 KiB GKI Image plus all 78
matching production GKI modules. KUnit is disabled. The 538 unique OEM
vendor modules and six recovery variants are retained from the reference OTA.
Kernel building is a separate Kleaf step, not an implicit part of `m bacon`.

## User-facing configuration

- Fan controls are integrated into Settings, immediately above Apps: one
  enable switch and a PWM slider over the full 0-100 range.
- Camera and Mi Remote are the only selected additional OEM consumer apps.
  The base LineageOS apps and required hardware services remain.
- The unused lockscreen gesture, call-audio and gaming-display UI modules
  are omitted from this repository; their audio/display hardware makefiles
  remain because the product needs them.
- System and recovery explicitly request global Permissive in diagnostic
  userdebug/eng builds. Neverallow and Soong tests remain enabled.

## Provenance and licensing

See [PROVENANCE.md](docs/PROVENANCE.md) and the vendor repository's difference
record. Existing copyright and license notices are preserved. This snapshot
does not grant a new license over third-party proprietary material.
