# Known issues

## Hardware testing

The last reported GPS test could not obtain an outdoor fix. IR transmission
reached the driver, but the tested appliance did not respond. Changes for
both are included; they still need to be retested on a device.

Camera, face unlock, fingerprint, Bluetooth audio, GPS, NFC and IR also need
regression testing on this revision. Build results alone do not establish
that these functions work.

## SELinux

System and recovery use global Permissive. Enforcing has not been established
across boot and hardware use.

Hardware rules and node labels are based on OS4.0.0.15.XHPCNXM, with
HyperOS-only services left out where they are unused. Device domains,
platform isolation and neverallow checks remain in place. Recovery does not
inherit the OTA permissions to execute writable VM data or associate procfs
objects.

## Vendor files

- The 538 OEM modules and six recovery variants are OTA binaries. The GKI
  repository does not provide the sources for these drivers.
- `proprietary-files.txt` lists the inputs, but a portable extraction and
  fixup script for the complete vendor tree is still missing.
- Five IMS/data libraries require 4 KiB kernel pages. The per-library build
  exceptions do not add 16 KiB support.
- The retained OEM apps and camera/face dependencies need further source,
  licensing and device-support review before an official submission.

## Official support

This device is not officially supported by LineageOS. Enforcing SELinux,
hardware testing and the vendor source/extraction requirements above remain
outstanding. Refer to the [device support requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md)
and [submission guide](https://wiki.lineageos.org/submitting_device) before
preparing a submission.
