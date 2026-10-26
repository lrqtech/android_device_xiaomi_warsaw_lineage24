# Warsaw camera AVC repairs — 2026-10-25

The source integration repairs below are included in the October 27 full build.
Normal boot and recovery use global SELinux enforcing. See
[STRICT_LOG_REPAIRS.md](STRICT_LOG_REPAIRS.md) for diagnostic attribution and
[build results](BUILD_RESULT_20261027.txt) for final artifact checks.

## Captured failure and source repairs

The latest strict interval contains 968 AVCs, 792 from camera domains. Raw logs
show 50 CameraMind permission exceptions and five FastRPC `open_shell failed`
sequences followed by device/session initialization failures. The supplied user
observation says capture/video buttons fail in enforcing and work after returning
to permissive and reopening the app. The installed policy predates these repairs.

| Defect | Current change |
| --- | --- |
| Dedicated camera domain cannot connect to CameraMind | Grant only the intended camera client `connectto`. Existing UID/PID and exact peer-label authentication stays mandatory. |
| FastRPC ioctl permissions incomplete | Permit the eight reviewed codes `5203,5205,5206,5207,520a,520b,520c,520d`; test the entire 256-code range and keep other codes denied. |
| Core camera process reads a private DSP-partition shell | Package the unchanged stock shell at `/odm/lib/dsp/fastrpc_shell_unsigned_3`, give that exact copy `same_process_hal_file`, and make it the loader's first search location. Private DSP file reads remain denied. |
| Obsolete FastRPC kernel-optimization request has the wrong ABI | In the pinned prepared library, branch over only the incompatible request. The matching OEM driver already creates persistent headers during normal CDSP initialization. |
| Provider temporary-file libraries select `/data/local/tmp` | Set `TMPDIR` and `OPENCV_TEMP_PATH` to `/data/vendor/camera/tmp`; create that cameraserver-owned directory with mode 0700 in post-fs-data. |
| Camera-specific AIDL and Binder calls lack permissions | Exact BG/PostProc service lookup, provider callbacks, system-server calls and event-device access. Unrelated app access stays denied. |
| CameraMind thermal links and GPU lookup denied | Thermal symlink read and exact GPU service lookup; existing client authentication and Binder rules remain. |
| Camera properties lack correct labels/read permissions | Twenty exact legacy getter/writer names plus the named EGL, low-battery, diagnostic, build and persistent FastRPC properties; three additional APK debug getters and readonly panel information. No default-property or broad system-property grant. |

## FastRPC binary and driver contract

The loader opens shell paths directly; its shell path does not use the separate
`ADSP_LIBRARY_PATH` search. The first root string at `0xe34d` changes from
`/usr/lib/dsp/` to the equal-length `/odm/lib/dsp/`. Other fallbacks remain intact.
The public shell is a regular packaged copy, not a symlink into the private mount.

The independent driver disassembly shows that optimization ioctl `0xc038520c`
request 1 expects an invoke structure. The retained userspace code supplies a
four-byte value 25 and triggers `Bad address`. The matching driver creates CDSP
persistent headers through the `0xd400 -> 0xfb34` call path. The corrected library
replaces the instruction at `0x3d1ec` with `3b000014`, branching to `0x3d2d8` after
the obsolete optional call. It does not change the driver ABI, normal session
creation, firmware verification, RPC error checks or module validation.

`configs/camera/prepare-fastrpc.py` requires the matching driver bytes. It accepts
only pinned prepared inputs or the final output and rejects raw stock, truncated,
unknown or mismatched-driver inputs. The older prepared input already contained
a one-byte change at `0x3765d`; that baseline is preserved. Compared with that
prepared input, only the public-path string, the reviewed four-byte instruction
and the GNU build ID differ. ELF load layout, relocations, exports and dynamic
dependencies remain unchanged. The previous report's statement that all machine
code was unchanged was superseded by this ABI repair.

