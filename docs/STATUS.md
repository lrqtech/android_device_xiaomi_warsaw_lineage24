# Development status

This is an unofficial LineageOS development tree.

## Verified on the build host

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

- Normal boot and recovery remain SELinux permissive in userdebug and eng.
  Enforcing boot, early-init behavior, mount transitions, and recovery operations
  still need physical-device testing. Policy compilation and neverallow checks
  remain enabled.
- Camera capture/video, face enrollment, telephony, GNSS, D2 game enhancement,
  and the remaining hardware support checklist need runtime qualification.
- The 538 OEM modules are retained binaries. Source coverage and the full kernel
  rebuild remain prerequisites for broader kernel qualification.
- Raw OTA and running-device extraction with reproducible fixups remain
  outstanding. The prepared-input importer accepts only the recorded baseline.

Official support requires SELinux enforcing and the remaining
[device support requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md).
See the [submission guide](https://github.com/LineageOS/lineage_wiki/blob/main/pages/submitting_device.md).
