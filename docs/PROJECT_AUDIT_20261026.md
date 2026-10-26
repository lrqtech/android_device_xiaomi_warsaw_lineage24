# Product-wide offline audit, October 26, 2026

This audit examines the current Warsaw device/vendor integration, its 36 pinned
platform patches and the paired kernel inputs. Source and host checks only:
no Android/kernel build, APK/OTA/image packaging, flashing or attached phone.
The pre-edit working tree already contained previous repairs; those were
preserved and validated rather than reapplied.

Later cleanup requested by the user removed the inactive MiCharge/display
sources and their test entry points. The counts below describe this earlier
audit, not the current runnable suite. See
[REMOVED_COMPONENTS_20261026.md](REMOVED_COMPONENTS_20261026.md) for the resulting
product and current checks.

## New confirmed defects repaired

### Active manual fan control

The product explicitly includes FanService and FanPwmController in Settings.
MiChargeClient and the old automatic fan engine are not in that filegroup.
Previous MiCharge helper validation must not be counted as active fan repair.

- A rejected confirmed-PWM Settings write was cached as successful, so later
  identical readback never retried publication. Cache only successful writes.
- A start request arriving during apply could be removed by the old apply's
  trailing reschedule, delaying the new setting for 30 seconds. Rescheduling is
  now serialized through the owning Handler without deleting queued changes.
- User switches and service teardown could leave old hardware work active.
  Check current user and stop state before each sysfs write and final success;
  superseded work queues a fresh request. This does not undo an already entered
  kernel write and is not a claim of atomicity with Android user switching.
- Retry delay incorrectly depended on the last successfully published status;
  a failed publication could retain the healthy 30-second delay after a hardware
  error. Use current operation/publication success to choose the five-second
  failure retry. Settings status exceptions are contained on the owner thread.

The 23-case host suite compiles the complete actual FanService and the actual
FanPwmController apply/check logic. Android scheduling/Settings and sysfs I/O
are modeled. Before: 12 failures. After: 23 pass. No real fan write occurred.

### Vendor payload index

The repository verifier failed because its global index still recorded the old
GKI release paths and old hashes for previously repaired userspace files. The
current 78-module GKI set independently passes the kernel repository checker;
the seven userspace changes match device/extract/proprietary.sha256. Only after
that cross-check were 180 changed/new entries synchronized and 92 obsolete GKI
paths removed. All 6,247 payload hashes and sizes now pass. No binary payload
was rebuilt or rewritten. The vendor README no longer claims an unchanged
October 15 kernel.

## Coverage and evidence

- All 36 companion patches replay against pinned bases and match their current
  platform files. Tracked differences outside their patch paths were checked.
  Fourteen mirrored sources and 51 literal Make integration guards match.
- Device Python/XML/JSON and shell syntax were checked. The linker JSON allows
  whole-line // comments per its real Soong consumer; it is not malformed JSON
  for that input format.
- Prepared userspace import/build metadata check passes for 5,589 inputs.
- Kernel pairing: 177 hashes, 78 modules, 4 KiB pages, KUnit disabled.
- 199 actual init scripts pass host_init_verifier with source OEM AIDs; ueventd
  rules use a different grammar and were not misreported as init failures.
- 120 vendor and 40 ODM VINTF inputs assemble separately and together; both
  device matrices parse. This is declaration merging, not full product FCM or
  running HAL compatibility. Proprietary AIDL metadata informational messages
  are expected with retained prebuilts.
- 310 distinct service declarations have no duplicate names in the scanned
  device/vendor init candidates. Literal device copy sources (48 references)
  exist. Generic disabled/factory services and source-built executables are
  separated from missing prepared-file candidates; a disabled definition is
  not by itself a proved normal-boot defect.
- 2,207 prepared ELF files parse. The 34 dependencies supplied outside that
  prepared set are catalogued. Android libraries have candidates in the prior
  product; DSP libgcc is outside this Android lookup. This does not validate
  current linker namespace visibility, DSP firmware or symbol compatibility.
- Twenty host regression groups pass: 367 test cases plus 19 display-policy
  assertions. This includes 14 cases for inactive MiChargeClient, clearly
  separated from the selected product. Actual-source suites cover camera
  request/metadata/session lifecycle, IR restart/wakelock, KeyMint early boot
  and root of trust, CT job/unlock, storage/OTA failures, active fan, keyguard
  sensor policy, display policy and Java/native CameraMind parsers. Native
  parser/control tests use UBSan. Android socket/SELinux and Binder device
  tests were not substituted with a false host success.

## Open acceptance items

Original crop/AWB and IR appliance non-response are still mandatory, unresolved
items. IR default-service registration is established by source and the supplied
phone record; optical output is not. Crop/AWB traces lack the failing frame's
internal pipeline/tuning state needed to choose a safe binary change. The
previous display SDR intent 0 versus stock vendor intent 257 mismatch also
remains unresolved. Strict cold boot and StrongBox hardware acknowledgement
still need their missing runtime evidence. Hardware acceptance for calls/IMS,
GNSS, Wi-Fi/Bluetooth, audio, UDFPS, thermal/display and updates is not established
by source inspection. Existing October 24 build outputs are not validation of
the newer source tree. No claim is made that the entire Android project is
free of hidden defects.

Reproduce new active-fan regression from the Android source root:

```sh
python3 device/xiaomi/warsaw/fan/tests/run-active-fan-tests.py --report /tmp/active-fan.json
python3 vendor/xiaomi/warsaw/verify-files.py
python3 device/xiaomi/warsaw/setup-makefiles.py --check
python3 device/xiaomi/warsaw/patches/check-patches.py
```
