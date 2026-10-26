#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import argparse,ast,json,hashlib,re,subprocess,tempfile
parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);parser.add_argument('--baseline-dir',type=Path);parser.add_argument('--report-dir',type=Path,required=True);a=parser.parse_args();r=a.source;b=r/'frameworks/av/services/camera/libcameraservice';d=r/'device/xiaomi/warsaw'
texts={}
for name in ['StreamingProcessor','CaptureSequencer','ZslProcessor','Camera2Client']:
 suffix='before-recording-error' if name=='Camera2Client' else 'before-request-identity'
 p=a.baseline_dir/f'{name}.{suffix}.cpp' if a.baseline_dir else b/('api1/' if name=='Camera2Client' else 'api1/client2/')/(name+'.cpp');texts[name]=p.read_text()
def block(kind):
 s=texts['StreamingProcessor' if kind in ['Preview','Recording'] else ('CaptureSequencer' if kind=='Capture' else 'ZslProcessor')]
 if kind in ['Preview','Recording']:
  start=s.index('status_t StreamingProcessor::update'+kind+'Request(');end=s.index(f'    res = m{kind}Request.update(ANDROID_REQUEST_ID,',start);segment=s[start:end];m=segment.find('    if (warsawCameraSession::needsClientIdentity())');return segment[m:] if m>=0 else ''
 end=s.index('    res = params.updateRequestJpeg(&mCaptureRequest);') if kind=='Capture' else s.index('    if (nullptr == mInputSurface.get()) {',s.index('status_t ZslProcessor::pushToReprocess'))
 start=s.rfind('    res = warsawCameraSession::setClientIdentity(',0,end)
 return s[start:end] if start>=0 else ''
source=texts['Camera2Client'];start=source.index('    if (!restart) {',source.index('status_t Camera2Client::startRecordingL('));end=source.index('    // Not all devices',start);recording=source[start:end]
node=ast.parse((d/'patches/camera-framework/tests/run-session-tests.py').read_text());v={n.targets[0].id:ast.literal_eval(n.value) for n in node.body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['stubs','tests']}
setup=v['tests'].split('int main(')[0];tests=r'''
#include <vector>
#define ALOGE(...) ((void)0)
constexpr int INVALID_OPERATION=-38;
struct CameraDevice {CameraMetadata metadata;const CameraMetadata& info()const{return metadata;}};
struct Camera2Client {std::shared_ptr<CameraDevice> device;std::string package="com.android.camera";std::string getPackageName()const{return package;}int getCameraId()const{return 0;}auto getCameraDevice()const{return device;}};
struct WeakClient {std::shared_ptr<Camera2Client> client;auto promote()const{return client;}};
struct Harness {
 std::shared_ptr<CameraDevice> mDevice;WeakClient mClient;int mId=0;
 explicit Harness(metadata_vendor_id_t id=Oem):mDevice(std::make_shared<CameraDevice>(CameraDevice{device(id)})),mClient{std::make_shared<Camera2Client>(Camera2Client{mDevice})}{}
'''
for kind in ['Preview','Recording','Capture','Zsl']:
 tests+=f' int {kind}(CameraMetadata& metadata){{\n status_t res=OK;auto device=mDevice;auto client=mClient.promote();CameraMetadata& '+('request' if kind=='Zsl' else 'm'+kind+'Request')+'=metadata;\n'+block(kind)+'\nreturn OK;\n}\n'
