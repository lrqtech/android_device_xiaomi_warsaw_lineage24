# Warsaw strict-mode log review — 2026-10-25

This review is incomplete at the device-validation boundary. Full Android/kernel
builds and packaging remain stopped. Normal and recovery configurations remain
permissive. No image was flashed, no user key or RPMB state was changed, and no
private signing material was copied. The results below describe current source
and host checks, not a repaired image running on the phone.

## Evidence integrity and scope

The supplied full-stage archive has SHA-256
`ebab53f79280ac8928bbc8a94fb2e3cb06a26b48d39e58e8ac8a5ebd668e825d`.
All 777 members were extracted with traversal, duplicate and symlink guards; ZIP
CRC checks and all 776 supplied manifest size/hash entries passed. Attached
collection programs were read as evidence and were not executed.

The independently reconstructed enforcing interval was 10:13:53.638–10:18:36.050
UTC, lasting 282.412 seconds. Audit serials 2912–3879 contain exactly 968 AVCs,
all `permissive=0`; 792 came from camera domains. All 129 in-window audit status
samples reported zero loss. The collected kmsg sequence is contiguous from 0 to
22359 and the final trace buffers report no overruns. These facts establish the
captured interval, not completeness of earlier boot or secure-world logging.

The installed policy and restored policy both hash to
`95a294c773286fa3d9d5eed6f5591ae9a270b62bbf67d932e42ebbd036074bb5`.
They predate the current source repairs. The supplied observation that camera
capture/video failed under enforcing and worked after restoring permissive is
consistent with the raw permission failures; it does not test the new source.

## Repairs applied to source

| Observed path or operation | Current repair |
| --- | --- |
| CameraMind connection, thermal links, camera AIDL endpoints and FastRPC ioctls | Dedicated camera/client grants with negative tests for unrelated apps. See `CAMERA_AVC_REPAIRS.md`. |
| Camera reads a shell from the private DSP mount | An unchanged stock shell is packaged at the exact public ODM path; the pinned loader selects it first. Private DSP file reads stay denied. |
| FastRPC optimization call returns `Bad address` | The actual OEM driver interprets this obsolete request as an invoke structure, not the supplied four-byte value. A driver-pinned four-byte branch repair avoids that incompatible optional request; the driver already creates persistent headers automatically. Normal initialization and error handling remain active. |
| Camera provider searches the shell temporary directory | Set `TMPDIR` and `OPENCV_TEMP_PATH` to `/data/vendor/camera/tmp`; create it after the parent with mode 0700 and cameraserver ownership. Both actual library selectors were emulated. Hardcoded debug paths are not assumed fixed. |
| Fingerprint daemon reads screen-film status | Read-only access to `vendor_touch_hostprocess_prop`; no setter access. |
| Suspend service reads wakeup statistics under the 3-0028 I2C device | Label that exact wakeup subtree `sysfs_wakeup`, covering dynamic wakeup IDs. Generic sysfs reads remain denied. |
| Media Vulkan layer selection falls under `default_prop` | Exact readonly `ro.vulkan.layers.platform` and `.oem` labels using `graphics_config_prop`. |
| Thermal boot-completion property is unlabeled | Exact boolean `vendor.sys.thermal.bcl.boot_completed` label using the existing thermal property contract. |
| Radio/debug/init and touch cross-platform identity queries | Exact labels for the verified legacy radio/build getters; read-only SSR debug access for vendor init and PD-locator debug access for rild. No fabricated property values. |
| Location APK reads the optional XTRA native-download switch | Exact restricted property type; the location app can read and vendor init can set it. The app still cannot read general `vendor_system_prop` or write the switch. |
| MI_RIC's shell commands find `/system/bin/awk` | Package the existing AOSP `awk_vendor` module as `/vendor/bin/awk`, label it `vendor_toolbox_exec`, and give all five MI_RIC services a vendor-first PATH. No system executable permission was added. |
| Camera algorithms use legacy unprefixed properties | Twenty exact labels derived from binary getter/writer callsites, using camera/build property contracts. No broad `persist.camera.*` grant. |
| EGL camera loop repeatedly cannot read properties | Six exact GPU configuration labels readable by established GPU clients. The strict interval contains 5,246 denied reads of `vendor.debug.egl.swapinterval`. Also label the observed low-battery camera flag, camera/gallery diagnostics, two persistent FastRPC parameters and a cross-platform readonly identity query. No `vendor_default_prop` grant. |

