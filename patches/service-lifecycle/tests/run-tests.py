#!/usr/bin/env python3
"""Compile actual display/fingerprint lifecycle classes with fault-injectable host APIs.

Binder transport, Handler queues, system services and parcels are modeled;
the selected source classes are compiled in full.
Only the three selected framework paths are tested; removed components have
no retained test suites or source dependencies.
"""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[6]);p.add_argument('--baseline',type=Path);p.add_argument('--report',type=Path,required=True);p.add_argument('--allow-failures',action='store_true');a=p.parse_args()
r=a.root;d=r/'device/xiaomi/warsaw';jdk=r/'prebuilts/jdk/jdk21/linux-x86/bin'
files={
'android/os/RemoteException.java':'package android.os;public class RemoteException extends Exception{public RemoteException(){}public RemoteException(String s){super(s);}}',
'android/os/IInterface.java':'package android.os;public interface IInterface{IBinder asBinder();}',
'android/os/IBinder.java':'''package android.os;public interface IBinder{int FLAG_ONEWAY=1,INTERFACE_TRANSACTION=1598968902;interface DeathRecipient{void binderDied();default void binderDied(IBinder who){binderDied();}}void linkToDeath(DeathRecipient d,int f)throws RemoteException;boolean unlinkToDeath(DeathRecipient d,int f);boolean isBinderAlive();String getInterfaceDescriptor()throws RemoteException;IBinder getExtension()throws RemoteException;boolean transact(int c,Parcel d,Parcel r,int f)throws RemoteException;}''',
'android/os/Binder.java':'''package android.os;import java.util.*;public class Binder implements IBinder{
 public final List<DeathRecipient> deaths=new ArrayList<>();public String descriptor="";public boolean alive=true,failLink,throwUnlink;public int links,unlinks;public Runnable duringLink;
 public void linkToDeath(DeathRecipient d,int f)throws RemoteException{links++;if(failLink)throw new RemoteException("link failed");deaths.add(d);if(duringLink!=null)duringLink.run();}
 public boolean unlinkToDeath(DeathRecipient d,int f){unlinks++;if(throwUnlink)throw new NoSuchElementException("already removed");return deaths.remove(d);}
 public IBinder extension;public IBinder getExtension()throws RemoteException{return extension;}public boolean isBinderAlive(){return alive;}public String getInterfaceDescriptor(){return descriptor;}
 public void attachInterface(IInterface i,String n){descriptor=n;}public void markVintfStability(){}public static IBinder allowBlocking(IBinder b){return b;}
 public boolean transact(int c,Parcel d,Parcel r,int f)throws RemoteException{d.setDataPosition(0);boolean ok=onTransact(c,d,r,f);if(r!=null)r.setDataPosition(0);return ok;}
 protected boolean onTransact(int c,Parcel d,Parcel r,int f)throws RemoteException{return false;}
 public void die(){alive=false;for(var d:new ArrayList<>(deaths))d.binderDied(this);}
}''',
'android/os/Parcel.java':'''package android.os;import java.util.*;public class Parcel{private final List<Object> values=new ArrayList<>();private int pos;
 public static Parcel obtain(){return new Parcel();}public void recycle(){}private void put(Object x){if(pos==values.size())values.add(x);else values.set(pos,x);pos++;}private Object get(){if(pos>=values.size())throw new IllegalStateException("truncated");return values.get(pos++);}
 public void writeInterfaceToken(String s){writeString(s);}public void enforceInterface(String s){if(!s.equals(readString()))throw new SecurityException();}
 public void writeInt(int n){put(n);}public int readInt(){return (Integer)get();}public void writeFloat(float n){put(n);}public float readFloat(){return (Float)get();}
 public void writeString(String s){put(s);}public String readString(){return (String)get();}public void writeStrongBinder(IBinder b){put(b);}public IBinder readStrongBinder(){return (IBinder)get();}
 public void writeFloatArray(float[] a){put(a);}public int dataAvail(){return (values.size()-pos)*4;}public int dataSize(){return values.size()*4;}public int dataPosition(){return pos*4;}public void setDataPosition(int n){pos=n/4;}
 public void readException(){int e=readInt();if(e!=0)throw new IllegalStateException("remote exception "+e);}public void enforceNoDataAvail(){if(dataAvail()!=0)throw new IllegalStateException("trailing data");}
 public <T> T readTypedObject(Parcelable.Creator<T> c){return readInt()==0?null:c.createFromParcel(this);}public boolean hasFileDescriptors(){return false;}
}''',
'android/os/Parcelable.java':'package android.os;public interface Parcelable{int describeContents();void writeToParcel(Parcel p,int f);interface Creator<T>{T createFromParcel(Parcel p);T[] newArray(int n);}}',
'android/os/BadParcelableException.java':'package android.os;public class BadParcelableException extends RuntimeException{public BadParcelableException(String s){super(s);}}',
'android/os/Looper.java':'''package android.os;import java.util.*;public class Looper{static final class Task{Runnable r;long at;Task(Runnable r,long at){this.r=r;this.at=at;}}static List<Looper> all=new ArrayList<>();static Looper current;static long now;final List<Task> tasks=new ArrayList<>();boolean stopped;public Looper(){all.add(this);}public static Looper myLooper(){return current;}public static void reset(){all.clear();now=0;current=new Looper();}public static void on(Looper l,Runnable r){Looper old=current;current=l;try{r.run();}finally{current=old;}}public static void drain(){int n=0;boolean work;do{work=false;for(Looper l:new ArrayList<>(all)){Task t=null;for(Task x:l.tasks)if(x.at<=now){t=x;break;}if(t!=null){l.tasks.remove(t);on(l,t.r);work=true;if(++n>500)throw new AssertionError("unbounded queue");}}}while(work);}public static void advance(long ms){now+=ms;drain();}}''',
'android/os/Handler.java':'package android.os;public class Handler{private final Looper l;public Handler(Looper l){this.l=l;}public Looper getLooper(){return l;}public boolean post(Runnable r){return postDelayed(r,0);}public boolean postDelayed(Runnable r,long ms){if(l.stopped)return false;l.tasks.add(new Looper.Task(r,Looper.now+ms));return true;}public void removeCallbacks(Runnable r){l.tasks.removeIf(t->t.r==r);}public void removeCallbacksAndMessages(Object o){l.tasks.clear();}}',
'android/os/HandlerThread.java':'package android.os;public class HandlerThread{private final Looper l=new Looper();public HandlerThread(String n){}public void start(){}public Looper getLooper(){return l;}public void quitSafely(){l.stopped=true;l.tasks.clear();}}',
'android/os/ServiceManager.java':'package android.os;import java.util.*;public class ServiceManager{public static final Map<String,IBinder> services=new HashMap<>();public static IBinder checkService(String n){return services.get(n);}}',
'android/os/UserHandle.java':'package android.os;public class UserHandle{public static final int USER_NULL=-10000;}',
'android/os/Build.java':'package android.os;public class Build{public static final String DEVICE="warsaw";}',
'android/os/SystemProperties.java':'package android.os;public class SystemProperties{public static String get(String s){return "2.0";}}',
'android/os/PowerManager.java':'package android.os;public class PowerManager{public static final String ACTION_POWER_SAVE_MODE_CHANGED="power";public boolean isPowerSaveMode(){return false;}}',
'android/os/UserManager.java':'package android.os;import java.util.*;import android.content.pm.UserInfo;public class UserManager{public List<UserInfo> getAliveUsers(){return List.of(new UserInfo());}}',
'android/content/pm/UserInfo.java':'package android.content.pm;public class UserInfo{public int id=0;}',
'android/content/Context.java':'package android.content;public class Context{public static final int RECEIVER_EXPORTED=2;public static final String FINGERPRINT_SERVICE="fingerprint";private final ContentResolver resolver=new ContentResolver();public ContentResolver getContentResolver(){return resolver;}public String getOpPackageName(){return "android";}public <T>T getSystemService(Class<T> c){try{return c.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}public Intent registerReceiver(BroadcastReceiver b,IntentFilter f,String p,android.os.Handler h,int flags){return null;}}',
'android/content/ContentResolver.java':'package android.content;public class ContentResolver{public void registerContentObserver(Object u,boolean b,android.database.ContentObserver o,int id){}public void unregisterContentObserver(android.database.ContentObserver o){}}',
'android/content/Intent.java':'package android.content;public class Intent{public static final String ACTION_SCREEN_OFF="screen_off";public String getAction(){return "";}}',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter{public IntentFilter(String a){}public void addAction(String s){}}',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver{public abstract void onReceive(Context c,Intent i);}',
'android/database/ContentObserver.java':'package android.database;public class ContentObserver{public ContentObserver(android.os.Handler h){}public void onChange(boolean b){}}',
'android/app/ActivityManager.java':'package android.app;public class ActivityManager{public static int user;public static int getCurrentUser(){return user;}}',
'android/app/admin/DevicePolicyManager.java':'package android.app.admin;public class DevicePolicyManager{public static final int KEYGUARD_DISABLE_FINGERPRINT=1;public static final String ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED="policy";public int getKeyguardDisabledFeatures(Object c,int user){return 0;}}',
'android/telephony/TelephonyManager.java':'package android.telephony;public class TelephonyManager{public static final String ACTION_SIM_CARD_STATE_CHANGED="sim_card",ACTION_SIM_APPLICATION_STATE_CHANGED="sim_app";public static final int SIM_STATE_UNKNOWN=0,SIM_STATE_PIN_REQUIRED=2,SIM_STATE_PUK_REQUIRED=3,SIM_STATE_PERM_DISABLED=7;public int getActiveModemCount(){return 1;}public int getSimState(int slot){return 5;}}',
'android/provider/Settings.java':'package android.provider;public class Settings{public static class Secure{public static final String ACCESSIBILITY_DISPLAY_INVERSION_ENABLED="inversion";public static Object getUriFor(String s){return s;}public static int getIntForUser(android.content.ContentResolver r,String k,int fallback,int user){return fallback;}}}',
'android/hardware/biometrics/IBiometricStateListener.java':'package android.hardware.biometrics;public interface IBiometricStateListener extends android.os.IInterface{void onStateChanged(int s)throws android.os.RemoteException;void onBiometricAction(int s)throws android.os.RemoteException;void onEnrollmentsChanged(int u,int sensor,boolean e)throws android.os.RemoteException;}',
'android/hardware/biometrics/BiometricStateListener.java':'package android.hardware.biometrics;public class BiometricStateListener extends android.os.Binder implements IBiometricStateListener{public static final int STATE_IDLE=0,STATE_ENROLLING=1,STATE_KEYGUARD_AUTH=2,STATE_BP_AUTH=3,STATE_AUTH_OTHER=4;public @interface State{}public @interface Action{}public android.os.IBinder asBinder(){return this;}public void onStateChanged(int s){}public void onBiometricAction(int s){}public void onEnrollmentsChanged(int u,int sensor,boolean e){}}',
'android/hardware/biometrics/SensorPropertiesInternal.java':'package android.hardware.biometrics;public class SensorPropertiesInternal{public int sensorId=5;}',
'android/hardware/fingerprint/FingerprintSensorPropertiesInternal.java':'package android.hardware.fingerprint;public class FingerprintSensorPropertiesInternal extends android.hardware.biometrics.SensorPropertiesInternal{public boolean halHandlesDisplayTouches=true,halControlsIllumination=false;public boolean isUltrasonicUdfps(){return true;}}',
'android/hardware/fingerprint/IFingerprintService.java':'package android.hardware.fingerprint;import android.hardware.biometrics.*;import android.os.*;import java.util.*;public interface IFingerprintService extends IInterface{void registerBiometricStateListener(IBiometricStateListener l)throws RemoteException;List<FingerprintSensorPropertiesInternal> getSensorPropertiesInternal(String p)throws RemoteException;boolean isHardwareDetected(int id,String p)throws RemoteException;boolean hasEnrolledFingerprints(int id,int user,String p)throws RemoteException;class Stub{public static IFingerprintService asInterface(IBinder b){return (IFingerprintService)b;}}}',
'com/android/server/biometrics/sensors/BiometricServiceProvider.java':'package com.android.server.biometrics.sensors;import java.util.*;import android.hardware.biometrics.*;public interface BiometricServiceProvider<P extends SensorPropertiesInternal>{List<P> getSensorProperties();boolean hasEnrollments(int sensor,int user);}',
'com/android/server/biometrics/sensors/ClientMonitorCallback.java':'package com.android.server.biometrics.sensors;public interface ClientMonitorCallback{void onClientStarted(BaseClientMonitor c);void onClientFinished(BaseClientMonitor c,boolean ok);void onBiometricAction(int action);}',
'com/android/server/biometrics/sensors/BaseClientMonitor.java':'package com.android.server.biometrics.sensors;public class BaseClientMonitor{public int getTargetUserId(){return 0;}public int getSensorId(){return 5;}}',
'com/android/server/biometrics/sensors/AuthenticationClient.java':'package com.android.server.biometrics.sensors;public class AuthenticationClient<T,U> extends BaseClientMonitor{public boolean isKeyguard(){return true;}public boolean isBiometricPrompt(){return false;}}',
'com/android/server/biometrics/sensors/EnrollClient.java':'package com.android.server.biometrics.sensors;public class EnrollClient extends BaseClientMonitor{}',
'com/android/server/biometrics/sensors/EnrollmentModifier.java':'package com.android.server.biometrics.sensors;public interface EnrollmentModifier{boolean hasEnrollmentStateChanged();boolean hasEnrollments();}',
'com/android/server/biometrics/sensors/WarsawFingerprintHooks.java':'package com.android.server.biometrics.sensors;public class WarsawFingerprintHooks{public static void onClientStarted(BaseClientMonitor c){}public static void onClientFinished(BaseClientMonitor c){}}',
'com/android/server/biometrics/Utils.java':'package com.android.server.biometrics;public class Utils{public static String getClientName(Object o){return "test";}}',
'android/os/vibrator/PrebakedSegment.java':'package android.os.vibrator;public class PrebakedSegment{private final int id;public PrebakedSegment(int id){this.id=id;}public int getEffectId(){return id;}}',
'android/os/VibrationEffect.java':'package android.os;public class VibrationEffect{public static VibrationEffect get(int id,boolean fallback){return new VibrationEffect();}public VibrationEffect applyEffectStrength(int s){return this;}}',
'com/android/server/vibrator/HalVibration.java':'package com.android.server.vibrator;public class HalVibration{public static class CallerInfo{String reason,opPkg;}public final CallerInfo callerInfo=new CallerInfo();}',
}
for name in ('Log','Slog'):
    files['android/util/'+name+'.java']='package android.util;public class '+name+'{'+''.join('public static int '+method+'(String t,String s){return 0;}public static int '+method+'(String t,String s,Throwable e){return 0;}' for method in ('w','e','d','i'))+'}'
