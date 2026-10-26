#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Add the pinned UFS extended-geometry format to Warsaw tee-supplicant."""
import argparse
import hashlib
from pathlib import Path

BEFORE = 'afeb3881659771428692b259c64249c573370b83bac90f06007e2a278a9ffb62'
AFTER = '59069d394272caa2f9f3cd1fc0eedd512a61378131de40981c47dac67ae65fc9'
OFFSET = 0x81b4
OLD = bytes.fromhex('a4024039e0031f2a9f200171a0f6ff549f5c017160f6ff54')
NEW = bytes.fromhex('a4024039882001511f85007104194f7a0419407a20ffff54')


def patched(data):
    digest = hashlib.sha256(data).hexdigest()
    if digest == AFTER:
        return data
    if digest != BEFORE:
        raise ValueError('Unsupported tee-supplicant input; review its descriptor parser first')
    if data[OFFSET:OFFSET + len(OLD)] != OLD or data[0x38c:0x390] != b'GNU\0':
        raise ValueError('Unexpected ELF layout')
    result = bytearray(data)
    # This geometry-only case previously accepted lengths 0x48 and 0x57.
    # Match (length - 0x48) against 0x21, 0x0f and 0 using CCMP. Once a
    # comparison matches, NZCV=4 preserves Z=1 for the following comparison.
    # The existing success block at 0x81ac sets w0=0; failures still reach
    # 0x81cc. Keep the READ_DESC request, status checks, field offset 0x17,
    # 1..64 count limit, error reporting and all RPMB command paths unchanged.
    result[OFFSET:OFFSET + len(OLD)] = NEW
    # This note identifies the edited executable; it is not a signing key.
    result[0x390:0x3a0] = bytes(16)
    result[0x390:0x3a0] = hashlib.sha256(result).digest()[:16]
    if hashlib.sha256(result).hexdigest() != AFTER:
        raise ValueError('tee-supplicant output hash mismatch')
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('--check', action='store_true', help='verify without changing the file')
    args = parser.parse_args()
    try:
        source = args.binary.read_bytes()
        result = patched(source)
        if args.check:
            if source != result:
                parser.error('The pinned UFS geometry compatibility fix has not been applied')
        elif source != result:
            args.binary.write_bytes(result)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print('PASS: pinned UFS geometry descriptor compatibility')


if __name__ == '__main__':
    main()
