#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Regenerate Warsaw vendor build rules from the matching prepared blobs."""

import argparse
from pathlib import Path
import sys
import tempfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent / 'extract'))
from warsaw import MAKEFILES, VENDOR, generate, install_makefiles, same_makefile, verify_blobs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vendor-dir', type=Path, default=VENDOR)
    parser.add_argument('--output-dir', type=Path, help='write only build metadata into a separate directory')
    parser.add_argument('--check', action='store_true', help='verify reproduction without changing the vendor tree')
    args = parser.parse_args()
    if args.check and args.output_dir:
        parser.error('--check and --output-dir are mutually exclusive')
    vendor = args.vendor_dir.resolve(strict=True)
    verify_blobs(vendor / 'proprietary')
    with tempfile.TemporaryDirectory(prefix='warsaw-makefiles-') as temporary:
        generated = Path(temporary) / 'generated'
        generate(vendor / 'proprietary', generated)
        if args.check:
            mismatches = [rel for rel in MAKEFILES if not same_makefile(generated / rel, vendor / rel)]
            if mismatches:
                raise ValueError('Vendor metadata does not reproduce: ' + ', '.join(mismatches))
        else:
            install_makefiles(generated, args.output_dir or vendor)
    print('PASS: prepared blobs and vendor build rules verified')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        sys.exit(str(error))