A subsequent APK disassembly pass identified 433 literal Java property callsites.
Three more exact camera debug labels and read-only access to `vendor_panel_info_prop`
were added; all identified reads now pass the current type-policy checks. This
static pass does not cover dynamically constructed property names. The low-battery
flag was independently confirmed to use the boolean getter.

The 20 distinct camera/media property names observed in the strict log now have
successful read contracts in the current compiled policy. This includes names
whose denials were only visible in libc messages because existing platform
policy suppresses some property AVCs. No new dontaudit rules were added.

## Boot-chain checkpoints

| Stage | Evidence and remaining boundary |
| --- | --- |
| Bootloader / modified strict images | Both temporary boot commands were accepted by fastboot. No fresh kernel/ADB stream was obtained from either failed attempt. The modified image bytes are explicitly excluded from this archive; their supplied metadata cannot validate AVB, headers or effective boot arguments. The strict cold-boot failure remains unresolved. |
| Kernel / persistent crash records | Persistent records contain several older panics with different boot identities. Empty pstore and `last_mcrash=none` provide no verified panic for these attempts. Historical crashes are not assigned to the strict failure. |
| First-stage mounts / policy load | The restored normal boot loads policy and reaches metadata-encrypted data mounting. Prior exact UFS node/genfs and init ownership repairs pass fresh compiled-policy checks. This is not evidence of strict first-stage success. |
| Metadata and DE keys | The normal log retrieves the existing metadata key, mounts `/data`, and installs system and user-0 DE keys. No key deletion, formatting or regeneration workaround was used. |
| TEE KeyMint / Gatekeeper / RPMB | In the captured runtime trace, 26 KeyMint and 10 Gatekeeper calls with ioctl `0xc0209801` returned zero; 73 qseecomd `0xc0389802` calls returned zero. All 60 captured RPMB SCSI completions have good transport status. This does not verify TA-internal results, key provenance or RPMB authentication. |
| StrongBox early boot | TEE `earlyBootEnded` succeeds; StrongBox returns `-49` (`SECURE_HW_COMMUNICATION_FAILED`) at uptime 3.283 seconds. Shared-secret negotiation later succeeds. The actual OEM library caches a failed early event and has pending-event calls before six crypto entry points. The pending-state setter was emulated. No APDU acknowledgement for the replay is present, so StrongBox early-boot notification is not certified. |
| APEX and framework | The file-backed mount capability probe returns EINVAL, then APEX reaches `activated`; this is not an APEX activation failure. The face service is gated by existing MiTEE/boot-ready conditions and later registers IFace; early lookup failures precede registration. The optional IQHDC service lookup has no provider in this tree and remains a vendor integration observation. |
| CE storage / first unlock | Some direct-boot app contexts encounter ENOKEY before CE unlock. The capture cannot establish a new persistent key failure from those warnings alone. Full first-unlock, lock/unlock and StrongBox key-use tests remain necessary. |
| Camera | Strict CameraMind socket failures and FastRPC shell/ioctl failures are directly visible. Source fixes and binary-path checks pass, but capture/video/lens switching have not been retested with the changed bytes. |
| Recovery | The folder named `recovery-boot` contains normal Android: `init.svc.zygote=running`, `sys.boot_completed=1`, framework and CameraMind logs, and no enforcing transition. It is not a successful REC strict-mode capture. Current REC policy, mountpoint preparation and sideload listener source pass host checks; actual REC boot/mount/sideload remain unverified. |

## TEE descriptor probes

