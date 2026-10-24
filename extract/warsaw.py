# SPDX-License-Identifier: Apache-2.0
"""Reproduce the prepared Warsaw vendor integration without changing its ABI."""

import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

DEVICE = Path(__file__).resolve().parents[1]
ANDROID = DEVICE.parents[2]
VENDOR = ANDROID / 'vendor/xiaomi/warsaw'
DATA = DEVICE / 'extract'
sys.path.insert(0, str(ANDROID / 'tools/extract-utils'))
from extract_utils.file import File
from extract_utils.main import ExtractUtilsModule

MAKEFILES = ('Android.bp', 'Android.mk', 'BoardConfigVendor.mk', 'warsaw-vendor.mk')
TOKEN = re.compile(r'\s+|//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|.', re.S)


def read_bp(text):
    """Read generated Blueprint literals and the retained Soong select guards."""
    tokens = [m.group() for m in TOKEN.finditer(text)
              if not m.group().isspace() and not m.group().startswith(('//', '/*'))]
    index = 0

    def value():
        nonlocal index
        token = tokens[index]
        index += 1
        if token == '{':
            result = {}
            while tokens[index] != '}':
                key = tokens[index]
                index += 1
                if key.startswith('"'):
                    key = json.loads(key)
                if tokens[index] != ':' or key in result:
                    raise ValueError('Invalid or repeated Blueprint property: ' + key)
                index += 1
                result[key] = value()
                if tokens[index] == ',':
                    index += 1
            index += 1
            return result
        if token == '[':
            result = []
            while tokens[index] != ']':
                result.append(value())
                if tokens[index] == ',':
                    index += 1
            index += 1
            return result
        if token.startswith('"'):
            return json.loads(token)
        if token in ('true', 'false'):
            return token == 'true'
        if token.isdigit():
            return int(token)
        if token == 'select':
            start, depth = index - 1, 0
            if tokens[index] != '(':
                raise ValueError('Malformed select expression')
            while index < len(tokens):
                if tokens[index] == '(':
                    depth += 1
                if tokens[index] == ')':
                    depth -= 1
                index += 1
                if depth == 0:
                    return {'__bp_expr__': ''.join(tokens[start:index])}
        raise ValueError('Unsupported Blueprint token: ' + token)

    modules = []
    while index < len(tokens):
        kind = tokens[index]
        index += 1
        properties = value()
        if not isinstance(properties, dict):
            raise ValueError('Expected a Blueprint module')
        modules.append({'type': kind, 'properties': properties})
    return modules


def encode_bp(value, level=0):
    indent = '    ' * level
    if isinstance(value, dict):
        if set(value) == {'__bp_expr__'}:
            return value['__bp_expr__']
        return '{\n' + ''.join('    ' + indent + k + ': ' + encode_bp(v, level + 1)
                              + ',\n' for k, v in value.items()) + indent + '}'
    if isinstance(value, list):
        return '[\n' + ''.join('    ' + indent + encode_bp(v, level + 1) + ',\n'
                              for v in value) + indent + ']'
    return json.dumps(value, ensure_ascii=False)


def name(module):
    return module['properties'].get('name', '__namespace')


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def blob_files():
    files = [File(line) for line in (DEVICE / 'proprietary-files.txt').read_text().splitlines()
             if line.strip() and not line.lstrip().startswith('#')]
    destinations = set()
    for file in files:
        for rel in (file.src, file.dst):
            p = Path(rel)
            if p.is_absolute() or '..' in p.parts or not p.parts:
                raise ValueError('Unsafe blob path: ' + rel)
        if file.dst in destinations:
            raise ValueError('Repeated blob destination: ' + file.dst)
        destinations.add(file.dst)
    return files


def verify_blobs(root):
    """Prepared inputs include adapted blobs; reject raw, LFS, or partial inputs."""
    root = root.resolve(strict=True)
    expected = {}
    for line in (DATA / 'proprietary.sha256').read_text().splitlines():
        digest_text, rel = line.split('  ', 1)
        expected[rel] = digest_text
    files = blob_files()
    if set(expected) != {f.dst for f in files}:
        raise ValueError('Blob list and prepared-input hashes disagree')
    for file in files:
        path = root / file.dst
        if not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError('Missing or unsafe prepared input: ' + file.dst)
        if sha256(path) != expected[file.dst]:
            raise ValueError('Prepared-input SHA256 mismatch: ' + file.dst)
    return files


