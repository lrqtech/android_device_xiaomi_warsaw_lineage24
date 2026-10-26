# Deep service lifecycle review, October 26, 2026

This continuation changes source and runs host fault tests only. No Android or
kernel build, APK/image/OTA packaging, flashing, device connection or hardware
I/O was performed. Previously completed repairs were preserved.

## Three repaired paths selected by the current product

1. **Display thermal/Dolby callback registration.** If unregister takes effect
   remotely but its reply fails, the old code keeps `registered = true`. A new
   subscriber can then reuse a callback that the HAL has already removed.
   Mark registration unconfirmed before unregister, retain the token and
   `mayOwnSlot` for cleanup, reject stale events, and retry cleanup/registration.
   The active chain is DisplayPowerController -> WarsawThermalBrightness ->
   WarsawThermalDisplayListener. Thermal caps and HDR/Dolby tables are unchanged.
2. **Biometric listener registry.** Registering the same Binder more than once
   duplicated listeners and death recipients. Linking to an already dead Binder
   could leave its listener retained because insertion preceded linkToDeath.
   Deduplicate by Binder identity, refresh the existing listener's initial
   enrollment snapshot, link death before inserting, and serialize death cleanup
   with registration. FingerprintService calls this registry. These changes
   preserve authentication/enrollment behavior and do not establish physical
   fingerprint or AOD acceptance.
3. **Camera-specific vibration gain cleanup.** The gain setter can take effect
   even when its Binder reply throws. Previously cleanup was armed only after a
   successful return, leaving an uncertain one-shot gain without a reset attempt.
   Arm cleanup before the setter and attempt the existing -1 reset in finally.
   Do not replay vibration on failure. If the reset itself fails, the error is
   logged and contained; actual gain clearance is not guaranteed. The active
   call is PerformPrebakedVibratorStep -> WarsawHapticHooks. This does not repair
   original crop/AWB behavior.

The three framework sources are in the pinned frameworks/base companion patch.
Mirrored device sources remain byte-identical. The series SHA-256 was refreshed;
two Make guards cover the biometric registry's deduplication and serialized
death cleanup. After the requested obsolete-component cleanup, all 36 companion
checks pass; 290 replayed files, 11 remaining mirrors and 44 remaining guards
match. Deleted fan/display endpoints are no longer carried by those patches.

## Host verification

The current suite compiles complete selected source classes with modeled
Android Binder/Parcel, service, Handler and framework APIs. A real host thread
exercises biometric registration/death interleaving. No Android Binder driver
or HAL is contacted. Its 28 cases pass: thermal callback 8, biometric registry
10, camera vibration gain 10. The same active cases reproduce ten failures
(3/5/2 respectively) against saved source from immediately before this batch.

Reproduce from the Android source root:

```sh
python3 device/xiaomi/warsaw/patches/service-lifecycle/tests/run-tests.py --report /tmp/warsaw-service-lifecycle.json
python3 device/xiaomi/warsaw/patches/check-patches.py
```

The user explicitly requested removal of components already omitted from the
product. Inactive display/FOD helpers and their 23 test cases are now deleted,
as described in [REMOVED_COMPONENTS_20261026.md](REMOVED_COMPONENTS_20261026.md).
They are not included in the current 28-case result and were not re-enabled.
The earlier pre-cleanup 51-case output is historical evidence only.

The batch also reviewed audio provider extension handling, thermal fallback
selection, telephony carrier-name integration and Bluetooth codec-index
compatibility changes. No additional reproducible defect was established in
those reads; they are not certified by the lifecycle tests.

## Still unresolved

Original camera crop/AWB, IR appliance non-response, strict cold boot,
StrongBox hardware acknowledgement and the SDR color-intent selection mismatch
remain open. IR default-service registration is established by source and
earlier supplied phone logs; optical operation is not. No runtime verdict is
inferred from mocked host tests or older October 24 build outputs. These results
do not establish that the whole system has no hidden defects.
