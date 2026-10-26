#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Exercise prepared-input verification without copying proprietary payloads."""

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import warsaw


class PreparedInputsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='warsaw-extract-test-')
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.root = self.directory / 'proprietary'
        self.device = self.directory / 'device'
        self.data = self.device / 'extract'
        self.data.mkdir(parents=True)
        self.blob = self.root / 'vendor/lib64/example.so'
        self.blob.parent.mkdir(parents=True)
        self.blob.write_bytes(b'prepared input fixture\n')
        self.sha1 = hashlib.sha1(self.blob.read_bytes()).hexdigest()
        self.sha256 = hashlib.sha256(self.blob.read_bytes()).hexdigest()
        self.relative = self.blob.relative_to(self.root).as_posix()
        self.manifest = self.data / 'proprietary.sha256'
        self.manifest.write_text(self.sha256 + '  ' + self.relative + '\n')
        self.listing = self.device / 'proprietary-files.txt'
        self.listing.write_text(self.relative + '\n')
        (self.data / 'retired-camera-inputs.json').write_text(
            json.dumps({'paths': ['vendor/lib64/retired.so']}))
        (self.data / 'retired-init-inputs.json').write_text(json.dumps({
            'paths': ['vendor/bin/retired-daemon'],
            'services': ['retired-daemon'],
            'undefined_control_targets': ['absent-daemon'],
            'forbidden_property_triggers': ['persist.vendor.test.thermal_off=1'],
        }))
        replacement = patch.multiple(warsaw, DEVICE=self.device, DATA=self.data)
        replacement.start()
        self.addCleanup(replacement.stop)

    def test_sha256_baseline(self):
        self.assertEqual(len(warsaw.verify_blobs(self.root)), 1)

    def test_inline_prepared_pin(self):
        self.listing.write_text(self.relative + '|' + self.sha1 + '\n')
        self.assertEqual(len(warsaw.verify_blobs(self.root)), 1)

    def test_inline_pin_mismatch(self):
        self.listing.write_text(self.relative + '|' + '0' * 40 + '\n')
        with self.assertRaisesRegex(ValueError, 'prepared SHA1 mismatch'):
            warsaw.verify_blobs(self.root)

    def test_two_hash_entry_checks_prepared_output(self):
        self.listing.write_text(self.relative + '|' + '0' * 40 + '|' + self.sha1 + '\n')
        self.assertEqual(len(warsaw.verify_blobs(self.root)), 1)

    def test_two_hash_entry_rejects_raw_input(self):
        self.listing.write_text(self.relative + '|' + self.sha1 + '|' + '0' * 40 + '\n')
        with self.assertRaisesRegex(ValueError, 'prepared SHA1 mismatch'):
            warsaw.verify_blobs(self.root)

    def test_invalid_inline_pin(self):
        self.listing.write_text(self.relative + '|not-a-sha1\n')
        with self.assertRaisesRegex(ValueError, 'Invalid proprietary-files SHA1'):
            warsaw.verify_blobs(self.root)

    def test_duplicate_sha256_entry(self):
        self.manifest.write_text(self.manifest.read_text() * 2)
        with self.assertRaisesRegex(ValueError, 'Repeated prepared-input SHA256'):
            warsaw.verify_blobs(self.root)

    def test_invalid_sha256_entry(self):
        self.manifest.write_text('invalid  ' + self.relative + '\n')
        with self.assertRaisesRegex(ValueError, 'Invalid prepared-input SHA256'):
            warsaw.verify_blobs(self.root)

    def test_modified_blob(self):
        self.blob.write_bytes(b'changed input\n')
        with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
            warsaw.verify_blobs(self.root)

    def test_retired_unlisted_blob(self):
        (self.root / 'vendor/lib64/retired.so').write_bytes(b'retired\n')
        with self.assertRaisesRegex(ValueError, 'Retired camera input remains'):
            warsaw.verify_blobs(self.root)

    def test_external_symlink(self):
        external = self.directory / 'outside.so'
        self.blob.rename(external)
        self.blob.symlink_to(external)
        with self.assertRaisesRegex(ValueError, 'Missing or unsafe prepared input'):
            warsaw.verify_blobs(self.root)

    def test_retired_init_payload(self):
        path = self.root / 'vendor/bin/retired-daemon'
        path.parent.mkdir(parents=True)
        path.write_bytes(b'obsolete executable\n')
        with self.assertRaisesRegex(ValueError, 'Retired init input remains'):
            warsaw.verify_blobs(self.root)

    def test_unlisted_retired_init_service(self):
        (self.root / 'unlisted.rc').write_text('service retired-daemon /vendor/bin/other\n')
        with self.assertRaisesRegex(ValueError, 'Retired init service'):
            warsaw.verify_blobs(self.root)

    def test_unlisted_retired_shell_caller(self):
        (self.root / 'unlisted.sh').write_text('start absent-daemon;;\n')
        with self.assertRaisesRegex(ValueError, 'Retired service control'):
            warsaw.verify_blobs(self.root)

    def test_unsafe_property_trigger(self):
        (self.root / 'unlisted.rc').write_text(
            'on boot && property:persist.vendor.test.thermal_off=1\n'
            '    stop thermal\n')
        with self.assertRaisesRegex(ValueError, 'Unsafe retired property trigger'):
            warsaw.verify_blobs(self.root)

    def test_parent_path_in_list(self):
        self.listing.write_text('../outside.so\n')
        with self.assertRaisesRegex(ValueError, 'Unsafe blob path'):
            warsaw.verify_blobs(self.root)


if __name__ == '__main__':
    unittest.main()
