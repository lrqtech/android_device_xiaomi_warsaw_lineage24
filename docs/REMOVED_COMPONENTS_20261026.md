# Removed product component cleanup, October 26, 2026

This cleanup follows the selected product package list. It removes the
excluded components and their source, policy and resource leftovers.

## Removed

- WarsawDisplay and display profiles: APK/Soong definition, manifest, Java,
  JNI, AIDL, resources, assets, privilege allowlist, tests, dedicated SELinux
  domain and its seapp mapping. Its exclusive frame-rate lease was removed from
  libgui, SurfaceComposer AIDL, SurfaceFlinger and the existing test double.
  The original GameManager frame-rate setter is restored; camera FPS monitoring
  and actual UDFPS display integration remain intact.
- WarsawCallAudio: obsolete application definition, empty manifest, UI and
  resources. audio/audio.mk still selects the actual audio vendor extension.
- WarsawGestures: application definition, empty manifest, settings activity
  and resources. SystemUI keyguard sensor integration remains intact.
- Retired automatic fan: five excluded Java classes, scenario/policy JSON,
  unused option arrays/strings, old preference state and MiCharge-only tests.
  Seven supplemental observer privileges are removed. The selected six manual
  PWM source files and their eight UI strings remain. The host regression now
  uses the actual FanPreferences constants rather than a substitute.
- Fan-only recorder/keyguard Binder methods and two helper classes are removed.
  The five affected framework interface/service files exactly match their
  pinned originals after removal. These were endpoint additions with no active
  consumer; existing standard interfaces and methods are preserved.
- The unreferenced FOD dream helper is deleted from both the device tree and
  its untracked platform copy. The service-lifecycle test runner drops all
  23 cases for removed display/FOD sources and retains only 28 active cases.
- Nine obsolete Make guards and three obsolete mirror checks are removed.
  Companion patches no longer recreate removed helpers or endpoints.

The cleanup deletes 59 files. A path/hash manifest and scoped pre-cleanup
snapshot are kept in the task's intermediate work directory; these are not
product inputs. Historical audit documents remain as records and explicitly
identify superseded inactive-helper tests.

## Preserved selected paths

The manual PWM Settings service, its boot entry, the currently selected camera
PowerKeeper bridge, native display configuration, framework thermal control,
audio HAL extension, camera frame statistics, UDFPS/SystemUI and all prior
independent boot/security/storage/OTA fixes remain selected. The audio/display
directories retain only their still-used Make configuration where applicable.
No component was added back to PRODUCT_PACKAGES.

## Verification

- 67,325 eligible source/build/policy files across device, frameworks/base,
  frameworks/native, Settings and vendor were searched. No removed-component
  source reference remains in that scan. Historical documentation mentions
  are classified separately. This is a scoped source scan, not a claim about
  every third-party source or old build artifact in the Android checkout.
- All 59 deletion targets are absent. All six selected fan files exist, and
  their eight remaining string resources match actual UI references.
- Framework lifecycle: 28/28 pass. Manual fan regression: 23/23 pass. These
  compile actual source/logic with modeled APIs and perform no hardware writes.
- All 36 companion hash/reverse checks pass. All 290 affected platform files
  replay byte-for-byte from pinned revisions; no extra tracked changes lie
  outside those patch paths. All 11 remaining mirrors and 44 guards pass.
- Prepared blob/vendor build metadata verification passes. Device syntax checks
  pass for 30 Python, 275 XML, 7 JSON inputs and one shell script. Whitespace
  checks pass in device, frameworks/base and frameworks/native.

The retained integrations and removal guards are included in the October 27
full build. See [build results](BUILD_RESULT_20261027.txt).
