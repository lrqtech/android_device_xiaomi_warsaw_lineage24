#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import sys,io,json,hashlib,struct,argparse,importlib.util
parser=argparse.ArgumentParser(description="Execute CHI camera-ID conversion with synthetic tables and an index-lookup double")
parser.add_argument('--baseline-dir',type=Path,required=True)
parser.add_argument('--prepared-dir',type=Path,required=True)
parser.add_argument('--python-path',type=Path)
parser.add_argument('--report',type=Path,required=True)
args=parser.parse_args()
if args.python_path:sys.path.insert(0,str(args.python_path))
spec=importlib.util.spec_from_file_location('chi_camera_id',Path(__file__).resolve().parents[1]/'prepare-chi-camera-id.py')
repair=importlib.util.module_from_spec(spec);spec.loader.exec_module(repair)
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE,UC_HOOK_MEM_INVALID,UcError
from unicorn.arm64_const import *

MAX=0xffffffffffffffff;START=0x45ea10;END=0x4000000;OBJ=0x2000000;ARR=0x3000000;NODE=0x3100000
cases=[dict(name='mode0_logical_index',mode=0,index=2,entries=[],primary=0,want=0xffffffff00000002),dict(name='mode2_framework_mapping',mode=2,index=1,entries=[],primary=0,want=0xffffffff00000003),dict(name='unknown_mode',mode=3,index=0,entries=[],primary=0,want=MAX),dict(name='invalid_index',mode=1,index=-1,entries=[],primary=0,want=MAX),dict(name='first_physical_match',mode=1,index=0,entries=[(2,3),(0,2)],primary=2,want=0x200000003),dict(name='second_physical_match',mode=1,index=0,entries=[(2,3),(0,2)],primary=0,want=2),dict(name='second_logical_entry',mode=1,index=1,entries=[(8,9)],primary=8,want=0x800000009),dict(name='empty_physical_table',mode=1,index=0,entries=[],primary=0,want=MAX),dict(name='missing_physical_id',mode=1,index=0,entries=[(2,3),(0,2)],primary=15,want=MAX),dict(name='single_entry_missing_id',mode=1,index=0,entries=[(2,3)],primary=0,want=MAX),dict(name='unknown_mode_negative',mode=0xffffffff,index=0,entries=[],primary=0,want=MAX),dict(name='large_physical_id',mode=1,index=0,entries=[(0xfffffffe,3)],primary=0xfffffffe,want=0xfffffffe00000003)]
def run(blob,c):
 u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);elf=ELFFile(io.BytesIO(blob));segments=[s for s in elf.iter_segments() if s['p_type']=='PT_LOAD'];u.mem_map(0,((max(s['p_vaddr']+s['p_memsz'] for s in segments)+4095)//4096)*4096)
 for s in segments:u.mem_write(s['p_vaddr'],s.data())
 for ptr,size in [(0x1000000,0x10000),(OBJ,0x10000),(ARR,0x10000),(NODE,0x10000),(END,0x1000)]:u.mem_map(ptr,size)
 index=max(0,c['index']);off=index*0x1c0
 u.mem_write(OBJ+off+0x41b8,struct.pack('<I',len(c['entries'])));u.mem_write(OBJ+off+0x41c0,struct.pack('<Q',ARR));u.mem_write(OBJ+off+0x41d8,struct.pack('<I',c['primary']));u.mem_write(OBJ+0x5c90+index*4,struct.pack('<I',3))
 for i,(physical,framework) in enumerate(c['entries']):u.mem_write(ARR+i*8,struct.pack('<Q',NODE+i*0x100));u.mem_write(NODE+i*0x100+0x20,struct.pack('<II',physical,framework))
 invalid=[]
 def code(mu,address,size,data):
  if address==0x6ccec8:mu.reg_write(UC_ARM64_REG_W0,c['index']&0xffffffff);mu.reg_write(UC_ARM64_REG_PC,mu.reg_read(UC_ARM64_REG_LR))
 def fault(mu,access,address,size,value,data):invalid.append(hex(address));return False
 u.hook_add(UC_HOOK_CODE,code);u.hook_add(UC_HOOK_MEM_INVALID,fault);u.reg_write(UC_ARM64_REG_X0,OBJ);u.reg_write(UC_ARM64_REG_W1,15);u.reg_write(UC_ARM64_REG_W2,c['mode']);u.reg_write(UC_ARM64_REG_SP,0x1008000);u.reg_write(UC_ARM64_REG_LR,END)
 try:u.emu_start(START,END,count=2000);value=u.reg_read(UC_ARM64_REG_X0);return dict(pass_case=value==c['want'] and u.reg_read(UC_ARM64_REG_PC)==END,value=hex(value),invalid_reads=invalid)
 except UcError as err:return dict(pass_case=False,error=str(err),invalid_reads=invalid)
out=[]
for name in ['libchifeature2.so','hw/com.qti.chi.override.so']:
 p=args.prepared_dir/name;baseline=args.baseline_dir/(Path(name).name+'.before-remap');b=baseline.read_bytes();new=repair.prepare(b)
 assert p.read_bytes()==new,'Prepared input differs from the reproducible repair'
 assert repair.prepare(new)==new,'Repair is not idempotent'
 changed={i for i,(x,y) in enumerate(zip(b,new)) if x!=y}
 assert len(b)==len(new) and changed<=set(range(0x308,0x318))|set(range(0x45ead4,0x45ead8))
 for bad in [b[:-1],bytes([b[0]^1])+b[1:],b[:0x45ead4]+bytes(4)+b[0x45ead8:]]:
  try:repair.prepare(bad)
  except ValueError:pass
  else:raise AssertionError('Unsupported input accepted')
 rows=[]
 for c in cases:rows.append({'case':c['name'],'before':run(b,c),'after':run(new,c)})
 assert all(x['after']['pass_case'] for x in rows),rows
 assert sum(not x['before']['pass_case'] for x in rows)==3,rows
 out.append({'path':str(p),'before_sha256':hashlib.sha256(b).hexdigest(),'after_sha256':hashlib.sha256(new).hexdigest(),'cases':rows,'changed_offsets':[hex(i) for i in sorted(changed)],'unsupported_inputs_rejected':3,'index_lookup_modelled':True,'physical_tables_synthetic':True,'phone_validation':False,'package_created':False})
 print(Path(name).name+': 12 ARM cases pass; baseline has three invalid reads; three unsupported inputs rejected')
args.report.write_text(json.dumps(out,indent=2)+'\n')
