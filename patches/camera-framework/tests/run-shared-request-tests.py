#!/usr/bin/env python3
"""Exercise actual CameraDeviceClient shared-request control flow on the host."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[6])
parser.add_argument('--baseline', type=Path)
parser.add_argument('--report', type=Path, required=True)
args = parser.parse_args()
path = args.source / 'frameworks/av/services/camera/libcameraservice/api2/CameraDeviceClient.cpp'
source = (args.baseline or path).read_text()

def braced(text, start):
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

submit_start = source.index('binder::Status CameraDeviceClient::submitRequestList(')
start = source.index('    int32_t sharedReqID;', submit_start)
end = source.index('    ALOGV("%s: Camera %s: End of function"', start)
submission = source[start:end].replace('int32_t sharedReqID;',
        'int32_t sharedReqID = 0x5a5a5a5a;')
# Defined poison avoids reading indeterminate memory in the pre-repair regression.
init = re.search(r'^      mSharedStreamingRequest\((.*)\),$', source, re.M)
ctor = ' : mSharedStreamingRequest(' + init.group(1) + ')' if init else ''
start = source.index('void CameraDeviceClient::onResultAvailable(')
prefix_start = source.index('    CaptureResult mutableResult = result;', start)
prefix_end = source.index('    // Thread-safe. No lock necessary.', prefix_start)
result_prefix = source[prefix_start:prefix_end]
idle_start = source.index('        if (mSharedMode) {\n            // If all the capture requests', prefix_end)
idle = braced(source, idle_start)
start = source.index('void CameraDeviceClient::notifyError(')
error_start = source.index('    if (mSharedMode && (resultExtras.requestId != -1)) {', start)
error = braced(source, error_start)
flush_start = source.index('binder::Status CameraDeviceClient::flush(')
flush_shared_start = source.index('    if (mSharedMode) {', flush_start)
flush_tail = braced(source, flush_shared_start)
matching = '\n'.join(braced(source, source.index('bool CameraDeviceClient::'+name+'('))
                     .replace('CameraDeviceClient::', '')
                     for name in ('matchSharedStreamingRequest','matchSharedCaptureRequest'))
burst = re.search(r'    if \(mSharedMode && requests.size\(\) > 1\) \{', source)
burst_guard = braced(source, burst.start()) if burst else ''
config_start = source.index('    const CameraMetadata &deviceInfo = mDevice->info();')
config_end = source.find('    camera_metadata_ro_entry_t physicalKeysEntry', config_start)
config = source[config_start:config_end]
# The partial-count setup may precede listener registration in the repaired source.
listener = config.find('    mFrameProcessor->registerListener(')
if listener >= 0:
    config = config[:listener]

code = r'''
#include <cstdint>
#include <cstring>
#include <iostream>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
constexpr int OK=0, ANDROID_REQUEST_ID=1, ANDROID_REQUEST_PARTIAL_RESULT_COUNT=2;
namespace CameraService {constexpr int ERROR_INVALID_OPERATION=-10;}
namespace fmt {template<class... T>std::string sprintf(const char*,T...){return "error";}}
#define ALOGE(...) ((void)0)
#define ALOGV(...) ((void)0)
#define STATUS_ERROR(code, ...) (code)
struct Mutex {struct Autolock {explicit Autolock(Mutex&) {}};};
struct CameraMetadata {
 int id=0, countValue=0;bool countPresent=false;
 struct Entry {size_t count;struct {const int32_t* i32;} data;};
 Entry find(int) const{return {countPresent?1U:0U,{&countValue}};}
 int update(int,const int* value,int){id=*value;return OK;}
};
struct CaptureResultExtras {
 int requestId=0;int partialResultCount=1;int64_t frameNumber=1;
 std::string errorPhysicalCameraId;
};
struct CaptureResult {CaptureResultExtras mResultExtras;CameraMetadata mMetadata;};
namespace hardware::camera2 {
struct ICameraDeviceCallbacks {
 static constexpr int ERROR_CAMERA_REQUEST=3, ERROR_CAMERA_RESULT=4, ERROR_CAMERA_BUFFER=5;
 std::vector<std::pair<int,CaptureResultExtras>> errors;
 void onDeviceError(int code,const CaptureResultExtras& extras){errors.emplace_back(code,extras);}
};
}
struct SubmitInfo {int mRequestId=41;int64_t mLastFrameNumber=-1;};
struct Device {
 int status=OK;int nextId=73;bool writeIdOnFailure=false;int calls=0;CameraMetadata metadata;
 const CameraMetadata& info()const{return metadata;}
 int shared(int* id,int64_t* last){++calls;if(status==OK||writeIdOnFailure){*id=nextId;*last=99;}return status;}
 int setSharedCaptureRequest(int,int,int* id,int64_t* last){return shared(id,last);}
 int setSharedStreamingRequest(int,int,int* id,int64_t* last){return shared(id,last);}
 int captureList(const std::vector<int>&,const std::vector<int>&,int64_t*){++calls;return status;}
 int setStreamingRequestList(const std::vector<int>&,const std::vector<int>&,int64_t*){++calls;return status;}
};
struct Harness {
 static constexpr int REQUEST_ID_NONE=-1;
 bool mSharedMode=true,mIsPrimaryClient=true;
 int mStreamingRequestId=-1;int64_t mStreamingRequestLastFrameNumber=-1;
 std::pair<int,int> mSharedStreamingRequest;
 std::map<int,int> mSharedRequestMap;
 int mNumPartialResults=1;
 Mutex mStreamingRequestIdLock;
 std::string mCameraIdStr="0";
 std::shared_ptr<Device> mDevice=std::make_shared<Device>();
 std::shared_ptr<hardware::camera2::ICameraDeviceCallbacks> remote=std::make_shared<hardware::camera2::ICameraDeviceCallbacks>();
 std::vector<CaptureResult> delivered;
 int activeCalls=0,idleCalls=0;
 void markClientActive(){++activeCalls;}
 void markClientIdle(){++idleCalls;}
 auto getRemoteCallback(){return remote;}
 Harness()''' + ctor + r''' {}
 void configure(){
''' + config + r'''
 (void)deviceInfo;
 }
 int guardBurst(size_t size){std::vector<int> requests(size);
''' + burst_guard + r'''
 return OK;
 }
 int submit(bool streaming=false){
 int res=OK,err=OK;SubmitInfo info;auto submitInfo=&info;
 std::vector<int> metadataRequestList{1},surfaceMapList{2};
''' + submission + r'''
 return res;
 }
''' + matching + r'''
 void result(int req,int partial,int64_t frame=1){
 CaptureResult result;result.mResultExtras={req,partial,frame,{}};result.mMetadata.id=req;
''' + result_prefix + r'''
 delivered.push_back(mutableResult);
''' + idle + r'''
 }
 void notifyError(int errorCode,const CaptureResultExtras& resultExtras){
 auto remoteCb=getRemoteCallback();
''' + error + r'''
 if(remoteCb)remoteCb->onDeviceError(errorCode,resultExtras);
 }
 void afterFlush(){int64_t frame=99;auto lastFrameNumber=&frame;
''' + flush_tail + r'''
 }
};
void expect(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
void isolated(Harness& h){h.mSharedStreamingRequest={-1,-1};}
int main(){int cases=0,failures=0;auto test=[&](const char*name,auto action){++cases;
try{action();std::cout<<"PASS "<<name<<"\n";}
catch(const std::exception&e){++failures;std::cout<<"FAIL "<<name<<": "<<e.what()<<"\n";}};
test("no_phantom_streaming_request_zero",[]{Harness h;expect(!h.matchSharedStreamingRequest(0),"default pair matches first capture");});
test("first_capture_zero_retains_client_id",[]{Harness h;h.mSharedRequestMap[0]=41;h.result(0,1);expect(h.delivered.size()==1&&h.delivered[0].mResultExtras.requestId==41&&h.mSharedRequestMap.empty(),"first request misrouted as streaming");});
test("failed_capture_with_unwritten_id_stays_idle",[]{Harness h;h.mDevice->status=-22;expect(h.submit()!=OK&&h.mSharedRequestMap.empty()&&h.activeCalls==0,"failure recorded poison ID or marked active");});
test("failed_capture_with_out_id_preserves_existing_map",[]{Harness h;h.mSharedRequestMap[22]=17;h.mDevice->status=-32;h.mDevice->writeIdOnFailure=true;expect(h.submit()!=OK&&h.mSharedRequestMap.size()==1&&h.mSharedRequestMap.at(22)==17&&h.activeCalls==0,"failure mutated live requests");});
test("successful_capture_records_shared_id",[]{Harness h;expect(h.submit()==OK&&h.mSharedRequestMap.at(73)==41&&h.activeCalls==1,"successful capture missing");});
test("nonshared_capture_failure_preserves_error",[]{Harness h;h.mSharedMode=false;h.mDevice->status=-22;expect(h.submit()!=OK&&h.mSharedRequestMap.empty()&&h.activeCalls==0,"normal failure changed");});
test("shared_burst_rejected_before_submission",[]{Harness h;expect(h.guardBurst(2)!=OK&&h.mDevice->calls==0,"burst would silently drop later requests");});
test("single_shared_and_normal_burst_allowed",[]{Harness h;expect(h.guardBurst(1)==OK,"single request blocked");h.mSharedMode=false;expect(h.guardBurst(2)==OK,"ordinary burst blocked");});
test("partial_count_read_from_static_metadata",[]{Harness h;h.mDevice->metadata.countPresent=true;h.mDevice->metadata.countValue=3;h.configure();expect(h.mNumPartialResults==3,"partial count ignored");});
test("absent_partial_count_defaults_to_one",[]{Harness h;h.configure();expect(h.mNumPartialResults==1,"missing count changed default");});
test("capture_partial_results_keep_mapping_until_final",[]{Harness h;isolated(h);h.mNumPartialResults=3;h.mSharedRequestMap[73]=41;h.result(73,1);expect(h.mSharedRequestMap.count(73)==1&&h.idleCalls==0,"partial result retired capture");h.result(73,2);h.result(73,3);expect(h.delivered.size()==3&&h.mSharedRequestMap.empty()&&h.idleCalls==1,"final result lost or idle missing");for(const auto&r:h.delivered)expect(r.mResultExtras.requestId==41&&r.mMetadata.id==41,"wrong client ID");});
test("one_final_does_not_idle_other_capture",[]{Harness h;isolated(h);h.mSharedRequestMap={{73,41},{74,42}};h.result(73,1);expect(h.idleCalls==0&&h.mSharedRequestMap.count(74)==1,"other capture discarded");h.result(74,1);expect(h.idleCalls==1&&h.delivered.size()==2,"last capture missing");});
test("cancelled_streaming_keeps_last_frame_partials",[]{Harness h;h.mNumPartialResults=2;h.mSharedStreamingRequest={73,41};h.mStreamingRequestId=-1;h.mStreamingRequestLastFrameNumber=9;h.result(73,1,9);expect(h.mSharedStreamingRequest.first==73&&h.idleCalls==0,"last-frame partial retired stream");h.result(73,2,9);expect(h.delivered.size()==2&&h.mSharedStreamingRequest.first==-1&&h.idleCalls==1,"last final lost");});
test("ongoing_streaming_keeps_mapping",[]{Harness h;h.mSharedStreamingRequest={73,41};h.mStreamingRequestId=41;h.result(73,1);expect(h.delivered.size()==1&&h.mSharedStreamingRequest.first==73&&h.idleCalls==0,"ongoing stream retired");});
test("buffer_error_preserves_final_result",[]{Harness h;isolated(h);h.mSharedRequestMap[73]=41;h.notifyError(5,{73,0,1,{}});expect(h.mSharedRequestMap.count(73)==1&&h.remote->errors.size()==1&&h.remote->errors[0].second.requestId==41,"buffer error retired whole request");h.result(73,1);expect(h.delivered.size()==1&&h.idleCalls==1,"metadata after buffer error lost");});
test("physical_result_error_preserves_logical_result",[]{Harness h;isolated(h);h.mSharedRequestMap[73]=41;h.notifyError(4,{73,0,1,"2"});expect(h.mSharedRequestMap.count(73)==1,"physical error retired logical result");h.result(73,1);expect(h.delivered.size()==1,"logical metadata lost");});
test("request_error_retires_capture_and_marks_idle",[]{Harness h;isolated(h);h.mSharedRequestMap[73]=41;h.notifyError(3,{73,0,1,{}});expect(h.mSharedRequestMap.empty()&&h.idleCalls==1&&h.remote->errors.size()==1,"terminal request stayed active");});
test("logical_result_error_retires_capture",[]{Harness h;isolated(h);h.mSharedRequestMap[73]=41;h.notifyError(4,{73,0,1,{}});expect(h.mSharedRequestMap.empty()&&h.idleCalls==1,"terminal metadata error stayed active");});
test("unknown_shared_request_ignored",[]{Harness h;isolated(h);h.result(999,1);h.notifyError(5,{999,0,1,{}});expect(h.delivered.empty()&&h.remote->errors.empty()&&h.mSharedRequestMap.empty(),"foreign request forwarded");});
test("nonshared_result_and_error_unchanged",[]{Harness h;h.mSharedMode=false;h.result(73,1);h.notifyError(5,{73,0,1,{}});expect(h.delivered.size()==1&&h.delivered[0].mResultExtras.requestId==73&&h.remote->errors[0].second.requestId==73&&h.idleCalls==0,"ordinary callbacks changed");});
test("flush_preserves_queued_final_result",[]{Harness h;isolated(h);h.mNumPartialResults=2;h.mSharedRequestMap[73]=41;h.result(73,1);h.afterFlush();h.result(73,2);expect(h.delivered.size()==2&&h.delivered.back().mResultExtras.requestId==41&&h.mSharedRequestMap.empty()&&h.idleCalls==1,"flush dropped queued final result");});
test("flush_preserves_queued_request_error",[]{Harness h;isolated(h);h.mSharedRequestMap[73]=41;h.afterFlush();h.notifyError(3,{73,0,1,{}});expect(h.remote->errors.size()==1&&h.remote->errors[0].second.requestId==41&&h.mSharedRequestMap.empty()&&h.idleCalls==1,"flush dropped queued request failure");});
std::cout<<"CASES "<<cases<<" FAILURES "<<failures<<"\n";return failures?1:0;}
'''
with tempfile.TemporaryDirectory(prefix='warsaw-shared-host-') as directory:
    tmp = Path(directory)
    (tmp/'test.cpp').write_text(code)
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Werror','-O1',
                    str(tmp/'test.cpp'),'-o',str(tmp/'test')],check=True)
    result = subprocess.run([str(tmp/'test')],text=True,capture_output=True)
    print(result.stdout,end='')
    assert not result.stderr, result.stderr
    cases, failures = map(int,re.search(r'CASES (\d+) FAILURES (\d+)',result.stdout).groups())
    args.report.write_text(json.dumps({'cases':cases,'failures':failures,
        'source_sha256':hashlib.sha256(source.encode()).hexdigest(),
        'actual_production_blocks':True,'host_api_doubles':True,
        'baseline_out_id_initialized_to_defined_poison':True,
        'full_android_compile':False,'hardware_validation':False,
        'concurrency_validation':False,'output':result.stdout},indent=2)+'\n')
    raise SystemExit(result.returncode)
