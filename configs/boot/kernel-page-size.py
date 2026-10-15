#!/usr/bin/env python3
"""Read the memory page size declared in an uncompressed arm64 kernel Image."""
import struct
import sys

with open(sys.argv[1], "rb") as stream:
    header = stream.read(64)
if len(header) != 64 or header[56:60] != b"ARM\x64":
    raise SystemExit("Expected an uncompressed arm64 kernel Image")
page_encoding = (struct.unpack_from("<Q", header, 24)[0] >> 1) & 3
if page_encoding == 0:
    raise SystemExit("Kernel Image does not declare its memory page size")
print({1: 4096, 2: 16384, 3: 65536}[page_encoding])
