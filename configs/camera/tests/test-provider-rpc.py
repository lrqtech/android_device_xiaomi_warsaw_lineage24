#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the actual provider RPC hooks with modeled external services.

Requires pyelftools and Unicorn. Does not build or access a device.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import runpy
import struct
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', required=True, type=Path)
parser.add_argument('--provider', required=True, type=Path)
parser.add_argument('--rpc-library', required=True, type=Path)
parser.add_argument('--report', type=Path)
parser.add_argument('--python-path', type=Path)
args = parser.parse_args()
if args.python_path:
    sys.path.insert(0, str(args.python_path))

from elftools.elf.elffile import ELFFile
from unicorn import Uc,UcError,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE,UC_HOOK_MEM_READ
from unicorn.arm64_const import *

class Machine:
 def __init__(self,b):
  self.u=u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0x1000,0x1f000)
  for s in E.iter_segments():
   if s['p_type']=='PT_LOAD':
    skip=max(0,0x1000-s['p_vaddr']);u.mem_write(s['p_vaddr']+skip,b[s['p_offset']+skip:s['p_offset']+s['p_filesz']])
  u.mem_map(0x100000,0x40000);u.reg_write(UC_ARM64_REG_TPIDR_EL0,0x100000);u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20)
  self.put64(0x18328,0x101000);self.put64(0x182e8,0x101100);self.put64(0x182a8,0x101200);self.put64(0x185a8,0x185b0)
  self.alloc=0x110000;self.result=0;self.handle=0xb40000714c291310;self.invoke_result=0;self.original=[];self.logs=[];self.watches=0;self.reads=0;self.ssr=[];self.lock_depth=0
  u.hook_add(UC_HOOK_CODE,self.hook);u.hook_add(UC_HOOK_MEM_READ,self.read)
 def put64(self,a,v):self.u.mem_write(a,struct.pack('<Q',v))
 def get64(self,a):return struct.unpack('<Q',self.u.mem_read(a,8))[0]
 def x(self,r):return self.u.reg_read(UC_ARM64_REG_X0+r)
 def ret(self,v=0):self.u.reg_write(UC_ARM64_REG_X0,v&((1<<64)-1));self.u.reg_write(UC_ARM64_REG_PC,self.u.reg_read(UC_ARM64_REG_LR))
 def string(self,a):
  out=b''
  for i in range(400):
   v=bytes(self.u.mem_read(a+i,1))
   if v==b'\0':return out.decode()
   out+=v
  raise AssertionError('unterminated string')
 def read(self,u,access,a,size,value,data):
  if a==0x103000:self.reads+=1
 def hook(self,u,a,size,data):
  if a==0x101000:
   self.original.append(('open',self.x(0),self.x(1)))
   result=self.result if self.x(0) and self.x(1) else 44
   if result==0:self.put64(self.x(1),self.handle)
   self.ret(result)
  elif a==0x101100:self.original.append(('invoke',self.x(0),self.x(1),self.x(2)));self.ret(self.invoke_result)
  elif a==0x101200:self.original.append(('close',self.x(0)));self.ret(0)
  elif a==0x98dc:self.ret()
  elif a==0x108e8:self.logs.append(self.string(self.x(2)));self.ret()
  elif a==0x10858:
   out=self.alloc;self.alloc+=0x100;self.ret(out)
  elif a==0x108d0:self.ret()
  elif a==0x10ab0:self.lock_depth+=1;self.ret()
  elif a==0x10ae0:self.lock_depth-=1;assert self.lock_depth>=0;self.ret()
  elif a in (0x10c90,0x10ca8):self.ret(123)
  elif a in (0x10cc0,0x10cd8):self.ret()
  elif a==0x10d08:self.ret(0)
  elif a==0x10d20:self.ssr.append(self.x(1));self.ret()
  elif a==0x10790:self.ret()
  elif a==0xd1f0:self.ret(0x104000)
  elif a==0xd86c:self.watches+=1;self.ret()
  elif a==0xdb80:self.ret()
  elif a==0x10cf0:self.ret()
  elif a in (0x10a98,0xeec8,0xefa0):raise AssertionError('guard failure '+hex(a))
 def call(self,a,args):
  self.u.reg_write(UC_ARM64_REG_SP,0x13f000);self.u.reg_write(UC_ARM64_REG_LR,0x102000)
  for i,v in enumerate(args):self.u.reg_write(UC_ARM64_REG_X0+i,v)
  self.u.emu_start(a,0x102000,count=100000)
  assert self.lock_depth==0
  return self.u.reg_read(UC_ARM64_REG_W0)
 def open(self,name,status=0,null_output=False,stale=0):
  self.result=status;self.put64(0x103000,stale);self.u.mem_write(0x102100,(name or '').encode()+b'\0')
  return self.call(0xbc48,[0x102100 if name is not None else 0,0 if null_output else 0x103000])
 def invoke(self,h=None,result=0):
  self.invoke_result=result;return self.call(0xb7d8,[self.handle if h is None else h,0x3010100,0x104100])
 def nodes(self):
  out={}
  def walk(a):
   if not a:return
   walk(self.get64(a));out[hex(self.get64(a+0x20))]=struct.unpack('<I',self.u.mem_read(a+0x28,4))[0];walk(self.get64(a+8))
  walk(self.get64(0x185b0));return out

