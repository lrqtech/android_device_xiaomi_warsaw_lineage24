from pathlib import Path
import argparse,subprocess,tempfile,json,hashlib,re
parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[6]);parser.add_argument('--report-dir',type=Path,default=None);parser.add_argument('--baseline',type=Path);args=parser.parse_args();R=args.source
header=R/'frameworks/av/services/camera/libcameraservice/utils/WarsawCameraSessionParams.h'
stubs=r'''
#pragma once
#include <cstdint>
#include <cstring>
#include <limits>
#include <map>
#include <memory>
#include <string>
using metadata_vendor_id_t = uint64_t;
constexpr metadata_vendor_id_t CAMERA_METADATA_INVALID_VENDOR_ID = UINT64_MAX;
constexpr int TYPE_BYTE=0, TYPE_INT32=1;
namespace android {
using status_t=int;
constexpr int OK=0, BAD_VALUE=-22, NAME_NOT_FOUND=-2, BAD_TYPE=-100, NO_MEMORY=-12;
template<class T> using sp=std::shared_ptr<T>;
inline bool failString8=false;
class String8 { public: explicit String8(const char* s):value(failString8?"":s){} std::string value; };
namespace base { inline std::string device="warsaw"; inline std::string GetProperty(const char*,const char*) {return device;} }
class VendorTagDescriptor {
 public:
  struct Tag {std::string name;int type;};
  std::map<uint32_t,Tag> tags;
  inline static sp<VendorTagDescriptor> global;
  static sp<VendorTagDescriptor> getGlobalVendorTagDescriptor(){return global;}
  int getTagCount()const{return tags.size();}
  int getTagType(uint32_t tag)const{auto i=tags.find(tag);return i==tags.end()?-1:i->second.type;}
};
class VendorTagDescriptorCache {
 public:
  std::map<metadata_vendor_id_t,sp<VendorTagDescriptor>> descriptors;
  inline static sp<VendorTagDescriptorCache> global;
  static sp<VendorTagDescriptorCache> getGlobalVendorTagCache(){return global;}
  int getVendorTagDescriptor(metadata_vendor_id_t id,sp<VendorTagDescriptor>* out){auto i=descriptors.find(id);if(i==descriptors.end())return NAME_NOT_FOUND;*out=i->second;return OK;}
};
}
struct camera_metadata_t {metadata_vendor_id_t id=CAMERA_METADATA_INVALID_VENDOR_ID;std::map<uint32_t,std::string> entries;};
inline bool failAllocation=false, failClone=false, failUpdate=false;
inline camera_metadata_t* allocate_camera_metadata(size_t,size_t){return failAllocation?nullptr:new camera_metadata_t;}
inline void set_camera_metadata_vendor_id(camera_metadata_t* p,metadata_vendor_id_t id){p->id=id;}
namespace android {
class CameraMetadata {
 std::unique_ptr<camera_metadata_t> data;
 public:
  CameraMetadata()=default;
  explicit CameraMetadata(camera_metadata_t* p):data(p){}
  CameraMetadata(const CameraMetadata& p):data(!failClone&&p.data?new camera_metadata_t(*p.data):nullptr){}
  metadata_vendor_id_t getVendorId()const{return data?data->id:CAMERA_METADATA_INVALID_VENDOR_ID;}
  static int getTagFromName(const char* name,const VendorTagDescriptor* desc,uint32_t* out){if(!desc)return NAME_NOT_FOUND;for(const auto&[tag,info]:desc->tags)if(info.name==name){*out=tag;return OK;}return NAME_NOT_FOUND;}
  int update(uint32_t tag,const String8& v){return updateValue(tag,v.value);}
  int update(uint32_t tag,const uint8_t* bytes,size_t count){
   if(count==0||bytes[count-1]!=0)return BAD_VALUE;
   return updateValue(tag,std::string(reinterpret_cast<const char*>(bytes),count-1));
  }
  int updateValue(uint32_t tag,const std::string& value){
   if(failUpdate)return NO_MEMORY;
   if(tag>=0x80000000){sp<VendorTagDescriptor> desc;if(getVendorId()!=CAMERA_METADATA_INVALID_VENDOR_ID){auto cache=VendorTagDescriptorCache::global;if(!cache||cache->getVendorTagDescriptor(getVendorId(),&desc)!=OK)return NAME_NOT_FOUND;}else desc=VendorTagDescriptor::global;if(!desc||desc->getTagType(tag)!=TYPE_BYTE)return BAD_TYPE;}
   if(!data)data.reset(new camera_metadata_t);data->entries[tag]=value;return OK;
  }
  camera_metadata_t* release(){return data.release();}
  void acquire(camera_metadata_t* p){data.reset(p);}
  void swap(CameraMetadata& p){data.swap(p.data);}
  bool exists(uint32_t tag)const{return data&&data->entries.count(tag);}
  std::string at(uint32_t tag)const{return data&&data->entries.count(tag)?data->entries.at(tag):"";}
  size_t count()const{return data?data->entries.size():0;}
  bool isEmpty()const{return count()==0;}
};
}
'''
tests=r'''
#include "production.h"
#include <functional>
#include <iostream>
#include <stdexcept>
using namespace android;
constexpr uint32_t Tag=0x81da0001;
constexpr metadata_vendor_id_t Oem=17,External=18;
void expect(bool pass,const char* msg){if(!pass)throw std::runtime_error(msg);}
sp<VendorTagDescriptor> desc(int type=TYPE_BYTE){auto d=std::make_shared<VendorTagDescriptor>();d->tags[Tag]={"com.xiaomi.sessionparams.clientName",type};return d;}
CameraMetadata device(metadata_vendor_id_t id){auto* p=new camera_metadata_t;p->id=id;return CameraMetadata(p);}
void reset(){base::device="warsaw";failAllocation=false;failClone=false;failUpdate=false;failString8=false;VendorTagDescriptor::global=nullptr;auto c=std::make_shared<VendorTagDescriptorCache>();c->descriptors[Oem]=desc();c->descriptors[External]=std::make_shared<VendorTagDescriptor>();VendorTagDescriptorCache::global=c;}
int set(CameraMetadata& p,const CameraMetadata& d){return warsawCameraSession::setClientIdentity(&p,d,"com.android.camera");}
void identity(const CameraMetadata& p){expect(p.getVendorId()==Oem,"wrong provider");expect(p.at(Tag)=="com.android.camera","wrong client identity");}
int main(int argc,char**argv){std::string selected=argc>1?argv[1]:"";int failed=0,count=0;auto test=[&](const char* name,std::function<void()> f){if(!selected.empty()&&selected!=name)return;++count;reset();try{f();std::cout<<"PASS "<<name<<"\n";}catch(const std::exception& e){++failed;std::cout<<"FAIL "<<name<<": "<<e.what()<<"\n";}};
 test("oem_empty_session",[]{CameraMetadata p;expect(set(p,device(Oem))==OK,"configure failed");identity(p);});
 test("preserve_standard_parameters",[]{CameraMetadata p;p.update(7,String8("preview"));expect(set(p,device(Oem))==OK,"configure failed");identity(p);expect(p.at(7)=="preview","standard metadata changed");});
 test("override_spoofed_identity",[]{auto p=device(Oem);p.update(Tag,String8("spoofed"));expect(set(p,device(Oem))==OK,"configure failed");identity(p);});
 test("foreign_vendor_rejected",[]{auto p=device(External);expect(set(p,device(Oem))==BAD_VALUE,"foreign provider accepted");expect(p.getVendorId()==External&&p.count()==0,"error mutated metadata");});
 test("missing_cache_rejected",[]{VendorTagDescriptorCache::global=nullptr;CameraMetadata p;expect(set(p,device(Oem))==NAME_NOT_FOUND,"missing cache accepted");expect(p.count()==0,"error mutated metadata");});
 test("unknown_provider_rejected",[]{CameraMetadata p;expect(set(p,device(19))==NAME_NOT_FOUND,"unknown provider accepted");});
 test("null_descriptor_rejected",[]{VendorTagDescriptorCache::global->descriptors[Oem]=nullptr;CameraMetadata p;expect(set(p,device(Oem))==NAME_NOT_FOUND,"null descriptor accepted");});
 test("wrong_tag_type_rejected",[]{VendorTagDescriptorCache::global->descriptors[Oem]=desc(TYPE_INT32);CameraMetadata p;expect(set(p,device(Oem))==BAD_TYPE,"wrong type accepted");expect(p.count()==0,"error mutated metadata");});
 test("incomplete_oem_descriptor_rejected",[]{VendorTagDescriptorCache::global->descriptors[Oem]->tags[Tag].name="other.tag";CameraMetadata p;expect(set(p,device(Oem))==NAME_NOT_FOUND,"incomplete OEM table accepted");});
 test("explicit_empty_external_descriptor",[]{CameraMetadata p;expect(set(p,device(External))==OK,"external provider blocked");expect(p.getVendorId()==CAMERA_METADATA_INVALID_VENDOR_ID&&p.count()==0,"external session mutated");});
 test("external_standard_session_preserved",[]{auto p=device(External);p.update(7,String8("preview"));expect(set(p,device(External))==OK,"external provider blocked");expect(p.getVendorId()==External&&p.count()==1&&p.at(7)=="preview","external metadata changed");});
 test("external_foreign_metadata_rejected",[]{auto p=device(Oem);expect(set(p,device(External))==BAD_VALUE,"unexpected foreign metadata rejection status");expect(p.getVendorId()==Oem,"foreign metadata rewritten");});
 test("external_does_not_use_global_oem_table",[]{VendorTagDescriptor::global=desc();CameraMetadata p;expect(set(p,device(External))==OK,"global table broke external provider");expect(p.count()==0,"OEM identity injected into external provider");});
 test("provider_cache_over_global_table",[]{VendorTagDescriptor::global=desc(TYPE_INT32);CameraMetadata p;expect(set(p,device(Oem))==OK,"foreign global type used");identity(p);});
 test("unknown_provider_cannot_borrow_global",[]{VendorTagDescriptor::global=desc();CameraMetadata p;expect(set(p,device(19))==NAME_NOT_FOUND,"global table borrowed");expect(p.count()==0,"unknown metadata changed");});
 test("missing_cache_cannot_borrow_global",[]{VendorTagDescriptor::global=desc();VendorTagDescriptorCache::global=nullptr;CameraMetadata p;expect(set(p,device(Oem))==NAME_NOT_FOUND,"global table used without cache");});
 test("legacy_global_descriptor",[]{VendorTagDescriptor::global=desc();CameraMetadata p;expect(set(p,device(CAMERA_METADATA_INVALID_VENDOR_ID))==OK,"legacy descriptor rejected");expect(p.at(Tag)=="com.android.camera","legacy identity missing");});
 test("legacy_missing_global_rejected",[]{CameraMetadata p;expect(set(p,device(CAMERA_METADATA_INVALID_VENDOR_ID))==NAME_NOT_FOUND,"legacy unknown table accepted");});
 test("null_parameters_rejected",[]{expect(warsawCameraSession::setClientIdentity(nullptr,device(Oem),"com.android.camera")==BAD_VALUE,"null accepted");});
 test("allocation_failure_preserved",[]{failAllocation=true;CameraMetadata p;expect(set(p,device(Oem))==NO_MEMORY,"allocation failure ignored");expect(p.count()==0,"failed allocation mutated metadata");});
 test("other_device_unchanged",[]{base::device="other";VendorTagDescriptorCache::global=nullptr;CameraMetadata p;expect(set(p,device(Oem))==OK&&p.count()==0,"non-Warsaw changed");});
 test("copy_failure_preserves_standard_request",[]{CameraMetadata p;p.update(7,String8("preview"));failClone=true;expect(set(p,device(Oem))==NO_MEMORY,"clone failure treated as empty request");expect(p.getVendorId()==CAMERA_METADATA_INVALID_VENDOR_ID&&p.count()==1&&p.at(7)=="preview","failed copy discarded controls");});
 test("copy_failure_preserves_oem_request",[]{auto p=device(Oem);p.update(7,String8("exposure"));p.update(Tag,String8("original"));failClone=true;expect(set(p,device(Oem))==NO_MEMORY,"failed OEM clone accepted");expect(p.getVendorId()==Oem&&p.count()==2&&p.at(7)=="exposure"&&p.at(Tag)=="original","failed copy changed original metadata");});
 test("copy_failure_preserves_legacy_request",[]{VendorTagDescriptor::global=desc();CameraMetadata p;p.update(7,String8("crop"));failClone=true;expect(set(p,device(CAMERA_METADATA_INVALID_VENDOR_ID))==NO_MEMORY,"failed legacy clone accepted");expect(p.count()==1&&p.at(7)=="crop","legacy controls lost");});
 test("empty_allocated_session_can_recover_allocation",[]{auto p=device(Oem);failClone=true;expect(set(p,device(Oem))==OK,"empty session unnecessarily rejected");identity(p);});
 test("update_failure_preserves_original_request",[]{auto p=device(Oem);p.update(7,String8("preview"));p.update(Tag,String8("original"));failUpdate=true;expect(set(p,device(Oem))==NO_MEMORY,"update failure ignored");expect(p.count()==2&&p.at(7)=="preview"&&p.at(Tag)=="original","failed update changed original");});
 test("identity_does_not_depend_on_temporary_String8_allocation",[]{CameraMetadata p;failString8=true;expect(set(p,device(Oem))==OK,"unnecessary string allocation rejected identity");identity(p);});
 test("identity_string_failure_does_not_erase_controls",[]{auto p=device(Oem);p.update(7,String8("preview"));failString8=true;expect(set(p,device(Oem))==OK,"identity rejected");identity(p);expect(p.at(7)=="preview"&&p.count()==2,"standard controls lost");});
 std::cout<<"CASES "<<count<<" FAILURES "<<failed<<"\n";return failed?1:0;}
'''
with tempfile.TemporaryDirectory(prefix='warsaw-session-host-') as temp:
 t=Path(temp);(t/'test_stubs.h').write_text(stubs)
 for name in ['android-base/properties.h','camera/CameraMetadata.h','camera/VendorTagDescriptor.h','camera_metadata_hidden.h','utils/String8.h']:
  p=t/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('#include "test_stubs.h"\n')
 (t/'production.h').write_text((args.baseline or header).read_text());(t/'tests.cpp').write_text(tests)
 subprocess.run(['g++','-std=c++20','-O1','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-I'+str(t),str(t/'tests.cpp'),'-o',str(t/'tests')],check=True)
 if args.baseline:
  cases=re.findall(r' test\("([^"]+)"',tests);results=[];output=[]
  for case in cases:
   one=subprocess.run([str(t/'tests'),case],capture_output=True,text=True)
   results.append({'case':case,'exit':one.returncode});output.append(one.stdout+one.stderr if one.returncode>=0 else f'FAIL {case}: terminated by signal {-one.returncode}\n')
  output.append(f'BASELINE CASES {len(cases)} FAILURES {sum(x["exit"]!=0 for x in results)}\n')
  r=subprocess.CompletedProcess([],0,''.join(output),'')
 else:r=subprocess.run([str(t/'tests')],capture_output=True,text=True)
 print(r.stdout,end='');assert not r.stderr,r.stderr
 if args.report_dir:
  args.report_dir.mkdir(parents=True,exist_ok=True);name='camera-session-before' if args.baseline else 'camera-session-after';(args.report_dir/(name+'.log')).write_text(r.stdout+r.stderr)
  if not args.baseline:
   assert r.returncode==0,r.stdout
   (args.report_dir/'camera-session-validation.json').write_text(json.dumps({'production_header':str(header),'sha256':hashlib.sha256(header.read_bytes()).hexdigest(),'host_cases':28,'api_doubles':True,'full_android_compile':False,'phone_validation':False,'package_created':False},indent=2)+'\n')
 if not args.baseline:assert r.returncode==0
