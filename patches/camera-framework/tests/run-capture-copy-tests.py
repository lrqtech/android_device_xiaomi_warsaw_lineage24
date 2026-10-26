#!/usr/bin/env python3
"""Host fault injection for production pre-identity metadata-copy blocks."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[6])
parser.add_argument('--baseline-dir', type=Path)
parser.add_argument('--report', required=True, type=Path)
args = parser.parse_args()
root = args.source
device = root / 'device/xiaomi/warsaw'
camera = root / 'frameworks/av/services/camera/libcameraservice'
paths = {'api2': camera / 'api2/CameraDeviceClient.cpp',
         'zsl': camera / 'api1/client2/ZslProcessor.cpp'}
texts = {name: (args.baseline_dir / (name + '.before-copy.cpp') if args.baseline_dir else path).read_text()
         for name, path in paths.items()}
api = texts['api2']
start = api.index('                physicalSettingsList.push_back({resolvedId, it.settings});')
end = api.index('            }\n        }\n\n        // Xiaomi preview plugins', start)
logical = api[start:end]
start = api.index('    CameraMetadata clientSessionParams(sessionParams);')
end = api.index('    err = mDevice->configureStreams(clientSessionParams, operatingMode);', start)
session_copy = api[start:end]
start = api.index('                if (!mSupportedPhysicalRequestKeys.empty()) {')
end = api.index('            } else {\n                physicalSettingsList.push_back({resolvedId, it.settings});', start)
physical_copy = api[start:end]
zsl = texts['zsl']
start = zsl.index('    CameraMetadata request = mFrameList[metadataIdx];')
end = zsl.index('    if (nullptr == mInputSurface.get()) {', start)
copy = zsl[start:end]
assert end < zsl.index('    res = enqueueInputBufferByTimestamp(candidateTimestamp,', end)
node = ast.parse((device / 'patches/camera-framework/tests/run-session-tests.py').read_text())
values = {n.targets[0].id: ast.literal_eval(n.value) for n in node.body
          if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name)
          and n.targets[0].id in ['stubs', 'tests']}
stubs = values['stubs'].replace('inline bool failAllocation=false, failClone=false, failUpdate=false;',
    '''inline bool failAllocation=false, failClone=false, failUpdate=false;
inline int failCloneAfter=-1;
inline bool cloneFails(){return failClone||(failCloneAfter>=0&&failCloneAfter--==0);}''').replace(
    '!failClone&&p.data?new camera_metadata_t(*p.data):nullptr',
    '!cloneFails()&&p.data?new camera_metadata_t(*p.data):nullptr')
stubs = stubs.replace('inline bool failAllocation=false,',
    'inline int failEntryAfter=-1; inline int entryError=-12; inline int metadataLocks=0; inline bool failAllocation=false,')
stubs = stubs.replace('inline void set_camera_metadata_vendor_id(camera_metadata_t* p,metadata_vendor_id_t id){p->id=id;}',
    'inline void set_camera_metadata_vendor_id(camera_metadata_t* p,metadata_vendor_id_t id){if(p)p->id=id;}')
stubs = stubs.replace('struct camera_metadata_t {',
    'struct camera_metadata_ro_entry {size_t count;uint32_t tag;std::string value;};\nstruct camera_metadata_t {')
stubs = stubs.replace('  CameraMetadata()=default;', r"""  CameraMetadata()=default;
  explicit CameraMetadata(size_t capacity):data(allocate_camera_metadata(capacity,0)){}
  const camera_metadata_t* getAndLock()const{++metadataLocks;return data.get();}
  void unlock(const camera_metadata_t*)const{--metadataLocks;}
  camera_metadata_ro_entry find(uint32_t tag)const{return {exists(tag)?1U:0U,tag,at(tag)};}
  int update(const camera_metadata_ro_entry& entry){
   if(failEntryAfter>=0&&failEntryAfter--==0)return entryError;
   return updateValue(entry.tag,entry.value);
  }
