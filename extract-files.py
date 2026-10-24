#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Import the prepared Warsaw blob baseline; raw OEM OTA fixups are not implicit."""

import argparse
from pathlib import Path
import shutil
import sys
import tempfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent / 'extract'))
from warsaw import MAKEFILES, VENDOR, generate, install_makefiles, verify_blobs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='matching prepared vendor tree or its proprietary/ directory')
    parser.add_argument('--output-dir', type=Path, default=VENDOR, help='vendor output directory (boot/firmware/configs are retained separately)')
    parser.add_argument('--check', action='store_true', help='verify all source inputs without writing anything')
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    if (source / 'proprietary').is_dir():
        source = (source / 'proprietary').resolve(strict=True)
    files = verify_blobs(source)
    if args.check:
        print(f'PASS: {len(files)} prepared blobs verified')
        return
    output = args.output_dir.resolve()
    if output == source or output.is_relative_to(source) or source.is_relative_to(output):
        raise ValueError('Source and output must not overlap; use setup-makefiles.py for the existing vendor tree')
    output.mkdir(parents=True, exist_ok=True)
    old_blobs = output / 'proprietary'
    if old_blobs.is_symlink():
        raise ValueError('Refusing to replace a symlinked proprietary directory')
    for rel in MAKEFILES:
        if (output / rel).is_symlink():
            raise ValueError('Refusing to replace a symlinked makefile: ' + rel)
    # A sibling staging directory keeps directory replacement on one filesystem.
    with tempfile.TemporaryDirectory(prefix='.warsaw-extract-', dir=output.parent) as temporary:
        stage = Path(temporary)
        blobs = stage / 'proprietary'
        for file in files:
            target = blobs / file.dst
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source / file.dst, target)
        verify_blobs(blobs)
        generated = stage / 'generated'
        generate(blobs, generated)
        original = {rel: (output / rel).read_bytes() if (output / rel).exists() else None for rel in MAKEFILES}
        backup = stage / 'previous-proprietary'
        moved_old = moved_new = False
        try:
            if old_blobs.exists():
                old_blobs.rename(backup)
                moved_old = True
            blobs.rename(old_blobs)
            moved_new = True
            install_makefiles(generated, output)
        except BaseException:
            if moved_new:
                old_blobs.rename(blobs)
            if moved_old:
                backup.rename(old_blobs)
            for rel, data in original.items():
                if data is None:
                    (output / rel).unlink(missing_ok=True)
                else:
                    (output / rel).write_bytes(data)
            raise
    print(f'PASS: imported {len(files)} prepared blobs and regenerated vendor rules')
    print('Kernel, firmware, boot and auxiliary vendor inputs remain separate prerequisites; see docs/proprietary-files.md.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        sys.exit(str(error))
