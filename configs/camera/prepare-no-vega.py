#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reproduce the exact Warsaw Qti-FD selection and ECD-disable adaptations."""

import argparse
import hashlib
import json
from pathlib import Path

RULES = Path(__file__).with_name("no-vega-fixups.json")


def prepare(blob):
    digest = hashlib.sha256(blob).hexdigest()
    rows = json.loads(RULES.read_text())["files"]
    for row in rows:
        if digest == row["output_sha256"]:
            return blob
        if digest != row["input_sha256"]:
            continue
        result = bytearray(blob)
        for patch in row["patches"]:
            offset = patch["offset"]
            old = bytes.fromhex(patch["before"])
            new = bytes.fromhex(patch["after"])
            if len(old) != len(new) or result[offset:offset + len(old)] != old:
                raise ValueError("Unexpected native adaptation site")
            result[offset:offset + len(new)] = new
        build_id = row["build_id"]
        offset = build_id["offset"]
        old = bytes.fromhex(build_id["before"])
        if result[offset:offset + len(old)] != old:
            raise ValueError("Unexpected ELF build ID")
        result[offset:offset + len(old)] = bytes(len(old))
        new = hashlib.sha256(result).digest()[:len(old)]
        if new.hex() != build_id["after"]:
            raise ValueError("Unexpected prepared build ID")
        result[offset:offset + len(old)] = new
        if hashlib.sha256(result).hexdigest() != row["output_sha256"]:
            raise ValueError("Unexpected prepared native digest")
        return bytes(result)
    raise ValueError("Unsupported input; refusing to patch a different camera ABI")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check == (args.output is not None):
        parser.error("choose --check or --output")
    original = args.input.read_bytes()
    prepared = prepare(original)
    if args.check:
        if prepared != original:
            raise SystemExit("Input still requires the reviewed no-Vega adaptation")
    else:
        args.output.write_bytes(prepared)
    print("PASS: " + hashlib.sha256(prepared).hexdigest())


if __name__ == "__main__":
    main()
