# Whole-system source review, October 26

Scope: continue offline repairs and host validation. Do not build Android or the
kernel, package images/OTA/APKs, or flash. Existing camera, IR reconnection,
security-notification and policy repairs remain intact.

## New repairs

### Storage (system/vold)

Unmount failures formerly changed the volume to Unmounted unconditionally, and
format() ignored the unmount result. A failed child unmount could also be lost
while its backing volume was torn down. Failures now retain a retryable Mounted
state, preserve live children, and stop format before any filesystem formatting.
Physical removal still destroys all remaining children and consumes their
created state, even when unmount fails; it reports the first failure. This keeps
the destructor invariant and does not leave created children behind on removal.

Private-volume unmount now returns ForceUnmount failure. FUSE pass-through
failure reaches its caller; public/emulated callers return the actual status
instead of a later errno. Public-volume shared-user bind, sdcardfs and lazy raw
unmount failures now stop teardown before mount paths are discarded. ASEC
teardown is restricted to the primary volume that owns that global mount.

### System update (system/update_engine)

The last partition's FinishedInstallOps/Close failures now stop Write with
kDownloadWriteError, consistent with intermediate partitions. TransferComplete
also propagates writer Close failure and suppresses DownloadComplete/output on
failure; a pre-existing network failure retains its error. A COW Finalize(false)
with errno zero now returns -EIO instead of false success.

### Historical MiCharge helper (subsequently removed)

The user subsequently requested obsolete-component cleanup. This helper and
its test runner are now deleted; the 14-case result below is historical. See
[REMOVED_COMPONENTS_20261026.md](REMOVED_COMPONENTS_20261026.md).

The product audit established that `fan/Android.bp` excludes
MiChargeClient. Its 14 host cases validate that helper source only and do not
prove a fix to the active fan service. The selected path is FanService plus
FanPwmController; its separate fixes and 23-case regression are recorded in
`PROJECT_AUDIT_20261026.md`.

MiChargeClient now associates each death recipient with its connection. An old
queued death cannot invalidate a replacement. Reconnection releases the old
recipient; close queues cleanup after the in-flight worker operation and is
idempotent. Normal setter/deadline/readback behavior is retained.

## Validation and limits

55 storage/update cases and 14 MiCharge cases pass. The same final cases fail
30 and 6 times respectively against saved pre-edit source. C++ tests execute
actual production functions/blocks with fake mount/format/writer dependencies
and UBSan. Java tests compile the actual MiChargeClient with modeled Binder and
Handler APIs, including queued death and close while the worker is occupied.
They perform no real mount, unmount, format, update, HAL operation or device I/O.

Eight platform files replay byte-for-byte from their pinned revisions and the
updated companion patches. The fan class lives directly in the device tree.
All 36 companion patch hashes/reverse checks and edited-repository diff checks
pass. These are host checks, not full Android compilation or phone acceptance.

Reproduce from the Android root:

```sh
python3 device/xiaomi/warsaw/patches/system-errors/tests/run-system-error-tests.py --output /tmp/system-errors.json
python3 device/xiaomi/warsaw/patches/check-patches.py
```

## Original required issues remain open

The original crop/AWB and infrared failures are required acceptance items. None
of the new storage/update/fan repairs is a substitute for them.

- IR default registration is confirmed in current source and the October 25
  phone records: consumer_ir -> IConsumerIr/default -> the stock service and
  consumerir.qcom.so -> /dev/ir_spi. hasIrEmitter returns true. The `example`
  executable name belongs to the retained stock executable. This is not a
  placeholder emitter. The phone was not connected during this review.
- The original stock framework was extracted from the existing OTA and read as
  bytecode. Its IR extension contains lazy binding and analytics, not an extra
  power/GPIO transmit sequence missing from LineageOS.
- The unchanged ARM64 stock encoder was executed under Unicorn for 10 generated
  patterns across 33/36/38/40/56 kHz. All 370 mark/space windows matched; encoded
  carrier errors are below 1 percent. The matching stock driver uses 32-bit SPI
  words at 1.92 MHz. Syscalls/libc are modeled, and this does not measure light
  output or reproduce the missing failing application's original IR pattern.
- Crop evidence still mixes a 4096x3072 logical crop with a 3264x2448 active
  array; existing traces reproduce the logged oversize results. Working
  permissive capture also logs crop errors. Request-internal optical-zoom/bokeh
  metadata and the active pipeline flag/ID are absent for the failing frame.
- AWB has a 63-slot global pool, not an arbitrary safe-to-increase limit. A
  normal modeled frame schedule resets it before scene registration; the
  failing runtime active-module list and tuning state are absent. Raising the
  limit or clearing live handles cannot be justified from the supplied trace.
- Whole-system log review also identified persistent display sRGB/P3 SDR intent
  0 lookup failures. The stock panel table has no matching profiles and uses
  vendor intent 257 for hal_srgb/hal_p3. Runtime advertised intents and the OEM
  selection contract are not yet established; no arbitrary color remap or
  calibration replacement was made. This issue is open, not repaired.

The review also inspected custom display/thermal, UDFPS/keyguard sensors,
audio/fan hooks, Bluetooth extension handling and telephony integration, and
categorized the supplied non-camera boot/runtime errors. This is a bounded
review, not proof that every system subsystem is defect-free. Logs contain
historical buffered entries as well as the live test interval; aggregate counts
must not be described as strict-mode-only counts. Previously fixed issues were
not re-applied just because they remain in old installed-system logs.
