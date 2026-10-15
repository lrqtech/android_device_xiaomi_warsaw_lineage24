package com.android.systemui.biometrics;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;

public final class WarsawUdfpsTouchLifecycle {
    public static final String WINDOW_TITLE = "WarsawUdfpsControllerOverlay";
    private static final String TAG = "WarsawUdfpsTouch";
    private static final int SENSOR_ID = 5;
    private static final int NON_UI_SENSOR_TYPE = 33171027;
    private static final int PUT_UP_SENSOR_TYPE = 33171030;
    private static final long ICON_TIMEOUT_MS = 5000;
    private static final String AOD_ICON = "gxzw_icon_aod_show_enable";
    private static final String AOD_LOWLIGHT = "gxzw_icon_aod_lowlight_show_enable";
    private static final String BRIDGE_SERVICE = "warsaw.udfps.ui";
    private static final String BRIDGE_DESCRIPTOR = "org.lineageos.warsaw.IUdfpsUi";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WarsawUdfpsTouchLifecycle sInstance;
    private static boolean sEnabled;
    private static UserTracker sUserTracker;
    private static volatile boolean sUserSwitching;
    private static final UserTracker.Callback USER_CALLBACK = new UserTracker.Callback() {
        @Override public void onBeforeUserSwitching(int newUser, Runnable completion) {
            try {
                sUserSwitching = true;
                if (sInstance != null) sInstance.detach();
            } finally { completion.run(); }
        }
        @Override public void onUserChanged(int newUser, Context context) {
            sUserSwitching = false;
            if (sInstance != null) {
                sInstance.publishLifecycle(true);
                sInstance.mWorker.post(sInstance::synchronizeUser);
            }
        }
    };

    public static void observeUsers(UserTracker tracker) {
        mainThread();
        if (sUserTracker == tracker) return;
        if (sUserTracker != null) sUserTracker.removeCallback(USER_CALLBACK);
        sUserTracker = tracker;
        tracker.addCallback(USER_CALLBACK, command -> MAIN.post(command));
        sUserSwitching = tracker.isUserSwitching();
    }

