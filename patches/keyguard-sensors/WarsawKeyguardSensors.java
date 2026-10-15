package com.android.systemui.keyguard;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicLong;

import com.android.systemui.keyguard.WarsawKeyguardSensorPolicy.Action;
import com.android.systemui.keyguard.WarsawKeyguardSensorPolicy.State;

final class WarsawKeyguardSensors {
    interface StateSource {
        State get();
        boolean runIfCurrent(State expected, BooleanSupplier valid, Runnable action);
    }
    private static final String TAG = "WarsawKeyguardSensors";
    private static final String PICKUP_SETTING = "pick_up_gesture_wakeup_mode";
    private static final String COVER_ACTION = "miui.intent.action.SMART_COVER";
    private static final String COVER_OPEN = "is_smart_cover_open";
    private static final int PICKUP = 33171036;
    private static final int PALM = 33171031;
    private static final Set<String> PALM_KEEP_SCREEN_ON = new HashSet<>(Arrays.asList(
            "com.android.incallui", "com.tencent.mobileqq", "com.tencent.qqlite",
            "com.tencent.mm", "com.android.deskclock", "com.google.android.dialer",
            "com.whatsapp", "com.skype.raider", "com.xiaomi.aiasst.service", "com.zing.zalo"));

    private final Context context;
    private final StateSource stateSource;
    private final SensorManager sensors;
    private final PowerManager power;
    private final Handler worker;
    private final WarsawKeyguardSensorPolicy policy = new WarsawKeyguardSensorPolicy();
    private final Slot pickup = new Slot(PICKUP, true);
    private final Slot palm = new Slot(PALM, false);
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private int user = UserHandle.USER_NULL;
    private long epoch;
    private boolean stopped, observing, retryQueued, settingEnabled, settingsValid;
    private boolean settingsRegistered, userRegistered, coverRegistered, cleanupPending;
    private boolean lidShowing;
    private final ContentObserver settingsObserver;
    private final BroadcastReceiver userReceiver;
    private final BroadcastReceiver coverReceiver;

    static WarsawKeyguardSensors start(Context context, StateSource states) {
        return "warsaw".equals(Build.DEVICE) ? new WarsawKeyguardSensors(context, states) : null;
    }

