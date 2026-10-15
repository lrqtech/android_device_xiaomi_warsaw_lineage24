package com.android.server.warsaw;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;

/** Starts the stock CameraMind engine using its OEM binding contract. @hide */
final class WarsawCameraMindLifecycle {
    private static final String TAG = "WarsawCameraMind";
    private static final ComponentName SERVICE = new ComponentName(
            "com.xiaomi.cameramind", "com.xiaomi.cameramind.CameraMindService");
    private final Context context;
    private final Handler worker;
    private final Runnable retry = this::bind;
    private Connection connection;
    private long delayMs = 500;

    WarsawCameraMindLifecycle(Context context, Handler worker) {
        this.context = context;
        this.worker = worker;
    }

    void start() {
        if (SystemProperties.getBoolean("persist.sys.miui.camera.cameramind.supported", false)) {
            worker.post(retry);
        }
    }

    private boolean trustedService() {
        try {
            PackageManager pm = context.getPackageManager();
            ServiceInfo info = pm.getServiceInfo(SERVICE,
                    PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
            return info.enabled && info.applicationInfo.enabled
                    && info.applicationInfo.isSystemApp()
                    && SERVICE.getPackageName().equals(info.applicationInfo.packageName)
                    && UserHandle.getUserId(info.applicationInfo.uid) == UserHandle.USER_SYSTEM
                    && pm.checkSignatures(info.applicationInfo.uid, Process.SYSTEM_UID)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException missing) {
            Slog.e(TAG, "Required OTA CameraMind service is missing", missing);
            return false;
        }
    }

    private void release() {
        Connection previous = connection;
        connection = null;
        if (previous != null && previous.bound) context.unbindService(previous);
    }

    private void schedule() {
        worker.removeCallbacks(retry);
        worker.postDelayed(retry, delayMs);
        delayMs = Math.min(20000, delayMs + 500);
    }

    private void bind() {
        if (connection != null && connection.connected) return;
        release();
        if (!trustedService()) {
            Slog.e(TAG, "CameraMind has an unexpected UID, signature or enabled state");
            schedule();
            return;
        }
        Connection pending = new Connection();
        connection = pending;
        try {
            pending.bound = context.bindServiceAsUser(new Intent().setComponent(SERVICE), pending,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, worker, UserHandle.SYSTEM);
            if (!pending.bound) {
                connection = null;
                Slog.e(TAG, "CameraMind bind request was rejected");
            }
        } catch (SecurityException failure) {
            connection = null;
            Slog.e(TAG, "CameraMind bind permission was rejected", failure);
        }
        schedule();
    }

    private final class Connection implements ServiceConnection {
        boolean bound;
        boolean connected;

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (connection != this) return;
            connected = service != null && service.isBinderAlive();
            if (connected) {
                delayMs = 500;
                worker.removeCallbacks(retry);
            } else schedule();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (connection != this) return;
            connected = false;
            schedule();
        }

        @Override public void onBindingDied(ComponentName name) {
            if (connection != this) return;
            connected = false;
            schedule();
        }

        @Override public void onNullBinding(ComponentName name) {
            if (connection != this) return;
            Slog.e(TAG, "CameraMind returned a null engine binder");
            schedule();
        }
    }
}
