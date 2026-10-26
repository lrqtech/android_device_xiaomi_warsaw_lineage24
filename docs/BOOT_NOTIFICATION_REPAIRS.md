# Boot notification and first-unlock repairs

## KeyMint earlyBootEnded

The selected NXP HAL reports SECURE_HW_COMMUNICATION_FAILED (-49) during early
boot. Its internal pending-event helper returns void, so its existence does not
prove acknowledgement or block later crypto requests. Keystore now notifies TEE
and StrongBox independently and retries this exact transient error on a separate
worker per security level. The retry delay increases from one second to a maximum
of thirty seconds. Permanent errors terminate the worker and remain visible.
The original caller still receives the original error. Only a successful HAL
reply records acknowledgement; an explicit later request is sent to the HAL
again instead of trusting a cached success. Repeated or concurrent failures share
one worker. dumpsys exposes notification state without key material.

The permission check and boot-level key-cache initialization remain unchanged.
Shared-secret checksum failure still stops RootOfTrust transfer. No keys, RPMB
contents, applet code, or signature requirements were changed. This repair does
not add a crypto-operation gate inside the proprietary HAL and is not evidence
that EARLY_BOOT_ONLY enforcement has passed on hardware.

Host validation: 17 tests, including 32 concurrent requests, 1004 error
classification inputs and seven TEE/StrongBox result combinations. The production
retry module is compiled directly. The actual maintenance adapter and notification
loop are compiled with HAL/error test doubles; this is not a full Android build.

```sh
python3 device/xiaomi/warsaw/patches/keystore-early-boot/tests/run-tests.py --source "$PWD"
```

## Certificate Transparency

The captured boot log reports that DownloadProvider is unavailable because the
user is locked, followed by Unknown URL content://downloads/my_downloads. The
service previously scheduled its initial daily update at system boot completion,
which can precede credential unlock. It now waits for the context user's unlock,
rechecks state after receiver registration, and then schedules the initial update.
The callback checks the current enable flag again. Disabling the service removes
the pending receiver, including protection against already queued callbacks.
Repeated configuration changes and unlock broadcasts do not add receivers or
alarms. Queued jobs now carry a scheduling generation, so cancellation followed by re-enable
cannot revive an older download or delete request. Dependency initialization and
teardown are serialized with cancellation. Download parsing, allowed keys and
signature verification are unchanged.

Fifteen deterministic host cases compile the actual service with Android test
doubles. Eight fail against the pinned original source and all pass after repair.
The cases cover locked boot, an already unlocked user, another user's unlock,
the registration race, cancellation, re-enable and duplicate broadcasts.

```sh
python3 device/xiaomi/warsaw/patches/ct-unlock/tests/run-tests.py --source "$PWD"
python3 device/xiaomi/warsaw/patches/ct-unlock/tests/run-job-tests.py --source "$PWD"
```

Eleven additional host cases exercise the actual job with a deterministic
executor, including cancellation while a download is running. Six fail before
repair and all pass afterward. The original baseline receives only an executor
injection for this test. Both unlock and update broadcasts remain protected
broadcasts in the framework manifest.

Both changes are included in the pinned platform patch series. No Android image,
OTA, APK or JAR was built. Phone verification remains outstanding. Normal and
recovery SELinux remain permissive as requested.

## Root of Trust delivery

The framework previously treated all challenge errors as an optional transfer,
and logged completion even after sendRootOfTrust failed. It now distinguishes
unsupported/legacy devices, an already-set report, an absent StrongBox, and an
acknowledged transfer. Other errors retain their stage and cause.

Only SECURE_HW_COMMUNICATION_FAILED (-49) is retried on the existing negotiation
thread, with 1, 2, 4, 8, 16 and capped 30-second delays. Every attempt obtains a
fresh challenge and freshly authenticated TEE data. CBOR is forwarded unchanged;
MAC failure is terminal. Failed shared-secret negotiation still prevents transfer.
No key, RPMB, secure firmware or verification rule is changed.

Fourteen host cases pass against the production functions with HAL doubles,
including 1003 error classification values, ambiguous-send recovery, challenge
freshness, exact payload forwarding, missing TEE, and failure logging. Run
`python3 device/xiaomi/warsaw/patches/keystore-early-boot/tests/run-rot-tests.py`
from the source tree. This does not establish an actual StrongBox acknowledgement,
hardware enforcement, or a full Android build result. The captured Widevine L1
Root of Trust error is a separate DRM path and is not resolved by this repair.

## Additional early-boot SELinux repairs

