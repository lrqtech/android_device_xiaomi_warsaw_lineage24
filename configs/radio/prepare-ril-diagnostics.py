#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Restrict the pinned Warsaw RIL diagnostic snapshot to its owner and log group."""
import argparse
import hashlib
from pathlib import Path

BEFORE = '5faf99bcbd058f3d5dc89951c791fe00c507a120fb47eb95fa8830807d7ac3dc'
AFTER = 'fb2fd07b935f112f7607c5892a37975f81ab6f86ca03f8b6b147bad146266b58'
OFFSET = 0x2b2d24
OLD = bytes.fromhex('e23f8052')
NEW = bytes.fromhex('02368052')
NOTE_HEADER = bytes.fromhex('040000001000000003000000474e5500')


def patched(data):
    digest = hashlib.sha256(data).hexdigest()
    if digest == AFTER:
        return data
    if digest != BEFORE:
        raise ValueError('Unsupported RIL library; review its diagnostic file creation first')
    if data[OFFSET:OFFSET + 4] != OLD or data[0x2f8:0x308] != NOTE_HEADER:
        raise ValueError('Unexpected RIL ELF layout')
    result = bytearray(data)
    # print_sync_info() passes mode 0777 to open(O_RDWR | O_CREAT) with umask 0.
    # Change only that mode argument to 0660. Preserve the path, flags, modem
    # reads, error handling and diagnostic writes. Init migrates existing modes.
    result[OFFSET:OFFSET + 4] = NEW
    # Update the public build-ID note; this does not involve a signing key.
    result[0x308:0x318] = bytes(16)
    result[0x308:0x318] = hashlib.sha256(result).digest()[:16]
    if hashlib.sha256(result).hexdigest() != AFTER:
        raise ValueError('RIL diagnostic preparation hash mismatch')
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('--check', action='store_true', help='verify without writing')
    args = parser.parse_args()
    try:
        source = args.binary.read_bytes()
        result = patched(source)
        if args.check:
            if source != result:
                parser.error('The diagnostic file mode repair has not been applied')
        elif source != result:
            args.binary.write_bytes(result)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print('PASS: pinned RIL diagnostic file creation mode')


if __name__ == '__main__':
    main()
