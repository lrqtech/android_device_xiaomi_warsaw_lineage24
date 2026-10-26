package org.lineageos.warsaw.fan;

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CancellationException;

/** Applies the active user's manual PWM setting, including while the screen is off. */
public final class FanService extends Service {
    private HandlerThread thread;
    private Handler handler;
    private final FanPwmController hardware = new FanPwmController();
    private ContentObserver observer;
    private String lastStatus;
    private int lastDuty = -1;
    private boolean receiverRegistered;
    private volatile boolean stopped;
    private final Runnable apply = this::applySetting;
    private final BroadcastReceiver users = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { changed(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("CoolingFanControl");
        thread.start();
        handler = new Handler(thread.getLooper());
        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { changed(); }
        };
        for (String key : new String[] {FanPreferences.ENABLE, FanPreferences.PWM}) {
            getContentResolver().registerContentObserver(Settings.System.getUriFor(key), false,
                    observer, UserHandle.USER_ALL);
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver(users, filter, null, handler);
        receiverRegistered = true;
        changed();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        changed();
        return START_STICKY;
    }
    private void changed() {
        if (stopped) return;
        // Queue rescheduling on the owner thread so a running apply cannot erase a new request.
        handler.post(() -> schedule(0));
    }
    private void schedule(long delay) {
        if (stopped) return;
        handler.removeCallbacks(apply);
        handler.postDelayed(apply, delay);
    }
    private boolean isCurrent(int user) {
        return !stopped && ActivityManager.getCurrentUser() == user;
    }
    private void applySetting() {
        if (stopped) return;
        boolean ready = false;
        try {
            int user = ActivityManager.getCurrentUser();
            boolean enabled = Settings.System.getIntForUser(getContentResolver(),
                    FanPreferences.ENABLE, 1, user) == 1;
            int duty = Settings.System.getIntForUser(getContentResolver(),
                    FanPreferences.PWM, FanPreferences.DEFAULT_PWM, user);
            int observed = hardware.apply(enabled ? Math.max(0, Math.min(100, duty)) : 0,
                    () -> isCurrent(user));
            if (!isCurrent(user)) throw new CancellationException("Fan request superseded");
            if (observed != lastDuty) {
                if (!Settings.Global.putInt(getContentResolver(),
                        FanPreferences.CONFIRMED_PWM, observed)) {
                    throw new IOException("Cannot publish confirmed PWM");
                }
                lastDuty = observed;
            }
            ready = publish("ready");
        } catch (CancellationException superseded) {
            changed();
            return;
        } catch (Exception error) {
            if (stopped) return;
            if (!"unavailable".equals(lastStatus)) Log.e("CoolingFan", "Cannot apply PWM", error);
            publish("unavailable");
        }
        schedule(ready ? 30000 : 5000);
    }
    private boolean publish(String status) {
        if (stopped) return false;
        if (status.equals(lastStatus)) return true;
        try {
            if (!Settings.Global.putString(getContentResolver(), FanPreferences.STATUS, status)) {
                return false;
            }
            lastStatus = status;
            return true;
        } catch (RuntimeException error) {
            Log.e("CoolingFan", "Cannot publish fan status", error);
            return false;
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        stopped = true;
        if (receiverRegistered) unregisterReceiver(users);
        getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        super.onDestroy();
    }
}
