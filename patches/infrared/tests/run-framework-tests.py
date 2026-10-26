#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the production ConsumerIrService with host Android API doubles.

The harness compiles temporary Java classes and exercises IR service
reconnection and wakelock state transitions.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

STUBS = {
    "android/Manifest.java": """package android;
public class Manifest { public static class permission {
 public static final String TRANSMIT_IR="android.permission.TRANSMIT_IR";
} }""",
    "android/annotation/EnforcePermission.java": """package android.annotation;
public @interface EnforcePermission { String value(); }""",
    "android/annotation/RequiresNoPermission.java": """package android.annotation;
public @interface RequiresNoPermission {}""",
    "android/content/Context.java": """package android.content;
import android.content.pm.PackageManager;
public abstract class Context {
 public static final String POWER_SERVICE="power";
 public abstract Object getSystemService(String name);
 public abstract PackageManager getPackageManager();
}""",
    "android/content/pm/PackageManager.java": """package android.content.pm;
public class PackageManager {
 public static final String FEATURE_CONSUMER_IR="android.hardware.consumerir";
 public boolean feature=true;
 public boolean hasSystemFeature(String name) { return feature; }
}""",
    "android/os/IBinder.java": """package android.os;
public interface IBinder { boolean isBinderAlive(); }""",
    "android/os/RemoteException.java": """package android.os;
public class RemoteException extends Exception {}""",
    "android/os/DeadObjectException.java": """package android.os;
public class DeadObjectException extends RemoteException {}""",
    "android/os/ServiceSpecificException.java": """package android.os;
public class ServiceSpecificException extends RuntimeException {
 public final int errorCode;
 public ServiceSpecificException(int code) { errorCode=code; }
}""",
    "android/os/ServiceManager.java": """package android.os;
public class ServiceManager {
 public static IBinder service;
 public static int waits, checks;
 public static IBinder waitForDeclaredService(String name) { waits++; return service; }
 public static IBinder checkService(String name) { checks++; return service; }
 public static void reset(IBinder initial) { service=initial; waits=0; checks=0; }
}""",
    "android/os/PowerManager.java": """package android.os;
public class PowerManager {
 public static final int PARTIAL_WAKE_LOCK=1;
 public final WakeLock lock=new WakeLock();
 public WakeLock newWakeLock(int type,String tag) { return lock; }
 public static class WakeLock {
  public int acquired, released, held, maximum;
  public void setReferenceCounted(boolean enabled) {}
  public synchronized void acquire() { acquired++; held++; maximum=Math.max(maximum,held); }
  public synchronized void release() {
   if (held<=0) throw new AssertionError("Unbalanced wake lock release");
   released++; held--;
  }
  public synchronized boolean isHeld() { return held>0; }
 }
}""",
    "android/hardware/IConsumerIrService.java": """package android.hardware;
public interface IConsumerIrService {
 boolean hasIrEmitter();
 void transmit(String name,int frequency,int[] pattern);
 int[] getCarrierFrequencies();
 abstract class Stub implements IConsumerIrService {
  public boolean permitted=true;
  protected void transmit_enforcePermission() { if (!permitted) throw new SecurityException(); }
  protected void getCarrierFrequencies_enforcePermission() { if (!permitted) throw new SecurityException(); }
 }
}""",
    "android/hardware/ir/ConsumerIrFreqRange.java": """package android.hardware.ir;
public class ConsumerIrFreqRange { public int minHz,maxHz; }""",
    "android/hardware/ir/IConsumerIr.java": """package android.hardware.ir;
import android.os.*;
public interface IConsumerIr {
 String DESCRIPTOR="android.hardware.ir.IConsumerIr";
 IBinder asBinder();
 void transmit(int frequency,int[] pattern) throws RemoteException;
 ConsumerIrFreqRange[] getCarrierFreqs() throws RemoteException;
 abstract class Stub {
  public static IConsumerIr asInterface(IBinder binder) { return (IConsumerIr)binder; }
 }
}""",
    "android/util/Slog.java": """package android.util;
public class Slog {
 public static void e(String tag,String message) {}
 public static void e(String tag,String message,Throwable error) {}
}""",
}


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[5])
    parser.add_argument("--source", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    source = args.source or args.android_root / (
        "frameworks/base/services/core/java/com/android/server/ConsumerIrService.java")
    java = args.android_root / "prebuilts/jdk/jdk21/linux-x86/bin"
    with tempfile.TemporaryDirectory(prefix="warsaw-ir-service-") as directory:
        tmp = Path(directory)
        for name, content in STUBS.items():
            path = tmp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content + "\n")
        production = tmp / "ConsumerIrService.java"
        production.write_bytes(source.read_bytes())
        classes = tmp / "classes"
        classes.mkdir()
        subprocess.run([str(java / "javac"), "-d", str(classes),
                        *[str(tmp / name) for name in STUBS], str(production),
                        str(here / "ConsumerIrServiceHostTest.java")], check=True)
        result = subprocess.run([str(java / "java"), "-cp", str(classes),
                                 "com.android.server.ConsumerIrServiceHostTest"],
                                text=True, capture_output=True, timeout=45)
        print(result.stdout, end="")
        print(result.stderr, end="")
        cases = [{"passed": line.startswith("PASS "), "case": line[5:]}
                 for line in result.stdout.splitlines()
                 if line.startswith(("PASS ", "FAIL "))]
        if args.report:
            args.report.write_text(json.dumps({
                "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "cases": cases, "failures": sum(not c["passed"] for c in cases),
                "exit_code": result.returncode,
                "scope": "Whole production Java class; host Binder/power/service doubles only",
                "device_tested": False, "platform_build_performed": False,
            }, indent=2) + "\n")
        raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