A new all-stage AVC inventory includes events before the strict-mode test window.
Do not confuse its raw counts with the earlier 968-event bounded strict capture;
it contains startup records, duplicates, property-area enumeration and quoted
property_service messages. Captured labels also predate the current source labels.
Generic property enumeration is not a reason to grant all property areas.

Three concrete source repairs were validated:

- Weaver's denied write of persist.vendor.remoteauth.config now uses an exact,
  dedicated property type. Actual references were found in the selected Weaver
  service and MIAuthSecret daemon; their calls publish state strings 1 or 2.
  Both producers are permitted. Existing readers of the former shared property
  type retain read access, while Weaver cannot write payment provisioning or
  RPMB status properties. No stored value is overwritten by init.
- fsck may check only the separately labeled rescue block device, which fstab
  mounts as ext4 with check,nofail. Its extended ioctl set is identical to the
  platform cache checker, including the captured BLKDISCARDZEROES (0x127c).
  Boot, recovery and FRP block writes remain denied.
- qmipriod's radio-owned log directory has a dedicated file type and boot-time
  relabeling. The daemon can manage its own logs and cannot write generic vendor
  data. The new vendor init file creates the existing directory mode/ownership
  and relabels existing contents without deleting any data.

Normal userdebug, normal user and REC policies compile with neverallow checks.
The normal variants include production technical_debt.cil attributes. Ninety-three
full-context positive/negative decisions, five exact/neighbor file labels, five
property-trie lookups and the new init script pass. Existing camera contracts,
FastRPC ioctl boundaries and property tests still pass. The only compiler warning
is the pre-existing crosvm_vendor empty-permission warning. No Android build,
package, key operation, hardware access or mode change was performed.

NFC's vendor-directory search was traced to the optional libnfc-nci-update.conf
probe in the platform NfcConfig loader, not NFA storage (which is correctly
/data/nfc). It has not been granted blanket access to vendor NFC data. Remaining
all-stage denials need attribution and current-label checks before any changes.

## Trusted VM status property

The captured product-side virtualization service could not publish
vendor.qvirtmgr.trustedvm.status because the name resolved to vendor_default_prop.
The service and manager already had the required permissions on vendor_qvirtmgr_prop.
An exact string mapping now connects that name to its existing dedicated type.
The system-owned declaration is in system_ext public policy, where the product
process domains are defined. No allow rule, status value, VM configuration or
secure hardware state is changed.

Current userdebug, user and recovery policies compile without disabling neverallow.
Forty-five full-context positive/negative decisions and five production property
trie cases pass, including adjacent names, ordinary-app setters and default-property
write denial. This is source validation, not evidence of successful device startup.
UFS coldboot labels were also rechecked: the current compiled labels permit vold's
block uevents and init's discard setting without allowing general sysfs_memory writes.
Camera crop/AWB and the other documented runtime questions remain open. No build
or package was started.

## Rescue relabeling, connector discovery and performance startup

The captured rescue fsck completes and mounts the partition, then vendor_init is
denied getattr during restorecon_recursive /mnt/rescue. That action now runs in
a system_ext init file on post-fs, after the vendor fs mount actions. Cache and
update-engine labels remain unchanged. No vendor_init cache access was added.
The previous prepared init.target.rc bytes were reconstructed and matched against
the existing SHA256 before updating that one input pin; metadata checking remains
strict and passes.

SystemServer's connector discovery was reading cable name/state attributes under
the external-display and EUD controllers with generic/graphics labels. Both extcon
subtrees now use sysfs_extcon in genfs and file contexts, including dynamic IDs.
The existing platform reader permissions suffice. Adjacent wakeup/control nodes
retain their labels. The existing graphics composer read contract also remains.

The retained PERFD reads two SoC capability types and watches/reads thermal
configuration (including thermalbreakboostconfig.xml and thermal-boost.conf).
Its missing read/monitor permissions are present without thermal/SoC writes.
PowerOpt's AIDL service now has the standard binder_use contract for service
manager calls. No unrelated app client or device access was added.

All three policies compile with neverallow checks. Sixty-three context decisions,
189 compiled genfs cases, 63 file labels, two init sources and prepared-input/vendor
metadata checks pass. Normal system_server still cannot read generic or graphics
sysfs files. Recovery has an existing difference: its non-Treble hal_client_domain
macro assigns the displayfeature passthrough attribute, which already allows
those two reads. The test records that configuration explicitly; it is not a new
grant and is not claimed to match normal boot. The pre-existing crosvm_vendor
empty-permission warning remains the only policy compiler warning.