def generate(blob_root, destination):
    """Use Lineage's generator, then restore the reviewed device-specific rules."""
    rules = json.loads((DATA / 'module-rules.json').read_text())
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError('Generation directory must be empty')
    (destination / 'proprietary').symlink_to(blob_root.resolve(), target_is_directory=True)
    try:
        namespace = json.loads((DATA / 'namespace-imports.json').read_text())
        module = ExtractUtilsModule('warsaw', 'xiaomi', namespace_imports=namespace)
        module.vendor_path = str(destination)
        module.parse(False, None)
        module.write_makefiles(False, False)
        modules = read_bp((destination / 'Android.bp').read_text())
        if digest(modules) != rules['input_sha256']:
            raise ValueError('Extract-utils output changed; review generator/dependency changes before updating module-rules.json')
        result = []
        for module in modules:
            old_name = name(module)
            if old_name in rules['remove']:
                continue
            module['properties'].update(copy.deepcopy(rules['defaults'].get(module['type'], {})))
            for change in rules['changes'].get(old_name, []):
                parts = change['path'].strip('/').split('/')
                parent = module
                for part in parts[:-1]:
                    parent = parent[part]
                if change['op'] == 'remove':
                    del parent[parts[-1]]
                elif change['op'] == 'set':
                    parent[parts[-1]] = copy.deepcopy(change['value'])
                else:
                    raise ValueError('Unsupported module rule')
            result.append(module)
        result.extend(rules['add'])
        if len({name(m) for m in result}) != len(result):
            raise ValueError('Repeated generated module name')
        if digest(sorted(result, key=name)) != rules['output_sha256']:
            raise ValueError('Reviewed module rules are inconsistent')
        text = '// Generated by device/xiaomi/warsaw/setup-makefiles.py.\n'
        text += '// SPDX-License-Identifier: Apache-2.0\n'
        text += ''.join('\n' + m['type'] + ' ' + encode_bp(m['properties']) + '\n' for m in result)
        (destination / 'Android.bp').write_text(text)
        expected = json.loads((DATA / 'makefiles.json').read_text())
        for rel, hashes in expected.items():
            if sha256(destination / rel) != hashes['input_sha256']:
                raise ValueError('Unreviewed generator change: ' + rel)
        patch_command = ['patch', '--batch', '--forward', '--fuzz=0', '-p1',
                         '-i', str(DATA / 'product-rules.patch'), '-d', str(destination)]
        subprocess.run(patch_command + ['--dry-run'], check=True, capture_output=True, text=True)
        subprocess.run(patch_command, check=True, capture_output=True, text=True)
        for rel, hashes in expected.items():
            if sha256(destination / rel) != hashes['output_sha256']:
                raise ValueError('Reviewed product rules are inconsistent: ' + rel)
    finally:
        (destination / 'proprietary').unlink()


def same_makefile(left, right):
    if not right.is_file():
        return False
    if left.name == 'Android.bp':
        return sorted(read_bp(left.read_text()), key=name) == sorted(read_bp(right.read_text()), key=name)
    return left.read_bytes() == right.read_bytes()


def install_makefiles(generated, destination):
    destination.mkdir(parents=True, exist_ok=True)
    changed = []
    for rel in MAKEFILES:
        source, target = generated / rel, destination / rel
        if same_makefile(source, target):
            continue
        if target.is_symlink() or (target.exists() and not target.is_file()):
            raise ValueError('Refusing to replace non-regular makefile: ' + str(target))
        changed.append(rel)
    with tempfile.TemporaryDirectory(prefix='.warsaw-makefiles-', dir=destination.parent) as temporary:
        stage = Path(temporary)
        for rel in changed:
            (stage / rel).write_bytes((generated / rel).read_bytes())
            (stage / rel).chmod(0o644)
        originals = []
        installed = []
        try:
            for rel in changed:
                target = destination / rel
                if target.exists():
                    target.rename(stage / (rel + '.previous'))
                    originals.append(rel)
                os.replace(stage / rel, target)
                installed.append(rel)
        except BaseException:
            for rel in installed:
                (destination / rel).unlink()
            for rel in originals:
                (stage / (rel + '.previous')).rename(destination / rel)
            raise
