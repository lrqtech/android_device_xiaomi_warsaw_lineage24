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

/** Applies the active user's manual PWM setting, including while the screen is off. */
public final class FanService extends Service {
    private HandlerThread thread;
    private Handler handler;
    private final FanPwmController hardware = new FanPwmController();
    private ContentObserver observer;
    private String lastStatus;
    private int lastDuty = -1;
    private boolean receiverRegistered;
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
        handler.removeCallbacks(apply);
        handler.post(apply);
    }
    private void applySetting() {
        try {
            int user = ActivityManager.getCurrentUser();
            boolean enabled = Settings.System.getIntForUser(getContentResolver(),
                    FanPreferences.ENABLE, 1, user) == 1;
            int duty = Settings.System.getIntForUser(getContentResolver(),
                    FanPreferences.PWM, FanPreferences.DEFAULT_PWM, user);
            int observed = hardware.apply(enabled ? Math.max(0, Math.min(100, duty)) : 0);
            if (observed != lastDuty) {
                Settings.Global.putInt(getContentResolver(), FanPreferences.CONFIRMED_PWM, observed);
                lastDuty = observed;
            }
            publish("ready");
        } catch (Exception error) {
            if (!"unavailable".equals(lastStatus)) Log.e("CoolingFan", "Cannot apply PWM", error);
            publish("unavailable");
        }
        handler.removeCallbacks(apply);
        handler.postDelayed(apply, "ready".equals(lastStatus) ? 30000 : 5000);
    }
    private void publish(String status) {
        if (!status.equals(lastStatus) && Settings.Global.putString(
                getContentResolver(), FanPreferences.STATUS, status)) lastStatus = status;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (receiverRegistered) unregisterReceiver(users);
        getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        super.onDestroy();
    }
}
