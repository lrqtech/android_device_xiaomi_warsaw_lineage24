#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import argparse, ast, hashlib, json, re, subprocess, tempfile
parser=argparse.ArgumentParser()
parser.add_argument('--source',type=Path,required=True)
parser.add_argument('--baseline',type=Path)
parser.add_argument('--report-dir',type=Path,required=True)
a=parser.parse_args();r=a.source;d=r/'device/xiaomi/warsaw';header=r/'frameworks/av/services/camera/libcameraservice/utils/WarsawCameraSessionParams.h';cpp=r/'frameworks/av/services/camera/libcameraservice/api2/CameraDeviceClient.cpp'
source=(a.baseline or cpp).read_text()
marker='        // Xiaomi preview plugins consume clientName from per-frame metadata.'
assert source.count(marker)==1
start=source.index(marker);end=source.index('        if (!enforceRequestPermissions(',start);block=source[start:end]
assert source.index('metadataRequestList.push_back(physicalSettingsList)',end)<source.index('setStreamingRequestList',end)
assert source.index('setSharedStreamingRequest',end)>end
host=ast.parse((d/'patches/camera-framework/tests/run-session-tests.py').read_text())
values={n.targets[0].id:ast.literal_eval(n.value) for n in host.body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['stubs','tests']}
setup=values['tests'].split('int main(')[0]
tests=r'''
#include <vector>
struct Setting {std::string id;CameraMetadata metadata;};
struct Device {CameraMetadata metadata;const CameraMetadata& info()const{return metadata;}};
namespace CameraService {constexpr int ERROR_INVALID_OPERATION=-10;}
#define STATUS_ERROR_FMT(code, ...) (code)
struct Client {
 std::shared_ptr<Device> mDevice;
 std::string package="com.android.camera";
 std::string getPackageName()const{return package;}
 int prepare(std::vector<Setting>& physicalSettingsList){
''' + block + r'''
 return OK;
 }
};
Client client(metadata_vendor_id_t id=Oem){return {std::make_shared<Device>(Device{device(id)})};}
std::vector<Setting> request(){CameraMetadata p;p.update(7,String8("preview"));return {{"0",p}};}
int main(){int failed=0,count=0;auto test=[&](const char* name,auto f){++count;reset();try{f();std::cout<<"PASS "<<name<<"\n";}catch(const std::exception& e){++failed;std::cout<<"FAIL "<<name<<": "<<e.what()<<"\n";}};
 test("request_identity_missing_in_session_only_path",[]{auto c=client();auto p=request();expect(c.prepare(p)==OK,"request rejected");identity(p[0].metadata);expect(p[0].metadata.at(7)=="preview","controls changed");});
 test("request_overrides_untrusted_package",[]{auto c=client();auto p=request();p[0].metadata=device(Oem);p[0].metadata.update(Tag,String8("spoof"));expect(c.prepare(p)==OK,"request rejected");identity(p[0].metadata);});
 test("application_metadata_unchanged",[]{auto c=client();auto original=request();auto copied=original;expect(c.prepare(copied)==OK,"request rejected");identity(copied[0].metadata);expect(original[0].metadata.getVendorId()==CAMERA_METADATA_INVALID_VENDOR_ID&&!original[0].metadata.exists(Tag),"application metadata mutated");});
 test("physical_metadata_not_stamped",[]{auto c=client();auto p=request();p.push_back({"2",device(Oem)});p[1].metadata.update(8,String8("physical-crop"));expect(c.prepare(p)==OK,"request rejected");identity(p[0].metadata);expect(p[1].metadata.count()==1&&p[1].metadata.at(8)=="physical-crop","unsupported physical key inserted");});
 test("burst_each_request_has_identity",[]{auto c=client();for(int i=0;i<5;++i){auto p=request();p[0].metadata.update(9,String8(std::to_string(i).c_str()));expect(c.prepare(p)==OK,"burst rejected");identity(p[0].metadata);expect(p[0].metadata.at(9)==std::to_string(i),"request controls lost");}});
 test("authenticated_client_changes_between_sessions",[]{auto c=client();c.package="org.lineageos.aperture";auto p=request();expect(c.prepare(p)==OK,"request rejected");expect(p[0].metadata.at(Tag)==c.package,"another client identity reused");});
 test("external_request_preserved",[]{auto c=client(External);auto p=request();expect(c.prepare(p)==OK,"external rejected");expect(p[0].metadata.count()==1&&!p[0].metadata.exists(Tag),"external metadata changed");});
 test("foreign_vendor_request_rejected",[]{auto c=client();std::vector<Setting> p={{"0",device(External)}};expect(c.prepare(p)==-10,"foreign request submitted");expect(p[0].metadata.getVendorId()==External,"rejected request mutated");});
 test("unknown_provider_request_rejected",[]{auto c=client(19);auto p=request();expect(c.prepare(p)==-10,"unknown provider submitted");expect(p[0].metadata.count()==1,"error mutated request");});
 test("wrong_vendor_tag_type_request_rejected",[]{auto c=client();VendorTagDescriptorCache::global->descriptors[Oem]=desc(TYPE_INT32);auto p=request();expect(c.prepare(p)==-10,"wrong type submitted");expect(p[0].metadata.count()==1,"error mutated request");});
 test("unavailable_descriptor_request_rejected",[]{auto c=client();VendorTagDescriptorCache::global=nullptr;auto p=request();expect(c.prepare(p)==-10,"unvalidated request submitted");expect(p[0].metadata.count()==1,"error mutated request");});
 test("other_device_request_preserved",[]{base::device="other";auto c=client();auto p=request();expect(c.prepare(p)==OK,"other device rejected");expect(p[0].metadata.count()==1,"other device changed");});
 std::cout<<"CASES "<<count<<" FAILURES "<<failed<<"\n";return failed?1:0;}
'''
# Metadata assignment is needed by the harness only. Keep the production header intact.
stubs=values['stubs'].replace('  metadata_vendor_id_t getVendorId()const', '  CameraMetadata& operator=(const CameraMetadata& p){if(this!=&p)data.reset(p.data?new camera_metadata_t(*p.data):nullptr);return *this;}\n  metadata_vendor_id_t getVendorId()const')
with tempfile.TemporaryDirectory(prefix='warsaw-request-host-') as tmp:
 t=Path(tmp);(t/'test_stubs.h').write_text(stubs)
 for name in ['android-base/properties.h','camera/CameraMetadata.h','camera/VendorTagDescriptor.h','camera_metadata_hidden.h','utils/String8.h']:
  p=t/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('#include "test_stubs.h"\n')
 (t/'production.h').write_text(header.read_text());(t/'test.cpp').write_text(setup+tests)
 subprocess.run(['g++','-std=c++20','-O1','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-Wno-unused-parameter','-I'+str(t),str(t/'test.cpp'),'-o',str(t/'test')],check=True)
 result=subprocess.run([str(t/'test')],text=True,capture_output=True)
 print(result.stdout,end='');assert not result.stderr,result.stderr
 a.report_dir.mkdir(parents=True,exist_ok=True);label='before' if a.baseline else 'after';(a.report_dir/f'camera-request-identity-{label}.log').write_text(result.stdout)
 count,failed=map(int,re.search(r'CASES (\d+) FAILURES (\d+)',result.stdout).groups())
 (a.report_dir/f'camera-request-identity-{label}.json').write_text(json.dumps({'source':str(a.baseline or cpp),'source_sha256':hashlib.sha256(source.encode()).hexdigest(),'header_sha256':hashlib.sha256(header.read_bytes()).hexdigest(),'cases':count,'failed':failed,'extracted_production_submission_block':True,'host_api_doubles':True,'android_compile':False,'runtime_validation':False,'package_created':False},indent=2)+'\n')
 if not a.baseline:assert result.returncode==0