| Item | SHA-256 |
| --- | --- |
| Stock DSP image | `658aaf2478bccca25b07e2a2eeb95edc81f072077d04f5d24be0830869f856ac` |
| Stock Hexagon shell, unchanged | `21a58491879dcf1c77d0b6b421652513d3989ae91a2f754da1ca4a78d5d16908` |
| Original prepared library | `ec0cb0b1ee92ec94685d013104fd628d69ece9bc750a84eaf16192050b0bb632` |
| Current prepared library | `e28f3e425f0beb1f195a701b47b7297ee473e6ae227b06832c466e439d78531f` |
| Matching OEM FastRPC module | `525a81ef61ae817011d8d2fa339f9a8318a24026bf0dfeea6166846b249424bd` |

The shell filename's `unsigned` suffix is inherited from the stock user-PD input.
No signing mode, TEE key, RPMB data or firmware image was changed. Prepared hashes,
generated vendor build rules and the payload manifest agree with the current
bytes. The prebuilt DSP module uses normal build dependency validation.

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

The device-tree template and framework copy contain identical changes. Ten
deterministic cases compile and execute the lifecycle class with Handler,
Context and PackageManager doubles. Eight cases reproduce the earlier defect;
all ten pass with the repaired callback and state ordering.

## Snapshot event pairing repair

The QuickCamera adapter retained `imageTitle` after event 2012 consumed a pending
2008 capture event. A following capture could therefore be published with the
previous image title before its own title arrived. The formatter now clears both
halves after pairing. It preserves the existing wire kind, capture fields,
sequence handling and title-before-capture behavior.

Seven regression cases execute the checkout's Android `org.json` implementation.
Four reproduce the old failure; all seven pass after the repair. The formatter
handles both notification orders using temporary test classes.

## Independent HAL metadata trace

All 2,662 crop-bound errors in the two reviewed logs come from
`InternalYuv2YuvForRealTimeReprocess_IPE14_cam2`. The logical camera 4 and main
physical camera 2 advertise 4096x3072; physical camera 3 advertises 3264x2448.
The actual `GetStreamCropInfo` fallback receives a 4096x3072 HAL crop and uses
3264x2448 as its active-array denominator. Emulating its ARM instructions exactly
reproduces 1807x1355 for a 1440x1080 frame and 1606x1204 for a 1280x960 frame.
The untouched bounds checks return error 7, which reaches the port-crop metadata
consumer. Five producer cases and five bound cases cover the observed mismatch,
matching dimensions, error propagation and overflow. The logical-to-physical
metadata transfer still needs correction at its source; cropping or changing the
reported sensor size would conceal the problem.

The AWB error has two independent limits: 20 zones per group and a total index
below 63. The observed group count is one, while the total index is already 63.
Four cases against the actual registration instructions reproduce that rejection.
The same code uses zone indices in 64-bit masks. No capacity constant, calibration,
array bound, error return or algorithm binary was changed. The actual reset at
0xdd620 clears the total and all 100 group counters; three ARM reset cases pass.
The registration entry is also reachable through the secondary vtable thunk at
0xdedc0, then the state wrapper at 0xc34f0. Three local registration sites were
identified.

The APK also really lacks `sounds/old/camera_fast_burst_end.wav` and
`sounds/old/camera_click_motor_sound.wav`. Its existing sound assets are stored
uncompressed, so these are not compressed-asset `openFd` failures. The loader
returns null, and its caller returns -1 for those loads. No replacement sound or
APK rewrite was fabricated.

## Error paths retained and examined

- The actual OpenCV and libc++ temporary-path selectors were emulated with and
  without their environment variables. The configured directory is selected.
  This establishes those paths, not the origin of every captured tmp-directory
  denial; no broad shell-data access was added.
- DSP queue `0x80000414` is a version-probe result in the inspected call path.
  The code retries with version 2, and all 24 probes in the earlier raw sample
  are followed by queue creation on the same PID/TID. Five branch cases confirm
  that unexpected errors remain errors. The queue implementation was not patched.
- The provider enumerates property areas through its real `property_list` path.
  Enumeration open/map/getattr denials were not converted into blanket access.
  Exact legacy getter/writer callsites were traced separately, and all 20 named
  camera/media reads in the latest strict window now pass current policy checks.
