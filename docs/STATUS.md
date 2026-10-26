# Development status

This is an unofficial LineageOS development tree.

## October 27 source set

The `warsaw-20261027` tags publish the current device, vendor and kernel sources.
The latest startup cleanup removes forty obsolete OEM service definitions,
their callers and nine dedicated files. Thermal control can no longer be stopped
by the legacy radio debug property; modem-log chmod-777 triggers are removed.
Prepared extraction and metadata checks now cover 5,559 inputs and 2,265 modules.
See [SELINUX_AUDIT.md](SELINUX_AUDIT.md) for the current policy and startup checks.

These source changes are included in the clean full build completed on October 27.
The full OTA, seven matching first-install images, Chinese installation guide and
SHA256 manifest are packaged together. OTA/payload signatures, AVB chains, all 45
payload partition hashes, VINTF and actual normal/recovery policies pass host
verification. See [build results](BUILD_RESULT_20261027.txt).
Physical-device boot, Recovery, crop/AWB, infrared output and the hardware
checklist remain outstanding. Older sections below record the checks available
at the time of each source repair; their no-build statements are historical.

## Earlier build results

- A clean `lineage_warsaw-cp2a-userdebug` build and target-files package completed
  on October 24, 2026.
- The OTA, boot and recovery images, policy labels, partition hashes, and package
  signatures passed the recorded host checks.
- The tree cleanup passed `m nothing` with normal dependency and policy checks.
  Prepared import and metadata generation reproduced 5,589 userspace files and
  2,277 Blueprint modules.
- The published platform patches reproduce the modified source trees from their
  pinned bases. The vendor hash manifest covers the prepared payloads.

These checks do not establish successful boot or hardware behavior on a phone.

## Remaining work

- Current source uses SELinux enforcing by default in normal boot and recovery.
  Enforcing boot, early-init behavior, mount transitions, and recovery operations
  still need physical-device testing. The October 24 images predate this change.
  Policy compilation and neverallow checks remain enabled.
- Camera capture/video, face enrollment, telephony, GNSS,
  and the remaining hardware support checklist need runtime qualification.
- The 538 OEM modules are retained binaries. Source coverage and the full kernel
  rebuild remain prerequisites for broader kernel qualification.
- Raw OTA and running-device extraction with reproducible fixups remain
  outstanding. The prepared-input importer accepts only the recorded baseline.

