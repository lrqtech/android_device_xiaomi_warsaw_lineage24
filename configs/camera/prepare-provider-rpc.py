#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Repair handle registration in the pinned Warsaw camera provider."""
import argparse
import hashlib
from pathlib import Path

BEFORE = '296252298a85204a7f4dbbd9c7aa2fd0e0c8e4c395e64a57ded8039029e3f38e'
AFTER = '31e7905c101ddaffc0fdbcf6e88425b85a82ea750f3e7e4ad369ab4d59f28528'
RPC_LIBRARY = 'e28f3e425f0beb1f195a701b47b7297ee473e6ae227b06832c466e439d78531f'
EDITS = (
    (0xbce0, bytes.fromhex('132200b4'), bytes.fromhex('1a220035')),
    (0xbd80, bytes.fromhex('29008052'), bytes.fromhex('09008052')),
)


def patched(data, rpc_library):
    if hashlib.sha256(rpc_library).hexdigest() != RPC_LIBRARY:
        raise ValueError('Unsupported FastRPC library; review its open contract first')
    digest = hashlib.sha256(data).hexdigest()
    if digest == AFTER:
        return data
    if digest != BEFORE:
        raise ValueError('Unsupported camera provider; review its RPC hooks first')
    if data[0x38c:0x390] != b'GNU\0':
        raise ValueError('Unexpected provider build-id note')
    out = bytearray(data)
    for offset, old, new in EDITS:
        if data[offset:offset + len(old)] != old:
            raise ValueError('Unexpected provider instruction layout')
        out[offset:offset + len(new)] = new
    # 0xbce0: cbnz w26, 0xc120. Check the underlying open result before
    # parsing the URI or dereferencing its output. The pinned RPC library
    # rejects null inputs, so success also establishes both pointers are valid.
    # The original result and SSR bookkeeping remain at 0xc120 and below.
    # 0xbd80: mov w9, #0. Domain 0 is valid ADSP; it must enter the map just
    # like domains 1..7. This does not enable the CDSP watchdog for ADSP.
    # Leave missing-handle diagnostics, invocation, close, locks, and errors intact.
    out[0x390:0x3a0] = bytes(16)
    out[0x390:0x3a0] = hashlib.sha256(out).digest()[:16]
    if hashlib.sha256(out).hexdigest() != AFTER:
        raise ValueError('Camera provider output hash mismatch')
    return bytes(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('provider', type=Path)
    parser.add_argument('--rpc-library', required=True, type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        data = args.provider.read_bytes()
        result = patched(data, args.rpc_library.read_bytes())
        if args.check:
            if data != result:
                parser.error('The pinned provider handle repair has not been applied')
        elif data != result:
            args.provider.write_bytes(result)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print('PASS: camera provider handle registration')


if __name__ == '__main__':
    main()
