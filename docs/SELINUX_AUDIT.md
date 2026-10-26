# SELinux and startup review, 27 October 2026

This review covers the source set tagged `warsaw-20261027`. The policies are
included in the October 27 full build; embedded image policies match the reviewed
hashes. See [build results](BUILD_RESULT_20261027.txt).

## New repairs

The OEM radio debug property `persist.vendor.radio.close_wenkong=yes` previously
stopped `mi_thermald`. Its init action is removed. The two modem debug actions
that changed log/configuration permissions to 777 are also removed. Normal
thermal and radio service startup remains unchanged.

Forty obsolete OEM service definitions are removed. Their programs were absent
from the selected inputs or had no valid SELinux entry transition. The cleanup
also removes their RC/shell callers, nine dedicated files, four obsolete
executable/socket labels, three unused policy types and their direct grants.
The old fan service is removed; the manual PWM controller integrated into
Settings remains selected. It retains its dedicated sysfs label and limited
access rules. Settings appears before Apps on the homepage.

Only five retained RC files and four shell scripts change in the vendor payload.
Retained ELF, APK, kernel, module and firmware bytes are unchanged by this startup
cleanup. The 27-case RIL control comparison preserves every command except the
calls to the unavailable qmiproxy service.

## Policy verification

Normal boot was checked through fresh platform, system_ext, product, vendor and
odm CIL, public versioning and all three 202604 mappings. The final `secilc` link
includes the configured genfs compatibility policy and keeps neverallow checks
enabled. Recovery was regenerated separately. Both `user` and `userdebug` were
checked. Policy inputs were freshly read and hashed; the existing build graph
supplied the configured directories and host transform recipes.

| Policy | Source files | Permissive domains |
| --- | ---: | --- |
| Normal user | 972 | None |
| Recovery user | 959 | None |
| Normal userdebug | 972 | Upstream `su`, `backuptool` |
| Recovery userdebug | 959 | Upstream `su`, `backuptool`, `recovery` |

The upstream debug-domain exceptions are unchanged. They are distinct from a
globally permissive boot; current boot arguments do not request permissive mode.
No device permissive domain or broad replacement allowance was added.

The normal/recovery file, property, service, hwservice, vndservice and app
contexts pass 22 validation calls across the two build variants. The upstream
structural policy suite passes for both. It emits an informational missing
`isolated_compute_allowed_property_type` message: this configured attribute has
no assigned types and is omitted from the binary. The upstream tests explicitly
allow it to be absent; no test or policy constraint was disabled.

The native-policy checks cover 1,209 existing normal-boot operation contracts
and 375 recovery contracts per build variant. Two IRQ-balance contracts were
retired with the executable and callers; their policy types are confirmed absent.
User builds are required to deny the debug-only su/adb-root operations. A further
204 focused checks cover startup and fan boundaries, with some overlap with the
registered contracts. All pass. Labels, ioctl rules and the normal/recovery
fstabs pass 70 additional checks.

## Startup, extraction and source checks

- All 190 retained device/vendor init files pass the host grammar verifier.
- Four VINTF declaration groups assemble successfully.
- 477 module checks cover selected GKI hashes, load dependencies and recovery
  load order. The GKI set contains 177 hashed files and 78 paired modules.
- All six boot-variable cases select the paired 4 KiB GKI and default enforcing
  behavior. The retired OEM-kernel switch cannot select the removed fallback.
- All 23 manual-fan host cases and 16 prepared-input validation cases pass.
- All 5,559 userspace pins and 6,214 vendor payload hashes match. Generated vendor
  definitions reproduce 2,265 Blueprint modules.

The service review reconciles current RC files with the recorded product graph
and current device/vendor copy rules. It covers 390 declarations and finds no
new entrypoint issue. The seven remaining special cases are upstream ABI/APEX
alternatives, a dynamic init path, and the legacy recovery charger that the
device explicitly stops before class startup. Generic platform controls for
optional audio HALs, the secondary zygote and llkd remain in upstream sources.

The 36 platform patches still reproduce their 290 affected source paths. Source
mirrors and integration guards are checked separately from the policy compiler.
Policy hashes and check counts are in [the machine-readable record](selinux-audit-20261027.json).

## Final image validation

The final normal and recovery policy hashes, ramdisk contents, startup arguments
and partition files are checked against the final target-files and OTA. AVB,
VINTF and image integrity checks are recorded in
[build results](BUILD_RESULT_20261027.txt).

See [LineageOS integration](OFFICIAL_SUPPORT.txt) and the
[device support requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md).