Official support requires SELinux enforcing and the remaining
[device support requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md).
See the [submission guide](https://github.com/LineageOS/lineage_wiki/blob/main/pages/submitting_device.md).

## October 25 source review

See [BOOT_SECURITY.md](BOOT_SECURITY.md) for the startup and key-lifecycle fixes,
host regression checks, and remaining device validation. These source changes
are newer than the October 24 OTA and first-install archive.


## October 26 infrared framework review

The framework IR service now recovers after HAL restarts and holds its transmit
wake lock. The 20-case host regression passes, with 10 failures reproduced before
the repair. See [IR_SERVICE_REPAIRS.md](IR_SERVICE_REPAIRS.md). This does not
establish the cause or repair of the reported appliance non-response. Platform
compilation, packaging and device validation remain outstanding.


## October 26 continued camera source review

New source repairs preserve metadata on allocation failure and correct shared
capture identity/state across failed submission, partial results, buffer and
physical-result errors, and queued flush callbacks. Unsupported native shared
bursts now fail explicitly. The 106 related host cases pass; 22 failures were
reproduced across the three new fault suites before their repairs. See
[CAMERA_REQUEST_IDENTITY.md](CAMERA_REQUEST_IDENTITY.md). These are new defects,
not a reapplication of earlier identity, camera policy or HAL binary repairs.

Strict cold boot, original camera crop/AWB behavior, infrared output and
StrongBox hardware acknowledgement remain unresolved. No build, package or
device validation was performed in this continuation.


## October 26: whole-system error propagation review

New storage, update writer completion and MiCharge helper lifecycle repairs are
recorded in `SYSTEM_ERROR_REPAIRS.md`. MiChargeClient is not selected by the
current product; those helper tests do not establish active fan behavior. All 69 new host cases pass; pre-edit source
fails 36 of those cases. Eight changed platform files reproduce from pinned
companion patches; all 36 companion checks pass. No Android/kernel build,
packaging or device test was performed. Original crop/AWB, infrared appliance
response and strict cold boot remain open requirements. IR default-service
registration is confirmed; it does not establish successful optical output.


## October 26: product-wide offline audit

See [PROJECT_AUDIT_20261026.md](PROJECT_AUDIT_20261026.md). The active fan path
now handles failed state publication, queued changes during an apply, user
switches and service destruction. All 23 fault/regression cases pass; the same
cases fail 12 times against the saved source immediately before this audit.
The vendor payload index was stale relative to already recorded kernel and
userspace repairs; it now verifies all 6,247 payloads without changing any
payload bytes. Existing repairs were not reapplied. Original crop/AWB, infrared
appliance response, strict boot, StrongBox acknowledgement and display color
intent selection remain open; none is established by host checks.


## October 26: deep service lifecycle continuation and component cleanup

See [DEEP_SERVICE_REPAIRS_20261026.md](DEEP_SERVICE_REPAIRS_20261026.md).
Three active framework paths now handle ambiguous display callback removal,
duplicate/dead biometric listeners and uncertain camera vibration gain cleanup.
Their 28 host cases pass, including ten failures reproduced before this batch.

Per the user's request, components removed from the product are cleaned rather
than re-enabled. [REMOVED_COMPONENTS_20261026.md](REMOVED_COMPONENTS_20261026.md)
records 59 file deletions: obsolete display/audio/gesture apps, retired automatic
fan code, unreferenced FOD helper, exclusive platform endpoints, policy and tests.
Current manual PWM and active framework/HAL integration remain. Its 23 fan
regressions pass. All 36 companion patches and 290 replayed files match;
11 remaining mirrors and 44 guards pass. Earlier inactive-helper case counts
are historical and are not current test coverage.

Original crop/AWB and IR appliance non-response remain unresolved. No build,
packaging, flash or device test was performed.


## October 26 startup-chain and fan entry audit

The current-source boot-chain/SELinux audit found no newly confirmed production
defect. Four standalone normal/recovery userdebug/user policy checks, 1,586
registered startup permission groups, current context checks and selected module
dependencies pass. The fan remains on the Settings homepage immediately before
Apps in all three layouts; 39 source integration checks and 23 fan host cases
pass. See [BOOT_SECURITY.md](BOOT_SECURITY.md) for scope and remaining limits.
Userdebug/eng still boot permissive; a live enforcing cold boot, current installed
split-policy validation, Settings rendering and physical fan operation remain
unverified. No product compilation, packaging, flashing or removed-component
reintroduction occurred in this review.


## October 26 camera and beauty recheck

The camera APK's China-region fallback and the OEM HAL's global fallback were
inconsistent when the two MIUI region properties were absent. The product now
provides the pinned camera stack's CN region values. The original beauty tables,
APK/HAL and plugin bytes remain unchanged. Eighty offline beauty configuration
cases pass, including actual ARM64 selection and real Android org.json parsing.
The existing exact build_prop labels and native user/userdebug policy grants
cover both readers; no policy relaxation was added.

Two additional CameraDeviceClient failures are also repaired: silent loss of
nonempty session parameters and ignored physical-camera filtering/copy failures.
All 121 related camera framework host cases pass. The frameworks/av companion
patch and SHA-256 are synchronized with the current source. A newly identified
cancelled shared-stream terminal-error lifetime issue remains open: immediate
retirement would drop earlier queued callbacks, so that proposal was withdrawn
and the previous shared-request code/tests remain unchanged.

See STRICT_LOG_REPAIRS.md and CAMERA_REQUEST_IDENTITY.md for evidence and test
boundaries. Beauty UI/preview/photo/video output still needs phone validation;
the original raw crop/AWB root causes remain unresolved. This continuation did
not build or package Android/kernel products, flash a phone, or restore removed
product components.

## October 26 official-device-tree cleanup

This section supersedes the earlier permissive-boot configuration statements.
The device no longer adds androidboot.selinux=permissive to normal boot or
recovery. The unused OEM GKI fallback switch and its three payloads are removed.
Current source defaults to global enforcing. The unchanged upstream userdebug
policy keeps su/backuptool permissive and, in recovery, the recovery domain;
user policies have none. These upstream debug exceptions are separate from a
globally permissive boot. No Warsaw-specific permissive domain is introduced.

The optional SenseTime portrait-restoration path and eight unselected extraction
inputs were removed with their build/extraction references. The six affected
snapshot graphs retain exactly the same reachability between all retained nodes.
The selected beautifier and AWB nodes remain intact. The prepared userspace list
now contains 5,577 inputs and the reviewed Blueprint output 2,276 modules.

Host validation checks boot configuration for user/userdebug/eng, all four
existing standalone policy binaries against current source hashes, the paired
GKI inputs, camera graph routing, deleted-input references and retained payload
hashes. It does not establish current device boot, split-policy packaging, image
generation or hardware acceptance. No Android/kernel build or packaging was run.

At this point the tree was still blocked by selected Vega/SenseTime dependencies;
the October 27 follow-up below removes them. Megvii-marked wrapper ownership,
installed-build extraction, OEM module source coverage and live qualification
remain outstanding. See [OFFICIAL_SUPPORT.txt](OFFICIAL_SUPPORT.txt) for current scope.

## October 27 Vega/SenseTime feature retirement

Removed nine SDK/consumer/model files and six generated modules. Six Mi FD
selector entries now use the original Qti FD node, whose callback ABI and node
descriptors match. The optional attribute/emotion path, closed-eye frame
selection, ECD snapshot nodes and automatic face ROI codec filter are disabled
or removed. The earlier portrait-repair removal remains in effect. No retired
SDK library is retained to satisfy a live dependency.

The anchor's existing ECD bypass is forced independently of persistent property
state. Four ECD nodes are bypassed in three snapshot graphs with reachability
preserved for all remaining nodes. The base camera sensor/crop/AWB and ordinary
codec paths are retained. Existing OEM plugins have missing-extended-FD-data
guards; fourteen of those guards were exercised as real ARM64 instruction blocks.
The RTB helper also clears its face output and returns when the extended tag
is missing; its initial scheduling dependency is the standard sensor timestamp.
The old Mi FD loader string has no remaining relocation or direct code references.
These bounded checks do not prove full graph scheduling, plugin processing or
image quality.

The host checks cover 22 CHI ABI/ECD-bypass cases, 14 empty-tag guard paths,
and five RTB face-output/dependency cases, plus two idempotent native fixups,
unsupported-input rejection, and the retired-file
guard for all thirteen paths removed across the two camera cleanups. Extraction
metadata reproduces 5,568 inputs and 2,270 modules. ELF dependency closure,
retained payload hashes and remaining SDK references are audited separately.

No Android/kernel build, APK/image/OTA packaging, flashing or device testing was
performed. SELinux default enforcing, policy sources and previous repairs remain
in place. Camera/codec hardware qualification and the other official-support
requirements remain open; the source cleanup does not establish official status.
