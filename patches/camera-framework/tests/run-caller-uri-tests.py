from pathlib import Path
import argparse,tempfile,subprocess,re,json,hashlib
p=argparse.ArgumentParser(description='Exercise caller URI snapshots using production permission and request methods with Android doubles.')
p.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[6])
p.add_argument('--report-dir',type=Path,default=None)
a=p.parse_args();R=a.source.resolve();report_tmp=tempfile.TemporaryDirectory(prefix='warsaw-uri-report-');N=a.report_dir or Path(report_tmp.name);N.mkdir(parents=True,exist_ok=True);B=R/'frameworks/base/services/core/java/com/android/server/wm'
s=(B/'ActivityCallerState.java').read_text();starter=(B/'ActivityStarter.java').read_text()
def method(source,signature):
 start=source.index(signature);i=source.index('{',start)+1;depth=1
 while depth:
  depth+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[start:i]
# XML persistence is unrelated to this regression. Compile the unmodified permission
# section, including the real CallerInfo fields/constructor and Intent URI collection.
body=s[s.index('final class ActivityCallerState {'):s.index('        public void saveToXml')]+ '    }\n}\n'
request=method(starter,'        void resolveCallerUriPermissions(')
helper=r'''
import java.util.*;
class Process {static final int INVALID_UID=-1;}
class ActivityTaskManagerDebugConfig {static final boolean TAG_WITH_CLASS_NAME=true;static final String TAG_ATM="ATM";}
interface IBinder{}
class Binder implements IBinder {static int uid=11001;static int getCallingUid(){return uid;}}
class UserHandle {static int getUserId(int uid){return uid/100000;}}
class BadParcelableException extends RuntimeException{}
class ArraySet<E> extends LinkedHashSet<E>{E valueAt(int i){return new ArrayList<>(this).get(i);}}
class Slog{static void e(String a,String b){}static void w(String a,String b){}}
class Uri {
 final String value;Uri(String s){value=s;}static Uri parse(String s){return new Uri(s);}
 String getScheme(){return value.split(":",2)[0];}String toSafeString(){return value;}
 @Override public boolean equals(Object x){return x instanceof Uri u&&value.equals(u.value);}
 @Override public int hashCode(){return value.hashCode();}
 @Override public String toString(){return value;}
}
class ContentResolver{static final String SCHEME_CONTENT="content";}
class ContentProvider {
 static int getUserIdFromUri(Uri uri,int fallback){String rest=uri.value.substring("content://".length());int at=rest.indexOf('@');return at<0?fallback:Integer.parseInt(rest.substring(0,at));}
 static Uri getUriWithoutUserId(Uri uri){return Uri.parse(uri.value.replaceFirst("content://[0-9]+@","content://"));}
}
class GrantUri {
 final int sourceUserId;final Uri uri;final boolean prefix;
 GrantUri(int user,Uri u,int flags){sourceUserId=user;uri=u;prefix=(flags&128)!=0;}
 @Override public boolean equals(Object x){return x instanceof GrantUri g&&sourceUserId==g.sourceUserId&&uri.equals(g.uri)&&prefix==g.prefix;}
 @Override public int hashCode(){return Objects.hash(sourceUserId,uri,prefix);}
}
class ClipData {
 static class Item {Uri uri;Intent intent;Item(Uri u){uri=u;}Item(Intent i){intent=i;}Uri getUri(){return uri;}Intent getIntent(){return intent;}}
 final ArrayList<Item> items=new ArrayList<>();ClipData(Item...is){items.addAll(Arrays.asList(is));}int getItemCount(){return items.size();}Item getItemAt(int i){return items.get(i);}
}
class Intent {
 static final int FLAG_GRANT_READ_URI_PERMISSION=1,FLAG_GRANT_WRITE_URI_PERMISSION=2;
 static final String EXTRA_STREAM="stream";
 Uri data;Object extra;ClipData clip;boolean bad;
 Intent(String u){data=u==null?null:Uri.parse(u);}Intent(){}
 Uri getData(){return data;}ClipData getClipData(){return clip;}boolean hasExtra(String key){return extra!=null||bad;}
 <T>T getParcelableExtra(String k,Class<T> c){if(bad)throw new BadParcelableException();return c.isInstance(extra)?c.cast(extra):null;}
 @SuppressWarnings("unchecked") <T>ArrayList<T> getParcelableArrayListExtra(String k,Class<T> c){if(bad)throw new BadParcelableException();return extra instanceof ArrayList<?>?(ArrayList<T>)extra:null;}
 static boolean isAccessUriMode(int f){return (f&3)!=0;}
}
class WindowProcessController {final int mUid;WindowProcessController(int uid){mUid=uid;}}
class ActivityTaskManagerService {
 final Object mGlobalLock=new Object();final Permissions mUgmInternal=new Permissions(this);WindowProcessController process;
 WindowProcessController getProcessController(Object caller){if(!Thread.holdsLock(mGlobalLock))throw new AssertionError("process lookup needs lock");return process;}
}
class Permissions {
 final ActivityTaskManagerService service;int queries;int lockedQueries;int mode=1;int allowedUid=11001;
 Permissions(ActivityTaskManagerService s){service=s;}
 boolean checkUriPermission(GrantUri u,int uid,int flags,boolean full){
  if(!full)throw new AssertionError("full access check was bypassed");queries++;
  if(Thread.holdsLock(service.mGlobalLock)){lockedQueries++;return false;}
  return uid==allowedUid&&(mode&flags)==flags;
 }
}
class Request {
 static final int DEFAULT_REAL_CALLING_UID=-1;
 int callingUid=-1,realCallingUid=-1;Object caller;Intent intent;
 ActivityCallerState.UriPermissionSnapshot callerUriPermissions;boolean callerUriPermissionsPrecomputed;
 __REQUEST__
}
'''.replace('__REQUEST__',request)
helper += 'class ActivityStarter {final Request mRequest=new Request();' + method(starter,'    ActivityStarter setCallerUriPermissions(') + '}\n'
# Keep production names while substituting Android APIs with deterministic doubles.
body=body.replace('TAG_WITH_CLASS_NAME','ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME').replace('TAG_ATM','ActivityTaskManagerDebugConfig.TAG_ATM').replace('INVALID_UID','Process.INVALID_UID')
tests=r'''
public class UriSnapshotTests {
 static int cases;
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static GrantUri grant(String uri,int user){return new GrantUri(user,Uri.parse(uri),0);}
 static void run(String name,Runnable test){test.run();cases++;System.out.println("PASS "+name);}
 static Request request(Intent i){Request r=new Request();r.intent=i;r.callingUid=11001;return r;}
 static void apply(ActivityTaskManagerService svc,ActivityCallerState state,IBinder token,Request r){synchronized(svc.mGlobalLock){state.computeCallerInfo(token,r.intent,11001,"camera",true,r.callerUriPermissions);}}
 public static void main(String[]args){
 run("captured launch does not query provider under WM lock",()->{var v=new ActivityTaskManagerService();var i=new Intent("content://media/images/1");var r=request(i);var c=new ActivityCallerState(v);var t=new Binder();r.resolveCallerUriPermissions(v);apply(v,c,t,r);ok(c.checkContentUriPermission(t,grant(i.data.value,0),1));ok(!c.checkContentUriPermission(t,grant(i.data.value,0),2));ok(v.mUgmInternal.lockedQueries==0&&v.mUgmInternal.queries==2);});
 run("write-only grant remains write-only for new intent",()->{var v=new ActivityTaskManagerService();v.mUgmInternal.mode=2;var r=request(new Intent("content://media/images/2"));var c=new ActivityCallerState(v);var t=new Binder();r.resolveCallerUriPermissions(v);apply(v,c,t,r);ok(!c.checkContentUriPermission(t,grant(r.intent.data.value,0),1));ok(c.checkContentUriPermission(t,grant(r.intent.data.value,0),2));ok(!c.checkContentUriPermission(t,grant(r.intent.data.value,0),3));});
 run("denied URI remains inaccessible",()->{var v=new ActivityTaskManagerService();v.mUgmInternal.mode=0;var r=request(new Intent("content://media/images/3"));var c=new ActivityCallerState(v);var t=new Binder();r.resolveCallerUriPermissions(v);apply(v,c,t,r);ok(!c.checkContentUriPermission(t,grant(r.intent.data.value,0),3));});
 run("different UID cannot reuse original permission",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/images/4"));r.resolveCallerUriPermissions(v);var c=new ActivityCallerState(v);var t=new Binder();c.computeCallerInfo(t,r.intent,11002,"other",false,r.callerUriPermissions);ok(!c.checkContentUriPermission(t,grant(r.intent.data.value,0),1));ok(c.getUid(t)==11002&&!c.isShareIdentityEnabled(t));ok(v.mUgmInternal.queries==4);});
 run("changed Intent does not inherit snapshot",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/images/5"));r.resolveCallerUriPermissions(v);r.intent.data=Uri.parse("content://media/images/6");v.mUgmInternal.mode=0;var c=new ActivityCallerState(v);var t=new Binder();c.computeCallerInfo(t,r.intent,11001,"camera",false,r.callerUriPermissions);ok(!c.checkContentUriPermission(t,grant(r.intent.data.value,0),1));ok(v.mUgmInternal.queries==4);});
 run("cross-user URI cannot reuse local-user snapshot",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/images/7"));r.resolveCallerUriPermissions(v);r.intent.data=Uri.parse("content://10@media/images/7");ok(!r.callerUriPermissions.matches(r.intent,11001));});
 run("explicit URI user preserved",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://10@media/images/8"));r.resolveCallerUriPermissions(v);var c=new ActivityCallerState(v);var t=new Binder();apply(v,c,t,r);ok(c.checkContentUriPermission(t,grant("content://media/images/8",10),1));try{c.checkContentUriPermission(t,grant("content://media/images/8",0),1);throw new AssertionError();}catch(IllegalArgumentException expected){}});
 run("data streams nested clip and duplicate URI collection",()->{var v=new ActivityTaskManagerService();var i=new Intent("content://media/a");i.extra=new ArrayList<>(List.of(Uri.parse("content://media/b")));i.clip=new ClipData(new ClipData.Item(Uri.parse("content://media/a")),new ClipData.Item(new Intent("content://media/c")));var r=request(i);r.resolveCallerUriPermissions(v);ok(r.callerUriPermissions.mContentUris.size()==3&&v.mUgmInternal.queries==6);});
 run("single stream URI collected",()->{var v=new ActivityTaskManagerService();var i=new Intent();i.extra=Uri.parse("content://media/single");var r=request(i);r.resolveCallerUriPermissions(v);ok(r.callerUriPermissions.mContentUris.size()==1);});
 run("non-content URIs never trigger provider calls",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("file:///data/other"));r.resolveCallerUriPermissions(v);ok(v.mUgmInternal.queries==0);});
 run("malformed parcel keeps safe empty URI set",()->{var v=new ActivityTaskManagerService();var i=new Intent();i.bad=true;var r=request(i);r.resolveCallerUriPermissions(v);ok(v.mUgmInternal.queries==0);});
 run("actual caller process UID wins over requested UID",()->{var v=new ActivityTaskManagerService();v.process=new WindowProcessController(11002);var r=request(new Intent("content://media/x"));r.caller=new Object();r.resolveCallerUriPermissions(v);ok(r.callerUriPermissions.mUid==11002);ok(r.callerUriPermissions.mReadableContentUris.isEmpty());});
 run("missing process cannot inherit old snapshot",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/x"));r.resolveCallerUriPermissions(v);int before=v.mUgmInternal.queries;r.caller=new Object();r.resolveCallerUriPermissions(v);ok(r.callerUriPermissions==null&&v.mUgmInternal.queries==before);});
 run("caller-free requests use real or Binder UID",()->{var v=new ActivityTaskManagerService();var r=request(new Intent());r.callingUid=-1;r.realCallingUid=21001;r.resolveCallerUriPermissions(v);ok(r.callerUriPermissions.mUid==21001);r.realCallingUid=-1;r.resolveCallerUriPermissions(v);ok(r.callerUriPermissions.mUid==Binder.getCallingUid());});
 run("disabled feature clears snapshot without queries",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/x"));r.resolveCallerUriPermissions(v);int before=v.mUgmInternal.queries;android.security.Flags.enabled=false;try{r.resolveCallerUriPermissions(v);ok(r.callerUriPermissions==null&&v.mUgmInternal.queries==before);}finally{android.security.Flags.enabled=true;}});
 run("request entered with WM lock does not make new IPC",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/x"));r.resolveCallerUriPermissions(v);int before=v.mUgmInternal.queries;synchronized(v.mGlobalLock){r.resolveCallerUriPermissions(v);}ok(r.callerUriPermissions==null&&v.mUgmInternal.queries==before&&v.mUgmInternal.lockedQueries==0);});
 run("URI order does not invalidate equivalent set",()->{var v=new ActivityTaskManagerService();var i=new Intent();i.extra=new ArrayList<>(List.of(Uri.parse("content://media/a"),Uri.parse("content://media/b")));var r=request(i);r.resolveCallerUriPermissions(v);i.extra=new ArrayList<>(List.of(Uri.parse("content://media/b"),Uri.parse("content://media/a")));ok(r.callerUriPermissions.matches(i,11001));});
 run("unknown URI and invalid access mode are rejected",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/x"));r.resolveCallerUriPermissions(v);var c=new ActivityCallerState(v);var t=new Binder();apply(v,c,t,r);for(int mode:new int[]{0,1}){try{c.checkContentUriPermission(t,grant("content://media/unknown",0),mode);throw new AssertionError();}catch(IllegalArgumentException expected){}}});
 run("snapshot retains neither caller package nor shared identity",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/x"));r.resolveCallerUriPermissions(v);var c=new ActivityCallerState(v);var t=new Binder();synchronized(v.mGlobalLock){c.computeCallerInfo(t,r.intent,11001,"new-package",false,r.callerUriPermissions);}ok(c.getPackage(t).equals("new-package")&&!c.isShareIdentityEnabled(t));});
 run("result prepareToLeaveUser preserves equivalent URI snapshot",()->{var v=new ActivityTaskManagerService();var r=request(new Intent("content://media/result"));r.resolveCallerUriPermissions(v);r.intent.data=Uri.parse("content://0@media/result");var c=new ActivityCallerState(v);var t=new Binder();apply(v,c,t,r);ok(c.checkContentUriPermission(t,grant("content://media/result",0),1));ok(v.mUgmInternal.lockedQueries==0&&v.mUgmInternal.queries==2);});
 run("work-profile result cannot inherit primary profile grants",()->{var v=new ActivityTaskManagerService();v.mUgmInternal.allowedUid=1011001;var i=new Intent("content://media/result");var snapshot=ActivityCallerState.computeUriPermissions(v,i,1011001);ok(snapshot.matches(new Intent("content://10@media/result"),1011001));ok(!snapshot.matches(new Intent("content://0@media/result"),1011001));ok(!snapshot.matches(i,11001));});
 run("internal navigation snapshot is consumed once",()->{var v=new ActivityTaskManagerService();var i=new Intent("content://media/navigation");var snapshot=ActivityCallerState.computeUriPermissions(v,i,11001);var starter=new ActivityStarter();var r=starter.mRequest;r.intent=i;r.callingUid=11001;ok(starter.setCallerUriPermissions(snapshot)==starter);synchronized(v.mGlobalLock){r.resolveCallerUriPermissions(v);}ok(r.callerUriPermissions==snapshot&&!r.callerUriPermissionsPrecomputed);var c=new ActivityCallerState(v);var t=new Binder();apply(v,c,t,r);ok(c.checkContentUriPermission(t,grant(i.data.value,0),1));synchronized(v.mGlobalLock){r.resolveCallerUriPermissions(v);}ok(r.callerUriPermissions==null&&v.mUgmInternal.lockedQueries==0);});
 run("disabled feature discards prepared navigation snapshot",()->{var v=new ActivityTaskManagerService();var starter=new ActivityStarter();var r=starter.mRequest;r.intent=new Intent("content://media/x");starter.setCallerUriPermissions(ActivityCallerState.computeUriPermissions(v,r.intent,11001));android.security.Flags.enabled=false;try{synchronized(v.mGlobalLock){r.resolveCallerUriPermissions(v);}ok(r.callerUriPermissions==null&&!r.callerUriPermissionsPrecomputed);}finally{android.security.Flags.enabled=true;}});
 run("changed navigation UID cannot inherit precomputed permissions",()->{var v=new ActivityTaskManagerService();var starter=new ActivityStarter();var r=starter.mRequest;r.intent=new Intent("content://media/x");starter.setCallerUriPermissions(ActivityCallerState.computeUriPermissions(v,r.intent,11001));synchronized(v.mGlobalLock){r.resolveCallerUriPermissions(v);}var c=new ActivityCallerState(v);var t=new Binder();c.computeCallerInfo(t,r.intent,11002,"other",false,r.callerUriPermissions);ok(!c.checkContentUriPermission(t,grant(r.intent.data.value,0),1));});
 System.out.println("Passed "+cases+" host cases");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='warsaw-uri-tests-') as tmp:
 t=Path(tmp);(t/'android/security').mkdir(parents=True);(t/'android/security/Flags.java').write_text('package android.security; public class Flags {public static boolean enabled=true;public static boolean contentUriPermissionApis(){return enabled;}}')
 (t/'UriSnapshotTests.java').write_text(helper+body+tests)
 jdk=R/'prebuilts/jdk/jdk21/linux-x86/bin'
 r=subprocess.run([str(jdk/'javac'),'-d',str(t/'classes'),str(t/'android/security/Flags.java'),str(t/'UriSnapshotTests.java')],capture_output=True,text=True);print(r.stderr);r.check_returncode()
 r=subprocess.run([str(jdk/'java'),'-cp',str(t/'classes'),'UriSnapshotTests'],capture_output=True,text=True);print(r.stdout,r.stderr);r.check_returncode();(N/'caller-uri-tests.txt').write_text(r.stdout)
 # Validate all modified source plumbing, pooled request lifecycle and lock boundary.
 assert starter.index('mRequest.resolveCallerUriPermissions(mService);')<starter.index('                    res = executeRequest(mRequest);')
 assert 'callerUriPermissions = null;' in method(starter,'        void reset()')
 assert 'callerUriPermissions = request.callerUriPermissions;' in method(starter,'        void set(@NonNull Request request)')
 assert 'started.computeInitialCallerInfo(mRequest.callerUriPermissions);' in starter
 assert 'UserHandle.getAppId(mStartActivity.getUid()), mRequest.callerUriPermissions);' in starter
 record=(B/'ActivityRecord.java').read_text()
 assert 'launchedFromPackage, mShareIdentity, permissions);' in record
 assert 'isShareIdentityEnabled, permissions);' in record
 client=(B/'ActivityClientController.java').read_text()
 finish=method(client,'    public boolean finishActivity(')
 assert finish.index('ActivityCallerState.computeUriPermissions(')<finish.index('// Check again in case activity was removed')
 assert 'true /* oomAdj */, callerPermissions);' in finish
 assert 'finishActivityResults(resultCode, resultData, resultGrants, permissions);' in record
 assert '/* isShareIdentityEnabled */ false, permissions);' in record
 service=method((B/'ActivityTaskManagerService.java').read_text(),'        public void sendActivityResult(')
 assert service.index('ActivityCallerState.computeUriPermissions(')<service.index('if (!r.isInHistory())')
 assert 'false /* forceSendForMediaProjection */, callerPermissions);' in service
 task=(B/'Task.java').read_text()
 assert '.setCallerUriPermissions(destCallerPermissions)' in task
 assert '"navigate-up", true /* oomAdj */, resultCallerPermissions);' in task
 assert '"navigate-top", true /* oomAdj */, resultCallerPermissions);' in task
 assert 'callerUriPermissionsPrecomputed = false;' in method(starter,'        void reset()')
 assert 'callerUriPermissionsPrecomputed = request.callerUriPermissionsPrecomputed;' in method(starter,'        void set(@NonNull Request request)')
 (N/'caller-uri-validation.json').write_text(json.dumps({'host_cases':24,'production_permission_and_request_methods_compiled_against_doubles':True,'xml_serialization_not_in_test_harness':True,'initial_and_new_intent_plumbing_checked':True,'pooled_request_reset_and_copy_checked':True,'full_android_build':False,'phone_validation':False,'finish_and_local_service_result_snapshots_checked':True,'internal_navigation_snapshot_checked':True,'interception_without_matching_snapshot_preserves_original_fail_closed_check':True,'source_sha256':{name:hashlib.sha256((B/name).read_bytes()).hexdigest() for name in ['ActivityCallerState.java','ActivityStarter.java','ActivityRecord.java','ActivityClientController.java','ActivityTaskManagerService.java','Task.java']}},indent=2)+'\n')
