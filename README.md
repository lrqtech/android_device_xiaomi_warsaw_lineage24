# Xiaomi warsaw

Device configuration for unofficial LineageOS 24.0 (Android 17).

| Setting | Value |
| --- | --- |
| Product | `lineage_warsaw-cp2a-userdebug` |
| Platform | Qualcomm SM8750 / sun |
| Firmware baseline | OS4.0.0.15.XHPCNXM |
| Kernel | 6.6.118, 4 KiB pages, KUnit disabled |
| SELinux | Enforcing by default in normal boot and recovery |

## Sources and build

| Repository | Checkout path |
| --- | --- |
| [Device](https://github.com/lrqtech/android_device_xiaomi_warsaw_lineage24) | `device/xiaomi/warsaw` |
| [Vendor](https://github.com/lrqtech/android_vendor_xiaomi_warsaw_lineage24) | `vendor/xiaomi/warsaw` |
| [Kernel](https://github.com/lrqtech/android_kernel_xiaomi_warsaw_lineage24) | `common` in a separate Kleaf workspace |

Use the `warsaw-20261027` tag in all three repositories. Development continues
on `lineage-24.0`; older tags preserve their original source sets.

Follow [BUILD.md](docs/BUILD.md) to sync the pinned platform, retrieve vendor LFS
objects, and apply the 36 companion patches. Keep dependency, neverallow,
Treble labeling and Soong checks enabled.

Manual fan controls are integrated into Settings, before Apps on the homepage.
The unused OEM fan service and the excluded call-audio, gaming-display and
gesture settings components are removed.

## Vega / SenseTime

The vendor baseline excludes the Vega/SenseTime libraries, their Mi FD and ECD
consumers, face ROI encoder filter, models, and portrait-repair plugin. The
associated facial attributes, closed-eye frame selection, ROI processing and
portrait restoration are disabled. The camera selector uses the retained Qti FD
node. Its descriptor and callback ABI were checked before changing the selection.

The two retained-library adaptations are reproducible with exact-hash scripts;
the anchor plugin cannot re-enable ECD through a saved property. The original
Qti FD library, sensor selection, crop and AWB configuration are preserved.
See [proprietary files](docs/proprietary-files.md) and the vendor
[difference list](https://github.com/lrqtech/android_vendor_xiaomi_warsaw_lineage24/blob/warsaw-20261027/docs/DIFFERENCES.md).
Camera image quality and codec behavior still require device testing.

## Vendor tools

```sh
device/xiaomi/warsaw/setup-makefiles.py --check
device/xiaomi/warsaw/extract-files.py --check /path/to/prepared/vendor/warsaw
python3 device/xiaomi/warsaw/extract/tests/test_prepared_inputs.py
```

The tools verify 5,559 prepared userspace inputs and reproduce 2,265 Blueprint
modules. SHA256 pins, inline SHA1 pins, and retirement checks reject mismatched
inputs and obsolete startup components. Raw OTA and running-device extraction
remain unsupported. See [dependencies](docs/dependencies.md).

## Validation and known limits

The October 27 source review checks normal and recovery SELinux policies for
both `user` and `userdebug`, split-policy mappings, init declarations, module
dependencies, and the Settings fan path. It also removes obsolete service
definitions and debug triggers that stopped thermal control or changed modem
log permissions to 777. See [SELinux and startup review](docs/SELINUX_AUDIT.md).

A clean full Android build from `warsaw-20261027` completed on October 27.
The full OTA and seven matching first-install images passed signature, AVB,
partition-content, VINTF and embedded-policy checks. See
[build results](docs/BUILD_RESULT_20261027.txt) for the archive and OTA hashes.
Boot, recovery, updates and hardware behavior have not been qualified on a phone.
Reported original-camera crop/AWB and IR appliance response remain unconfirmed.

This tree is not ready for official admission. Installed-system extraction,
remaining blob provenance, feasible OEM module source coverage and hardware
acceptance are recorded in [OFFICIAL_SUPPORT.txt](docs/OFFICIAL_SUPPORT.txt).
See [STATUS.md](docs/STATUS.md), [PROVENANCE.md](docs/PROVENANCE.md) and [NOTICE](NOTICE).
