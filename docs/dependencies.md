# Source dependencies

Use the [pinned manifest](lineage-24.0-pinned.xml) with the
`lineage_warsaw-cp2a-userdebug` target. It records the platform base for every
entry in [series.json](../patches/platform/series.json).

## Platform

The LineageOS manifest supplies Qualcomm SM8750 audio, display, data, common
hardware and policy projects, the Lineage framework, and `tools/extract-utils`.
No additional LineageOS device/common tree is inherited, so
`lineage.dependencies` is an empty list.

The vendor generator is pinned to extract-utils revision
`78ba5d58c4b06712e18bb387a2a72eac8fbc8d70`. Its input and output fingerprints
detect generator changes. Review the generated difference before updating the
integration rules.

## Vendor and kernel

Place the [vendor repository](https://github.com/lrqtech/android_vendor_xiaomi_warsaw_lineage24)
at `vendor/xiaomi/warsaw` and retrieve its LFS objects. Its `boot/`, `firmware/`,
`configs/`, and `Xpan/` directories are required alongside `proprietary/`.
The prepared userspace importer does not recreate those directories from an OTA.

The [kernel repository](https://github.com/lrqtech/android_kernel_xiaomi_warsaw_lineage24)
contains the GKI source, Kleaf configuration, production KMI list, and header
layout patch. Keep the selected Image, signed modules, DTB/DTBO, recovery modules,
and firmware together. The selected kernel uses 4 KiB pages.

## Platform patches

The 36 companion patches cover the modified platform projects, including the
subsequent recovery, filesystem, update-engine, camera and restorecon repairs. Cache files are excluded. Each patch was checked against its recorded
base and reverse-checked against the working source.

Use `apply-platform-patches.py` as described in [BUILD.md](BUILD.md), then run
`patches/check-patches.py` to check the applied state. Source inputs and regression
tests elsewhere in `patches/` remain available to the build and companion projects.
