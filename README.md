# Xiaomi warsaw

Device configuration for unofficial LineageOS 24.0 (Android 17).

| Setting | Value |
| --- | --- |
| Product | `lineage_warsaw-cp2a-userdebug` |
| Platform | Qualcomm SM8750 / sun |
| Firmware baseline | OS4.0.0.15.XHPCNXM |
| Kernel | 6.6.118, 4 KiB pages, KUnit disabled |
| SELinux | Permissive in normal boot and recovery |

## Sources

| Repository | Checkout path |
| --- | --- |
| [Device](https://github.com/lrqtech/android_device_xiaomi_warsaw_lineage24) | `device/xiaomi/warsaw` |
| [Vendor](https://github.com/lrqtech/android_vendor_xiaomi_warsaw_lineage24) | `vendor/xiaomi/warsaw` |
| [Kernel](https://github.com/lrqtech/android_kernel_xiaomi_warsaw_lineage24) | `common` in a separate Kleaf workspace |

The three repositories use `lineage-24.0`. The `warsaw-20261024` tag identifies
this source set; `warsaw-20261015` preserves the earlier publication.

## Build

Follow [BUILD.md](docs/BUILD.md) to sync the pinned platform, retrieve the vendor
LFS files, and apply the 33 companion patches. Then run:

```sh
source build/envsetup.sh
lunch lineage_warsaw-cp2a-userdebug
m --no-skip-soong-tests bacon
```

The patch series includes the current recovery mount and sideload fixes,
fastboot cleanup checks, startup ordering, and SELinux label repairs.
Neverallow, ELF dependency, Treble labeling, and Soong checks remain enabled.

The product retains fan controls. The optional call-audio, gaming-display, and
gesture settings screens remain excluded.

## Vendor tools

```sh
device/xiaomi/warsaw/setup-makefiles.py --check
device/xiaomi/warsaw/extract-files.py --check /path/to/prepared/vendor/warsaw
```

These tools verify the 5,589 prepared userspace files and reproduce the vendor
build definitions. Raw OTA and running-device extraction are not yet supported.
See [proprietary files](docs/proprietary-files.md) and
[source dependencies](docs/dependencies.md).

## Status

A clean userdebug build and host image checks completed on October 24, 2026.
The subsequent tree cleanup passed a full Soong/Make configuration build.
Physical-device boot, recovery, and hardware acceptance remain outstanding.
SELinux is intentionally permissive during bring-up; see [STATUS.md](docs/STATUS.md).

Source origins and license scope are recorded in [PROVENANCE.md](docs/PROVENANCE.md)
and [NOTICE](NOTICE). Existing per-file licenses and copyright notices apply.
