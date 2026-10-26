#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reject failed physical-camera lookup before dereferencing the CHI ID table."""
import argparse
import hashlib
from pathlib import Path

INPUTS = {
    "b491ed00aae52917e92b6522ab99909432b0a9069a23d45836ea21245c7c04fa":
        "83bcb5dd46e7bc4cf2b85060af4958a3898b34dc4c56c817d2d2d9be8f240778",
    "d8a8003f7befbd8c039faed19933ff1fd596d56e20d7d43294165f1fae087659":
        "f028110ef304650477cbbdaac43ff49eef6e512b2c7c7345c45f40aeb5a48db9",
}


def prepare(blob: bytes) -> bytes:
    digest = hashlib.sha256(blob).hexdigest()
    if digest in INPUTS.values():
        return blob
    if digest not in INPUTS:
        raise ValueError("Unsupported CHI input")
    if blob[0x45ead4:0x45ead8] != bytes.fromhex("0a008012"):
        raise ValueError("Unexpected physical-camera failure branch")
    fixed = bytearray(blob)
    # The existing invalid-ID return path preserves both packed sentinel fields.
    # Success still returns the selected physical/framework ID pair unchanged.
    fixed[0x45ead4:0x45ead8] = bytes.fromhex("e8ffff17")
    fixed[0x308:0x318] = bytes(16)
    fixed[0x308:0x318] = hashlib.sha256(fixed).digest()[:16]
    fixed = bytes(fixed)
    if hashlib.sha256(fixed).hexdigest() != INPUTS[digest]:
        raise ValueError("Unexpected prepared CHI digest")
    return fixed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    fixed = prepare(args.input.read_bytes())
    args.output.write_bytes(fixed)
    print(hashlib.sha256(fixed).hexdigest())


if __name__ == "__main__":
    main()
