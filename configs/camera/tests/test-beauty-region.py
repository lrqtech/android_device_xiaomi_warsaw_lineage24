#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run stock ARM64 beauty selection and Android org.json on the selected OEM tables.

The harness uses temporary host executables/classes and a transcription of the
app parser contract.
"""
from pathlib import Path
import argparse, hashlib, io, json, re, struct, subprocess, sys, tempfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--android-root', type=Path, required=True)
p.add_argument('--properties', type=Path)
p.add_argument('--python-path', type=Path)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
if a.python_path: sys.path.insert(0, str(a.python_path))
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *

r = a.android_root
v = r / 'vendor/xiaomi/warsaw/proprietary'
mk = a.properties or r / 'device/xiaomi/warsaw/configs/camera/camera.mk'
properties = dict(re.findall(r'^\s*(ro\.miui\.(?:build\.region|region))=([^\s\\]+)', mk.read_text(), re.M))
blob = (v / 'odm/lib64/hw/camera.qcom.so').read_bytes()
apk_digest = hashlib.sha256((v / 'product/priv-app/MiuiCamera/MiuiCamera.apk').read_bytes()).hexdigest()
assert hashlib.sha256(blob).hexdigest() == 'e6d6c0973786a8f565db9c66ad005a20c96fe10f93cadf6c12fac093d432bbd9', 'Unsupported HAL revision'
assert apk_digest == 'acac3f9244ed515c8b283a55fef6a5c2c3d293dada15b884ab3a9a948ba14923', 'Unsupported APK parser revision'
elf = ELFFile(io.BytesIO(blob))
segments = [s for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
imports = {}
for section in elf.iter_sections():
    if section['sh_type'] not in ['SHT_RELA', 'SHT_REL']: continue
    syms = elf.get_section(section['sh_link'])
    for rel in section.iter_relocations():
        if rel['r_offset'] in [0x995688, 0x9956e0, 0x9956e8]:
            imports[rel['r_offset']] = syms.get_symbol(rel['r_info_sym']).name
assert imports == {0x995688: 'property_get', 0x9956e0: 'sysinfo', 0x9956e8: 'strcasecmp'}, imports

def select(props, memory):
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    u.mem_map(0, (max(s['p_vaddr'] + s['p_memsz'] for s in segments) + 4095) & ~4095)
    for s in segments: u.mem_write(s['p_vaddr'], s.data())
    obj = 0x2000000
    u.mem_map(obj, 0x10000)
    u.reg_write(UC_ARM64_REG_X19, obj)
    u.reg_write(UC_ARM64_REG_X28, obj + 0x4000)
    reads = []
    def string(address):
        out = bytearray()
        while True:
            value = u.mem_read(address + len(out), 1)
            if value == b'\0': return out.decode()
            out.extend(value)
            if len(out) > 256: raise AssertionError('unterminated native string')
    def hook(mu, address, size, data):
        if address == 0x96ec60:
            key = string(mu.reg_read(UC_ARM64_REG_X0)); default = string(mu.reg_read(UC_ARM64_REG_X2))
            value = props.get(key) or default; reads.append([key, default, value])
            mu.mem_write(mu.reg_read(UC_ARM64_REG_X1), value.encode() + b'\0')
            mu.reg_write(UC_ARM64_REG_X0, len(value))
        elif address == 0x96ed68:
            mu.mem_write(obj + 0x380, struct.pack('<Q', memory))
            mu.reg_write(UC_ARM64_REG_X0, 0)
        elif address == 0x96ed80:
            left = string(mu.reg_read(UC_ARM64_REG_X0)).lower()
            right = string(mu.reg_read(UC_ARM64_REG_X1)).lower()
            mu.reg_write(UC_ARM64_REG_W0, 0 if left == right else 1)
        else: return
        mu.reg_write(UC_ARM64_REG_PC, mu.reg_read(UC_ARM64_REG_LR))
    u.hook_add(UC_HOOK_CODE, hook)
    u.emu_start(0x267290, 0x26734c, count=500)
    assert u.reg_read(UC_ARM64_REG_PC) == 0x26734c
    return string(u.reg_read(UC_ARM64_REG_X2)), reads

cases = []; native = []
scenarios = [('current', properties, 'CNRegion'),
             ('fallback_build_cn', {'ro.miui.build.region': 'cn'}, 'CNRegion'),
             ('global', {'ro.miui.build.region': 'global', 'ro.miui.region': 'US'}, 'GLRegion'),
             ('india', {'ro.miui.build.region': 'in', 'ro.miui.region': 'IN'}, 'INRegion')]
for name, props, app_region in scenarios:
    for ram in [8, 12]:
        selected, reads = select(props, ram << 30)
        expected = 'odm/etc/camera/beauty_config_' + ('cn' if app_region == 'CNRegion' else 'gl') + ('_8g' if ram == 8 else '') + '.json'
        native.append({'case': f'{name}_{ram}GB', 'passed': selected == expected,
                       'selected': selected, 'expected': expected, 'properties_read': reads})
        cases.append({'name': f'{name}_{ram}GB', 'file': selected, 'app_region': app_region,
                      'china': app_region == 'CNRegion', 'india': app_region == 'INRegion'})

java_sources = sorted((r / 'libcore/json/src/main/java/org/json').glob('*.java'))
java_sources += [r / path for path in [
    'frameworks/libs/modules-utils/java/android/annotation/SystemApi.java',
    'tools/platform-compat/java/android/compat/annotation/UnsupportedAppUsage.java',
    'libcore/luni/src/main/java/libcore/util/NonNull.java',
    'libcore/luni/src/main/java/libcore/util/Nullable.java',
    'libcore/luni/src/main/java/libcore/api/IntraCoreApi.java']]
java_sources.append(Path(__file__).with_name('BeautyRegionContractTest.java'))
jdk = r / 'prebuilts/jdk/jdk21/linux-x86/bin'
with tempfile.TemporaryDirectory(prefix='warsaw-beauty-host-') as tmp:
    spec = Path(tmp) / 'cases.json'; spec.write_text(json.dumps(cases))
    subprocess.run([str(jdk / 'javac'), '-proc:none', '-d', tmp, *map(str, java_sources)], check=True, capture_output=True)
    result = subprocess.run([str(jdk / 'java'), '-cp', tmp, 'BeautyRegionContractTest', str(v), str(spec)], check=True, capture_output=True, text=True)
    parsed = json.loads(result.stdout)
report = {'properties_file': str(mk), 'properties': properties, 'native': native, 'app_contract': parsed,
          'hal_sha256': hashlib.sha256(blob).hexdigest(),
          'apk_sha256': apk_digest,
          'table_sha256': {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in (v / 'odm/etc/camera').glob('beauty*.json')},
          'cases': len(native) + len(parsed), 'failures': sum(not x['passed'] for x in native + parsed),
          'native_selection_executed': True, 'app_parser_transcribed': True, 'android_json_executed': True,
          'ui_or_algorithm_executed': False, 'phone_verified': False, 'package_created': False}
a.report.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: report[k] for k in ['cases', 'failures', 'properties', 'hal_sha256', 'apk_sha256']}))
raise SystemExit(bool(report['failures']))