The qseecomd trace contains 340 `EBADF` results for ioctl `0x40086200` on fd -1.
The actual `libminkdescriptor.so` exports a DMA-buffer descriptor type probe at
`0x9bc4`: it passes deliberately invalid flags `0xfffffffffffffff8` and interprets
EINVAL as a valid DMA-buffer descriptor, while EBADF yields false. Four emulated
return cases pass. The trace lacks the pointed argument bytes and user callstack,
so it cannot attribute every failure to that probe. Neither all such results nor
all successful ioctls prove that secure-world operations are healthy. This DMA
descriptor probe was not modified or suppressed. The separate UFS geometry
compatibility repair is documented below.

## Additional camera source repair

The QuickCamera event formatter now consumes each image title once. Previously,
a capture-before-title pair left the completed title available to the next
capture. Seven host regressions using Android's actual JSON sources pass; four
failed before the change. See `CAMERA_AVC_REPAIRS.md` for the exact defect and tests.

The independent HAL trace also reproduces both logged crop sizes from the mixed
logical/physical active arrays, and distinguishes AWB's 63-slot total limit from
its 20-zone group limit. Neither issue is repaired by widening SELinux access.
Those algorithm paths remain open; bounds and calibration were preserved.

## CameraMind binding lifecycle repair

The boot-completed hook accepted a CameraMind binding and still scheduled its
retry timer after 500 ms. The timer unbound the pending request before creating
another. A slow service startup could therefore be interrupted by its own retry
loop. A false bind return or SecurityException also discarded the connection
without releasing the framework dispatcher. Current Context documentation and
ContextImpl require that cleanup even for those failed requests.

Accepted requests now wait for the connection callback, with a separate 30-second
timeout. Rejected, denied, null, dead and expired bindings release their dispatcher
before retrying. Repeated start requests are idempotent, successful connections
cancel pending timers, and callbacks from replaced connections are ignored.
Package, signature, user and enabled-state checks remain in place.

The device-tree template and active framework copy contain identical changes.
Ten deterministic host cases compile and execute the production lifecycle class;
eight failed before the repair and all ten pass afterward. The Handler, Context
and PackageManager are test doubles, so this verifies callback/state ordering,
not ServiceManager behavior or startup timing on the phone. The runner produces
only temporary class files, with no APK, JAR, image, OTA or install archive.

## Validation of current source

- Normal userdebug, normal user and recovery policies compile with neverallow
  enabled. The normal binaries also include the production `technical_debt.cil`
  attribute expansion. The earlier TE-only test lacked those GPU memberships;
  the test pipeline was corrected without widening policy to compensate.
- 74 new positive/negative policy contracts, 48 camera contracts and 36 startup
  contracts pass. All 256 FastRPC `0x52xx` values were checked in each normal
  variant, with exactly the eight intended values allowed.
- 44 full-context access decisions, including MLS/category constraints, were
  evaluated. All 16 new positive/negative repair contracts pass.
- 30 wakeup and 12 UFS compiled genfs lookups pass. Exact public/private DSP,
  AWK and UFS device-node labels were checked, including negative sibling cases.
- All five current property-context partitions serialize against all three
  policies. All 57 exact/prefix property-trie checks and strict vendor property
  namespace checks pass. All 20 named camera/media reads resolve correctly.
- Seven distinct edited init scripts pass host verification. Prepared input
  hashes and generated vendor rules reproduce; the five MI_RIC service PATHs
  and source package selection agree.
- Four FastRPC ABI branch cases, four actual-library temporary-path cases and
  five DSP queue classifier cases pass. All 24 queue version probes in the
  earlier raw sample are followed by queue creation on the same PID/TID.
- The actual REC sideload listener passes all nine regression cases; the
  pre-fix implementation reproduces three failures. REC firmware mountpoint
  preparation also passes.

No test bypass, blanket default-property grant, signature change, KMI relaxation
or dependency exception was used. Host checks do not replace a full build,
final-image inspection or device testing.

## Open issues

1. Strict cold boot has no attributable early failure trace; REC strict boot was
   not actually captured. Neither is declared fixed.
