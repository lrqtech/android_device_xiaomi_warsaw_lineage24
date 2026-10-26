# Project status

Unofficial LineageOS 24.0 for Xiaomi warsaw, based on Android 17 and firmware
OS4.0.0.15.XHPCNXM.

## October 27 build

The clean full build from `warsaw-20261027` completed with
`m --no-skip-soong-tests -j24 bacon`. The full A/B OTA contains 45 partitions.
The first-install archive includes that OTA, seven matching boot-chain images,
Chinese installation instructions and a SHA256 manifest.

OTA signatures, payload operations, AVB chains, VINTF, EROFS
integrity, partition content and embedded policies passed the recorded build
and host checks. All 13 archive members passed CRC and SHA256 verification.
See [build results](BUILD_RESULT_20261027.txt) and [build instructions](BUILD.md).

## Startup and SELinux

Normal boot and recovery use global enforcing. The device adds no permissive
boot argument or device-specific permissive domain. Upstream userdebug domains
`su` and `backuptool`, plus `recovery` in the recovery policy, retain their debug
exceptions. User policies have no permissive domains.

The policy review covers all four normal/recovery user/userdebug variants,
split-policy mappings, init entrypoints, file/property contexts and selected
ioctl contracts. Forty obsolete service definitions, their callers and nine
dedicated inputs are removed. Legacy thermal-stop and modem chmod-777 actions
are removed. See [SELinux review](SELINUX_AUDIT.md) and
[startup changes](BOOT_SECURITY.md).

## Kernel and modules

The boot image uses the paired 6.6.118 GKI with 4 KiB pages and KUnit disabled.
All 78 signed GKI modules match the vendor input set. The retained OEM module
set, firmware, DTB/DTBO and load order are recorded with payload hashes.

## Settings and hardware integration

- Cooling fan controls are on the Settings homepage, immediately before Apps.
  The compiled APK includes the fragment, controller, boot receiver and service.
- IR is registered as `android.hardware.ir.IConsumerIr/default`, with its init
  service, feature declaration and retained `consumerir.qcom.so` HAL. Framework
  reconnection and transmit-wakelock handling have 20 regression cases.
- Camera request identity is propagated through API1/API2 and provider-scoped
  metadata. Copy failures, shared-request bookkeeping, CHI index bounds and
  regional beauty configuration have targeted regression coverage.
- Storage failure propagation, update-writer finalization, KeyMint early-boot
  notification and callback lifecycle changes are included in the companion
  platform patch series.

See [IR service changes](IR_SERVICE_REPAIRS.md),
[camera request handling](CAMERA_REQUEST_IDENTITY.md),
[camera integration](CAMERA_AVC_REPAIRS.md),
[system error handling](SYSTEM_ERROR_REPAIRS.md) and
[service lifecycle](DEEP_SERVICE_REPAIRS_20261026.md).

## Vega / SenseTime and product cleanup

Vega/SenseTime libraries, models, Mi FD/ECD consumers, face ROI encoding filter
and portrait-repair plugin are removed. Facial attributes, closed-eye selection,
related ROI processing and portrait restoration are disabled. The selector uses
the retained Qti FD node. Original sensor, crop and AWB configuration is retained.

The excluded call-audio, gaming-display and gesture components and their
leftovers are removed from the selected product. The fan Settings service and
selected platform integrations are retained. See
[component cleanup](REMOVED_COMPONENTS_20261026.md).

## Source preparation

`extract-files.py` accepts the recorded prepared vendor root. SHA256 and inline
SHA1 pins, exact-hash native preparation and retirement guards validate 5,559
userspace inputs; generation reproduces 2,265 Blueprint modules. Separate kernel,
firmware and auxiliary input sets retain their own manifests. The 36 companion
platform patches replay against pinned bases.

See [proprietary files](proprietary-files.md), [provenance](PROVENANCE.md) and
[LineageOS integration references](OFFICIAL_SUPPORT.txt).
