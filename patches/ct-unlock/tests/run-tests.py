#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import subprocess, tempfile, json, hashlib

import argparse
parser=argparse.ArgumentParser(description="Run CT unlock ordering host tests without building an APK.")
parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[6])
parser.add_argument('--report-dir',type=Path)
args=parser.parse_args()
R=args.source.resolve()
workspace=tempfile.TemporaryDirectory(prefix='ct-unlock-regression-')
N=args.report_dir or Path(workspace.name)
N.mkdir(parents=True,exist_ok=True)
project=R/'packages/modules/Connectivity'
relative='networksecurity/service/src/com/android/server/net/ct/CertificateTransparencyService.java'
source=project/relative
(N/'ct-service.before.java').write_bytes(subprocess.check_output(['git','-C',str(project),'show','HEAD:'+relative]))
java=R/'prebuilts/jdk/jdk21/linux-x86/bin'
stubs = {
'android/annotation/RequiresApi.java': 'package android.annotation; public @interface RequiresApi { int value(); }',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION_CODES { public static final int VANILLA_ICE_CREAM=35; } }',
'android/os/UserManager.java': 'package android.os; public class UserManager { public boolean unlocked; public boolean isUserUnlocked(){return unlocked;} }',
'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }',
'android/content/Intent.java': 'package android.content; public class Intent { public static final String ACTION_USER_UNLOCKED="android.intent.action.USER_UNLOCKED"; private String action; public Intent(String a){action=a;} public String getAction(){return action;} }',
'android/content/IntentFilter.java': 'package android.content; public class IntentFilter { public final String action; public IntentFilter(String a){action=a;} }',
'android/content/Context.java': '''package android.content;
import android.os.UserManager; import java.util.*;
public class Context {
 public static final int RECEIVER_EXPORTED=2;
 public final UserManager users=new UserManager();
 public final Map<BroadcastReceiver,IntentFilter> receivers=new LinkedHashMap<>();
 public int registrations,unregistrations; public Runnable duringRegistration;
 public <T> T getSystemService(Class<T> c){return c.cast(users);}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){
  if(receivers.put(r,f)!=null)throw new AssertionError("duplicate receiver");
  registrations++; if(duringRegistration!=null){Runnable x=duringRegistration;duringRegistration=null;x.run();} return null;
 }
 public void unregisterReceiver(BroadcastReceiver r){if(receivers.remove(r)==null)throw new AssertionError("unregistered receiver");unregistrations++;}
 public void send(String action){for(var e:new ArrayList<>(receivers.entrySet()))if(e.getValue().action.equals(action))e.getKey().onReceive(this,new Intent(action));}
}''',
'android/provider/DeviceConfig.java': '''package android.provider;
import java.util.concurrent.Executor;
public class DeviceConfig {
 public static boolean enabled=true;
 public interface OnPropertiesChangedListener{void onPropertiesChanged(Properties p);}
 public static void addOnPropertiesChangedListener(String n,Executor e,OnPropertiesChangedListener l){}
 public static boolean getBoolean(String n,String k,boolean d){return enabled;}
 public static class Properties {private String n; Properties(String s){n=s;} public String getNamespace(){return n;}
  public static class Builder {String n; public Builder(String s){n=s;} public Properties build(){return new Properties(n);}}
 }
}''',
'android/util/Log.java': 'package android.util; public class Log { public static int d(String t,String m){return 0;} }',
'com/android/net/ct/flags/Flags.java': 'package com.android.net.ct.flags; public class Flags { public static boolean flatbuffersLogList(){return false;} }',
'com/android/server/SystemService.java': 'package com.android.server; public class SystemService { public static final int PHASE_BOOT_COMPLETED=1000; }',
'com/android/server/net/ct/Config.java': '''package com.android.server.net.ct; public class Config {
 public static final boolean DEBUG=false;
 public static final String TAG="CT", NAMESPACE_NETWORK_SECURITY="network_security", FLAG_SERVICE_ENABLED="enabled", URL_SIGNATURE_V2="",URL_LOG_LIST_V2="",URL_SIGNATURE_V3="",URL_LOG_LIST_V3="";
 public static final int COMPATIBILITY_VERSION_V2=2, COMPATIBILITY_VERSION_V3=3;
}''',
'com/android/server/net/ct/SignatureVerifier.java': 'package com.android.server.net.ct; public class SignatureVerifier { public SignatureVerifier(android.content.Context c){} }',
'com/android/server/net/ct/DownloadHelper.java': 'package com.android.server.net.ct; public class DownloadHelper { public DownloadHelper(android.content.Context c){} }',
'com/android/server/net/ct/CompatibilityVersion.java': 'package com.android.server.net.ct; public class CompatibilityVersion { public CompatibilityVersion(int i,String s,String t){} }',
'com/android/server/net/ct/CertificateTransparencyLoggerImpl.java': 'package com.android.server.net.ct; public class CertificateTransparencyLoggerImpl {}',
'com/android/server/net/ct/CertificateTransparencyDownloader.java': 'package com.android.server.net.ct; public class CertificateTransparencyDownloader { public CertificateTransparencyDownloader(DownloadHelper h,SignatureVerifier v,CertificateTransparencyLoggerImpl l,java.util.Collection<CompatibilityVersion> c){} }',
'com/android/server/net/ct/CertificateTransparencyJob.java': '''package com.android.server.net.ct;
public class CertificateTransparencyJob {
 public static CertificateTransparencyJob last; public int alarms,cancels;public boolean scheduled;
 public CertificateTransparencyJob(android.content.Context c,CertificateTransparencyDownloader d,SignatureVerifier v,java.util.Collection<CompatibilityVersion> versions){last=this;}
 public void schedule(){if(!scheduled){alarms++;scheduled=true;}}
 public void cancel(){cancels++;scheduled=false;}
}''',
'com/android/server/net/ct/UnlockTest.java': '''package com.android.server.net.ct;
import android.content.*; import android.provider.DeviceConfig; import com.android.server.SystemService;
public class UnlockTest {
 static int passed,failed;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static class Fixture {
  Context context=new Context(); CertificateTransparencyService service; CertificateTransparencyJob job;
  Fixture(boolean unlocked){DeviceConfig.enabled=true; context.users.unlocked=unlocked; service=new CertificateTransparencyService(context);job=CertificateTransparencyJob.last;}
  void boot(){service.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);}
  void config(){service.onPropertiesChanged(new DeviceConfig.Properties.Builder(Config.NAMESPACE_NETWORK_SECURITY).build());}
  void unlock(){context.users.unlocked=true;context.send(Intent.ACTION_USER_UNLOCKED);}
 }
 static void run(String name,Runnable test){try{test.run();passed++;System.out.println("PASS "+name);}catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e);}}
 public static void main(String[] args){
  run("locked boot waits",()->{var f=new Fixture(false);f.boot();check(f.job.alarms==0,"download before unlock");check(f.context.receivers.size()==1,"missing unlock receiver");});
  run("unlock starts first update",()->{var f=new Fixture(false);f.boot();f.unlock();check(f.job.alarms==1,"no first update");check(f.context.receivers.isEmpty(),"receiver leaked");});
  run("already unlocked",()->{var f=new Fixture(true);f.boot();check(f.job.alarms==1,"no initial update");check(f.context.registrations==0,"unneeded receiver");});
  run("unrelated action",()->{var f=new Fixture(false);f.boot();f.context.send("unrelated");check(f.job.alarms==0,"unexpected update");});
  run("other user remains locked",()->{var f=new Fixture(false);f.boot();f.context.send(Intent.ACTION_USER_UNLOCKED);check(f.job.alarms==0,"wrong user used");check(f.context.receivers.size()==1,"lost receiver");});
  run("cancel waiting",()->{var f=new Fixture(false);f.boot();DeviceConfig.enabled=false;f.config();f.unlock();check(f.job.alarms==0,"update after cancel");check(f.context.receivers.isEmpty(),"cancel leaked receiver");});
  run("queued callback after cancel",()->{var f=new Fixture(false);f.boot();BroadcastReceiver r=f.context.receivers.keySet().iterator().next();DeviceConfig.enabled=false;f.config();f.context.users.unlocked=true;r.onReceive(f.context,new Intent(Intent.ACTION_USER_UNLOCKED));check(f.job.alarms==0,"stale callback scheduled update");});
  run("reenable while locked",()->{var f=new Fixture(false);f.boot();DeviceConfig.enabled=false;f.config();DeviceConfig.enabled=true;f.config();check(f.context.receivers.size()==1,"receiver not restored");f.unlock();check(f.job.alarms==1,"reenable lost update");});
  run("unlock registration race",()->{var f=new Fixture(false);f.context.duringRegistration=()->f.context.users.unlocked=true;f.boot();check(f.job.alarms==1,"missed unlock race");check(f.context.receivers.isEmpty(),"race leaked receiver");});
  run("repeated config while locked",()->{var f=new Fixture(false);f.boot();f.config();f.config();check(f.context.registrations==1,"duplicate receiver");check(f.job.alarms==0,"premature update");});
  run("duplicate unlock",()->{var f=new Fixture(false);f.boot();f.unlock();f.unlock();check(f.job.alarms==1,"duplicate alarm");check(f.context.unregistrations==1,"duplicate unregister");});
  run("irrelevant namespace",()->{var f=new Fixture(false);f.service.onPropertiesChanged(new DeviceConfig.Properties.Builder("other").build());check(f.context.registrations==0&&f.job.alarms==0,"wrong namespace handled");});
  run("disabled boot",()->{var f=new Fixture(false);DeviceConfig.enabled=false;f.boot();check(f.context.registrations==0&&f.job.alarms==0,"disabled service started");});
  run("cancel after unlock",()->{var f=new Fixture(false);f.boot();f.unlock();DeviceConfig.enabled=false;f.config();check(!f.job.scheduled,"alarm retained");check(f.context.receivers.isEmpty(),"receiver retained");});
  run("repeated enable after unlock",()->{var f=new Fixture(true);f.boot();f.config();f.config();check(f.job.alarms==1,"duplicate daily alarm");});
  System.out.println("RESULT passed="+passed+" failed="+failed);if(failed>0)System.exit(1);
 }
}'''
}

results = {}
for label, candidate in [('before', N/'ct-service.before.java'), ('after', source)]:
    with tempfile.TemporaryDirectory(prefix='ct-unlock-tests-') as directory:
        root = Path(directory)
        for name, content in stubs.items():
            p = root/name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
        p = root/'com/android/server/net/ct/CertificateTransparencyService.java'
        p.write_bytes(candidate.read_bytes())
        subprocess.run([str(java/'javac'), '-d', str(root/'classes')]+[str(x) for x in root.rglob('*.java')], check=True)
        result = subprocess.run([str(java/'java'), '-cp', str(root/'classes'), 'com.android.server.net.ct.UnlockTest'], text=True, capture_output=True)
        print(label, result.stdout, result.stderr)
        results[label] = {'exit_code': result.returncode, 'output': result.stdout, 'sha256': hashlib.sha256(candidate.read_bytes()).hexdigest()}
        (N/f'ct-unlock-{label}.log').write_text(result.stdout + result.stderr)
        if label == 'after':
            result.check_returncode()
assert results['before']['exit_code'] != 0
(N/'ct-unlock-validation.json').write_text(json.dumps({'host_android_test_doubles': True, 'cases': 15, 'results': results, 'package_built': False}, indent=2)+'\n')