    private final Context mContext;
    private final Handler mWorker;
    private final SensorManager mSensors;
    private final AlarmManager mAlarms;
    private final UiBridge mBridge = new UiBridge();
    private View mOverlay;
    private int mOverlayUser = UserHandle.USER_NULL;
    private boolean mDozing;
    private boolean mAttached;
    private volatile long mLifecycleEpoch;
    private long mUiEpoch;
    private boolean mUiAttached;
    private boolean mUiDozing;
    private boolean mConfigured = true;
    private boolean mAodIcon;
    private boolean mAodLowlight;
    private int mNonUiMode;
    private int mUser = UserHandle.USER_NULL;
    private volatile int mGeneration;
    private SensorEventListener mNonUiListener;
    private SensorEventListener mPutUpListener;
    private boolean mIconSession;
    private boolean mIconVisible;
    private boolean mDeviceMoving;
    private boolean mIconTimedOut;
    private boolean mFingerDown;
    private Runnable mIconTimeout;
    private AlarmManager.OnAlarmListener mIconAlarm;
    private long mTimerGeneration;
    private boolean mRetryScheduled;
    private boolean mSensorRetryScheduled;
    private boolean mSensorFailureReported;
    private boolean mSettingsValid;
    private boolean mSettingsRetryScheduled;
    private boolean mSettingsFailureReported;
    private boolean mObserversRegistered;
    private boolean mObserverRetryScheduled;

    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("UDFPS lifecycle must run on the main thread");
        }
    }

    public static void configure(Context context, StatusBarStateController statusBar,
            FingerprintSensorPropertiesInternal sensor) {
        mainThread();
        final boolean enabled = "warsaw".equals(Build.DEVICE)
                && "2.0".equals(SystemProperties.get("ro.hardware.fp.fod.touch.ctl.version", ""))
                && sensor.sensorId == SENSOR_ID
                && sensor.sensorType == FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC;
        final boolean changed = enabled != sEnabled;
        boolean created = false;
        sEnabled = enabled;
        if (enabled && sInstance == null) {
            sInstance = new WarsawUdfpsTouchLifecycle(context, statusBar);
            created = true;
        } else if (sInstance != null && changed) {
            sInstance.setConfigured(enabled);
        }
        if (sInstance != null && (created || changed)) {
            sInstance.publishLifecycle(true);
        }
    }

    public static boolean isEnabled() { mainThread(); return sEnabled; }

    public static void onAcquired(int sensorId, boolean down) {
        final WarsawUdfpsTouchLifecycle instance = sInstance;
        if (instance == null || sensorId != SENSOR_ID) return;
        final long epoch = instance.mLifecycleEpoch;
        final int generation = instance.mGeneration;
        instance.mWorker.post(() -> {
            if (epoch != instance.mLifecycleEpoch || generation != instance.mGeneration
                    || !instance.currentIconSession()) return;
            if (instance.mFingerDown == down) return;
            instance.mFingerDown = down;
            if (down) {
                instance.mIconVisible = true;
                instance.cancelIconTimeout();
            } else {
                instance.scheduleIconTimeout();
            }
            instance.flush();
        });
    }

    public static void onOverlayAdded(View view) {
        mainThread();
        if (sEnabled && sInstance != null) sInstance.attach(view);
    }

    public static void onOverlayRemoved(View view) {
        mainThread();
        if (sInstance != null && sInstance.mOverlay == view) sInstance.detach();
    }

    private WarsawUdfpsTouchLifecycle(Context context, StatusBarStateController statusBar) {
        mContext = context.getApplicationContext();
        mSensors = mContext.getSystemService(SensorManager.class);
        mAlarms = mContext.getSystemService(AlarmManager.class);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mWorker = new Handler(thread.getLooper());
        mDozing = statusBar.isDozing();
        statusBar.addCallback(new StatusBarStateController.StateListener() {
            @Override public void onDozingChanged(boolean dozing) {
                if (mDozing == dozing) return;
                mDozing = dozing;
                publishLifecycle(true);
            }
        });
        mWorker.post(this::registerObservers);
    }

    private void registerObservers() {
        if (mObserversRegistered) return;
        synchronizeUser();
        ContentObserver observer = new ContentObserver(mWorker) {
            @Override public void onChange(boolean selfChange) { updateSettings(); }
        };
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                synchronizeUser();
            }
        };
        try {
            for (String key : new String[]{AOD_ICON, AOD_LOWLIGHT,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED}) {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL);
            }
            mContext.registerReceiverAsUser(receiver, UserHandle.ALL,
                    new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mWorker);
            mObserversRegistered = true;
            updateSettings();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not register UDFPS policy observers; retrying", error);
            try { mContext.getContentResolver().unregisterContentObserver(observer); }
            catch (RuntimeException cleanupError) { Log.w(TAG, "Observer cleanup failed", cleanupError); }
            try { mContext.unregisterReceiver(receiver); }
            catch (IllegalArgumentException notRegistered) {   }
            mSettingsValid = false;
            stopNonUi();
            flush();
            if (!mObserverRetryScheduled) {
                mObserverRetryScheduled = true;
                mWorker.postDelayed(() -> {
                    mObserverRetryScheduled = false;
                    registerObservers();
                }, 1000);
            }
        }
    }

    private final View.OnAttachStateChangeListener mAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    if (view == mOverlay) {
                        mAttached = true;
                        publishLifecycle(true);
                    }
                }
                @Override public void onViewDetachedFromWindow(View view) {
                    if (view == mOverlay) {
                        mAttached = false;
                        publishLifecycle(true);
                    }
                }
            };

    private void attach(View view) {
        if (mOverlay == view) return;
        detach();
        mOverlay = view;
        mOverlayUser = ActivityManager.getCurrentUser();
        view.addOnAttachStateChangeListener(mAttachListener);
        mAttached = view.isAttachedToWindow();
        publishLifecycle(true);
    }

    private void detach() {
        if (mOverlay != null) mOverlay.removeOnAttachStateChangeListener(mAttachListener);
        mOverlay = null;
        mOverlayUser = UserHandle.USER_NULL;
        mAttached = false;
        publishLifecycle(true);
    }

    private void setConfigured(boolean enabled) {
        if (!enabled) detach();
        mWorker.post(() -> {
            if (mConfigured == enabled) return;
            mConfigured = enabled;
            stopNonUi();
            updateSettings();
        });
    }

    private void publishLifecycle(boolean reset) {
        final long epoch = ++mLifecycleEpoch;
        final boolean attached = sEnabled && mAttached;
        final boolean dozing = mDozing;
        final int user = mOverlayUser;
        WarsawUdfpsAodVisibility.publish(sEnabled && dozing);
        com.android.systemui.doze.WarsawUdfpsDoze.publish(user, epoch, false);
        mWorker.post(() -> {
            if (epoch != mLifecycleEpoch) return;
            mUiEpoch = epoch;
            mUiAttached = attached && user == ActivityManager.getCurrentUser() && user == mUser;
            mUiDozing = dozing;
            if (reset) stopNonUi();
            updateNonUiRegistration();
            flush();
        });
    }

    public static void refreshDozeRequest() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Doze refresh must run on main");
        }
        WarsawUdfpsTouchLifecycle instance = sInstance;
        if (instance != null) instance.publishLifecycle(false);
    }

    private void updateSettings() {
        try {
            readSettings();
            mSettingsFailureReported = false;
        } catch (RuntimeException error) {
            if (!mSettingsFailureReported) Log.e(TAG, "UDFPS policy read failed", error);
            mSettingsFailureReported = true;
            mSettingsValid = false;
            mAodIcon = false;
            stopNonUi();
            mAodLowlight = false;
            flush();
            if (!mSettingsRetryScheduled) {
                mSettingsRetryScheduled = true;
                mWorker.postDelayed(() -> {
                    mSettingsRetryScheduled = false;
                    updateSettings();
                }, 1000);
            }
        }
    }

    private void readSettings() {
        boolean icon = mConfigured && Settings.Secure.getIntForUser(
                mContext.getContentResolver(), AOD_ICON, 1, mUser) == 1
                && Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, mUser) == 0;
        boolean lowlight = mConfigured && Settings.Secure.getIntForUser(
                mContext.getContentResolver(), AOD_LOWLIGHT, 0, mUser) == 1;
        mSettingsValid = mObserversRegistered;
        if (mAodIcon != icon) stopNonUi();
        mAodIcon = icon;
        mAodLowlight = lowlight;
        updateNonUiRegistration();
        flush();
    }

    private void stopNonUi() {
        ++mGeneration;
        if (mNonUiListener != null && mSensors != null) {
            try { mSensors.unregisterListener(mNonUiListener); }
            catch (RuntimeException error) { Log.w(TAG, "Non-UI listener removal failed", error); }
        }
        mNonUiListener = null;
        if (mPutUpListener != null && mSensors != null) {
            try { mSensors.unregisterListener(mPutUpListener); }
            catch (RuntimeException error) { Log.w(TAG, "Put-up listener removal failed", error); }
        }
        mPutUpListener = null;
        mNonUiMode = 0;
        mIconSession = false;
        mIconVisible = false;
        mDeviceMoving = false;
        mIconTimedOut = false;
        mFingerDown = false;
        cancelIconTimeout();
    }

    private boolean currentIconSession() {
        return mConfigured && mSettingsValid && mUiAttached && mUiDozing && mAodIcon
                && !sUserSwitching && mUiEpoch == mLifecycleEpoch
                && ActivityManager.getCurrentUser() == mUser;
    }

    private void cancelIconTimeout() {
        ++mTimerGeneration;
        if (mIconTimeout != null) mWorker.removeCallbacks(mIconTimeout);
        if (mIconAlarm != null && mAlarms != null) {
            try { mAlarms.cancel(mIconAlarm); }
            catch (RuntimeException error) { Log.w(TAG, "AOD alarm cancellation failed", error); }
        }
        mIconTimeout = null;
        mIconAlarm = null;
    }

    private void scheduleIconTimeout() {
        cancelIconTimeout();
        if (!currentIconSession()) return;
        mIconTimedOut = false;
        final long ticket = mTimerGeneration;
        final long epoch = mUiEpoch;
        final long deadline = SystemClock.elapsedRealtime() + ICON_TIMEOUT_MS;
        final Runnable timeout = () -> {
            if (ticket != mTimerGeneration || epoch != mLifecycleEpoch
                    || !currentIconSession()) return;
            if (SystemClock.elapsedRealtime() < deadline) return;
            cancelIconTimeout();
            mIconTimedOut = true;
            if (mFingerDown) scheduleIconTimeout();
            else if (!mDeviceMoving) mIconVisible = false;
            flush();
        };
        mIconTimeout = timeout;
        mWorker.postDelayed(timeout, ICON_TIMEOUT_MS);
        if (mAlarms != null) {
            mIconAlarm = timeout::run;
            try {
                mAlarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline,
                        TAG, mIconAlarm, mWorker);
            } catch (RuntimeException error) {
                mIconAlarm = null;
                Log.e(TAG, "AOD elapsed-realtime alarm unavailable; awake timer only", error);
            }
        } else Log.e(TAG, "AlarmManager missing; AOD suspend timeout is unavailable");
    }

    private void showIcon() {
        mIconVisible = true;
        scheduleIconTimeout();
    }

    private void hideIcon() {
        mIconVisible = false;
        cancelIconTimeout();
    }

    private void updateNonUiRegistration() {
        final int features = SystemProperties.getInt("ro.vendor.touchfeature.type", 0);
        final boolean required = currentIconSession();
        if (!required) {
            if (mIconSession || mNonUiListener != null || mPutUpListener != null) stopNonUi();
            mNonUiMode = 0;
            return;
        }
        if (!mIconSession) {
            mIconSession = true;
            showIcon();
        }
        if (mSensors == null) {
            reportSensorFailure("SensorManager is unavailable");
            retrySensor();
            return;
        }
        if (mPutUpListener == null) mPutUpListener = registerIconSensor(PUT_UP_SENSOR_TYPE, true, true);
        if ((features & 128) == 0 || mNonUiListener != null) return;
        mNonUiListener = registerIconSensor(NON_UI_SENSOR_TYPE, (features & 256) == 0, false);
    }

    private SensorEventListener registerIconSensor(int type, boolean wakeUp, boolean putUp) {
        final Sensor sensor;
        try {
            sensor = mSensors.getDefaultSensor(type, wakeUp);
        } catch (RuntimeException error) {
            reportSensorFailure("OEM icon sensor " + type + " lookup failed: " + error);
            retrySensor();
            return null;
        }
        if (sensor == null) {
            reportSensorFailure("Required OEM icon sensor is unavailable: " + type);
            retrySensor();
            return null;
        }
        final int generation = mGeneration;
        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if ((putUp ? mPutUpListener != this : mNonUiListener != this)
                        || generation != mGeneration || mUiEpoch != mLifecycleEpoch
                        || sUserSwitching || !mConfigured || !mSettingsValid || !mAodIcon || !mUiAttached || !mUiDozing
                        || ActivityManager.getCurrentUser() != mUser || event.sensor != sensor
                        || event.values.length == 0) return;
                final float value = event.values[0];
                if (putUp ? value != 1f && value != 2f && value != 3f
                          : value != 0f && value != 1f && value != 2f) {
                    Log.w(TAG, "Rejected unknown icon sensor value for " + type);
                    return;
                }
                if (putUp) {
                    if (value == 1f) {
                        if (mNonUiMode == 0) showIcon();
                        mDeviceMoving = true;
                    } else if (value == 2f) {
                        mDeviceMoving = false;
                        if (mIconTimedOut) {
                            if (mFingerDown) scheduleIconTimeout();
                            else hideIcon();
                        }
                    } else if (mNonUiMode == 0) showIcon();
                } else {
                    if (mNonUiMode == (int) value) return;
                    mNonUiMode = (int) value;
                    if (mNonUiMode != 0) hideIcon();
                    else if (mDeviceMoving) showIcon();
                }
                flush();
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
                if (generation == mGeneration) Log.d(TAG, "Non-UI sensor accuracy=" + accuracy);
            }
        };
        boolean registered = false;
        try {
            registered = mSensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL,
                    mWorker);
        } catch (RuntimeException error) {
            reportSensorFailure("OEM non-UI sensor registration failed: " + error);
        }
        if (registered) {
            mSensorFailureReported = false;
            return listener;
        } else {
            try { mSensors.unregisterListener(listener); }
            catch (RuntimeException error) { Log.w(TAG, "Failed icon registration cleanup", error); }
            reportSensorFailure("Could not register the OEM icon sensor " + type);
            retrySensor();
            return null;
        }
    }

    private void reportSensorFailure(String message) {
        if (!mSensorFailureReported) Log.e(TAG, message);
        mSensorFailureReported = true;
    }

    private void retrySensor() {
        if (mSensorRetryScheduled) return;
        mSensorRetryScheduled = true;
        mWorker.postDelayed(() -> {
            mSensorRetryScheduled = false;
            updateNonUiRegistration();
        }, 1000);
    }

    private boolean synchronizeUser() {
        final int activeUser = ActivityManager.getCurrentUser();
        if (activeUser == mUser) return false;
        mUser = activeUser;
        mSettingsValid = false;
        mUiAttached = false;
        stopNonUi();
        updateSettings();
        MAIN.post(() -> {
            if (mOverlayUser != ActivityManager.getCurrentUser()) detach();
            else publishLifecycle(true);
        });
        return true;
    }

    private void flush() {
        if (synchronizeUser()) return;
        final long epoch = mUiEpoch;
        final int visualUser = mUser;
        final boolean hide = !currentIconSession() || !mIconVisible;
        MAIN.post(() -> {
            if (epoch != mLifecycleEpoch || visualUser != ActivityManager.getCurrentUser()) return;
            WarsawUdfpsAodVisibility.publish(sEnabled && mDozing && hide);
            com.android.systemui.doze.WarsawUdfpsDoze.publish(visualUser, epoch,
                    sEnabled && mDozing && !hide);
        });
        final boolean policyReady = mConfigured && mSettingsValid;
        final boolean currentNonUi = mUiEpoch == mLifecycleEpoch && mUiAttached && mUiDozing;
        final boolean accepted = mBridge.send(mUser, policyReady && mAodIcon,
                policyReady && mAodLowlight, policyReady && currentNonUi ? mNonUiMode : 0);
        if (!accepted && !mRetryScheduled) {
            mRetryScheduled = true;
            mWorker.postDelayed(() -> {
                mRetryScheduled = false;
                flush();
            }, 1000);
        }
    }

    private final class UiBridge {
        private final IBinder mOwner = new Binder();
        private IBinder mBinder;
        private IBinder.DeathRecipient mDeath;
        private boolean mReportedFailure;
        private boolean mHaveSent;
        private int mSentUser;
        private boolean mSentIcon;
        private boolean mSentLowlight;
        private int mSentNonUi;

        boolean send(int user, boolean icon, boolean lowlight, int nonUi) {
            try {
                if (mBinder == null) {
                    IBinder binder = ServiceManager.checkService(BRIDGE_SERVICE);
                    if (binder == null) throw new RemoteException("Service is unavailable");
                    Binder.allowBlocking(binder);
                    IBinder.DeathRecipient death = () -> mWorker.post(() -> {
                        if (mBinder != binder) return;
                        mBinder = null;
                        mDeath = null;
                        mHaveSent = false;
                        flush();
                    });
                    binder.linkToDeath(death, 0);
                    mBinder = binder;
                    mDeath = death;
                    mHaveSent = false;
                }
                if (mHaveSent && user == mSentUser && icon == mSentIcon
                        && lowlight == mSentLowlight && nonUi == mSentNonUi) return true;
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(BRIDGE_DESCRIPTOR);
                    data.writeStrongBinder(mOwner);
                    data.writeInt(user);
                    data.writeInt(icon ? 1 : 0);
                    data.writeInt(lowlight ? 1 : 0);
                    data.writeInt(nonUi);
                    if (!mBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) {
                        throw new RemoteException("Unsupported UDFPS bridge transaction");
                    }
                    reply.readException();
                    if (reply.dataAvail() < Integer.BYTES || reply.readInt() != 0) {
                        throw new RemoteException("UDFPS bridge rejected the state");
                    }
                    mHaveSent = true;
                    mSentUser = user;
                    mSentIcon = icon;
                    mSentLowlight = lowlight;
                    mSentNonUi = nonUi;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
                mReportedFailure = false;
                return true;
            } catch (RemoteException | RuntimeException error) {
                if (!mReportedFailure) Log.e(TAG, BRIDGE_SERVICE + " state submission failed", error);
                mReportedFailure = true;
                if (mBinder != null && mDeath != null) {
                    try { mBinder.unlinkToDeath(mDeath, 0); }
                    catch (java.util.NoSuchElementException alreadyRemoved) {   }
                }
                mBinder = null;
                mDeath = null;
                mHaveSent = false;
                return false;
            }
        }
    }
}
