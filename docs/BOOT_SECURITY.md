# Boot security review, October 25, 2026

This review covers Warsaw startup code and configuration, with additional
checks for KeyMint, Gatekeeper, Keystore2 and encrypted storage. Normal boot and
recovery use global enforcing; policy and neverallow checks remain enabled.

## Changes

- `vold` reports failed `earlyBootEnded` calls to its caller instead of returning
  success. A missing maintenance service and a KeyMint rejection both propagate
  failure. This preserves init's existing action handling; it does not add an
  automatic reboot gate.
- Metadata key initialization requires a genuinely absent key directory and an
  allowed key-generation operation before considering hardware key cleanup.
  Access errors, I/O errors, non-directory paths and symlinks are rejected.
  A failed `deleteAllKeys` call stops initialization before generating a key.
- The shared key retrieval path distinguishes `ENOENT` from other filesystem
  errors. A failed read of an existing key never becomes a key-generation request.
- Keystore operation completion accepts a successful response with no output
  bytes, as allowed by the nullable AIDL result, without dereferencing an empty
  optional value.
- Shared-secret computation errors or checksum mismatches stop the negotiation
  worker before the success log and RootOfTrust transfer. Parameter acquisition
  retains the existing retry behavior for hardware startup delays.
- The snapuserd readiness probe checks positive `posix_memalign` error codes
  before using its buffer and reports the returned allocation error.
- The generated vendor security patch level was empty. `VENDOR_SECURITY_PATCH`
  now preserves `2026-09-01` from the retained OS4 vendor image. The Qualcomm
  KeyMint library reads this property and reports a fallback to zero for an
  invalid date. The rebuilt platform keeps its separate `2026-10-01` SPL.

## Startup checkpoints reviewed

| Checkpoint | Source and configuration | Configuration and result |
| --- | --- | --- |
| Boot image selection and AVB | `configs/boot/boot.mk`, `configs/avb.mk`, fstabs | Global enforcing defaults; paired boot-chain images and AVB descriptors are validated. |
| First-stage mounts and modules | `first_stage_init.cpp`, `first_stage_mount.cpp`, module lists, `fstab.qcom` | Required setup errors have fatal paths; nofail/formattable mount failures can be deferred to later init actions. Metadata precedes encrypted userdata. |
| Policy selection and loading | `selinux.cpp` | Precompiled policy uses three identity pairs; installed CIL fallback, mapping and genfs inputs are checked with neverallow enabled on the host. |
| Snapshot transition | `snapuserd_transition.cpp` | Policy is read before dynamic-partition I/O is suspended. Allocation failure handling is fixed. |
| Second-stage labels and device discovery | `init.cpp`, `selabel.cpp`, `ueventd.cpp`, `subcontext.cpp` | Recovery requires startup labeling; normal init logs restorecon failures. Vendor subcontext and ueventd reject a missing label handle. |
| Service domains and property actions | `service.cpp`, `property_service.cpp`, installed init files | Security services have expected entrypoint labels and init transitions. Gatekeeper is enabled through the retained non-SPU property selector. |
| QSEE and MiTEE | `qseecomd.rc`, `tee-supplicant.rc`, ueventd rules and policy | QSEE, MiTEE, DMA heap, RPMB-facing node labels and selected access edges are checked. |
| TEE and StrongBox | Keystore2 service construction, shared-secret negotiation, retained NXP transport | Negotiation failure no longer reports success or proceeds to RootOfTrust transfer. A transport symbol in a library is not proof that the retained executable uses that transport. |
| Encrypted storage | `MetadataCrypt.cpp`, `KeyUtil.cpp`, `KeyStorage.cpp`, `Keystore.cpp` | Metadata encryption and FBE continue using the existing hardware-wrapped configuration. Errors cannot authorize the new initialization paths covered by the regression tests. |
| Early-boot key invalidation | `VoldNativeService.cpp`, `Keystore.cpp`, `maintenance.rs` | The maintenance result reaches vdc. |
| APEX and framework startup | `init.rc`, `maintenance.rs` | The `apexd.status` and `keystore.module_hash.sent` gates remain. The checked APEX metadata call contains no Binder objects and needs no extra transfer permission. |
| First unlock and user state | `SyntheticPasswordManager.java`, `SyntheticPasswordCrypto.java`, `LockSettingsService.java`, `StorageManagerService.java`, `UserController.java` | Credential and blob failures do not create replacement keys. CE unlock failure is logged, and later user-state transitions check CE readiness. New protector creation retains its existing non-rollback-resistant fallback. |

## Validation

- 36 host fault-injection cases pass; the previous code fails 22 of those cases.
  The harnesses execute extracted production functions with mocked Binder,
  storage and allocation boundaries.
- 92 selected security-chain TE and property permission checks pass against the
  compiled normal policy.
- Normal and recovery policy contracts pass (1,211 and 375 checks). Neverallow
  recompilation, installed CIL fallback loading, file-context validation,
  precompiled-policy identity pairs and the host init verifier pass.
- Target builds of `vold`, `keystore2`, normal and recovery `init`, both policies
  and `vendor/build.prop` pass after full product configuration regeneration.
  The generated vendor SPL matches the retained vendor image.
- All 35 companion patch hashes and reverse-application checks pass. The three
  updated platform snapshots also reproduce their source trees when applied
  to the recorded base revisions.
- The module audit reads the null-delimited `key=value` metadata returned by
  `modinfo -0`. Recomputed dependency closures have no missing modules, and
  recovery audio insertion order passes. QSEE/MiTEE and crypto modules are
  present in their selected load lists. Kernel FBE and inline encryption
  options remain enabled. Six recovery audio variants have matching loaded
  ELF sections; OEM first-stage zram/zsmalloc and rebuilt GKI copies remain
  distinct.

The October 27 full build includes these changes. Its matching OTA and startup
images are recorded in [build results](BUILD_RESULT_20261027.txt).

## October 26 source and host recheck

Current-source standalone policy checks pass for normal/recovery in both
userdebug and user, with neverallow enabled, genfs version 202604 and the normal
policy's technical_debt CIL included. The 1,211 normal and 375 recovery native
permission contracts pass against these fresh policies. Eleven merged context
checks and 204 additional startup/fan groups pass (the latter overlap existing
contracts). Final split-policy and embedded-policy checks are recorded in the
October 27 build results.

Current normal and recovery boot configuration uses global enforcing. Userdebug
policies retain upstream `su` and `backuptool` debug domains, plus `recovery` in
the recovery policy. Both user policies have no permissive domains. Eighteen
mode-selection cases exercise the init functions with modeled boot arguments.

The selected GKI's 177 manifest hashes, current module dependency/load lists,
recovery's six second-stage module order, fstabs, contexts and key ioctl contracts
pass the offline checks. There are 199 init grammar checks and four VINTF merges,
plus the combined vendor/ODM declaration merge. Current RC contents for 430
service declarations show no new entry issues under the previously recorded
selection/symlink mapping. Ten
property waits retain their producer and init permissions. Existing disabled,
unselected or replaced service candidates were not enabled.

The fan entry is **Settings homepage -> Cooling fan**, immediately before Apps,
in regular, Expressive and desktop layouts. Its Fragment allowlist, controller,
resources, direct-boot single-user receiver/service and system-UID DAC/SELinux
access are connected. All 39 source integration checks and 23 fan host cases
pass; the final Settings APK contains the entry and its components.

All 36 companion project patches reproduce 290 files; the 11 mirrors and
44 integration guards pass. The October 27 full build and final image validation
are recorded in [build results](BUILD_RESULT_20261027.txt).