The 87 earlier display/camera permission checks, 16 full-context contracts and
camera FastRPC contracts still pass against the resulting policies. No Android
or kernel build, package, flashing or secure key operation was performed. TEE
persistent-storage attribution and camera crop/AWB investigation continue.

## Certificate-service private data, IWLAN discovery and USB traversal

The retained CACertService manifest uses shared UID android.uid.datacacert,
registered as 2918 by its selected OEM UID XML, and the process name
.cacertservice. Its package is vendor.qti.hardware.cacert.server. The policy only
matched the process name, so installd's package-name lookup left its direct-boot
ART cache with system_data_file. The captured app then failed to open that file.
An exact, type-only package selector now assigns app_data_file with the same UID
and user categories. Process labeling, package UID, certificates and permissions
are unchanged. UID 2918 is already occupied; it must not be reused for another
service merely because config.fs has no entry for it.

The current Android libselinux seapp parser and lookup engine were compiled as
a host probe. Ten cases reproduce the two missing package lookups before the
repair and pass after it, covering primary/secondary users, the retained process
selector, wrong UID, wrong signature seinfo and adjacent package names. Forty-eight
full-context policy checks across userdebug, user and recovery pass. Cross-user
app-data reads and generic system_data_file opens remain denied. All three policy
variants and combined seapp neverallow checks pass; no Android build was started.

The QTI IWLAN client's AIDL service lookup was missing while its Binder calls,
callbacks and FD permissions already existed. Only service discovery was added
to its existing client attribute. USB needs to traverse the qcom-battery parent
to reach its existing supply attributes; only directory search was added. No
controller-file read/write or general-app IWLAN access was granted.

MTD startup inspection remains open. Resolving its actual GOT-backed PLT entries
shows stat checks on seven directories (including persist/data), with exact
0700/system/system checks. A failure triggers recursive restorecon on the whole
persist mount before service registration continues. Its root UID conflicts with
searching these system-owned private directories without a forbidden DAC bypass.
The service identity and secure-storage permissions have not been changed without
proof of the provisioning and TEE caller contracts. No keys, RPMB or directory
contents were touched. Camera crop/AWB investigation also remains open.

## RIL diagnostic snapshot: labels and file permissions

The captured /data/vendor/diag/sync_info rejection comes from
libxiaomi_qcril.so print_sync_info(), which reads modem NV items and writes a
text diagnostic snapshot. It is not the modem NV store or a TEE/RPMB key store.
The exact directory and regular file now have separate labels. RIL can traverse
the parent, create only its named snapshot there, and read/write that file.
Nested minidump directories, other filenames, symlinks and non-regular objects
do not acquire that creation rule or file label. Init restores existing labels
before main-class services start. No data is deleted or rewritten by migration.

The OEM function also passed 0777 to open(O_RDWR | O_CREAT) after setting umask
zero. The pinned preparation changes that one ARM64 mode argument to 0660. The
path, open flags, NV reads, diagnostic writes and error handling are unchanged.
Init chmods an existing file without following symlinks and ignores a missing
file through its normal ENOENT handling. The five MI_RIC services retain root
and gain the log supplementary group, matching the other existing collectors.
No DAC override capability is added. Normal and recovery remain permissive.

Forty-two full-context access checks, 21 named creation checks, nine inode-aware
file-label cases and both edited init sources pass. The initial label probe used
S_IFCHR for every path and could not test directory-specific entries; the new
probe uses the actual object kind, with negative regular-file/symlink cases.
Across every concrete source type and permission bit in all three policies,
only RIL gains access to the new types; all existing collector access is retained.
The old broad minidump labels remain inaccessible to RIL. All policies compile
with neverallow checks retained.

ARM execution of the actual call setup confirms the old/new modes and unchanged
path/flags. Six real temporary-filesystem cases check creation under three umasks,
existing-content preservation, symlink refusal and missing-file handling. Four
unsupported binary inputs are rejected; preparation is reproducible and
idempotent. ELF layout, dynamic symbols and dependencies are unchanged. Only the
mode instruction and public build-ID note differ. Both prepared-input pins were
checked against their previous bytes before updating; full metadata checking
passes. No Android/kernel build, package, flash or device key operation was run.
Phone startup and camera crop/AWB/MTD questions remain open.

## NFC startup configuration lookup

