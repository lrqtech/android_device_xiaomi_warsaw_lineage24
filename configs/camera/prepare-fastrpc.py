#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Apply the pinned Warsaw FastRPC path and driver-ABI compatibility fixes."""
import argparse
import hashlib
from pathlib import Path

BEFORE = 'ec0cb0b1ee92ec94685d013104fd628d69ece9bc750a84eaf16192050b0bb632'
PATH_ONLY = '9e7ad9370952ab62a51ef24c6d5fbc11c64c453c2d7d3135272a6f27af9d8228'
AFTER = 'e28f3e425f0beb1f195a701b47b7297ee473e6ae227b06832c466e439d78531f'
DRIVER = '525a81ef61ae817011d8d2fa339f9a8318a24026bf0dfeea6166846b249424bd'

def patched(data, driver):
    if hashlib.sha256(driver).hexdigest() != DRIVER:
        raise ValueError('Unsupported FastRPC driver; review its ioctl ABI first')
    digest = hashlib.sha256(data).hexdigest()
    if digest == AFTER:
        return data
    if digest not in (BEFORE, PATH_ONLY):
        raise ValueError('Unsupported prepared FastRPC library')
    out = bytearray(data)
    if digest == BEFORE:
        old, new = b'/usr/lib/dsp/\0', b'/odm/lib/dsp/\0'
        if data.count(old) != 1 or data.find(old) != 0xe34d:
            raise ValueError('Unexpected DSP shell path layout')
        out[0xe34d:0xe34d + len(old)] = new
    # This OEM driver uses 0xc038520c for multimode invocation, not SET_OPTION.
    # req=1 consumes a 56-byte invocation, not the old 4-byte concurrency value.
    # CDSP persistent headers are already allocated by its process-create path.
    # Continue after this obsolete request; keep real initialization/error paths.
    if out[0x3d1ec:0x3d1f0] != bytes.fromhex('7f0f0071'):
        raise ValueError('Unexpected optimization callsite')
    out[0x3d1ec:0x3d1f0] = bytes.fromhex('3b000014')  # b 0x3d2d8
    out[0x2b0:0x2c0] = bytes(16)
    out[0x2b0:0x2c0] = hashlib.sha256(out).digest()[:16]
    if hashlib.sha256(out).hexdigest() != AFTER:
        raise ValueError('FastRPC output hash mismatch')
    return bytes(out)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    parser.add_argument('--driver', required=True, type=Path,
                        help='frpc-adsprpc.ko extracted from the matching vendor_dlkm image')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = args.library.read_bytes()
    result = patched(source, args.driver.read_bytes())
    if args.check:
        if source != result:
            parser.error('The pinned compatibility fixes have not been applied')
    elif source != result:
        args.library.write_bytes(result)
    print('PASS: pinned FastRPC path and driver ABI')