""")
setup = values['tests'].split('int main(')[0]
code = setup + r'''
#include <utils/List.h>
#include <vector>
#define ALOGE(...) ((void)0)
#define STATUS_ERROR_FMT(code, ...) (code)
#define STATUS_ERROR(code, ...) (code)
namespace CameraService { constexpr int ERROR_INVALID_OPERATION=-10; }
struct Setting {std::string id;CameraMetadata metadata;bool mode=false,data=false;};
struct Device {CameraMetadata metadata;const CameraMetadata& info() const{return metadata;} metadata_vendor_id_t getVendorTagId()const{return metadata.getVendorId();}};
struct Client {
 std::shared_ptr<Device> dev;
 auto getCameraDevice()const{return dev;}
 std::string getPackageName()const{return "com.android.camera";}
};
struct Harness {
 std::shared_ptr<Device> dev=std::make_shared<Device>(Device{device(Oem)});
 size_t resultCount=0;std::string control;int enqueueCalls=0,configureCalls=0;
 std::string getPackageName()const{return "com.android.camera";}
 int physicalCopy(const CameraMetadata& input,const std::vector<uint32_t>& mSupportedPhysicalRequestKeys){
  struct { const CameraMetadata& settings; } it{input};
  const std::string resolvedId="2";
  const bool hasTestPatternModePhysicalKey=true,hasTestPatternDataPhysicalKey=false;
  auto mDevice=dev;List<Setting> physicalSettingsList;
''' + physical_copy + r'''
  ++enqueueCalls;
  if(!physicalSettingsList.empty()){
   resultCount=physicalSettingsList.begin()->metadata.count();
   control=physicalSettingsList.begin()->metadata.at(7);
  }
  return OK;
 }
 int sessionCopy(const CameraMetadata& sessionParams){
  auto mDevice=dev;
''' + session_copy + r'''
  ++configureCalls;resultCount=clientSessionParams.count();control=clientSessionParams.at(7);return OK;
 }
 int logicalCopy(const CameraMetadata& input){
  struct { const CameraMetadata& settings; } it{input};
  const std::string resolvedId="0";
  List<Setting> physicalSettingsList;
''' + logical + r'''
  int result=warsawCameraSession::setClientIdentity(&(--physicalSettingsList.end())->metadata,
                                                  dev->metadata,"com.android.camera");
  if(result!=OK)return result;
  resultCount=(--physicalSettingsList.end())->metadata.count();
  control=(--physicalSettingsList.end())->metadata.at(7);
  return OK;
 }
 int zslCopy(const std::vector<CameraMetadata>& mFrameList){
  size_t metadataIdx=0;int res=OK;
  auto client=std::make_shared<Client>(Client{dev});
''' + copy + r'''
  ++enqueueCalls;resultCount=request.count();control=request.at(7);return OK;
 }
};
int main(){int count=0,failed=0;auto test=[&](const char*name,auto action){
 ++count;reset();failCloneAfter=-1;failEntryAfter=-1;entryError=NO_MEMORY;metadataLocks=0;
 try{action();std::cout<<"PASS "<<name<<"\n";}
 catch(const std::exception&e){++failed;std::cout<<"FAIL "<<name<<": "<<e.what()<<"\n";}};
 test("logical_copy_preserves_controls",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));expect(h.logicalCopy(p)==OK&&h.resultCount==2&&h.control=="crop","controls lost");});
 test("logical_first_clone_failure_stops_submission",[]{Harness h;CameraMetadata p;p.update(7,String8("exposure"));failCloneAfter=0;expect(h.logicalCopy(p)==-10,"failed clone became identity-only request");expect(p.count()==1&&p.at(7)=="exposure","caller changed");});
 test("logical_list_clone_failure_stops_submission",[]{Harness h;CameraMetadata p;p.update(7,String8("exposure"));failCloneAfter=1;expect(h.logicalCopy(p)==-10,"list copy loss became identity-only request");});
 test("logical_external_request_preserved",[]{Harness h;h.dev=std::make_shared<Device>(Device{device(External)});CameraMetadata p;p.update(7,String8("crop"));expect(h.logicalCopy(p)==OK&&h.resultCount==1&&h.control=="crop","external controls changed");});
 test("zsl_copy_preserves_controls",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));std::vector<CameraMetadata> frames{p};expect(h.zslCopy(frames)==OK&&h.enqueueCalls==1&&h.resultCount==2&&h.control=="crop","ZSL changed");});
 test("zsl_copy_failure_stops_before_enqueue",[]{Harness h;CameraMetadata p;p.update(7,String8("exposure"));std::vector<CameraMetadata> frames{p};failCloneAfter=0;expect(h.zslCopy(frames)==NO_MEMORY&&h.enqueueCalls==0,"buffer queued after lost frame metadata");expect(frames[0].at(7)=="exposure","original frame changed");});
 test("zsl_identity_clone_failure_stops_before_enqueue",[]{Harness h;CameraMetadata p;p.update(7,String8("exposure"));std::vector<CameraMetadata> frames{p};failCloneAfter=1;expect(h.zslCopy(frames)==NO_MEMORY&&h.enqueueCalls==0,"identity failure queued input");});
 test("zsl_foreign_metadata_stops_before_enqueue",[]{Harness h;auto p=device(External);p.update(7,String8("crop"));std::vector<CameraMetadata> frames{p};expect(h.zslCopy(frames)==BAD_VALUE&&h.enqueueCalls==0,"foreign frame queued");});

 test("session_copy_preserves_controls",[]{Harness h;CameraMetadata p;p.update(7,String8("beauty-session"));expect(h.sessionCopy(p)==OK&&h.configureCalls==1&&h.resultCount==2&&h.control=="beauty-session","session controls lost");expect(p.count()==1,"caller session changed");});
 test("session_copy_failure_stops_before_configuration",[]{Harness h;CameraMetadata p;p.update(7,String8("beauty-session"));failCloneAfter=0;expect(h.sessionCopy(p)==-10&&h.configureCalls==0,"lost session became identity-only configuration");expect(p.count()==1&&p.at(7)=="beauty-session","caller changed");});
 test("session_identity_copy_failure_stops_before_configuration",[]{Harness h;CameraMetadata p;p.update(7,String8("control"));failCloneAfter=1;expect(h.sessionCopy(p)==-10&&h.configureCalls==0,"identity copy failure configured device");});
 test("genuinely_empty_session_remains_supported",[]{Harness h;CameraMetadata p;expect(h.sessionCopy(p)==OK&&h.configureCalls==1&&h.resultCount==1,"empty session blocked");});
 test("external_session_controls_preserved",[]{Harness h;h.dev=std::make_shared<Device>(Device{device(External)});CameraMetadata p;p.update(7,String8("control"));expect(h.sessionCopy(p)==OK&&h.configureCalls==1&&h.resultCount==1&&h.control=="control","external controls changed");});
 test("external_session_copy_failure_is_not_silent",[]{Harness h;h.dev=std::make_shared<Device>(Device{device(External)});CameraMetadata p;p.update(7,String8("control"));failCloneAfter=0;expect(h.sessionCopy(p)==-10&&h.configureCalls==0,"external clone loss silently configured device");});

 test("physical_filter_keeps_supported_controls",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));p.update(8,String8("awb"));p.update(9,String8("unsupported"));expect(h.physicalCopy(p,{7,8})==OK&&h.enqueueCalls==1&&h.resultCount==2&&h.control=="crop"&&metadataLocks==0,"physical keys changed");expect(p.count()==3,"original request changed");});
 test("physical_empty_supported_values_remain_valid",[]{Harness h;CameraMetadata p;p.update(9,String8("unadvertised"));expect(h.physicalCopy(p,{7,8})==OK&&h.enqueueCalls==1&&h.resultCount==0&&metadataLocks==0,"legitimate empty filtered settings blocked");});
 test("physical_no_advertised_keys_remains_supported",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));expect(h.physicalCopy(p,{})==OK&&h.enqueueCalls==1&&h.resultCount==0,"absent advertised keys changed");});
 test("physical_allocation_failure_stops_submission",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));failAllocation=true;expect(h.physicalCopy(p,{7})==-10&&h.enqueueCalls==0&&metadataLocks==0,"missing buffer continued filtering");});
 test("physical_first_update_failure_stops_submission",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));p.update(8,String8("awb"));failEntryAfter=0;expect(h.physicalCopy(p,{7,8})==-10&&h.enqueueCalls==0,"lost crop was submitted");expect(p.count()==2&&p.at(7)=="crop","caller changed");});
 test("physical_second_update_failure_stops_submission",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));p.update(8,String8("awb"));failEntryAfter=1;expect(h.physicalCopy(p,{7,8})==-10&&h.enqueueCalls==0,"partial physical controls submitted");});
 test("physical_invalid_entry_stops_submission",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));failEntryAfter=0;entryError=BAD_VALUE;expect(h.physicalCopy(p,{7})==-10&&h.enqueueCalls==0,"invalid entry ignored");});
 test("physical_temporary_clone_failure_stops_submission",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));failCloneAfter=0;expect(h.physicalCopy(p,{7})==-10&&h.enqueueCalls==0,"temporary clone lost physical controls");});
 test("physical_list_clone_failure_stops_submission",[]{Harness h;CameraMetadata p;p.update(7,String8("crop"));failCloneAfter=1;expect(h.physicalCopy(p,{7})==-10&&h.enqueueCalls==0,"list clone lost physical controls");});
 std::cout<<"CASES "<<count<<" FAILURES "<<failed<<"\n";return failed?1:0;
}
'''
with tempfile.TemporaryDirectory(prefix='warsaw-copy-host-') as directory:
    tmp = Path(directory)
    (tmp / 'test_stubs.h').write_text(stubs)
    for name in ['android-base/properties.h','camera/CameraMetadata.h',
                 'camera/VendorTagDescriptor.h','camera_metadata_hidden.h','utils/String8.h']:
        path = tmp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#include "test_stubs.h"\n')
    (tmp / 'production.h').write_bytes((camera / 'utils/WarsawCameraSessionParams.h').read_bytes())
    (tmp / 'tests.cpp').write_text(code)
    subprocess.run(['g++','-std=c++20','-O1','-Wall','-Wextra','-Werror',
                    '-Wno-misleading-indentation','-I'+str(tmp),
                    '-I'+str(root/'system/core/libutils/include'),str(tmp/'tests.cpp'),
                    '-o',str(tmp/'tests')], check=True)
    run = subprocess.run([str(tmp/'tests')], text=True, capture_output=True)
    print(run.stdout, end='')
    assert not run.stderr, run.stderr
    count, failed = map(int, re.search(r'CASES (\d+) FAILURES (\d+)',run.stdout).groups())
    args.report.write_text(json.dumps({'cases':count,'failures':failed,
        'source_sha256':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in texts.items()},
        'production_blocks_extracted': True,'host_api_doubles': True,
        'production_container_header':str(root/'system/core/libutils/include/utils/List.h'),
        'android_build':False,'device_tested':False,'output':run.stdout},indent=2)+'\n')
    raise SystemExit(run.returncode)