The captured NFC directory-search denials at logcat lines 12260 and 15408
correspond to the platform NfcConfig loader's optional
/data/vendor/nfc/libnfc-nci-update.conf stat/read path. This is not NFA storage;
that remains /data/nfc. The exact parent directory and regular override file
now have distinct types. NFC can traverse this parent and read the override,
but cannot list or modify the parent or read/write HAL state and debug files.
The NFC HAL retains its existing operations; its named creation transition is
limited to this filename in this parent. Non-regular objects, nearby names and
nested paths keep their original labels. Init preserves directory ownership
and restores the two labels before main-class services, without creating an
override, resetting state or altering any key or secure element contents.

All three policies compile with neverallow checks. Sixty-six full-context
access decisions, 24 named creation cases, 12 inode-aware path labels and init
verification pass. A comparison of every concrete source type and permission
bit finds only the intended NFC additions; all other domains retain their
previous access. Existing CACert/IWLAN/USB and RIL checks also pass. This is a
configuration-access repair, not evidence of successful NFC hardware or card
operation. The optional file was not shown to exist in the supplied capture.
Normal and recovery remain permissive. No Android/kernel build, package or
flash operation was performed.

Camera crop investigation now resolves the actual tag indices used by the
active-array lookup: InputMetadataOpticalZoom (292), InputMetadataBokeh (293)
and OutputMetadataOpticalZoom (119). The captured dumps contain no values for
these internal per-request metadata objects. A later SAT master-ID message
belongs to another point in the session and cannot establish the selection for
an earlier failing frame. No camera-ID remapping, crop clamping, calibration
change or suppression of HAL errors was applied.

## Device-node labeling and MISYS event publication

The selected mi_game.ko exposes a distinct migt_reset device. It previously
inherited the generic device label, while the vendor init script attempted to
restore labels on /dev/migt, /dev/migt_reset and /dev/miev from vendor_init.
These nodes are created by ueventd, whose actual MakeDevice implementation sets
the file context before mknod and verifies the inode kind and device number on
EEXIST. The reset character device now has its own exact type. The three
redundant vendor restorecon commands were removed; their chmod values are
unchanged. No normal migt client inherits access to the reset device.

The captured BSPLogProcessor process is hypsys_vendor, which links libmisight.
The inspected library path at 0x6344 opens /dev/miev with O_WRONLY, writes the
flattened event and preserves open/write errors. Only open/write is now allowed
for hal_misys_vendor_default. Reading events, ioctl access and unrelated app
access remain denied. Neither module nor event library bytes were changed.

All three policy variants compile with neverallow checks. Eighty-seven context
decisions, seven inode-aware labels, the init parser and complete prepared-input
metadata verification pass. Reconstructing the prior init text confirms exactly
three removed restorecon lines plus one ownership comment. The initial test
incorrectly expected init and ueventd to have general character-device relabel
permissions; source inspection shows fresh creation uses setfscreatecon instead.
The corrected test checks that creation contract and confirms the unrelated
relabel permission remains denied. No new privilege was added to make that test
pass. Runtime boot and hardware validation remain open; builds, packaging and
flashing remain stopped.

## MTD directory-check boundary confirmed

Reading the actual path strings resolves the seven startup checks to
/mnt/vendor/persist/data and its six children: sfs, tz, CardApp, MliPay,
vsimapp and SecureAuth. The children are under data, not directly under persist.
The parent init action explicitly sets data to 0700 system:system. A root
service with primary group system cannot traverse this parent when its DAC
bypass is unavailable. A host-only test with temporary directories and both
DAC capabilities cleared reproduces six EACCES results; the directory owner's
identity can stat all seven. This isolates the DAC condition, without accessing
Android storage or changing the daemon identity on the device.

Fifteen actual ARM startup cases cover valid metadata, each missing child,
wrong mode/owner/group, a non-directory and modeled traversal failures. Any
failed child check leads to recursive restorecon on the whole persist mount;
a modeled failure of that call still reaches service registration. A missing
or non-directory persist root also reaches registration through the existing
force-continue branch. This is existing OEM behavior, not a new bypass.
The imported callbacks/stat responses are modeled, not measurements of the
phone's directory metadata. The supplied capture does not contain the six
children's mode/owner state or the startup diagnostic messages.

The current root UID, group list, binary and secure-storage permissions remain
unchanged. Changing the whole daemon UID would also change its TEE caller
identity, whose acceptance cannot be established from this closed-source
library and these logs. No DAC/neverallow exception, key deletion, RPMB command,
mode widening or fabricated service success was introduced. This issue remains
open alongside camera crop/AWB, infrared and hardware startup acceptance.
