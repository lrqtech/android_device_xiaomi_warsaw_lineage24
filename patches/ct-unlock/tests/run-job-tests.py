#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import ast, json, subprocess, tempfile, hashlib

import argparse
parser=argparse.ArgumentParser(description="Check CT job cancellation with a deterministic host executor.")
parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[6])
parser.add_argument('--report-dir',type=Path)
args=parser.parse_args();R=args.source.resolve()
workspace=tempfile.TemporaryDirectory(prefix='ct-job-regression-')
N=args.report_dir or Path(workspace.name);N.mkdir(parents=True,exist_ok=True)
project=R/'packages/modules/Connectivity'
relative='networksecurity/service/src/com/android/server/net/ct/CertificateTransparencyJob.java'
source=project/relative
(N/'ct-job.before.java').write_bytes(subprocess.check_output(['git','-C',str(project),'show','HEAD:'+relative]))
java=R/'prebuilts/jdk/jdk21/linux-x86/bin'
tree=ast.parse(Path(__file__).with_name('run-tests.py').read_text())
stubs = ast.literal_eval(next(n.value for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'stubs' for t in n.targets)))
del stubs['com/android/server/net/ct/UnlockTest.java']
del stubs['com/android/server/net/ct/CertificateTransparencyJob.java']
stubs.update({
'android/app/AlarmManager.java': '''package android.app; public class AlarmManager { public static final int ELAPSED_REALTIME=3;public static final long INTERVAL_DAY=86400000;public int alarms,cancels;public void setInexactRepeating(int t,long s,long i,PendingIntent p){alarms++;}public void cancel(PendingIntent p){cancels++;}}''',
'android/app/PendingIntent.java': '''package android.app;public class PendingIntent {public static final int FLAG_IMMUTABLE=1;public static PendingIntent getBroadcast(android.content.Context c,int id,android.content.Intent i,int flags){return new PendingIntent();}}''',
'android/app/DownloadManager.java': '''package android.app;public class DownloadManager {public static final String ACTION_DOWNLOAD_COMPLETE="download";}''',
'android/os/ConfigUpdate.java': '''package android.os;public class ConfigUpdate {public static final String ACTION_UPDATE_CT_LOGS="update-ct";}''',
'android/os/SystemClock.java': '''package android.os;public class SystemClock {public static long elapsedRealtime(){return 1;}}''',
'android/content/Intent.java': '''package android.content;public class Intent {public static final String ACTION_USER_UNLOCKED="unlock";private String action;private boolean delete;public Intent(String a){action=a;}public String getAction(){return action;}public Intent setPackage(String p){return this;}public boolean getBooleanExtra(String k,boolean d){return delete;}public Intent putExtra(String k,boolean v){delete=v;return this;}}''',
'android/content/IntentFilter.java': '''package android.content;public class IntentFilter {public final String action;public IntentFilter(String a){action=a;}public boolean hasAction(String a){return action.equals(a);}}''',
'android/util/Log.java': '''package android.util;public class Log {public static int d(String t,String m){return 0;}public static int i(String t,String m){return 0;}public static int w(String t,String m){return 0;}public static int e(String t,String m){return 0;}}''',
'com/android/server/net/ct/SignatureVerifier.java': '''package com.android.server.net.ct;public class SignatureVerifier {public int loads,clears;public SignatureVerifier(android.content.Context c){}public void loadAllowedKeys(){loads++;}public void clearAllowedKeys(){clears++;}}''',
'com/android/server/net/ct/CompatibilityVersion.java': '''package com.android.server.net.ct;public class CompatibilityVersion {public int deletes;public CompatibilityVersion(int v,String a,String b){}public void delete(){deletes++;}}''',
'com/android/server/net/ct/DirectoryUtils.java': '''package com.android.server.net.ct;public class DirectoryUtils {public static int removals;public static void removeDir(java.io.File f){removals++;}}''',
'com/android/server/net/ct/CertificateTransparencyDownloader.java': '''package com.android.server.net.ct;public class CertificateTransparencyDownloader extends android.content.BroadcastReceiver {public int starts;public long result=1;public Runnable whileStarting;public CertificateTransparencyDownloader(DownloadHelper h,SignatureVerifier v,CertificateTransparencyLoggerImpl l,java.util.Collection<CompatibilityVersion> c){}public long startPublicKeyDownload(){starts++;if(whileStarting!=null)whileStarting.run();return result;}public void onReceive(android.content.Context c,android.content.Intent i){}}''',
'com/android/server/net/ct/JobLifecycleTest.java': '''package com.android.server.net.ct;
import android.content.*;import android.os.ConfigUpdate;import java.util.*;import java.util.concurrent.*;
public class JobLifecycleTest {
 static int passed,failed;
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static class QueueExecutor extends AbstractExecutorService {
  ArrayDeque<Runnable> queue=new ArrayDeque<>();boolean stopped;
  public void execute(Runnable r){queue.add(r);}public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;return List.of();}public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped;}public boolean awaitTermination(long t,TimeUnit u){return stopped;}
  void drain(){while(!queue.isEmpty())queue.remove().run();}
 }
 static class Fixture {
  Context c=new Context();QueueExecutor executor=new QueueExecutor();SignatureVerifier verifier=new SignatureVerifier(c);
  CompatibilityVersion version=new CompatibilityVersion(2,"","");
  CertificateTransparencyDownloader downloader=new CertificateTransparencyDownloader(new DownloadHelper(c),verifier,new CertificateTransparencyLoggerImpl(),List.of(version));
  CertificateTransparencyJob job=new CertificateTransparencyJob(c,downloader,verifier,List.of(version),executor);
  void update(){job.onReceive(c,new Intent(ConfigUpdate.ACTION_UPDATE_CT_LOGS));}
  void delete(){job.onReceive(c,new Intent(ConfigUpdate.ACTION_UPDATE_CT_LOGS).putExtra("delete",true));}
 }
 static void run(String n,Runnable r){try{r.run();passed++;System.out.println("PASS "+n);}catch(Throwable e){failed++;System.out.println("FAIL "+n+": "+e);}}
 public static void main(String[] args){
  run("unscheduled update ignored",()->{var f=new Fixture();f.update();f.executor.drain();check(f.downloader.starts==0,"unscheduled download");});
  run("one daily alarm",()->{var f=new Fixture();f.job.schedule();f.job.schedule();check(f.c.alarms.alarms==1,"duplicate alarm");check(f.c.registrations==1,"duplicate listener");});
  run("queued start cancelled",()->{var f=new Fixture();f.job.schedule();f.update();f.job.cancel();f.executor.drain();check(f.downloader.starts==0,"cancelled download started");check(f.verifier.loads==0,"cancelled dependencies started");check(f.c.receivers.isEmpty(),"receiver leaked");});
  run("old generation stays cancelled after reenable",()->{var f=new Fixture();f.job.schedule();f.update();f.job.cancel();f.job.schedule();f.executor.drain();check(f.downloader.starts==0,"old generation ran");f.update();f.executor.drain();check(f.downloader.starts==1,"new generation did not run");});
  run("dependency initialization once",()->{var f=new Fixture();f.job.schedule();f.update();f.update();f.executor.drain();check(f.downloader.starts==2,"valid jobs lost");check(f.verifier.loads==1,"duplicate dependency setup");f.job.cancel();check(f.verifier.clears==1,"keys not released");check(f.c.receivers.isEmpty(),"download receiver leaked");});
  run("valid delete request",()->{DirectoryUtils.removals=0;var f=new Fixture();f.job.schedule();f.delete();f.executor.drain();check(DirectoryUtils.removals==1,"delete request lost");check(f.downloader.starts==0,"delete launched download");});
  run("queued delete cancelled",()->{DirectoryUtils.removals=0;var f=new Fixture();f.job.schedule();f.delete();f.job.cancel();f.job.schedule();f.executor.drain();check(DirectoryUtils.removals==0,"old delete survived reenable");});
  run("unscheduled delete ignored",()->{DirectoryUtils.removals=0;var f=new Fixture();f.delete();f.executor.drain();check(DirectoryUtils.removals==0,"unscheduled deletion");});
  run("unexpected broadcast rejected",()->{var f=new Fixture();f.job.schedule();f.job.onReceive(f.c,new Intent("other"));f.executor.drain();check(f.downloader.starts==0,"wrong broadcast accepted");});
  run("download failure not success",()->{var f=new Fixture();f.downloader.result=-1;f.job.schedule();f.update();f.executor.drain();check(f.downloader.starts==1,"download not attempted");f.job.cancel();check(f.verifier.clears==1,"failed download leaked dependencies");});
  run("cancel serializes with running initialization",()->{
   var f=new Fixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelStarted=new CountDownLatch(1);var cancelDone=new CountDownLatch(1);
   f.downloader.whileStarting=()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){throw new AssertionError(e);}};
   f.job.schedule();f.update();Thread worker=new Thread(f.executor::drain);worker.start();
   try{check(entered.await(5,TimeUnit.SECONDS),"job did not start");Thread canceller=new Thread(()->{cancelStarted.countDown();f.job.cancel();cancelDone.countDown();});canceller.start();check(cancelStarted.await(5,TimeUnit.SECONDS),"cancel thread absent");boolean early=cancelDone.await(50,TimeUnit.MILLISECONDS);release.countDown();worker.join(5000);canceller.join(5000);check(!worker.isAlive()&&!canceller.isAlive(),"deadlock");check(!early,"cancel returned before active work ended");check(f.verifier.clears==1&&f.c.receivers.isEmpty(),"dependency teardown incomplete");}catch(InterruptedException e){throw new AssertionError(e);}finally{release.countDown();}
  });
  System.out.println("RESULT passed="+passed+" failed="+failed);if(failed>0)System.exit(1);
 }
}'''
})
stubs['android/content/Context.java'] = stubs['android/content/Context.java'].replace(
    'public final UserManager users=new UserManager();',
    'public final UserManager users=new UserManager();public final android.app.AlarmManager alarms=new android.app.AlarmManager();public String getPackageName(){return "android";}').replace(
    'return c.cast(users);', 'return c.cast(c==UserManager.class?users:alarms);')