- The optional
  `QcSoCServiceUtils.dll` lookup has now been traced in the actual QNN host and
  Hexagon DSP libraries. In libQnnHtpV79Skel.so, 0x56b210 requests the library and
  resolves Soc_GetAPIVersion and Soc_GetCapability. Its consumer tests all three
  pointers before use and bypasses dynamic capability construction when missing.
  The selector at 0x56a04c then takes its existing static-table path when the
  dynamic object is null. No DSP binary was changed or substitute DLL invented.
- The optional MiPush system-property write is not granted to the camera domain.

## Checks performed

Current normal userdebug, strict user and recovery policies compile with
neverallow enabled. Normal policy checks include the production CIL attribute
expansion. Forty-eight camera TE contracts and both complete `0x52xx` ioctl
ranges pass. Full-context MLS checks also pass for the camera-to-CameraMind
connection, public DSP read and denied unrelated/private accesses.

The binary transform reproduces the installed source payload and is idempotent;
three unsupported libraries and two wrong driver inputs are rejected. Four ARM
ABI cases, four temporary-path cases, five queue-result cases, init verification,
property trie/type/namespace checks and generated vendor metadata checks pass. A
separate APK scan found 433 literal Java property callsites; their identified
read contracts pass after the additional exact-label and panel-info repair.
Dynamically constructed names remain outside that static scan.

The October 27 full build includes these changes. Its final artifact checks
cover OTA/payload signatures, AVB chains, all 45 partition hashes, VINTF, image
contents and embedded policies. See [build results](BUILD_RESULT_20261027.txt).

## UFS geometry parser repair

The restored boot trace pairs `Unofficial Geometry desc size, len = 0x69` with
TEE's fallback reliable-write count64. The actual userspace query routine only
accepted geometry lengths0x48/0x57. The new pinned preparation tool adds exactly
0x69 while retaining read-descriptor transport/status checks, the existing1..64
count check, and all RPMB command paths. See [TEE_RPMB_REPAIR.md](TEE_RPMB_REPAIR.md)
for the binary addresses, hashes, source reference and reproduction commands.

ARM regressions cover 3,584 length branches and 32 capability flows across both
versions, including I/O errors, query errors and retries. The old rejection is
reproduced; the repaired code reads the modeled descriptor value. `mult size=16`
is the region multiplier. Prepared-input and generated-metadata checks pass.

## Camera and activity-result URI permissions

The supplied comparison capture at 1792923707.153 records a ContentProviderHelper
failure when the camera launches Glimpse: ActivityCallerState queried dynamic
MediaProvider permissions while ActivityStarter held the window-manager lock.
The provider-call deadlock guard correctly denied the query, so caller metadata
could classify a readable image URI as inaccessible.

The framework now captures read/write permission decisions before taking that
lock and supplies the snapshot to initial launches, reused-activity intents,
Activity.finish results, LocalService result delivery and navigate-up requests.
Snapshots are matched against the final caller UID and normalized URI/user set;
interception cannot reuse another caller's results. Explicit user prefixes added
for cross-profile result delivery are normalized to the same GrantUri identity.
An internally prepared navigation snapshot is consumed once, and pooled request
reset/copy handling is explicit. Actual URI grants and provider verification are
unchanged. A missing/mismatched snapshot retains the original permission check
and fail-closed behavior; this is not a blanket provider bypass.

The original production permission method reproduces two locked provider calls
and an incorrect denial for a readable camera URI. Twenty-four host cases pass
with the edited production permission/request methods and Android doubles.
Coverage includes identity changes, URI replacement, explicit users, read/write
separation, malformed extras, nested ClipData, disabled flags, pooled request
plumbing and one-shot navigation. Large ActivityRecord/Task integration methods
have source wiring checks. XML persistence is unchanged.

Run `python3 device/xiaomi/warsaw/patches/camera-framework/tests/run-caller-uri-tests.py`
from the source root.

AWB scheduling was also exercised using the actual ARM dispatch and FDD reset
instructions in 16 modeled list/branch cases. With the selected FDD at active
list index 9, reset precedes scene analyzers; first-module failure skips scene
calls. Other modules and memset were modeled. Runtime list/tuning contents were
not captured, so this rules out the tested scheduling hypothesis only; no zone
limit or camera calibration was changed.