def run(b):
 tests=[]
 for dom,num in [('adsp',0),('mdsp',1),('sdsp',2),('cdsp',3)]:
  for session in [0,1]:
   m=Machine(b);uri=f'file:///unit_skel.so?invoke&_modver=1.0&_dom={dom}&_session={session}';rv=m.open(uri);nv=m.invoke(result=77);nodes=m.nodes()
   tests.append({'case':f'{dom}_session{session}','return':rv,'invoke_return':nv,'nodes':nodes,'missing_domain':any('no find domain' in x for x in m.logs),'watchdogs':m.watches,'pass':rv==0 and nv==77 and nodes.get(hex(m.handle))==num+session*4 and not any('no find domain' in x for x in m.logs) and m.watches==int(dom=='cdsp')})
 for status in [1,14,44,104,0xffffffff]:
  m=Machine(b);rv=m.open('file:///missing.so?invoke&_dom=cdsp',status,stale=0xb400000012345600)
  tests.append({'case':f'failed_open_{status}','return':rv,'nodes':m.nodes(),'output_reads':m.reads,'pass':rv==status and not m.nodes() and m.reads==0})
 for name,nulloutput in [('file:///a.so?invoke&_dom=cdsp',True),(None,False),(None,True)]:
  m=Machine(b)
  try:rv=m.open(name,null_output=nulloutput);ok=rv==44 and not m.nodes();error=None
  except UcError as ex:rv=None;ok=False;error=str(ex)
  tests.append({'case':f'null_name_{name is None}_output_{nulloutput}','return':rv,'error':error,'pass':ok})
 for initial_domain in ['adsp','cdsp']:
  m=Machine(b);m.open(f'file:///valid.so?invoke&_dom={initial_domain}');before=m.nodes();rv=m.open('file:///missing.so?invoke&_dom=sdsp',14,stale=m.handle);m.invoke(result=104)
  tests.append({'case':f'failed_open_preserves_{initial_domain}_mapping','return':rv,'before':before,'after':m.nodes(),'invoke_return':m.u.reg_read(UC_ARM64_REG_W0),'pass':rv==14 and m.nodes()==before and m.watches==int(initial_domain=='cdsp')})
 for domain,num in [('adsp',0),('cdsp',3)]:
  m=Machine(b);m.open(f'file:///valid.so?invoke&_dom={domain}');m.invoke();close=m.call(0xb4f8,[m.handle]);empty=m.nodes();m.handle+=0x100;m.open(f'file:///valid.so?invoke&_dom={domain}');result=m.invoke(result=0xffffffff)
  tests.append({'case':f'{domain}_close_and_reopen','nodes':m.nodes(),'closed_nodes':empty,'pass':close==0 and not empty and result==0xffffffff and m.nodes().get(hex(m.handle))==num and not any('no find domain' in x for x in m.logs)})
 m=Machine(b);result=m.invoke(result=44)
 tests.append({'case':'unknown_handle_keeps_error','pass':result==44 and any('no find domain' in x for x in m.logs) and not m.nodes() and m.watches==0})
 # Force a stale output equal to a live handle, then retry successfully.
 m=Machine(b);m.open('file:///first.so?invoke&_dom=cdsp');first=m.handle;m.open('file:///retry.so?invoke&_dom=sdsp',14,stale=first);m.handle+=0x200;m.open('file:///retry.so?invoke&_dom=sdsp');nodes=m.nodes()
 tests.append({'case':'retry_keeps_other_live_handle','nodes':nodes,'pass':nodes=={hex(first):3,hex(m.handle):2}})
 return tests

tool = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'prepare-provider-rpc.py'))
B = args.baseline.read_bytes()
E = ELFFile(io.BytesIO(B))
rpc = args.rpc_library.read_bytes()
current = args.provider.read_bytes()
assert hashlib.sha256(B).hexdigest() == tool['BEFORE']
assert tool['patched'](B, rpc) == current
assert tool['patched'](current, rpc) == current
assert len(B) == len(current)
allowed = set(range(0xbce0, 0xbce4)) | set(range(0xbd80, 0xbd84)) | set(range(0x390, 0x3a0))
changed = {i for i, (old, new) in enumerate(zip(B, current)) if old != new}
assert changed <= allowed
# ELF program/section headers, dynamic symbols, relocations, and dependencies
# are byte-identical; only the two instructions and build-id note may differ.
rejected = 0
for data, library in [(b'', rpc), (B[:-1], rpc), (B[:400] + b'X' + B[401:], rpc),
                      (B, b''), (current, rpc[:-1])]:
    try:
        tool['patched'](data, library)
    except ValueError:
        rejected += 1
    else:
        raise AssertionError('Unsupported input accepted')
before = run(B)
after = run(current)
assert all(case['pass'] for case in after)
assert len([case for case in before if not case['pass']]) == 11
result = {'before_sha256': hashlib.sha256(B).hexdigest(),
          'after_sha256': hashlib.sha256(current).hexdigest(),
          'baseline_failures': [case['case'] for case in before if not case['pass']],
          'cases': after, 'count': len(after), 'unsupported_inputs_rejected': rejected,
          'changed_offsets': [hex(i) for i in sorted(changed)],
          'actual_arm_instructions': True, 'external_calls_modeled': True,
          'device_tested': False, 'build_started': False, 'packaging_started': False}
if args.report:
    args.report.write_text(json.dumps(result, indent=2) + '\n')
print(f"PASS: {len(after)} ARM cases; 11 baseline failures; {rejected} unsupported inputs rejected")
