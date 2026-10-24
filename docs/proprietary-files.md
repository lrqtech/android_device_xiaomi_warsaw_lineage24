# Prepared proprietary inputs

`proprietary-files.txt` lists all 5,589 userspace inputs in the current prepared
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

## Outstanding official extraction requirement

This import path is deliberately limited to the exact prepared baseline. A raw
OS4 OTA, a LineageOS OTA ZIP, or a connected device is not a supported replacement
source yet. Some prepared libraries and APKs differ from their stock or installed
forms. Reproducing those transformations and validating an extraction round trip
from an installed LineageOS build remains work for official admission. A SHA256
lock and a successful prepared import do not establish that round trip.

For a baseline update, review each blob change and its provenance, reproduce its
fixup, regenerate the upstream comparison in isolation, review the module/product
adjustments, then update hashes and run configuration, policy, image and hardware
checks. Do not blindly refresh fingerprints to make a failed check pass.

Official references: [working with proprietary blobs](https://github.com/LineageOS/lineage_wiki/blob/main/pages/internal/working_with_blobs.md),
[device support requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md#proprietary-files-extraction).
