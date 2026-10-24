#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the recorded companion patches without modifying any repository."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
tree = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, default=tree.parents[2], help='Android source root')
args = parser.parse_args()
root = args.source.resolve(strict=True)
series_dir = tree / 'patches/platform'
series = json.loads((series_dir / 'series.json').read_text())
failed = []
for entry in series['projects']:
    patch = series_dir / entry['patch']
    project = (root / entry['project']).resolve()
    if not project.is_relative_to(root) or not patch.resolve().is_relative_to(series_dir):
        failed.append(entry['project'] + ': unsafe path')
        continue
    if hashlib.sha256(patch.read_bytes()).hexdigest() != entry['sha256']:
        failed.append(entry['project'] + ': patch SHA256 mismatch')
        continue
    result = subprocess.run(['git', '-C', str(project), 'apply', '--reverse', '--check', str(patch)],
                            capture_output=True, text=True)
    if result.returncode:
        failed.append(entry['project'] + ': ' + result.stderr.strip())
if failed:
    sys.exit('\n'.join(failed))
print(f"PASS: {len(series['projects'])} companion patch hashes and reverse checks")
