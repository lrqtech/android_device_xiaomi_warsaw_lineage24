# Xiaomi warsaw

Unofficial LineageOS 24 device tree for Xiaomi warsaw (Android 17, SDK 37).
The product is `lineage_warsaw`; builds use the `userdebug` variant.

## Source layout

| Repository | Location |
| --- | --- |
| [Device tree](https://github.com/lrqtech/android_device_xiaomi_warsaw_lineage24) | `device/xiaomi/warsaw` |
| [Vendor files](https://github.com/lrqtech/android_vendor_xiaomi_warsaw_lineage24) | `vendor/xiaomi/warsaw` |
| [Kernel](https://github.com/lrqtech/android_kernel_xiaomi_warsaw_lineage24) | `common` in a separate Kleaf workspace |

All three repositories use `lineage-24.0`. The `warsaw-20261015` tag records
the original source set.

## Building

See [BUILD.md](docs/BUILD.md). Use the supplied platform manifest and apply
the 28 platform patches before building. The patches depend on the revisions
in that manifest.

The vendor tree supplies a source-built 4 KiB GKI kernel and 78 matching
GKI modules, with KUnit disabled. The 538 OEM modules and six recovery
variants come from OS4.0.0.15.XHPCNXM. Kernel builds run separately from the
Android build.

## Device settings

Fan controls sit immediately above Apps in Settings, with an enable switch
and a 0-100 PWM slider. Camera and Mi Remote are the only additional OEM
consumer apps; the standard LineageOS apps and hardware services are retained.
The lockscreen gesture, call-audio and gaming-display screens are omitted.

System and recovery boot with SELinux Permissive in userdebug and eng builds.
Neverallow checks and Soong tests remain enabled. See [STATUS.md](docs/STATUS.md)
for known issues and testing still needed.

Source origins and license information are listed in
[PROVENANCE.md](docs/PROVENANCE.md).
