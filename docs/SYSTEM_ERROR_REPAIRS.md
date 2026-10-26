# Whole-system source review, October 26

This review covers storage and update error propagation and the retained
helper lifecycle. Camera, IR reconnection, security-notification and policy
changes are maintained in their companion patches.

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

## Regression checks

55 storage/update cases and 14 MiCharge cases pass. The same final cases fail
30 and 6 times respectively against saved pre-edit source. C++ tests execute
actual production functions/blocks with fake mount/format/writer dependencies
and UBSan. Java tests compile the actual MiChargeClient with modeled Binder and
Handler APIs, including queued death and close while the worker is occupied.
They perform no real mount, unmount, format, update, HAL operation or device I/O.

Eight platform files replay byte-for-byte from their pinned revisions and the
updated companion patches. The fan class lives directly in the device tree.
All 36 companion patch hashes/reverse checks and edited-repository diff checks
pass.

Reproduce from the Android root:

```sh
python3 device/xiaomi/warsaw/patches/system-errors/tests/run-system-error-tests.py --output /tmp/system-errors.json
python3 device/xiaomi/warsaw/patches/check-patches.py
```

## Camera and infrared integration

The source configuration retains stock camera limits and the default IR HAL
chain alongside the storage/update changes.

- IR follows consumer_ir -> IConsumerIr/default -> the retained stock service
  and consumerir.qcom.so -> /dev/ir_spi. The service executable keeps its OEM
  `example` filename. The full build includes its declaration and feature flag.
- Stock framework bytecode uses lazy IR binding and analytics. The existing HAL
  carries the transmit path.
- The retained ARM64 encoder was exercised under Unicorn for 10 generated
  patterns at 33/36/38/40/56 kHz. All 370 mark/space windows matched; encoded
  carrier errors are below 1 percent. The driver uses 32-bit SPI words at
  1.92 MHz. Syscall and libc boundaries are modeled by the harness.
- Camera metadata paths preserve logical and physical active-array ownership.
  Crop bounds and the AWB 63-slot total/20-zone group limits are retained.
- The stock panel table uses vendor intent 257 for hal_srgb/hal_p3. Display
  intent mapping and calibration tables are preserved.

The review also inspected custom display/thermal, UDFPS/keyguard sensors,
audio/fan hooks, Bluetooth extension handling and telephony integration, and
categorized the supplied non-camera boot/runtime errors. This is a bounded
review, not proof that every system subsystem is defect-free. Logs contain
historical buffered entries as well as the live test interval; aggregate counts
must not be described as strict-mode-only counts. Previously fixed issues were
not re-applied just because they remain in old installed-system logs.