## Early display clients and external camera sessions

The full startup capture contains additional QSPM service lookup/Binder denials
for bootanim and SurfaceFlinger, gpu_model reads from the allocator, and a denied
external camera provider lookup from cameraserver. The first two now join the
existing bounded AIDL rendering-client contract, the allocator can read the KGSL
file type, and cameraserver can discover the existing external provider label.
No GPU control write, unrestricted app client or legacy HIDL grant was added.
Eighty-seven full-context positive/negative checks pass across userdebug, user
and recovery. All three policies compile with neverallow checks retained.

Independent source review then found a separate session configuration defect:
the Warsaw identity helper required a Xiaomi tag even from the retained external
provider, whose actual getVendorTags routine clears the vector. The helper now
uses the descriptor for the immutable device-provider ID. A known empty table
preserves the standard session; an unknown descriptor, incomplete nonempty table,
wrong type or foreign metadata still returns an error. A process-global descriptor
cannot override another provider's cache entry. The OEM clientName tag is still
written from the authenticated CameraService client, replacing app-supplied names.
Session filtering tests the explicit capability result before accessing the tag.

Twenty-one cases exercise the helper header with Android API doubles. They
cover empty-provider tables, descriptor ownership and metadata rejection. Six
native cases are also present in the platform test source. Run
`patches/camera-framework/tests/run-session-tests.py` from the device tree, or
provide `--source` for another checkout. Platform patch hashes and replay checks
pass.

The unchanged CamX HAL selects active-array metadata through
GetActivePixelArray at `0x363450` and GetCameraIdForMetadataQuery at `0x362d30`.
Provider identity and metadata ownership are preserved; crop bounds and camera
IDs retain their existing validation.

## Camera provider RPC handle repair (2026-10-26)

The actual provider executable contains the midlhook map. Its ADSP session-0
insertion skipped valid domain zero; a failed open could also read and register
an unwritten output, corrupt a live mapping, or dereference null. Both branches
are repaired in the pinned prepared input by configs/camera/prepare-provider-rpc.py.
22 actual ARM cases pass, with 11 failures reproduced before the repair; five
unsupported inputs are rejected. Vendor metadata reproduction passes. Original
RPC statuses, unknown-handle errors, SSR bookkeeping and CDSP watchdog remain.
See docs/PROVIDER_RPC_REPAIR.md and provider-rpc-validation.json for boundaries.

The two reviewed logs contain 6,338 missing-map messages. The handle-map
repair targets the reproduced registration path documented in
[PROVIDER_RPC_REPAIR.md](PROVIDER_RPC_REPAIR.md).

## Camera request identity (2026-10-26)

API2 capture requests now carry the authenticated client name in logical frame
metadata, using the immutable device provider table. Session-only stamping left
this request path uncovered. The selected preview plugins read this tag from
MiaParams metadata. Twelve host submission cases pass; the baseline fails ten.
Physical request keys and original app metadata are preserved; malformed provider
metadata still fails. See docs/CAMERA_REQUEST_IDENTITY.md for verification limits.

## Legacy camera identity and recording error propagation (2026-10-26)

The same identity repair now covers API1 preview, recording, still and ZSL
reprocessing. ZSL validates before enqueueing an input buffer. startRecordingL
now uses the real updateRecordingRequest result and stops on failure. Thirty-six
additional host cases pass (26 baseline failures); API2 and session tests add 33.

## CHI physical-camera lookup bounds (2026-10-26)

A missing physical-camera match made RemapCameraId index the pointer table with
0xffffffff. Both distinct CHI inputs now branch to their existing invalid-ID
return before the read. Twenty-four actual ARM cases pass; six baseline invalid
reads reproduce, and six unsupported inputs are rejected. Each DSO retains its
own identity data. See docs/CHI_CAMERA_ID_REPAIR.md for the exact scope. The log's
NZSL ID-15 warning comes from another lookup and is not proven to trigger this
branch.
