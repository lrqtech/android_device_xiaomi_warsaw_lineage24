# Prepared proprietary inputs

`proprietary-files.txt` lists all 5,559 userspace inputs in the current prepared
vendor tree, including the five Warsaw camera/face compatibility inputs that were
previously missing. Preserve the original vendor ownership and licensing. This
repository contains metadata, not a grant to redistribute proprietary assets.

## Supported workflow

Use Python 3.11 or newer, the checkout's `tools/extract-utils`, and GNU `patch`.
The scripts locate the checkout from their own paths and work from any directory.

Verify the prepared input and current build definitions without changing them:

```sh
device/xiaomi/warsaw/extract-files.py --check /path/to/prepared/vendor/warsaw
device/xiaomi/warsaw/setup-makefiles.py --check
```

Import from a separate matching prepared vendor tree or its `proprietary/`
directory:

```sh
device/xiaomi/warsaw/extract-files.py /path/to/prepared/vendor/warsaw
```

The importer verifies every SHA256 before use, copies to a staging directory,
verifies the copied inputs, generates and checks metadata, and only then replaces
the output's `proprietary/` directory. It retains the output's separate kernel,
firmware and auxiliary inputs. Failed import/generation does not replace existing
blobs. `--output-dir /path/to/new/vendor` supports isolated verification; it does
not create a complete buildable vendor tree without those separate inputs.

Regenerate metadata from the current prepared input:

```sh
device/xiaomi/warsaw/setup-makefiles.py
device/xiaomi/warsaw/setup-makefiles.py --output-dir /path/to/metadata-only
```

Blueprint module equivalence is checked structurally, including Soong select
guards, dependency lists, partition variants and all validation properties.
Semantically unchanged metadata is left in place. Product and board makefiles
must reproduce byte for byte.

## How generation is preserved

The baseline was originally generated and then adapted during bring-up. Running
the unconfigured upstream template would lose the source/prebuilt ownership,
partition aliases, app dependencies, firmware links and conditional selections.
`extract/module-rules.json` records those reviewed adjustments over upstream
generation; `extract/product-rules.patch` records the makefile adjustments.
Input/output fingerprints reject an unexpected generator result. Checks are not
disabled to accommodate a mismatch.

`extract/proprietary.sha256` pins the complete prepared userspace baseline. The
`EXTRACT_ONLY` entries are intentional inputs to the explicit integration rules,
not permission to omit files. For example, the stock infrared APK resides under
`product/data-app` but its existing `android_app_import` installs a normally signed
product app. The face and camera variants also have explicit module owners.

## RIL diagnostic file preparation

`configs/radio/prepare-ril-diagnostics.py` accepts only the reviewed
`vendor/lib64/libxiaomi_qcril.so` input and its prepared output. It changes the
`print_sync_info()` file-creation mode from 0777 to 0660 and updates the public
build-ID note. It leaves modem NV operations and the library ABI unchanged.
Run its `--check` mode against the prepared library before updating the complete
prepared-input pin set. The related init actions and log groups are described in
[boot repair notes](BOOT_NOTIFICATION_REPAIRS.md#ril-diagnostic-snapshot-labels-and-file-permissions).


## Prepared source layout

Pass the exact prepared vendor root for `warsaw-20261027`. Its libraries and
APKs include the documented transformations from stock. The importer validates
the pinned inputs before staging and generation; separate boot, kernel, firmware
and auxiliary inputs come from the matching vendor tree.

For a baseline update, review each blob change and its provenance, reproduce its
fixup, regenerate the upstream comparison in isolation, review the module/product
adjustments, then update hashes and run configuration, policy, image and hardware
checks. Do not blindly refresh fingerprints to make a failed check pass.

Official references: [working with proprietary blobs](https://github.com/LineageOS/lineage_wiki/blob/main/pages/internal/working_with_blobs.md),
[device support requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md#proprietary-files-extraction).

## October 26 selected-input cleanup

The optional portrait-repair SDK, its loader, model and scene settings were
removed. Six snapshot graphs bypass that node, and both portrait-repair
capability masks are zero. The remaining beautifier, AWB and other graph nodes
are unchanged. The original beautifier and AWB configuration is retained.

Five excluded init scripts and three unused OEM binary copies are also removed
from the file list, SHA256 pins and vendor repository. The repaired face module
and the system_ext DPM alias now have explicit generator definitions using their
already-selected inputs. Their installed definitions are unchanged. The source
vndservicemanager and the Xpan resource package remain selected.

The reviewed generator delta was limited to two portrait-repair module removals;
the later unused-input cleanup left the installed metadata semantically unchanged.
Both raw generator fingerprints and reviewed product patches were regenerated
from those verified deltas. See [official-support review](OFFICIAL_SUPPORT.txt)
for the subsequent Vega cleanup and integration checks.

## October 27 Vega/SenseTime retirement

The prepared tree no longer contains the Vega SDK, Mi FD consumer, ECD engine,
ECD snapshot plugin/preprocessor, ROI encoding filter or three Vega models.
`extract/retired-camera-inputs.json` also includes the previously removed portrait
repair inputs. Prepared-input verification rejects any of these thirteen paths,
including a retired file that is absent from the extraction list.

`configs/camera/prepare-no-vega.py` and its exact-hash manifest reproduce two
adaptations: six selector references use the original Qti FD node, and the anchor
plugin unconditionally uses its existing ECD bypass. ELF symbols, dependencies
and segment layouts stay intact. This does not strip a live DT_NEEDED dependency
or substitute a fake SDK. Check each prepared library with `--input FILE --check`;
use `--input BASELINE --output PREPARED` to reproduce a recorded adaptation.
Unsupported hashes are rejected and already-prepared inputs are idempotent.

Three snapshot graphs bypass four ECD nodes. Both media configurations omit the
automatic face ROI filter; other codec filters remain. Optional facial attributes
are disabled, as is the earlier portrait-repair feature. SenseTime Chat's optional
camera allowlist entry is removed. These are intentional feature retirements.

At the end of the camera retirement, the prepared list had 5,568 inputs and
2,270 Blueprint modules. The startup cleanup below reduces those totals.

## October 27 startup cleanup and pin validation

The current prepared list contains 5,559 inputs and 2,265 Blueprint modules.
Nine obsolete startup payloads are removed: the legacy fan executable and RC,
the codec test executable/library and RC, the unusable debug-diag executable and
wrapper, and RC files for absent FM-loader and psi executables. Forty retired
service definitions and their RC/shell callers are removed. Thermal shutdown and
world-writable modem-log debug triggers are also removed.

`extract/retired-init-inputs.json` records the retired paths, service names and
unsafe triggers. Verification checks unlisted RC and shell files too. It rejects
obsolete service definitions, callers and triggers before generating metadata.
`extract/retired-camera-inputs.json` continues to reject all thirteen SDK paths.

The five added compatibility inputs now carry standard extract-utils SHA1 pins
in `proprietary-files.txt`, alongside the complete SHA256 inventory. Both are
checked. A two-hash entry must match its prepared-output hash; this importer does
not silently substitute its raw-input hash. Malformed or duplicate SHA256 entries
are rejected. Run `python3 extract/tests/test_prepared_inputs.py` from the device
directory for the sixteen synthetic input-validation cases.

Comparing the earlier installed output with the prepared archives found five
JARs with changed classes.dex and APKs with changed packaging/signature bytes.
That older output is diagnostic evidence only. It cannot prove a round trip for
the current source, and exact prepared hashes are not relaxed to accept it.