for name in ('NonNull','Nullable'):files['android/annotation/'+name+'.java']='package android.annotation;public @interface '+name+'{}'
files['host/TestSupport.java']='''package host;import android.os.*;import java.lang.reflect.*;public class TestSupport{
 public interface Checked{void run()throws Exception;}public static void check(String n,boolean ok){System.out.println(n+" "+(ok?"PASS":"FAIL"));}
 public static void test(String n,Checked c){try{c.run();check(n,true);}catch(Throwable e){check(n,false);System.out.println("DETAIL "+n+" "+e);}}
 public static void require(boolean ok){if(!ok)throw new AssertionError();}
 public static void reset(){Looper.reset();ServiceManager.services.clear();android.app.ActivityManager.user=0;}
 public static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 public static Object call(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);try{return m.invoke(o);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
}'''
files['com/android/server/display/ThermalHostTest.java']='''package com.android.server.display;import android.os.*;import java.util.*;import static host.TestSupport.*;
public class ThermalHostTest{
 static class Endpoint extends Binder{int registers,unregisters;boolean failUnregister,failRegister,emit=true;IBinder callback;Endpoint(){descriptor="vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature";}
 protected boolean onTransact(int op,Parcel p,Parcel reply,int flags)throws RemoteException{p.enforceInterface(descriptor);p.readInt();IBinder cb=p.readStrongBinder();p.enforceNoDataAvail();if(op==2){registers++;callback=cb;if(emit)event(cb,2);if(failRegister)throw new RemoteException("register reply lost");}else if(op==10){unregisters++;callback=null;if(failUnregister)throw new RemoteException("unregister reply lost");}else throw new AssertionError();reply.writeInt(0);return true;}
 void event(IBinder cb,int v)throws RemoteException{Parcel p=Parcel.obtain();p.writeInterfaceToken(descriptor+"Callback");p.writeInt(50000);p.writeInt(v);p.writeFloat(0);p.writeFloat(0);p.writeFloat(0);cb.transact(1,p,null,IBinder.FLAG_ONEWAY);}}
 static Endpoint fresh()throws Exception{reset();var f=WarsawThermalDisplayListener.class.getDeclaredField("sInstance");f.setAccessible(true);f.set(null,null);Endpoint e=new Endpoint();ServiceManager.services.put(e.descriptor+"/default",e);return e;}
 static WarsawThermalDisplayListener.Subscription sub(List<String> events){return WarsawThermalDisplayListener.subscribe(new Handler(Looper.myLooper()),(k,e)->events.add(k+":"+e));}
 public static void main(String[] args){
 test("thermal-normal-registration-and-dolby",()->{var e=fresh();var v=new ArrayList<String>();var s=sub(v);Looper.drain();require(e.registers==1&&v.contains("true:true"));s.close();Looper.drain();require(e.unregisters==1&&e.deaths.isEmpty());});
 test("thermal-ambiguous-unregister-new-subscriber-reconnects",()->{var e=fresh();var s=sub(new ArrayList<>());Looper.drain();e.failUnregister=true;s.close();Looper.drain();e.failUnregister=false;var v=new ArrayList<String>();sub(v);Looper.drain();require(e.registers==2&&e.unregisters==2&&v.contains("true:true"));});
 test("thermal-ambiguous-unregister-retains-cleanup-until-confirmed",()->{var e=fresh();var s=sub(new ArrayList<>());Looper.drain();e.failUnregister=true;s.close();Looper.drain();var v=new ArrayList<String>();sub(v);Looper.drain();require(e.unregisters==2&&e.registers==1&&!v.contains("true:true"));e.failUnregister=false;Looper.advance(1000);require(e.registers==2&&v.contains("true:true"));});
 test("thermal-old-slot-events-ignored-after-failed-unregister",()->{var e=fresh();var s=sub(new ArrayList<>());Looper.drain();var old=e.callback;e.failUnregister=true;s.close();Looper.drain();var v=new ArrayList<String>();sub(v);Looper.drain();e.event(old,2);Looper.drain();require(!v.contains("true:true"));});
 test("thermal-register-reply-loss-retries-cleanup",()->{var e=fresh();e.failRegister=true;var v=new ArrayList<String>();sub(v);Looper.drain();require(!v.contains("true:true"));e.failRegister=false;Looper.advance(1000);require(e.registers==2&&e.unregisters==1&&v.contains("true:true"));});
 test("thermal-closed-subscription-drops-queued-state",()->{var e=fresh();var v=new ArrayList<String>();var s=sub(v);s.close();Looper.drain();require(e.registers==0&&v.isEmpty());});
 test("thermal-binder-death-reconnects",()->{var e=fresh();var v=new ArrayList<String>();sub(v);Looper.drain();Endpoint next=new Endpoint();ServiceManager.services.put(e.descriptor+"/default",next);e.die();Looper.drain();require(next.registers==1&&v.get(v.size()-1).equals("true:true"));});
 test("thermal-same-state-coalesces",()->{var e=fresh();var v=new ArrayList<String>();sub(v);Looper.drain();int n=v.size();e.event(e.callback,2);e.event(e.callback,2);Looper.drain();require(v.size()==n);e.event(e.callback,0);Looper.drain();require(v.get(v.size()-1).equals("true:false"));});
 }
}'''
files['com/android/server/biometrics/sensors/BiometricHostTest.java']='''package com.android.server.biometrics.sensors;import android.os.*;import android.hardware.biometrics.*;import java.util.*;import java.util.concurrent.*;import static host.TestSupport.*;
public class BiometricHostTest{
 public static class Provider implements BiometricServiceProvider<SensorPropertiesInternal>{public boolean fail;public List<SensorPropertiesInternal> getSensorProperties(){return List.of(new SensorPropertiesInternal());}public boolean hasEnrollments(int s,int u){if(fail)throw new IllegalStateException("provider not ready");return true;}}
 public static class Listener extends BiometricStateListener{public int actions,enrollments;public void onBiometricAction(int s){actions++;}public void onEnrollmentsChanged(int u,int s,boolean e){enrollments++;}}
 static BiometricStateCallback<Provider,SensorPropertiesInternal> fresh(){return new BiometricStateCallback<>(new UserManager());}
 public static int size(BiometricStateCallback<?,?> c)throws Exception{return ((List<?>)field(c,"mBiometricStateListeners")).size();}
 public static void main(String[] args){
 test("biometric-register-and-initial-snapshot",()->{var c=fresh();c.start(List.of(new Provider()));var l=new Listener();c.registerBiometricStateListener(l);c.onBiometricAction(1);require(size(c)==1&&l.links==1&&l.enrollments==1&&l.actions==1);});
 test("biometric-same-listener-registration-idempotent",()->{var c=fresh();var l=new Listener();c.registerBiometricStateListener(l);c.registerBiometricStateListener(l);c.onBiometricAction(1);require(size(c)==1&&l.links==1&&l.actions==1);});
 test("biometric-deduplicates-binder-identity",()->{var c=fresh();var l=new Listener();var alias=new Listener(){public IBinder asBinder(){return l;}};c.registerBiometricStateListener(l);c.registerBiometricStateListener(alias);c.onBiometricAction(1);require(size(c)==1&&l.actions==1&&alias.actions==0);});
 test("biometric-dead-on-register-not-retained",()->{var c=fresh();var l=new Listener();l.failLink=true;c.registerBiometricStateListener(l);c.onBiometricAction(1);require(size(c)==0&&l.actions==0);});
 test("biometric-link-failure-no-initial-delivery",()->{var c=fresh();c.start(List.of(new Provider()));var l=new Listener();l.failLink=true;c.registerBiometricStateListener(l);require(l.enrollments==0);});
 test("biometric-register-death-race-does-not-resurrect-listener",()->{var c=fresh();var l=new Listener();Thread[] t={null};CountDownLatch done=new CountDownLatch(1);l.duringLink=()->{t[0]=new Thread(()->{c.binderDied(l);done.countDown();});t[0].start();try{done.await(80,TimeUnit.MILLISECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}};c.registerBiometricStateListener(l);t[0].join(1000);require(!t[0].isAlive()&&size(c)==0);});
 test("biometric-snapshot-retry-keeps-one-listener",()->{var c=fresh();var p=new Provider();c.start(List.of(p));p.fail=true;var l=new Listener();try{c.registerBiometricStateListener(l);}catch(IllegalStateException expected){}p.fail=false;c.registerBiometricStateListener(l);c.onBiometricAction(1);require(size(c)==1&&l.links==1&&l.actions==1&&l.enrollments==1);});
 test("biometric-death-does-not-remove-other-listener",()->{var c=fresh();var a=new Listener();var b=new Listener();c.registerBiometricStateListener(a);c.registerBiometricStateListener(b);c.binderDied(a);c.onBiometricAction(1);require(size(c)==1&&a.actions==0&&b.actions==1);});
 test("biometric-authentication-state-unchanged",()->{var c=fresh();var auth=new AuthenticationClient<>();c.onClientStarted(auth);require(c.getBiometricState()==BiometricStateListener.STATE_KEYGUARD_AUTH);c.onClientFinished(auth,true);require(c.getBiometricState()==BiometricStateListener.STATE_IDLE);});
 test("biometric-enrollment-state-unchanged",()->{var c=fresh();var e=new EnrollClient();c.onClientStarted(e);require(c.getBiometricState()==BiometricStateListener.STATE_ENROLLING);c.onClientFinished(e,false);require(c.getBiometricState()==BiometricStateListener.STATE_IDLE);});
 }
}'''
files['com/android/server/vibrator/HapticHostTest.java']='''package com.android.server.vibrator;import android.os.*;import android.os.vibrator.*;import java.util.*;import java.util.function.*;import static host.TestSupport.*;
public class HapticHostTest{
 static final String PREFIX="TAG=haptic_feedback_infinite_intensity;intensity=";
 static class Extension extends Binder{List<Float> gains=new ArrayList<>();boolean failApply,failClear,reject;Extension(){descriptor="vendor.hardware.vibratorfeature.IVibratorExt";}protected boolean onTransact(int op,Parcel p,Parcel reply,int flags)throws RemoteException{require(op==5&&flags==0);p.enforceInterface(descriptor);float value=p.readFloat();require(p.readInt()==1);p.enforceNoDataAvail();gains.add(value);if(value>=0&&failApply)throw new RemoteException("gain applied, reply lost");if(value<0&&failClear)throw new RemoteException("cleanup unavailable");if(reject)return false;reply.writeInt(0);return true;}}
 static Extension setup(){reset();var e=new Extension();var hal=new Binder();hal.extension=e;ServiceManager.services.put("android.hardware.vibrator.IVibrator/default",hal);return e;}
 static HalVibration vibration(String reason,String pkg){var v=new HalVibration();v.callerInfo.reason=reason;v.callerInfo.opPkg=pkg;return v;}
 static long perform(String gain,int effect,LongSupplier action){return WarsawHapticHooks.perform(new PrebakedSegment(effect),vibration(PREFIX+gain+";effect=1","com.android.camera"),action);}
 public static void main(String[] args){
 test("haptic-normal-gain-perform-and-reset",()->{var e=setup();int[] calls={0};require(perform("0.7",1,()->{calls[0]++;return 42;})==42&&calls[0]==1&&e.gains.equals(List.of(0.7f,-1f)));});
 test("haptic-ambiguous-gain-failure-still-resets",()->{var e=setup();e.failApply=true;int[] calls={0};require(perform("0.7",1,()->{calls[0]++;return 42;})==0&&calls[0]==0&&e.gains.equals(List.of(0.7f,-1f)));});
 test("haptic-ambiguous-gain-and-reset-failure-contained",()->{var e=setup();e.failApply=true;e.failClear=true;require(perform("0.7",1,()->{throw new AssertionError("must not perform");})==0&&e.gains.equals(List.of(0.7f,-1f)));});
 test("haptic-native-perform-failure-still-resets",()->{var e=setup();require(perform("1.5",1,()->{throw new IllegalStateException("native failed");})==0&&e.gains.equals(List.of(1.5f,-1f)));});
 test("haptic-reset-failure-preserves-perform-result",()->{var e=setup();e.failClear=true;require(perform("0",1,()->17)==17&&e.gains.equals(List.of(0f,-1f)));});
 test("haptic-other-caller-keeps-native-behavior",()->{var e=setup();require(WarsawHapticHooks.perform(new PrebakedSegment(1),vibration(PREFIX+"0.7;x","other.app"),()->9)==9&&e.gains.isEmpty());});
 test("haptic-ordinary-camera-effect-keeps-native-behavior",()->{var e=setup();require(WarsawHapticHooks.perform(new PrebakedSegment(1),vibration("ordinary","com.android.camera"),()->11)==11&&e.gains.isEmpty());});
 test("haptic-invalid-gain-does-not-touch-hal",()->{var e=setup();for(String gain:List.of("NaN","Infinity","-0.1","1.6","invalid"))require(perform(gain,1,()->{throw new AssertionError();})==0);require(e.gains.isEmpty());});
 test("haptic-unsupported-effect-does-not-touch-hal",()->{var e=setup();require(perform("0.7",99999,()->{throw new AssertionError();})==0&&e.gains.isEmpty());});
 test("haptic-missing-and-wrong-extension-rejected",()->{var e=setup();e.descriptor="wrong";require(perform("0.7",1,()->{throw new AssertionError();})==0&&e.gains.isEmpty());ServiceManager.services.clear();require(perform("0.7",1,()->{throw new AssertionError();})==0);});
 }
}'''
production={
 'com/android/server/display/WarsawThermalDisplayListener.java':'device/patches/display-thermal/WarsawThermalDisplayListener.java',
 'com/android/server/biometrics/sensors/BiometricStateCallback.java':'frameworks/base/services/core/java/com/android/server/biometrics/sensors/BiometricStateCallback.java',
 'com/android/server/vibrator/WarsawHapticHooks.java':'device/patches/camera-framework/WarsawHapticHooks.java',
 'android/os/vibrator/WarsawHapticPatterns.java':'device/patches/camera-framework/WarsawHapticPatterns.java',
}
hashes={}
for dest,rel in production.items():
    src=(d/rel.removeprefix('device/')) if rel.startswith('device/') else r/rel
    if a.baseline and (a.baseline/rel).exists():src=a.baseline/rel
    files[dest]=src.read_text();hashes[str(src)]=hashlib.sha256(src.read_bytes()).hexdigest()
