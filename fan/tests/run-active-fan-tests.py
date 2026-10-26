from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile
p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[5])
p.add_argument('--service',type=Path);p.add_argument('--controller',type=Path);p.add_argument('--report',type=Path,required=True);p.add_argument('--allow-failures',action='store_true');a=p.parse_args()
root=a.source_root;fan=root/'device/xiaomi/warsaw/fan/src/org/lineageos/warsaw/fan';jdk=root/'prebuilts/jdk/jdk21/linux-x86/bin'
service=a.service or fan/'FanService.java';controller=a.controller or fan/'FanPwmController.java'
files={
'android/content/ContentResolver.java':'package android.content; public class ContentResolver { public void registerContentObserver(Object u,boolean b,android.database.ContentObserver o,int user){} public void unregisterContentObserver(android.database.ContentObserver o){} }',
'android/content/Context.java':'package android.content; public class Context { public ContentResolver getContentResolver(){return new ContentResolver();} public Intent registerReceiver(BroadcastReceiver b,IntentFilter f,String p,android.os.Handler h){return null;}public void unregisterReceiver(BroadcastReceiver b){} }',
'android/content/Intent.java':'package android.content; public class Intent {public static final String ACTION_USER_SWITCHED="switch",ACTION_USER_UNLOCKED="unlock";}',
'android/content/IntentFilter.java':'package android.content; public class IntentFilter {public IntentFilter(String s){}public void addAction(String s){}}',
'android/content/BroadcastReceiver.java':'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/database/ContentObserver.java':'package android.database; public class ContentObserver {public ContentObserver(android.os.Handler h){} public void onChange(boolean b){}}',
'android/os/HandlerThread.java':'package android.os;public class HandlerThread{boolean stopped;public HandlerThread(String n){}public void start(){}public Object getLooper(){return this;}public void quitSafely(){stopped=true;}}',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {
 public static final ArrayDeque<Runnable> ready=new ArrayDeque<>();public static final Map<Runnable,Long> delayed=new LinkedHashMap<>();
 private final HandlerThread looper;public Handler(Object l){looper=(HandlerThread)l;}public boolean post(Runnable r){if(looper.stopped)return false;ready.add(r);return true;}
 public boolean postDelayed(Runnable r,long t){if(looper.stopped)return false;if(t==0)return post(r);delayed.put(r,t);return true;}
 public void removeCallbacks(Runnable r){ready.removeIf(x->x==r);delayed.remove(r);}
 public void removeCallbacksAndMessages(Object o){ready.clear();delayed.clear();}
 public static void reset(){ready.clear();delayed.clear();}public static void drain(){int max=50;while(!ready.isEmpty()){if(--max==0)throw new AssertionError("loop");ready.remove().run();}}
}''',
'android/os/IBinder.java':'package android.os;public interface IBinder{}',
'android/os/UserHandle.java':'package android.os;public class UserHandle{public static final int USER_ALL=-1;}',
'android/app/ActivityManager.java':'package android.app;public class ActivityManager{public static int user=0;public static int getCurrentUser(){return user;}}',
'android/app/Service.java':'package android.app; public class Service extends android.content.Context {public static final int START_STICKY=1;public void onCreate(){}public int onStartCommand(android.content.Intent i,int f,int id){return 0;}public android.os.IBinder onBind(android.content.Intent i){return null;}public void onDestroy(){}}',
'android/util/Log.java':'package android.util;public class Log{public static int e(String t,String m,Throwable e){return 0;}}',
'android/provider/Settings.java':'''package android.provider;import android.content.*;import java.util.*;public class Settings {
 public static class System {public static int enabled=1,duty=50;public static Runnable afterDuty;
 public static Object getUriFor(String s){return s;}public static int getIntForUser(ContentResolver r,String key,int d,int user){
 if(key.equals("cooling_fan_enable"))return enabled;int v=user==0?duty:75;if(afterDuty!=null){var f=afterDuty;afterDuty=null;f.run();}return v;}}
 public static class Global {public static boolean save=true,statusSave=true,statusThrow;public static int attempts;public static Map<String,Object> values=new HashMap<>();
 public static boolean putInt(ContentResolver r,String k,int v){attempts++;if(!save)return false;values.put(k,v);return true;}
 public static boolean putString(ContentResolver r,String k,String v){if(statusThrow)throw new IllegalStateException("settings unavailable");if(!statusSave)return false;values.put(k,v);return true;}}
}''',
'org/lineageos/warsaw/fan/FanPwmController.java':'''package org.lineageos.warsaw.fan;import java.util.*;import java.util.function.*;import java.util.concurrent.*;
class FanPwmController {static List<Integer> writes=new ArrayList<>();static Runnable during;static boolean fail;
 int apply(int n)throws java.io.IOException{return apply(n,()->true);}
 int apply(int n,BooleanSupplier current)throws java.io.IOException{
 if(during!=null){var f=during;during=null;f.run();}if(!current.getAsBoolean())throw new CancellationException();
 if(fail)throw new java.io.IOException();writes.add(n);return n;}}
''',
'org/lineageos/warsaw/fan/ActiveFanHostTest.java':'''package org.lineageos.warsaw.fan;
import android.app.*;import android.os.*;import android.provider.Settings;import java.util.*;
public class ActiveFanHostTest {
 static void check(String n,boolean ok){java.lang.System.out.println(n+" "+(ok?"PASS":"FAIL"));}
 static FanService fresh(){Handler.reset();ActivityManager.user=0;Settings.System.duty=50;Settings.System.enabled=1;Settings.System.afterDuty=null;Settings.Global.save=true;Settings.Global.statusSave=true;Settings.Global.statusThrow=false;Settings.Global.attempts=0;Settings.Global.values.clear();FanPwmController.writes.clear();FanPwmController.during=null;FanPwmController.fail=false;var s=new FanService();s.onCreate();return s;}
 static Object value(String k){return Settings.Global.values.get(k);}
 public static void main(String[] args){
 {var s=fresh();Handler.drain();check("active-default-pwm",FanPwmController.writes.equals(List.of(50))&&Integer.valueOf(50).equals(value("warsaw_fan_confirmed_pwm"))&&"ready".equals(value("warsaw_fan_status")));s.onDestroy();}
 {var s=fresh();Settings.Global.save=false;Handler.drain();check("publish-failure-not-ready",!"ready".equals(value("warsaw_fan_status")));
 Settings.Global.save=true;s.onStartCommand(null,0,0);Handler.drain();check("publish-failure-retried",Settings.Global.attempts==2&&Integer.valueOf(50).equals(value("warsaw_fan_confirmed_pwm")));s.onDestroy();}
 {var s=fresh();FanPwmController.fail=true;Handler.drain();check("hardware-failure-not-ready","unavailable".equals(value("warsaw_fan_status"))&&FanPwmController.writes.isEmpty());s.onDestroy();}
 {var s=fresh();Settings.System.enabled=0;Handler.drain();check("disabled-writes-zero",FanPwmController.writes.equals(List.of(0)));s.onDestroy();}
 {var s=fresh();Settings.System.duty=200;Handler.drain();check("duty-clamped",FanPwmController.writes.equals(List.of(100)));s.onDestroy();}
 {var s=fresh();Settings.System.afterDuty=()->ActivityManager.user=1;Handler.drain();check("user-change-before-write",FanPwmController.writes.equals(List.of(75)));s.onDestroy();}
 {var s=fresh();FanPwmController.during=()->ActivityManager.user=1;Handler.drain();check("user-change-during-hardware-operation",FanPwmController.writes.equals(List.of(75)));s.onDestroy();}
 {var s=fresh();s.onDestroy();Handler.drain();check("destroy-cancels-queued-work",FanPwmController.writes.isEmpty());}
 {var s=fresh();FanPwmController.during=s::onDestroy;Handler.drain();check("destroy-stops-in-flight-write",FanPwmController.writes.isEmpty());check("destroy-prevents-late-ready",!"ready".equals(value("warsaw_fan_status")));check("destroy-prevents-reschedule",Handler.ready.isEmpty()&&Handler.delayed.isEmpty());}
 {var s=fresh();FanPwmController.during=()->{Settings.System.duty=75;s.onStartCommand(null,0,0);};Handler.drain();check("new-request-during-apply-not-lost",FanPwmController.writes.equals(List.of(50,75)));s.onDestroy();}
 {var s=fresh();Handler.drain();FanPwmController.fail=true;Settings.Global.statusSave=false;s.onStartCommand(null,0,0);Handler.drain();check("failed-status-save-keeps-error-retry",Handler.delayed.containsValue(5000L));s.onDestroy();}
 {var s=fresh();Settings.Global.statusThrow=true;boolean crashed=false;try{Handler.drain();}catch(RuntimeException e){crashed=true;}check("status-provider-exception-contained",!crashed&&Handler.delayed.containsValue(5000L));Settings.Global.statusThrow=false;s.onStartCommand(null,0,0);Handler.drain();check("status-provider-recovers","ready".equals(value("warsaw_fan_status")));s.onDestroy();}
 }
}'''
}
def extract(text,signature):
    start=text.index(signature);i=text.index('{',start)+1;depth=1
    while depth:depth+=(text[i]=='{')-(text[i]=='}');i+=1
    return text[start:i]
real=controller.read_text();method=extract(real,'    int apply(')
if 'BooleanSupplier' not in method:
    # Keep the original method and expose the new caller shape only in the baseline harness.
    adapter='int guarded(int duty,BooleanSupplier current)throws IOException{return apply(duty);}'
else:
    adapter='int guarded(int duty,BooleanSupplier current)throws IOException{return apply(duty,current);}'
extra=''
if 'checkCurrent(' in real:extra=extract(real,'    private void checkCurrent(')
files['org/lineageos/warsaw/fan/PwmApplyHostTest.java']='''package org.lineageos.warsaw.fan;
import java.io.*;import java.util.*;import java.util.function.*;import java.util.concurrent.*;
public class PwmApplyHostTest {Map<String,Integer> values=new HashMap<>();List<String> writes=new ArrayList<>();int calls;int cancelAfter=Integer.MAX_VALUE;
 int read(String n)throws IOException{return values.get(n);}void write(String n,int v)throws IOException{writes.add(n+"="+v);values.put(n,v);}
 boolean current(){return ++calls<=cancelAfter;}
'''+method+extra+adapter+'''
 static void check(String n,boolean ok){System.out.println(n+" "+(ok?"PASS":"FAIL"));}
 static PwmApplyHostTest fresh(){var p=new PwmApplyHostTest();p.values.put("fan_support",1);p.values.put("target_level",2);p.values.put("pwm_duty",0);return p;}
 public static void main(String[] args)throws Exception{
 {var p=fresh();check("controller-reset-sequence",p.guarded(50,()->true)==50&&p.writes.equals(List.of("target_level=100","target_level=0","pwm_duty=50")));}
 {var p=fresh();boolean rejected=false;try{p.guarded(50,()->false);}catch(CancellationException e){rejected=true;}check("controller-cancel-before-any-write",rejected&&p.writes.isEmpty());}
 for(int boundary=1;boundary<=2;boundary++){var p=fresh();final int limit=boundary;boolean cancelled=false;
 try{p.guarded(50,()->p.writes.size()<limit);}catch(CancellationException e){cancelled=true;}
 check("controller-cancel-between-writes-"+boundary,cancelled&&p.writes.size()==limit);}
 {var p=fresh();p.values.put("target_level",0);p.values.put("pwm_duty",50);check("controller-no-unneeded-write",p.guarded(50,()->true)==50&&p.writes.isEmpty());}
 {var p=fresh();p.values.put("fan_support",0);boolean failed=false;try{p.guarded(50,()->true);}catch(IOException e){failed=true;}check("controller-unsupported",failed&&p.writes.isEmpty());}
 {var p=fresh();boolean failed=false;try{p.guarded(101,()->true);}catch(IllegalArgumentException e){failed=true;}check("controller-bounds",failed&&p.writes.isEmpty());}
 }
}'''
files['org/lineageos/warsaw/fan/FanPreferences.java']=(fan/'FanPreferences.java').read_text()
files['org/lineageos/warsaw/fan/FanService.java']=service.read_text()
rows=[]
with tempfile.TemporaryDirectory(prefix='warsaw-active-fan-') as d:
    temp=Path(d);sources=[]
    for name,body in files.items():
        f=temp/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(body);sources.append(str(f))
    subprocess.run([str(jdk/'javac'),'-d',str(temp/'classes'),*sources],check=True)
    for main in ['ActiveFanHostTest','PwmApplyHostTest']:
        run=subprocess.check_output([str(jdk/'java'),'-cp',str(temp/'classes'),'org.lineageos.warsaw.fan.'+main],text=True)
        print(run,end='')
        rows += [{'suite':main,'case':line.rsplit(' ',1)[0],'passed':line.endswith(' PASS')} for line in run.splitlines()]
report={'cases':rows,'passed':sum(r['passed'] for r in rows),'failed':sum(not r['passed'] for r in rows),
        'source_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (service,controller,fan/'FanPreferences.java')},
        'active_product_classes':True,'actual_service_and_controller_apply_logic':True,'android_apis_and_sysfs_io_modeled':True,'android_build':False,'device_tested':False}
a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
if report['failed'] and not a.allow_failures:raise SystemExit(1)