2. StrongBox early-event replay has no device acknowledgement. Its first error
   occurred in permissive mode, so adding SELinux allows cannot establish a fix.
3. Camera crop metadata errors, AWB detection-zone exhaustion, missing stock
   sound assets still need their functional effects established. The QNN HTP
   DSP lookup for `QcSoCServiceUtils.dll` has a checked static-table fallback;
   its runtime capability selection is not verified. Crop/DLL/sound errors also occur in the
   supplied permissive working-camera sample. Bounds and errors remain intact.
4. The provider's known temporary-directory selectors are repaired; the capture
   has no callstack proving that they account for every `/data/local/tmp` access.
5. Infrared remains unresolved. The supplied observation says appliances do not
   respond in either mode; no strict IR AVC was captured. Electrical/optical
   timing cannot be verified from these files and was not guessed.

6. Two ordinary app render threads cannot traverse the KGSL sysfs directory.
   The Adreno log explicitly falls back to reading chip ID through GSL. The Mi
   Remote app also probes `ro.vendor.display.dynamic_refresh_rate`; it is absent
   from the captured property dumps. Generic KGSL files and vendor display
   properties were not exposed just to remove these warnings. No causal link
   to the infrared transmit failure is established.

These are unresolved boundaries, not waived acceptance checks. Packaging is prohibited by the latest user instruction. Passing checks does not
authorize a build or package; only a new explicit user instruction can change this.


## UFS geometry parser repair

The restored boot trace pairs `Unofficial Geometry desc size, len = 0x69` with
TEE's fallback reliable-write count64. The actual userspace query routine only
accepted geometry lengths0x48/0x57. The new pinned preparation tool adds exactly
0x69 while retaining read-descriptor transport/status checks, the existing1..64
count check, and all RPMB command paths. See [TEE_RPMB_REPAIR.md](TEE_RPMB_REPAIR.md)
for the binary addresses, hashes, source reference and reproduction commands.

The actual ARM regressions cover3,584 length branches and32 capability flows
across both versions, including I/O errors, query errors and retries. The old
failure is reproduced; the new code reads a modeled device value instead of
substituting64 for a recognized extended descriptor. `mult size=16` is a region
size multiplier and does not reveal the real write-sector count. No device was
accessed, no key/RPMB operation was performed, and no build or packaging started.
Prepared-input and generated-metadata verification passed. This is not runtime
proof for the rest of TEE, StrongBox, strict cold boot or REC.


## Secure boot retry boundaries

The early MiTEE warning is emitted by Trusted UI connection setup while the
message queue is not ready. The actual `mitee_dlkm.ko` token path for ioctl0x7405
retries connection when the stored token is-1 or0; positive tokens reuse the
connection. Four actual ARM branch cases pass. The supplied boot logs have no
later `check connect token` success, so the retry mechanism does not establish a
successful Trusted UI connection. Module bytes and signatures were not changed.

The StrongBox pending-event result needs a stricter interpretation. In the actual
`libjc_keymint-nxp.so`, `sendPendingEvents` at0x4db48 clears the early-event flag
only after a successful reply; errors retain the flag and log. Three actual ARM
result-branch cases confirm that behavior. The helper does not return that failure
to its six crypto callers: they continue constructing their own request after the
call. The common sendRequest/sendData path propagates its own transport/response
errors, but has no additional pending-event flag gate. The matching source pattern
also exists in `hardware/nxp/keymint/generic/HAL/JavacardSecureElement.cpp` and
`JavacardKeyMintDevice.cpp`; the Warsaw product currently uses the OEM prebuilt.
The newer source is not assumed ABI/firmware-equivalent and was not substituted.

Thus neither a later successful shared-secret negotiation nor existence of a
pending flag proves earlyBootEnded was acknowledged. The captured startup includes
repeated StrongBox parameter retrieval errors before negotiation succeeds. It does
not capture a failed replay followed by a successful prohibited key operation.
No APDU failure was converted to success, no key was deleted, and no service was
moved earlier without verifying its transport dependencies. This remains an open
boot-security acceptance gate.