rows=[];outputs=[]
with tempfile.TemporaryDirectory(prefix='warsaw-service-lifecycle-') as directory:
    temp=Path(directory);sources=[]
    for name,body in files.items():
        f=temp/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(body);sources.append(str(f))
    subprocess.run([str(jdk/'javac'),'-d',str(temp/'classes'),*sources],check=True)
    for main in ['com.android.server.display.ThermalHostTest','com.android.server.biometrics.sensors.BiometricHostTest','com.android.server.vibrator.HapticHostTest']:
        p=subprocess.run([str(jdk/'java'),'-cp',str(temp/'classes'),main],text=True,capture_output=True,timeout=30)
        if p.returncode:raise RuntimeError(p.stdout+p.stderr)
        print(p.stdout,end='');outputs.append(p.stdout)
        for line in p.stdout.splitlines():
            if line.endswith(' PASS') or line.endswith(' FAIL'):rows.append({'suite':main,'case':line.rsplit(' ',1)[0],'passed':line.endswith(' PASS')})
for row in rows:
    row['current_product_scope'] = 'active_source'
scope_counts = {'active_source': {'passed': sum(row['passed'] for row in rows),
                                  'failed': sum(not row['passed'] for row in rows)}}
report={'scope_counts':scope_counts,'scope_review_date':'2026-10-26','cases':rows,'passed':sum(x['passed'] for x in rows),'failed':sum(not x['passed'] for x in rows),'source_sha256':hashes,'android_apis_modeled':True,'full_production_classes_compiled':True,'android_build':False,'device_tested':False}
a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n');a.report.with_suffix('.log').write_text(''.join(outputs))
if report['failed'] and not a.allow_failures:raise SystemExit(1)
