#!/usr/bin/env python3
"""Compile only the lifecycle class with deterministic Android test doubles.

No platform build, APK, JAR, image or install archive is produced. These checks
exercise state/callback ordering, not an actual Android ServiceManager process.
"""
from pathlib import Path
import argparse
import subprocess
import tempfile

STUBS = {
    "android/content/ComponentName.java": """package android.content;
public class ComponentName {
 private final String pkg;
 public ComponentName(String pkg, String cls) { this.pkg = pkg; }
 public String getPackageName() { return pkg; }
}""",
    "android/content/Intent.java": """package android.content;
public class Intent { public Intent setComponent(ComponentName n) { return this; } }""",
    "android/content/ServiceConnection.java": """package android.content;
import android.os.IBinder;
public interface ServiceConnection {
 void onServiceConnected(ComponentName n, IBinder b);
 void onServiceDisconnected(ComponentName n);
 default void onBindingDied(ComponentName n) {}
 default void onNullBinding(ComponentName n) {}
}""",
    "android/content/Context.java": """package android.content;
import android.content.pm.PackageManager; import android.os.*;
public abstract class Context {
 public static final int BIND_AUTO_CREATE=1, BIND_IMPORTANT=64;
 public abstract PackageManager getPackageManager();
 public abstract boolean bindServiceAsUser(Intent i, ServiceConnection c, int f, Handler h, UserHandle u);
 public abstract void unbindService(ServiceConnection c);
}""",
    "android/content/pm/ApplicationInfo.java": """package android.content.pm;
public class ApplicationInfo {
 public boolean enabled=true; public int uid=10000; public String packageName="com.xiaomi.cameramind";
 public boolean isSystemApp() { return true; }
}""",
    "android/content/pm/ServiceInfo.java": """package android.content.pm;
public class ServiceInfo { public boolean enabled=true; public ApplicationInfo applicationInfo=new ApplicationInfo(); }""",
    "android/content/pm/PackageManager.java": """package android.content.pm;
import android.content.ComponentName;
public class PackageManager {
 public static final int MATCH_DIRECT_BOOT_AWARE=1, MATCH_DIRECT_BOOT_UNAWARE=2, SIGNATURE_MATCH=0;
 public boolean trusted=true;
 public ServiceInfo getServiceInfo(ComponentName n,int flags) throws NameNotFoundException { return new ServiceInfo(); }
 public int checkSignatures(int a,int b) { return trusted ? SIGNATURE_MATCH : -1; }
 public static class NameNotFoundException extends Exception {}
}""",
    "android/os/IBinder.java": """package android.os; public interface IBinder { boolean isBinderAlive(); }""",
    "android/os/Process.java": """package android.os; public class Process { public static final int SYSTEM_UID=1000; }""",
    "android/os/SystemProperties.java": """package android.os;
public class SystemProperties { public static boolean enabled=true; public static boolean getBoolean(String n,boolean d) { return enabled; } }""",
    "android/os/UserHandle.java": """package android.os;
public class UserHandle {
 public static final int USER_SYSTEM=0; public static final UserHandle SYSTEM=new UserHandle();
 public static int getUserId(int uid) { return uid/100000; }
}""",
    "android/util/Slog.java": """package android.util;
public class Slog { public static void e(String t,String m) {} public static void e(String t,String m,Throwable x) {}
 public static void w(String t,String m,Throwable x) {} }""",
    "android/os/Handler.java": """package android.os;
import java.util.*;
public class Handler {
 private static class Task { Runnable run; long time, order; Task(Runnable r,long t,long o) { run=r; time=t; order=o; } }
 private final PriorityQueue<Task> tasks=new PriorityQueue<>(Comparator.comparingLong((Task t)->t.time).thenComparingLong(t->t.order));
 private long time, order;
 public boolean post(Runnable r) { return postDelayed(r,0); }
 public boolean postDelayed(Runnable r,long delay) { tasks.add(new Task(r,time+delay,order++)); return true; }
 public void removeCallbacks(Runnable r) { tasks.removeIf(t->t.run==r); }
 public void advance(long delta) {
  long end=time+delta; int steps=0;
  while (!tasks.isEmpty() && tasks.peek().time<=end) {
   if (++steps>10000) throw new AssertionError("Runaway retry loop");
   Task task=tasks.remove(); time=task.time; task.run.run();
  }
  time=end;
 }
}""",
}


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[5])
    parser.add_argument("--source", type=Path, default=here.parent / "WarsawCameraMindLifecycle.java")
    args = parser.parse_args()
    java = args.android_root / "prebuilts/jdk/jdk21/linux-x86/bin"
    with tempfile.TemporaryDirectory(prefix="warsaw-lifecycle-") as directory:
        tmp = Path(directory)
        for name, content in STUBS.items():
            path = tmp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content + "\n")
        source = tmp / "WarsawCameraMindLifecycle.java"
        source.write_bytes(args.source.read_bytes())
        classes = tmp / "classes"
        classes.mkdir()
        subprocess.run([str(java / "javac"), "-d", str(classes),
            *[str(tmp / name) for name in STUBS], str(source),
            str(here / "WarsawCameraMindLifecycleTest.java")], check=True)
        result = subprocess.run([str(java / "java"), "-cp", str(classes),
            "com.android.server.warsaw.WarsawCameraMindLifecycleTest"])
        raise SystemExit(result.returncode)

if __name__ == "__main__":
    main()