tests+=r'''
};
namespace CameraService {constexpr int SOUND_RECORDING_START=1;}
struct Service {void playSound(int){}};
struct Processor {int result;int calls=0;int updateRecordingRequest(int){++calls;return result;}};
struct RecordingClient {Service service;Service* sCameraService=&service;Processor processor;Processor* mStreamingProcessor=&processor;int mCameraId=0;int continued=0;int start(bool restart){int params=0;int res=OK;
'''+recording+r'''
 ++continued;return OK;}};
int main(){int count=0,failed=0;auto test=[&](std::string name,auto f){++count;reset();try{f();std::cout<<"PASS "<<name<<"\n";}catch(const std::exception& e){++failed;std::cout<<"FAIL "<<name<<": "<<e.what()<<"\n";}};
 using Method=int(Harness::*)(CameraMetadata&);
 std::vector<std::pair<std::string,Method>> methods={{"preview",&Harness::Preview},{"recording",&Harness::Recording},{"still",&Harness::Capture},{"zsl",&Harness::Zsl}};
 for(auto [name,method]:methods){
  test(name+"_identity",[&]{Harness h;CameraMetadata p;p.update(7,String8("controls"));expect((h.*method)(p)==OK,"rejected");identity(p);expect(p.at(7)=="controls","controls lost");});
  test(name+"_spoofed_identity",[&]{Harness h;auto p=device(Oem);p.update(Tag,String8("spoof"));expect((h.*method)(p)==OK,"rejected");identity(p);});
  test(name+"_foreign_provider",[&]{Harness h;auto p=device(External);expect((h.*method)(p)==BAD_VALUE,"foreign metadata accepted");expect(p.getVendorId()==External,"failure modified metadata");});
  test(name+"_wrong_tag_type",[&]{Harness h;VendorTagDescriptorCache::global->descriptors[Oem]=desc(TYPE_INT32);CameraMetadata p;expect((h.*method)(p)==BAD_TYPE,"bad type accepted");expect(p.count()==0,"failure modified metadata");});
  test(name+"_missing_descriptor",[&]{Harness h;VendorTagDescriptorCache::global=nullptr;CameraMetadata p;expect((h.*method)(p)==NAME_NOT_FOUND,"missing table accepted");});
  test(name+"_external_provider",[&]{Harness h(External);CameraMetadata p;p.update(7,String8("controls"));expect((h.*method)(p)==OK,"external blocked");expect(p.count()==1&&p.at(7)=="controls","external metadata changed");});
  test(name+"_other_device",[&]{base::device="other";Harness h;CameraMetadata p;expect((h.*method)(p)==OK,"other device blocked");expect(p.count()==0,"other device changed");});
 }
 for(auto method:{&Harness::Preview,&Harness::Recording})test("expired_streaming_client",[&]{Harness h;h.mClient.client=nullptr;CameraMetadata p;expect((h.*method)(p)==INVALID_OPERATION,"expired client accepted");});
 for(int error:{-12,-22,-19,-100})test("recording_error_"+std::to_string(error),[&]{RecordingClient h;h.processor.result=error;expect(h.start(false)==error,"request preparation error lost");expect(h.continued==0,"continued after failure");});
 test("recording_success",[]{RecordingClient h;h.processor.result=0;expect(h.start(false)==OK&&h.processor.calls==1&&h.continued==1,"success path changed");});
 test("recording_restart",[]{RecordingClient h;h.processor.result=-12;expect(h.start(true)==OK&&h.processor.calls==0&&h.continued==1,"restart recreates request");});
 std::cout<<"CASES "<<count<<" FAILURES "<<failed<<"\n";return failed?1:0;}
'''
with tempfile.TemporaryDirectory(prefix='warsaw-legacy-request-host-') as tmp:
 t=Path(tmp);(t/'test_stubs.h').write_text(v['stubs'])
 for name in ['android-base/properties.h','camera/CameraMetadata.h','camera/VendorTagDescriptor.h','camera_metadata_hidden.h','utils/String8.h']:
  p=t/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('#include "test_stubs.h"\n')
 (t/'production.h').write_text((b/'utils/WarsawCameraSessionParams.h').read_text());(t/'tests.cpp').write_text(setup+tests)
 subprocess.run(['g++','-std=c++20','-O1','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-Wno-unused-variable','-Wno-unused-parameter','-I'+str(t),str(t/'tests.cpp'),'-o',str(t/'tests')],check=True)
 result=subprocess.run([str(t/'tests')],text=True,capture_output=True);print(result.stdout,end='');assert not result.stderr,result.stderr
 a.report_dir.mkdir(parents=True,exist_ok=True);label='before' if a.baseline_dir else 'after';(a.report_dir/f'camera-legacy-request-{label}.log').write_text(result.stdout);count,failed=map(int,re.search(r'CASES (\d+) FAILURES (\d+)',result.stdout).groups());(a.report_dir/f'camera-legacy-request-{label}.json').write_text(json.dumps({'cases':count,'failed':failed,'source_sha256':{n:hashlib.sha256(s.encode()).hexdigest() for n,s in texts.items()},'actual_production_blocks':True,'api_doubles':True,'android_compile':False,'device_validation':False,'packaging':False},indent=2)+'\n')
 if not a.baseline_dir:assert result.returncode==0