stubs['com/android/server/net/ct/Config.java'] = stubs['com/android/server/net/ct/Config.java'].replace(
    'public static final boolean DEBUG=false;', 'public static final boolean DEBUG=false;public static final String CT_ROOT_DIRECTORY_PATH="/unused-test-path";')
results = {}
for label, candidate in [('before', N/'ct-job.before.java'), ('after', source)]:
    text = candidate.read_text()
    if label == 'before':
        # Inject only the deterministic executor into the original baseline. Scheduling,
        # cancellation, callbacks and worker logic are unchanged.
        text = text.replace('private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();', 'private final ExecutorService mExecutorService;')
        text = text.replace('Collection<CompatibilityVersion> compatVersions) {', 'Collection<CompatibilityVersion> compatVersions, ExecutorService executorService) {\n        mExecutorService = executorService;', 1)
    with tempfile.TemporaryDirectory(prefix='ct-job-tests-') as directory:
        root = Path(directory)
        for name, content in stubs.items():
            p = root/name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
        (root/'com/android/server/net/ct/CertificateTransparencyJob.java').write_text(text)
        subprocess.run([str(java/'javac'), '-d', str(root/'classes')]+[str(p) for p in root.rglob('*.java')], check=True)
        result = subprocess.run([str(java/'java'), '-cp', str(root/'classes'), 'com.android.server.net.ct.JobLifecycleTest'], text=True, capture_output=True)
        print(label, result.stdout, result.stderr)
        results[label] = {'exit_code': result.returncode, 'output': result.stdout, 'original_sha256': hashlib.sha256(candidate.read_bytes()).hexdigest()}
        (N/f'ct-job-{label}.log').write_text(result.stdout+result.stderr)
        if label == 'after': result.check_returncode()
assert results['before']['exit_code'] != 0
(N/'ct-job-validation.json').write_text(json.dumps({'cases': 11, 'host_android_test_doubles': True, 'baseline_executor_injection_only': True, 'results': results, 'package_built': False}, indent=2)+'\n')
