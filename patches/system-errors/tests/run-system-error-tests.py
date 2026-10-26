#!/usr/bin/env python3
"""Host fault injection for vold/update_engine functions with modeled I/O."""
from pathlib import Path
import argparse, hashlib, json, subprocess, tempfile

p = argparse.ArgumentParser()
p.add_argument('--root', default=str(Path(__file__).resolve().parents[6]))
p.add_argument('--output', required=True)
p.add_argument('--allow-failures', action='store_true', help='Use only when recording a known failing baseline')
args = p.parse_args()
root = Path(args.root)
sources = {}

def source(name):
    text = (root / name).read_text()
    sources[name] = hashlib.sha256(text.encode()).hexdigest()
    return text

def function(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

common = r'''
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdarg>
#include <cstdlib>
#include <iostream>
#include <list>
#include <map>
#include <memory>
#include <string>
#include <vector>
#include <sys/mount.h>
#include <unistd.h>
struct Log { template<class T> Log& operator<<(const T&) { return *this; } };
#define LOG(x) Log()
#define PLOG(x) Log()
#define LOG_IF(x,cond) if (!(cond)) {} else Log()
#define CHECK(x) do { if(!(x)) std::abort(); } while(0)
#define CHECK_EQ(x,y) CHECK((x)==(y))
#define TEST_AND_RETURN_FALSE(x) do { if(!(x)) return false; } while(0)
void check(const char* name, bool passed) {
  std::cout << name << " " << (passed ? "PASS" : "FAIL") << "\n";
}
'''

vb = source('system/vold/model/VolumeBase.cpp')
pr = source('system/vold/model/PrivateVolume.cpp')
pu = source('system/vold/model/PublicVolume.cpp')
em = source('system/vold/model/EmulatedVolume.cpp')
ut = source('system/vold/Utils.cpp')
vold = common + r'''
using status_t=int; using userid_t=unsigned; const int OK=0;
namespace android { using status_t=int; const int OK=0; }
namespace flags { bool enhanced=false; bool enhance_fuse_unmount(){return enhanced;} }
std::map<std::string,int> forces, lazies;
std::vector<std::string> removed, forced, detached;
int fuseResult=0, sdResult=0, bindResult=0;
void reset(){forces.clear();lazies.clear();removed.clear();forced.clear();detached.clear();
 flags::enhanced=false;fuseResult=sdResult=bindResult=0;errno=0;}
std::string StringPrintf(const char* fmt,...){ char b[512];va_list a;va_start(a,fmt);
 vsnprintf(b,sizeof(b),fmt,a);va_end(a);return b; }
int ForceUnmount(const std::string& p){forced.push_back(p);errno=0;return forces[p];}
int fake_rmdir(const char* p){removed.push_back(p);errno=0;return 0;}
int fake_umount2(const char* p,int){detached.push_back(p);errno=lazies[p];return errno ? -1 : 0;}
#define rmdir fake_rmdir
#define umount2 fake_umount2
void KillProcessesUsingPath(const std::string&){}
bool IsFuseBpfEnabled(){return false;}
std::string GetFuseMountPathForUser(userid_t u,const std::string& n){return StringPrintf("/mnt/user/%d/%s",u,n.c_str());}
int UnmountUserFuseEnhanced(userid_t,const std::string&,const std::string&,const std::string&,
 const std::vector<std::string>& = {}){return fuseResult;}
struct VolumeManager { static VolumeManager* Instance(){static VolumeManager v;return &v;}
 std::vector<userid_t> users{0}; auto getStartedUsers(){return users;}
 userid_t getSharedStorageUser(userid_t){return 0;} };
struct Listener {void onVolumeDestroyed(const std::string&){};};
struct VolumeBase {
 enum class State {kMounted,kUnmounted,kUnmountable,kChecking,kEjecting,kFormatting,kBadRemoval,kRemoved};
 enum MountFlags {kPrimary=1};
 State mState=State::kMounted; bool mCreated=true;
 std::list<std::shared_ptr<VolumeBase>> mVolumes;
 int unmountResult=0,destroyResult=0,formatCalls=0,unmountCalls=0,destroyCalls=0,mountFlags=0;
 virtual ~VolumeBase()=default;
 State getState() const{return mState;} void setState(State s){mState=s;}
 std::string getId()const{return "vol";} Listener* getListener(){return nullptr;}
 std::string getPath()const{return "/storage/test";}
 std::string getInternalPath()const{return "/mnt/media_rw/test";}
 userid_t getMountUserId()const{return 0;} int getMountFlags()const{return mountFlags;}
 virtual int doUnmount(){++unmountCalls;return unmountResult;}
 int doDestroy(){++destroyCalls;return destroyResult;}
 int doFormat(const std::string&){++formatCalls;return 0;}
 int unmount(); int format(const std::string&); int destroy();
};
struct PrivateVolume: VolumeBase {std::string mPath="/mnt/expand/test";int doUnmount() override;};
const char* kAsecPath="/mnt/secure/asec";
struct PublicVolume: VolumeBase {
 bool mFuseMounted=false,mUseSdcardFs=false;
 std::string mRawPath="/mnt/media_rw/test",mSdcardFsDefault="/default",mSdcardFsRead="/read",
 mSdcardFsWrite="/write",mSdcardFsFull="/full";
 std::string getStableName(){return "test";} int doUnmount() override;
};
struct EmulatedVolume: VolumeBase {
 bool mFuseMounted=true; std::string getLabel(){return "test";}
 int unmountFuseBindMounts(){return bindResult;} int unmountSdcardFs(){return sdResult;}
 int doUnmount() override;
};
'''
vold += function(ut, 'status_t UnmountUserFuse(') + '\n'
for text, signature in [(vb,'status_t VolumeBase::destroy()'),(vb,'status_t VolumeBase::unmount()'),
                        (vb,'status_t VolumeBase::format('),(pr,'status_t PrivateVolume::doUnmount()'),
                        (pu,'status_t PublicVolume::doUnmount()'),(em,'status_t EmulatedVolume::doUnmount()')]:
    vold += function(text, signature) + '\n'
vold += r'''
int main(){
 {VolumeBase v;v.unmountResult=-EBUSY;int r=v.unmount();
 check("unmount-failure-retains-retry-state",r==-EBUSY&&v.getState()==VolumeBase::State::kMounted);
 v.unmountResult=0;r=v.unmount();check("retry-unmount",r==0&&v.unmountCalls==2);}
 {VolumeBase v;v.unmountResult=-EIO;int r=v.format("ext4");
 check("format-stops-on-unmount-error",r==-EIO&&v.formatCalls==0);}
 {VolumeBase v;check("format-after-success",v.format("ext4")==0&&v.formatCalls==1);}
 {VolumeBase v;v.mState=VolumeBase::State::kUnmountable;
 check("format-unmountable",v.format("ext4")==0&&v.unmountCalls==0);}
 {VolumeBase v;v.mState=VolumeBase::State::kEjecting;
 check("format-busy-state",v.format("ext4")==-EBUSY&&v.formatCalls==0);}
 {VolumeBase v;auto c=std::make_shared<VolumeBase>();c->unmountResult=-EBUSY;v.mVolumes.push_back(c);
 int r=v.destroy();check("physical-removal-releases-busy-child",!v.mCreated&&!c->mCreated&&v.mVolumes.empty());
 check("physical-removal-reports-unmount-error",r==-EBUSY&&v.destroyCalls==1);}
 {VolumeBase v;auto c=std::make_shared<VolumeBase>();v.mVolumes.push_back(c);
 check("physical-removal-success",v.destroy()==0&&!v.mCreated&&!c->mCreated&&v.mVolumes.empty());}
 {VolumeBase v;auto c=std::make_shared<VolumeBase>();c->unmountResult=-EBUSY;v.mVolumes.push_back(c);
 int r=v.format("ext4");check("stacked-busy-prevents-format",r==-EBUSY&&v.formatCalls==0&&v.unmountCalls==0);
 check("stacked-busy-keeps-child",c->mCreated&&c->destroyCalls==0&&v.mVolumes.size()==1);
 c->unmountResult=0;r=v.unmount();check("stacked-child-retry",r==0&&!c->mCreated&&v.mVolumes.empty());}
 {VolumeBase v;auto a=std::make_shared<VolumeBase>(),b=std::make_shared<VolumeBase>();
 a->destroyResult=-EIO;v.mVolumes={a,b};int r=v.unmount();
 check("stacked-destroy-error-propagated",r==-EIO&&v.unmountCalls==0);
 check("stacked-destroy-error-retains-pending",v.mVolumes.size()==1&&v.mVolumes.front()==b&&b->mCreated);
 if(v.mState==VolumeBase::State::kMounted){r=v.unmount();check("stacked-no-double-destroy",r==0&&a->destroyCalls==1&&b->destroyCalls==1);}
 else check("stacked-no-double-destroy",false);}
 {reset();PrivateVolume v;forces[v.mPath]=-EBUSY;int r=v.doUnmount();
 check("private-unmount-error",r==-EBUSY&&removed.empty());}
 {reset();PrivateVolume v;check("private-unmount-success",v.doUnmount()==0&&removed.size()==1);}
 {reset();forces["/mnt/pass_through/0/test"]=-EIO;
 int r=UnmountUserFuse(0,"lower","test");check("fuse-pass-through-error",r==-EIO);
 check("fuse-failed-mount-not-rmdir",removed.size()==1&&removed[0]=="/mnt/user/0/test");}
 {reset();forces["/mnt/user/0/test"]=-EBUSY;
 check("fuse-lazy-success",UnmountUserFuse(0,"lower","test")==0&&detached.size()==1);}
 {reset();forces["/mnt/user/0/test"]=-EBUSY;lazies["/mnt/user/0/test"]=EACCES;
 check("fuse-lazy-failure",UnmountUserFuse(0,"lower","test")==-EACCES&&removed.empty());}
 {reset();PublicVolume v;v.mFuseMounted=true;forces["/mnt/pass_through/0/test"]=-EIO;
 int r=v.doUnmount();check("public-propagates-status-with-zero-errno",r==-EIO&&v.mFuseMounted&&!v.mRawPath.empty());}
 {reset();EmulatedVolume v;forces["/mnt/pass_through/0/test"]=-EIO;
 check("emulated-propagates-status-with-zero-errno",v.doUnmount()==-EIO&&v.mFuseMounted);}
 {reset();PublicVolume v;v.mFuseMounted=true;VolumeManager::Instance()->users={0,10};
 forces["/mnt/user/10/test"]=-EBUSY;
 check("public-shared-bind-busy",v.doUnmount()==-EBUSY&&v.mFuseMounted&&removed.empty());
 VolumeManager::Instance()->users={0};}
 {reset();PublicVolume v;std::string raw=v.mRawPath;forces[v.mRawPath]=-EBUSY;lazies[v.mRawPath]=EACCES;
 int r=v.doUnmount();check("public-raw-lazy-failure",r==-EACCES&&!v.mRawPath.empty());
 check("public-no-rmdir-mounted-raw",std::find(removed.begin(),removed.end(),raw)==removed.end());
 lazies[raw]=0;int retry=v.doUnmount();check("public-raw-retry",retry==0&&v.mRawPath.empty()&&std::count(forced.begin(),forced.end(),raw)==2);}
 {reset();PublicVolume v;v.mUseSdcardFs=true;forces[v.mSdcardFsRead]=-EIO;
 check("public-sdcardfs-failure-retains-paths",v.doUnmount()==-EIO&&!v.mSdcardFsRead.empty()&&!v.mRawPath.empty());}
 {reset();PublicVolume v;v.mountFlags=VolumeBase::kPrimary;forces[kAsecPath]=-EBUSY;
 check("primary-asec-blocks-raw-unmount",v.doUnmount()==-EBUSY&&!v.mRawPath.empty());}
 {reset();PublicVolume v;forces[kAsecPath]=-EBUSY;v.doUnmount();
 check("secondary-does-not-unmount-primary-asec",std::find(forced.begin(),forced.end(),kAsecPath)==forced.end());}
 {reset();PublicVolume v;v.mFuseMounted=true;flags::enhanced=true;fuseResult=-EBUSY;
 check("enhanced-error-preserved",v.doUnmount()==-EBUSY&&v.mFuseMounted);}
 {reset();PublicVolume v;check("public-success",v.doUnmount()==0&&v.mRawPath.empty());}
}
'''

da = source('system/update_engine/download_action.cc')
va = source('system/update_engine/payload_consumer/vabc_partition_writer.cc')
de = source('system/update_engine/payload_consumer/delta_performer.cc')
tail_start = de.index('\n  if (partition_writer_', de.index('    CheckpointUpdateProgress(false);', de.index('bool DeltaPerformer::Write(')))
tail_end = de.index('  // In major version 2', tail_start)
update = common + r'''
enum class ErrorCode {kSuccess,kDownloadTransferError,kDownloadWriteError,kPayloadHashMismatchError};
struct HttpFetcher{};
struct Writer {int result=0, closes=0,verifies=0;ErrorCode verify=ErrorCode::kSuccess;
 int Close(){++closes;return result;} ErrorCode VerifyPayload(int,int){++verifies;return verify;}};
struct Payload {bool already_applied=false;int hash=0,size=0;};
struct InstallPlan {std::vector<Payload> payloads{Payload{}};std::string download_url="test";};
struct Delegate{int count=0;void DownloadComplete(){++count;}};
struct Processor{int count=0;ErrorCode code=ErrorCode::kSuccess;
 void ActionComplete(void*,ErrorCode c){++count;code=c;}};
namespace base{struct StatisticsRecorder{static void WriteGraph(const char*,std::string*){}};}
struct DownloadAction {
 Writer* delta_performer_=nullptr;bool download_active_=true,hasPipe=true;int outputs=0;
 InstallPlan install_plan_;Payload* payload_=&install_plan_.payloads[0];Delegate* delegate_=nullptr;
 Processor* processor_=nullptr;bool HasOutputPipe(){return hasPipe;}
 void SetOutputObject(const InstallPlan&){++outputs;}void TransferComplete(HttpFetcher*,bool);
};
struct CowWriter{bool ok=true;int error=0;bool Finalize(){errno=error;return ok;}};
struct Partition {std::string partition_name()const{return "system";}};
struct VABCPartitionWriter{std::unique_ptr<CowWriter> cow_writer_;Partition partition_update_;int Close();};
struct PartWriter{bool finish=true;bool FinishedInstallOps(){return finish;}};
struct DeltaPerformer {std::unique_ptr<PartWriter> partition_writer_;int result=0,closes=0;
 int CloseCurrentPartition(){++closes;partition_writer_=nullptr;return result;}
 bool Finish(ErrorCode* error);
};
'''
update += function(da,'void DownloadAction::TransferComplete(') + '\n'
update += function(va,'int VABCPartitionWriter::Close()') + '\n'
update += 'bool DeltaPerformer::Finish(ErrorCode* error){\n' + de[tail_start:tail_end] + 'return true;\n}\n'
update += r'''
int main(){
 for(int close:{0,-EIO,1})for(bool network:{false,true})for(bool applied:{false,true}){
 Writer w;w.result=close;Processor p;Delegate d;DownloadAction a;
 a.delta_performer_=&w;a.processor_=&p;a.delegate_=&d;a.payload_->already_applied=applied;
 a.TransferComplete(nullptr,network);
 auto expected=!network?ErrorCode::kDownloadTransferError:close?ErrorCode::kDownloadWriteError:ErrorCode::kSuccess;
 bool ok=expected==ErrorCode::kSuccess;
 std::string name="download-close-"+std::to_string(close)+"-network-"+std::to_string(network)+"-resume-"+std::to_string(applied);
 check(name.c_str(),p.count==1&&p.code==expected&&w.closes==1&&!a.download_active_&&a.outputs==int(ok)&&d.count==int(ok)&&w.verifies==int(ok&&!applied));
 }
 {Processor p;DownloadAction a;a.processor_=&p;a.TransferComplete(nullptr,true);
 check("download-no-writer",p.code==ErrorCode::kSuccess&&a.outputs==1);}
 {Writer w;w.verify=ErrorCode::kPayloadHashMismatchError;Processor p;Delegate d;DownloadAction a;
 a.delta_performer_=&w;a.processor_=&p;a.delegate_=&d;a.TransferComplete(nullptr,true);
 check("payload-verification-error",p.code==w.verify&&a.outputs==0&&d.count==0);}
 for(int error:{0,EIO,ENOSPC}){VABCPartitionWriter v;v.cow_writer_=std::make_unique<CowWriter>();
 v.cow_writer_->ok=false;v.cow_writer_->error=error;
 std::string name="cow-finalize-error-"+std::to_string(error);
 check(name.c_str(),v.Close()==-(error?error:EIO));}
 {VABCPartitionWriter v;v.cow_writer_=std::make_unique<CowWriter>();
 check("cow-finalize-success",v.Close()==0&&!v.cow_writer_);check("cow-close-again",v.Close()==0);}
 for(bool finish:{false,true})for(int close:{0,-EIO}){DeltaPerformer d;
 d.partition_writer_=std::make_unique<PartWriter>();d.partition_writer_->finish=finish;d.result=close;
 ErrorCode e=ErrorCode::kSuccess;bool ok=d.Finish(&e);
 std::string name="last-partition-finish-"+std::to_string(finish)+"-close-"+std::to_string(close);
 check(name.c_str(),ok==(finish&&close==0)&&e==(ok?ErrorCode::kSuccess:ErrorCode::kDownloadWriteError)&&d.closes==int(finish));}
}
'''

results = []
with tempfile.TemporaryDirectory(prefix='warsaw-system-host-') as temp:
    for name, code in [('vold',vold),('update_engine',update)]:
        cpp=Path(temp)/f'{name}.cpp'; exe=Path(temp)/name;cpp.write_text(code)
        subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror','-Wno-unused-parameter',
                        '-fsanitize=undefined','-fno-sanitize-recover=all',str(cpp),'-o',str(exe)],check=True)
        output=subprocess.check_output([str(exe)],text=True)
        for line in output.splitlines():
            case, result=line.rsplit(' ',1)
            results.append({'suite':name,'case':case,'passed':result=='PASS'})
report={'sources':sources,'actual_production_functions':True,'external_dependencies_modeled':True,
        'device_io':False,'android_build':False,'cases':results,
        'passed':sum(x['passed'] for x in results),'failed':sum(not x['passed'] for x in results)}
Path(args.output).write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:report[k] for k in ('passed','failed')}))
for r in results:
    if not r['passed']:print('FAIL',r['suite'],r['case'])
if report['failed'] and not args.allow_failures:
    raise SystemExit(1)
