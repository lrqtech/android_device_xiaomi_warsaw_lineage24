# CHI physical-camera lookup failure

Inspection of the selected CHI inputs found an out-of-bounds read in
ExtensionModule::RemapCameraId. In physical mapping mode, a missing physical ID
or empty physical table falls through 0x45ead4, sets w10 to 0xffffffff, then
uses x10 as an array index at 0x45ead8. The read is outside the pointer table.

The instruction at 0x45ead4 now branches to the existing invalid-ID return at
0x45ea74. Both packed ID fields remain 0xffffffff on failure. Valid physical
matches, logical-index mapping, framework-ID mapping and unsupported-mode
handling are unchanged. No table entry or valid camera mapping is invented.

The two DSOs have distinct original hashes and identity data. They were prepared
independently, preserving each SONAME, exports, dependencies, relocations, size
and layout. Only the reviewed instruction and each GNU build ID changed.
configs/camera/prepare-chi-camera-id.py pins both supported input versions,
verifies the output hashes, rejects other inputs and is idempotent. The vendor
prepared-input hash manifest was updated for both files.

## Host regression

configs/camera/tests/test-chi-camera-id.py executes the actual RemapCameraId ARM
instructions with synthetic physical-camera tables and a modelled GetCameraIdIndex
call. Each input passes 12 cases; each old input reproduces three invalid reads.
Six unsupported inputs are rejected across the pair. Tests cover empty tables,
missing IDs, first/second matches, another logical-camera entry, all mapping
modes, invalid IDs and the packed return values. Byte-change bounds and exact
reproduction are checked. This does not establish the device's runtime table.

Run from the device tree with originals retained outside the public repository:

```sh
python3 configs/camera/tests/test-chi-camera-id.py \
  --baseline-dir /path/to/local-originals \
  --prepared-dir /path/to/vendor/odm/lib64 \
  --report /path/to/local-report.json
```

The original filenames are libchifeature2.so.before-remap and
com.qti.chi.override.so.before-remap. pyelftools and Unicorn are required;
--python-path can select an existing dependency directory.

## Relationship to the supplied log

The dump identifies framework physical camera 2 as 4096x3072 and physical camera
3 as 3264x2448. The crop failure uses the former dimensions with the latter
active-array denominator. Framework IDs and CHI internal IDs are not assumed
to be interchangeable.

The `Invalid logicalCameraId 15` diagnostic comes from a separate NZSL config
map lookup at 0x153e60. That path reports an absent entry; the captured log does
not prove it reaches the repaired RemapCameraId failure branch. The source-level
memory-safety defect is reproduced and repaired, while crop-coordinate ownership,
AWB limits and runtime acceptance remain open. No crop clamp, fake ID, weakened
validation, Android build, package or flashing is included.