    private WarsawKeyguardSensors(Context context, StateSource states) {
        this.context = context.getApplicationContext();
        stateSource = states;
        sensors = this.context.getSystemService(SensorManager.class);
        power = this.context.getSystemService(PowerManager.class);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        worker = new Handler(thread.getLooper());
        settingsObserver = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) { refresh(); }
        };
        userReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) stop();
                else { invalidate(true); refresh(); }
            }
        };
        coverReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!COVER_ACTION.equals(intent.getAction())) return;
                lidShowing = !intent.getBooleanExtra(COVER_OPEN, false);
                invalidate(false);
                refresh();
            }
        };
        worker.post(this::refresh);
    }

    void requestRefresh() {
        lifecycleGeneration.incrementAndGet();
        worker.post(() -> { invalidate(false); refresh(); });
    }

    void onInteractiveBoundary(boolean goingToSleep) {
        lifecycleGeneration.incrementAndGet();
        worker.post(() -> { invalidate(goingToSleep); refresh(); });
    }

    private void invalidate(boolean resetPickupOwnership) {
        epoch++;
        pickup.unregister();
        palm.unregister();
        if (resetPickupOwnership) policy.reset();
    }

    private boolean registerObservers() {
        if (observing) return true;
        if (cleanupPending && !cleanupObservers()) { retry(); return false; }
        try {
            settingsRegistered = true;
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(PICKUP_SETTING), false, settingsObserver,
                    UserHandle.USER_ALL);
            IntentFilter users = new IntentFilter(Intent.ACTION_USER_SWITCHED);
            users.addAction(Intent.ACTION_SHUTDOWN);
            userRegistered = true;
            context.registerReceiverAsUser(userReceiver, UserHandle.ALL, users, null, worker);
            coverRegistered = true;
            context.registerReceiverAsUser(coverReceiver, UserHandle.ALL,
                    new IntentFilter(COVER_ACTION), android.Manifest.permission.DEVICE_POWER,
                    worker, Context.RECEIVER_EXPORTED);
            observing = true;
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Observer registration failed", error);
            cleanupPending = true;
            cleanupObservers();
            retry();
            return false;
        }
    }

    private boolean cleanupObservers() {
        observing = false;
        if (settingsRegistered) {
            try {
                context.getContentResolver().unregisterContentObserver(settingsObserver);
                settingsRegistered = false;
            } catch (RuntimeException error) { Log.e(TAG, "Settings observer cleanup failed", error); }
        }
        if (userRegistered) {
            try { context.unregisterReceiver(userReceiver); userRegistered = false; }
            catch (IllegalArgumentException notRegistered) { userRegistered = false; }
            catch (RuntimeException error) { Log.e(TAG, "User receiver cleanup failed", error); }
        }
        if (coverRegistered) {
            try { context.unregisterReceiver(coverReceiver); coverRegistered = false; }
            catch (IllegalArgumentException notRegistered) { coverRegistered = false; }
            catch (RuntimeException error) { Log.e(TAG, "Cover receiver cleanup failed", error); }
        }
        cleanupPending = settingsRegistered || userRegistered || coverRegistered;
        return !cleanupPending;
    }

    private void refresh() {
        if (stopped) return;
        if (sensors == null || power == null || !registerObservers()) {
            invalidate(true);
            retry();
            return;
        }
        try {
            State state = stateSource.get();
            int foreground = ActivityManager.getCurrentUser();
            if (foreground != state.user || foreground < 0) {
                invalidate(true);
                settingsValid = false;
                retry();
                return;
            }
            if (user != foreground) {
                invalidate(true);
                user = foreground;
            }
            settingEnabled = Settings.System.getIntForUser(context.getContentResolver(),
                    PICKUP_SETTING, 0, user) != 0;
            settingsValid = true;
            pickup.update(policy.wantsPickup(state, settingEnabled));
            palm.update(policy.wantsPalm(state));
        } catch (RuntimeException error) {
            settingsValid = false;
            invalidate(true);
            Log.e(TAG, "Cannot resolve current keyguard policy", error);
            retry();
        }
    }

    private void retry() {
        if (retryQueued || stopped) return;
        retryQueued = true;
        worker.postDelayed(() -> { retryQueued = false; refresh(); }, 1000);
    }

    private boolean keepScreenOn(State state) {
        if (!state.occluded) return false;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
        if (tasks.isEmpty()) return true;
        ActivityManager.RunningTaskInfo task = tasks.get(0);
        if (task.userId != user || task.topActivity == null) return true;
        return PALM_KEEP_SCREEN_ON.contains(task.topActivity.getPackageName());
    }

    private void event(Slot slot, SensorEventListener listener, long registrationEpoch,
            long lifecycleToken, long registeredAt, SensorEvent event) {
        if (stopped || !settingsValid || slot.listener != listener || epoch != registrationEpoch
                || lifecycleGeneration.get() != lifecycleToken
                || event.values == null || event.values.length == 0
                || event.timestamp < registeredAt || event.sensor.getType() != slot.type) return;
        float value = event.values[0];
        if (!Float.isFinite(value)) return;
        try {
            State state = stateSource.get();
            if (state.user != user || ActivityManager.getCurrentUser() != user) {
                invalidate(true);
                refresh();
                return;
            }
            settingEnabled = Settings.System.getIntForUser(context.getContentResolver(),
                    PICKUP_SETTING, 0, user) != 0;
            Action action = slot == pickup ? policy.pickup(value, state, settingEnabled, lidShowing)
                    : policy.palm(value, state, keepScreenOn(state));
            if (action != Action.NONE) {
                stateSource.runIfCurrent(state, () -> {
                    if (stopped || !settingsValid || slot.listener != listener
                            || epoch != registrationEpoch
                            || lifecycleGeneration.get() != lifecycleToken
                            || ActivityManager.getCurrentUser() != user) return false;
                    boolean enabled = Settings.System.getIntForUser(context.getContentResolver(),
                            PICKUP_SETTING, 0, user) != 0;
                    Action admitted = slot == pickup ? policy.pickup(value, state, enabled, lidShowing)
                            : policy.palm(value, state, keepScreenOn(state));
                    return action == admitted && lifecycleGeneration.get() == lifecycleToken
                            && ActivityManager.getCurrentUser() == user;
                }, () -> {
                    if (action == Action.WAKE) {
                        power.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                                "com.android.systemui:PICK_UP");
                        policy.wakeRequestAccepted();
                    } else {
                        power.goToSleep(SystemClock.uptimeMillis());
                    }
                });
            }
            refresh();
        } catch (RuntimeException error) {
            settingsValid = false;
            invalidate(true);
            Log.e(TAG, "Sensor action failed", error);
            retry();
        }
    }

    private void stop() {
        if (stopped) return;
        stopped = true;
        invalidate(true);
        worker.removeCallbacksAndMessages(null);
        cleanupObservers();
        worker.getLooper().quitSafely();
    }

    private final class Slot {
        final int type;
        final boolean wake;
        SensorEventListener listener;
        SensorEventListener pendingRemoval;
        Slot(int type, boolean wake) { this.type = type; this.wake = wake; }

        void update(boolean wanted) {
            if (!cleanup()) return;
            if (!wanted) { unregister(); return; }
            if (listener != null) return;
            Sensor sensor = sensors.getDefaultSensor(type, wake);
            boolean nameMatches = sensor != null && (type == PICKUP
                    ? "oem7 Pick Up Gesture".equalsIgnoreCase(sensor.getName())
                        || "pickup  Wakeup".equalsIgnoreCase(sensor.getName())
                    : "Touch Sensor".equals(sensor.getName()));
            if (!nameMatches || sensor.isWakeUpSensor() != wake) {
                Log.e(TAG, "Expected stock sensor unavailable: " + type + " wake=" + wake);
                retry();
                return;
            }
            final long token = epoch;
            final long lifecycleToken = lifecycleGeneration.get();
            final long started = SystemClock.elapsedRealtimeNanos();
            SensorEventListener next = new SensorEventListener() {
                @Override public void onAccuracyChanged(Sensor ignored, int accuracy) { }
                @Override public void onSensorChanged(SensorEvent event) {
                    event(Slot.this, this, token, lifecycleToken, started, event);
                }
            };
            try {
                if (sensors.registerListener(next, sensor, SensorManager.SENSOR_DELAY_NORMAL, worker)) {
                    listener = next;
                    return;
                }
            } catch (RuntimeException error) {
                Log.e(TAG, "Sensor registration failed: " + type, error);
            }
            pendingRemoval = next;
            cleanup();
            Log.e(TAG, "Sensor subscription rejected: " + type);
            retry();
        }

        void unregister() {
            SensorEventListener previous = listener;
            listener = null;
            if (previous != null) pendingRemoval = previous;
            cleanup();
        }

        boolean cleanup() {
            if (pendingRemoval == null) return true;
            try {
                sensors.unregisterListener(pendingRemoval);
                pendingRemoval = null;
                return true;
            } catch (RuntimeException error) {
                Log.e(TAG, "Sensor cleanup failed: " + type, error);
                retry();
                return false;
            }
        }
    }
}