## October 26 offline evidence boundaries

Eighteen host cases execute the current init StatusFromProperty/IsEnforcing
functions with modeled boot-argument getters. With permissive-capable init,
either a permissive kernel androidboot.selinux value or a permissive bootconfig
value selects permissive. An enforcing command-line value does not cancel a
permissive bootconfig value. Kernel enforcing= is a separate parameter not read
by these init functions. The archive's command-line-only experiment therefore
does not establish init's enforcing mode. This does not explain the earlier
combined-image failure or prove either attempt reached init. Original test-image
bytes remain unavailable in the archive and the supplied local locations.

Direct inspection of the retained MiuiCamera APK confirms that
assets/sounds/old/camera_fast_burst_end.wav and
assets/sounds/old/camera_click_motor_sound.wav are absent, not merely compressed
assets that cannot be opened by file descriptor. Their absence also appears in
the working permissive sample; no causal capture fix is claimed. The APK, sound
selection, AWB limits, crop calibration and infrared timing were not modified.

The user confirmed that the phone is unavailable and requested continued offline
source work without rebuilding or packaging. Newly repaired camera source error
paths are documented separately in CAMERA_REQUEST_IDENTITY.md. They do not close
these hardware and missing-evidence boundaries.


## October 26: beauty region mismatch found in both camera log captures

The pinned MiuiCamera APK reads `ro.miui.build.region` with a `cn` default
(`Te.d`), then requests `CNRegion` (`F1.n0.b` / `w2.l0.F`). The stock CN
beauty files use legacy root-level scenes; the APK deliberately falls back to
that root for a China build. A missing CNRegion warning alone is therefore not
evidence of a malformed file.

The actual HAL uses a different default: camera.qcom.so at 0x267290 first reads
`ro.miui.region` with `0`, falls back to `ro.miui.build.region` with `0`, compares
case-insensitively with CN, and chooses the CN or global table (including an
8 GB variant). Neither property was supplied by the current product. The APK
therefore parsed as China while the HAL supplied a global regional document,
which has no root FrontCapture/RearCapture scenes. The strict log at lines
127621-127629 and permissive comparison at lines 13707-13728 show the global
document, missing CNRegion and missing scene data. The permissive capture also
contains INVALID BEAUTY and select invalid 0 item. These logs predate this fix.

`configs/camera/camera.mk` now supplies `ro.miui.build.region=cn` and
`ro.miui.region=CN` on system_ext for this pinned CN camera stack. The four
OEM beauty JSON tables, calibration/default values, APK, HAL and plugin bytes
are unchanged. No unsupported makeup/HD-beauty capability was invented and no
exception was hidden. Numeric IDs with leading zeros are accepted by Android's
actual org.json; they were not rewritten using strict desktop JSON assumptions.

`configs/camera/tests/test-beauty-region.py` executes the original ARM64 HAL
selection instructions with modeled property_get/sysinfo/strcasecmp calls,
checks their imported symbol identities, and pins the HAL/APK SHA-256s. It then
uses this checkout's real org.json with the APK's transcribed region/scene
contract on the selected OEM files. All 80 cases pass; the previous product
fails 20. Coverage includes the 8 GB and larger-memory paths, CN fallback,
global/India tables, front/rear photo/portrait/video and existing default values.
The global table's unsupported watermark scenes remain unsupported.

Both existing properties already have exact build_prop labels. Native policy
queries on the current-source userdebug and user normal-boot policies confirm
the camera APK and HAL have open/read/getattr/map, and init may set the values.
No SELinux source change or permissive allowance is needed for this repair.

This proves the configuration mismatch and its offline repair. It does not
execute the APK UI or beauty image algorithm, and does not prove live preview,
saved-photo or video beauty quality. Original raw crop/AWB failures are still
unresolved; this fix is not claimed to repair them. No Android/kernel build,
APK/JAR/image/OTA packaging, flashing or connected-device test was performed.
