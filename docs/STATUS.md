# Integration status and remaining requirements

This snapshot follows a Device / Vendor / Kernel repository split. Repository
organization alone does not establish official LineageOS eligibility.

## Current behavior and limits

- System and recovery are configured for global Permissive for diagnosis.
  Enforcing operation has not been demonstrated across the complete boot and
  hardware lifecycle. This remains incompatible with official acceptance.
- GPS was reported unable to obtain an outdoor fix. IR transmit calls reached
  the driver but did not operate the tested appliance. Code-level repairs and
  additional diagnostics do not establish that either issue is resolved.
- Camera, face authentication, fingerprint, Bluetooth audio, GNSS, NFC and IR
  integration require on-device regression testing of this exact revision.
  Offline policy, build and package checks do not replace those tests.
- The 538 unique OEM vendor modules and six recovery variants are OTA binaries,
  not a complete source-built OEM driver set. The GKI source does not include
  their unavailable proprietary implementations.
- `proprietary-files.txt` records blob inputs, but a complete portable extraction
  and fixup pipeline reproducing every published vendor file is still missing.
- Five IMS/data libraries require 4 KiB kernel pages. Their scoped build
  exceptions do not make them compatible with a 16 KiB kernel.
- Retained OEM application and camera/face dependencies still need a complete
  provenance and device-support policy review before an official submission.

## SELinux reference

Hardware policy and node labels were compared with OS4.0.0.15.XHPCNXM.
HyperOS-only services are not indiscriminately imported. The policy retains
platform isolation, compile-time neverallow checks and device-specific domains.
The recovery policy intentionally does not copy permissions to execute
writable VM data or associate procfs objects. Permissive boot configuration
does not prove that the policy is complete enough for Enforcing operation.

## Official references

- [Device support requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md)
- [Submitting a device](https://wiki.lineageos.org/submitting_device)

These requirements must be reassessed against the final device behavior,
source availability and extraction process before requesting official support.
