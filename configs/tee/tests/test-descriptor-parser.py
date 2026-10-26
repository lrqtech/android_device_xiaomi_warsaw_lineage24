#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual pinned ARM descriptor parser without any device I/O.

Requires pyelftools and Unicorn. UFS READ_DESC and libc calls are modeled;
this does not validate storage firmware, key operations, or a booted device.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import runpy
import struct
import sys

sys.dont_write_bytecode = True
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--original', required=True, type=Path)
parser.add_argument('--prepared', required=True, type=Path)
parser.add_argument('--python-deps', type=Path)
parser.add_argument('--report', type=Path)
args = parser.parse_args()
if args.python_deps:
    sys.path.insert(0, str(args.python_deps))
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *

tool = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'prepare-tee-supplicant.py'))
b = args.original.read_bytes()
after = args.prepared.read_bytes()
assert hashlib.sha256(b).hexdigest() == tool['BEFORE']
assert hashlib.sha256(after).hexdigest() == tool['AFTER']
assert tool['patched'](b) == after
assert tool['patched'](after) == after
assert len(b) == len(after)
changed = {i for i, (x, y) in enumerate(zip(b, after)) if x != y}
assert changed <= set(range(0x81b4, 0x81cc)) | set(range(0x390, 0x3a0))
for invalid in [b'', b[:-1], b[:0x81b4] + bytes(24) + b[0x81cc:]]:
    try:
        tool['patched'](invalid)
    except ValueError:
        pass
    else:
        raise AssertionError('Unsupported input accepted')
left, right = ELFFile(io.BytesIO(b)), ELFFile(io.BytesIO(after))
assert [dict(s.header) for s in left.iter_segments()] == [dict(s.header) for s in right.iter_segments()]
assert [dict(s.header) for s in left.iter_sections()] == [dict(s.header) for s in right.iter_sections()]
for section in left.iter_sections():
    if section.name not in ['.text', '.note.gnu.build-id']:
        assert section.data() == right.get_section_by_name(section.name).data()

rows = []
for label,data in [('before',b),('prepared',after)]:
 e=ELFFile(io.BytesIO(data));u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x20000)
 for s in e.iter_segments():
  if s['p_type']=='PT_LOAD':u.mem_write(s['p_vaddr'],s.data())
 u.mem_map(0x200000,0x20000);u.reg_write(UC_ARM64_REG_X21,0x200000);u.reg_write(UC_ARM64_REG_SP,0x210000)
 def hook(uc,a,n,_):
  if a==0x8094:uc.emu_stop()
  elif a==0xa088:uc.reg_write(UC_ARM64_REG_X0,0);uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
 u.hook_add(UC_HOOK_CODE,hook);accepted={}
 for kind in [2,4,7,0,1,8,255]:
  hits=[]
  for length in range(256):
   u.mem_write(0x200000,bytes([length]));u.reg_write(UC_ARM64_REG_W19,kind);u.reg_write(UC_ARM64_REG_X0,0xf123);u.emu_start(0x8168,0,count=100)
   assert u.reg_read(UC_ARM64_REG_PC)==0x8094,(kind,length,hex(u.reg_read(UC_ARM64_REG_PC)))
   actual=u.reg_read(UC_ARM64_REG_W0)==0
   allowed={2:[0x23,0x2d],4:[6],7:[0x48,0x57]+([0x69] if label=='prepared' else [])}.get(kind,range(256))
   assert actual==(length in allowed),(label,kind,length,actual)
   if actual:hits.append(length)
  accepted[str(kind)]={'count':len(hits),'values':hits if len(hits)<10 else 'unchanged unrestricted for this descriptor'}
 rows.append({'version':label,'cases':7*256,'accepted':accepted})

branch_rows = rows
def run(data,length,count,io_error=0,status=0,info=0,open_error=False,retry=None):
 e=ELFFile(io.BytesIO(data));u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x20000)
 for s in e.iter_segments():
  if s['p_type']=='PT_LOAD':u.mem_write(s['p_vaddr'],s.data())
 u.mem_map(0x200000,0x40000);u.reg_write(UC_ARM64_REG_SP,0x230000);u.reg_write(UC_ARM64_REG_TPIDR_EL0,0x200000);u.reg_write(UC_ARM64_REG_X1,0x201000);u.reg_write(UC_ARM64_REG_LR,0x10000);calls=[];logs=[];attempt=0
 def ptr(addr):return struct.unpack('<Q',u.mem_read(addr,8))[0]
 def cstr(addr):return bytes(u.mem_read(addr,512)).split(b'\0')[0].decode(errors='replace')
 def hook(uc,a,n,_):
  nonlocal attempt
  if a==0x10000:uc.emu_stop();return
  ret=0
  if a==0xa100:
   assert cstr(uc.reg_read(UC_ARM64_REG_X0))=='/dev/ufs-bsg0';assert uc.reg_read(UC_ARM64_REG_W1)==2
   ret=-1 if open_error else 3;calls.append('open')
   if open_error:uc.mem_write(0x200100,struct.pack('<I',13))
  elif a==0xa118:
   assert uc.reg_read(UC_ARM64_REG_W0)==3;assert uc.reg_read(UC_ARM64_REG_W1)==0x2285
   sg=uc.reg_read(UC_ARM64_REG_X2);req=ptr(sg+0x10);rsp=ptr(sg+0x30);buf=ptr(sg+0x50);idn=uc.mem_read(req+0x11,1)[0]
   assert uc.mem_read(req+0x10,1)[0]==1 # READ_DESC only
   calls.append('query_geometry' if idn==7 else 'query_unit')
   if idn==7:
    attempt+=1
    if retry and attempt==1:ret=-1;uc.mem_write(0x200100,struct.pack('<I',retry))
    elif io_error:ret=-1;uc.mem_write(0x200100,struct.pack('<I',io_error))
    else:
     size=struct.unpack('<I',uc.mem_read(sg+0x44,4))[0];assert size==0x57
     desc=bytearray(size);desc[0]=length;desc[1]=7;desc[0x17]=count;uc.mem_write(buf,bytes(desc));uc.mem_write(rsp+0xe,bytes([status]));uc.mem_write(sg+0x7c,struct.pack('<I',info))
   else:
    assert idn==2;desc=bytearray(0x23);desc[0]=0x23;desc[1]=2;desc[2]=0xc4;desc[8]=0xf;desc[9]=2;desc[0x14]=16;uc.mem_write(buf,bytes(desc))
  elif a==0xa148:calls.append('close')
  elif a==0xa130:ret=0x200100
  elif a==0xa4c0:uc.mem_write(0x200200,b'modeled error\0');ret=0x200200
  elif a==0xa088:logs.append(cstr(uc.reg_read(UC_ARM64_REG_X2)))
  elif a==0xa430:logs.append(cstr(uc.reg_read(UC_ARM64_REG_X1)))
  elif 0xa020<=a<0xa748:raise AssertionError(('unexpected external call',hex(a)))
  else:return
  uc.reg_write(UC_ARM64_REG_X0,ret&0xffffffffffffffff);uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
 u.hook_add(UC_HOOK_CODE,hook);u.emu_start(0x7560,0,count=3000);assert u.reg_read(UC_ARM64_REG_PC)==0x10000,hex(u.reg_read(UC_ARM64_REG_PC));out=bytes(u.mem_read(0x201000,0x13));return {'count':out[0x11],'multiplier':out[0x10],'fallback':any('get dev wr sec count failed' in s for s in logs),'calls':calls,'logs':logs}
cases=[('new_geometry',0x69,16,{},16),('old_geometry_48',0x48,32,{},32),('old_geometry_57',0x57,4,{},4),('minimum_count',0x69,1,{},1),('maximum_count',0x69,64,{},64),('zero_count',0x69,0,{},64),('too_large_count',0x69,65,{},64),('max_byte_count',0x69,255,{},64),('unknown_short_length',0x68,16,{},64),('unknown_long_length',0x6a,16,{},64),('io_error',0x69,16,{'io_error':5},64),('query_status',0x69,16,{'status':6},64),('transport_info',0x69,16,{'info':1},64),('open_denied',0x69,16,{'open_error':True},64),('interrupted_query',0x69,16,{'retry':4},16),('would_block_query',0x69,16,{'retry':11},16)]
rows=[]
for name,length,count,kw,expected in cases:
 before=run(b,length,count,**kw);result=run(bytes(after),length,count,**kw)
 assert result['count']==expected,(name,result)
 if name in ['io_error','query_status','transport_info','open_denied','unknown_short_length','unknown_long_length']:assert result['fallback']
 if expected!=64 or name=='maximum_count':assert not result['fallback']
 if name=='new_geometry':assert before['count']==64 and before['fallback']
 rows.append({'case':name,'descriptor_length':length,'device_count':count,'before':before,'after':result})

report = {
    'original_sha256': tool['BEFORE'],
    'prepared_sha256': tool['AFTER'],
    'branch_cases': branch_rows,
    'capability_flow_cases': rows,
    'reproducible_and_idempotent': True,
    'unknown_inputs_rejected': 3,
    'elf_layout_and_dependencies_unchanged': True,
    'boundary': 'Actual ARM code with modeled read-descriptor I/O; no device access, key operation, image or package.'
}
if args.report:
    args.report.write_text(json.dumps(report, indent=2) + '\n')
print('PASS: 3584 length branches, 32 capability flows, pinned inputs and ELF layout')
