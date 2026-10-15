package com.android.server.dreams;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.List;

final class WarsawFodDreamGate {
    private static final String TAG = "WarsawFodDreamGate";
    private static final String ICON_SETTING = "gxzw_icon_aod_show_enable";
    private static final int SENSOR_ID = 5;
    private static final int MAX_RETRIES = 5;
    private static final long READ_TIMEOUT_MS = 5000;
    private static WarsawFodDreamGate sInstance;
    private static volatile Snapshot sSnapshot = new Snapshot(UserHandle.USER_NULL, false, "not_started");
    private final Context mContext;
    private final Handler mWorker;
    private final Handler mDreamHandler;
    private final Runnable mChanged;
    private final Object mGuard = new Object();
    private long mEpoch;
    private int mExpectedUser = UserHandle.USER_NULL;
    private boolean mQueued;
    private int mRetries;
    private boolean mObserving;
    private int mObservedUser = UserHandle.USER_NULL;
    private volatile ContentObserver mSettings;
    private boolean mReceiving;
    private volatile IBinder mBinder;
    private IFingerprintService mFingerprint;
    private boolean mRegistrationAttempted;
    private boolean mRegistered;

    private static final class Snapshot {
        final int user;
        final boolean enabled;
        final String reason;
        Snapshot(int user, boolean enabled, String reason) {
            this.user = user; this.enabled = enabled; this.reason = reason;
        }
    }

    static synchronized void start(Context context, Handler dreamHandler, Runnable changed) {
        if (sInstance != null || !"warsaw".equals(Build.DEVICE)
                || !"2.0".equals(SystemProperties.get("ro.hardware.fp.fod.touch.ctl.version"))) return;
        sInstance = new WarsawFodDreamGate(context, dreamHandler, changed);
        sInstance.invalidate(UserHandle.USER_NULL, "initializing");
    }

    static boolean isEnabled(int userId) {
        Snapshot snapshot = sSnapshot;
        return userId >= 0 && snapshot.user == userId && snapshot.enabled;
    }

    static synchronized void onUserSwitching(int userId) {
        if (sInstance != null) sInstance.invalidate(userId, "user_switch");
    }

    static synchronized void onUserUnlocked(int userId) {
        if (sInstance != null) {
            WarsawFodDreamGate instance = sInstance;
            instance.mWorker.post(() -> {
                try {
                    if (userId == ActivityManager.getCurrentUser()) {
                        instance.invalidate(userId, "user_unlock");
                    }
                } catch (RuntimeException error) {
                    instance.invalidate(UserHandle.USER_NULL, "unlock_user_unknown");
                }
            });
        }
    }

    static void dump(PrintWriter writer) {
        Snapshot snapshot = sSnapshot;
        writer.println("WarsawFodDreamGate user=" + snapshot.user + " enabled=" + snapshot.enabled
                + " reason=" + snapshot.reason);
    }

    private WarsawFodDreamGate(Context context, Handler dreamHandler, Runnable changed) {
        mContext = context;
        mDreamHandler = dreamHandler;
        mChanged = changed;
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mWorker = new Handler(thread.getLooper());
    }

    private void publishLocked(Snapshot next) {
        Snapshot old = sSnapshot;
        sSnapshot = next;
        if (old.user != next.user || old.enabled != next.enabled) {
            mDreamHandler.post(mChanged);
        }
    }

    private void invalidate(int userId, String reason) {
        synchronized (mGuard) {
            ++mEpoch;
            if (userId != UserHandle.USER_NULL) mExpectedUser = userId;
            mRetries = 0;
            publishLocked(new Snapshot(UserHandle.USER_NULL, false, reason));
            scheduleLocked(0);
        }
    }

    private void scheduleLocked(long delay) {
        if (mQueued) return;
        mQueued = true;
        mWorker.postDelayed(this::refresh, delay);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                synchronized (mGuard) { mRetries = 0; scheduleLocked(0); }
            } else invalidate(UserHandle.USER_NULL, "policy_changed");
        }
    };

    private void observe(int user) {
        if (mSettings != null && mObservedUser != user) {
            ContentObserver old = mSettings;
            mSettings = null;
            mObserving = false;
            mContext.getContentResolver().unregisterContentObserver(old);
        }
        if (!mObserving) {
            ContentObserver observer = new ContentObserver(null) {
                @Override public void onChange(boolean selfChange) {
                    synchronized (mGuard) {
                        if (mSettings != this || (mExpectedUser != UserHandle.USER_NULL
                                && mExpectedUser != user)) return;
                        invalidate(UserHandle.USER_NULL, "settings_changed");
                    }
                }
            };
            mSettings = observer;
            try {
                for (String setting : new String[] {ICON_SETTING,
                        Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED}) {
                    mContext.getContentResolver().registerContentObserver(
                            Settings.Secure.getUriFor(setting), false, observer, user);
                }
                mObserving = true;
                mObservedUser = user;
            } catch (RuntimeException error) {
                mSettings = null;
                mContext.getContentResolver().unregisterContentObserver(observer);
                throw error;
            }
        }
        if (!mReceiving) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            filter.addAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
            filter.addAction(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED);
            mContext.registerReceiver(mReceiver, filter, null, mWorker, Context.RECEIVER_EXPORTED);
            mReceiving = true;
        }
    }

    private void connect() throws RemoteException {
        IBinder binder = ServiceManager.checkService(Context.FINGERPRINT_SERVICE);
        if (binder == null) throw new RemoteException("fingerprint service not published");
        if (mBinder != binder) {
            mBinder = binder;
            mFingerprint = IFingerprintService.Stub.asInterface(binder);
            mRegistrationAttempted = false;
            mRegistered = false;
            binder.linkToDeath(() -> mWorker.post(() -> {
                if (mBinder != binder) return;
                mBinder = null;
                mFingerprint = null;
                mRegistered = false;
                invalidate(UserHandle.USER_NULL, "fingerprint_service_died");
            }), 0);
        }
        if (!mRegistrationAttempted) {
            mRegistrationAttempted = true;
            mFingerprint.registerBiometricStateListener(new BiometricStateListener() {
                @Override public void onEnrollmentsChanged(int userId, int sensorId, boolean enrolled) {
                    if (mBinder != binder || sensorId != SENSOR_ID) return;
                    synchronized (mGuard) {
                        if (mExpectedUser != UserHandle.USER_NULL && userId != mExpectedUser) return;
                        if (!enrolled) invalidate(UserHandle.USER_NULL, "enrollment_removed");
                        else { mRetries = 0; scheduleLocked(0); }
                    }
                }
            });
            mRegistered = true;
        }
        if (!mRegistered) throw new RemoteException("listener registration unconfirmed");
    }

    private boolean simAllowsFingerprint() {
        TelephonyManager telephony = mContext.getSystemService(TelephonyManager.class);
        if (telephony == null) throw new IllegalStateException("telephony not published");
        int count = telephony.getActiveModemCount();
        if (count <= 0 || count > 8) throw new IllegalStateException("modem count unknown/out of bound");
        for (int slot = 0; slot < count; ++slot) {
            int state = telephony.getSimState(slot);
            if (state == TelephonyManager.SIM_STATE_UNKNOWN) throw new IllegalStateException("SIM state unknown");
            if (state == TelephonyManager.SIM_STATE_PIN_REQUIRED
                    || state == TelephonyManager.SIM_STATE_PUK_REQUIRED
                    || state == TelephonyManager.SIM_STATE_PERM_DISABLED) return false;
        }
        return true;
    }

    private void refresh() {
        final long epoch;
        final int expectedUser;
        synchronized (mGuard) {
            mQueued = false;
            epoch = mEpoch;
            expectedUser = mExpectedUser;
        }
        final Runnable deadline = () -> {
            synchronized (mGuard) {
                if (epoch != mEpoch) return;
                ++mEpoch;
                publishLocked(new Snapshot(UserHandle.USER_NULL, false, "read_deadline"));
                if (++mRetries <= MAX_RETRIES) scheduleLocked(1000);
            }
        };
        mDreamHandler.postDelayed(deadline, READ_TIMEOUT_MS);
        try {
            int user = ActivityManager.getCurrentUser();
            if (expectedUser != UserHandle.USER_NULL && expectedUser != user) {
                throw new IllegalStateException("user switch not settled");
            }
            observe(user);
            connect();
            List<FingerprintSensorPropertiesInternal> sensors =
                    mFingerprint.getSensorPropertiesInternal(mContext.getOpPackageName());
            if (sensors == null || sensors.isEmpty()) throw new RemoteException("authenticators not registered");
            int matches = 0;
            for (FingerprintSensorPropertiesInternal sensor : sensors) {
                if (sensor.sensorId == SENSOR_ID && sensor.isUltrasonicUdfps()
                        && sensor.halHandlesDisplayTouches && !sensor.halControlsIllumination) ++matches;
            }
            DevicePolicyManager policy = mContext.getSystemService(DevicePolicyManager.class);
            PowerManager power = mContext.getSystemService(PowerManager.class);
            if (policy == null || power == null) throw new IllegalStateException("policy/power service not published");
            boolean enabled = matches == 1
                    && Settings.Secure.getIntForUser(mContext.getContentResolver(), ICON_SETTING, 1, user) == 1
                    && Settings.Secure.getIntForUser(mContext.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, user) == 0
                    && (policy.getKeyguardDisabledFeatures(null, user)
                            & DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT) == 0
                    && !power.isPowerSaveMode() && simAllowsFingerprint()
                    && mFingerprint.isHardwareDetected(SENSOR_ID, mContext.getOpPackageName())
                    && mFingerprint.hasEnrolledFingerprints(SENSOR_ID, user, mContext.getOpPackageName());
            if (user != ActivityManager.getCurrentUser()) throw new IllegalStateException("user changed during read");
            if (!mBinder.isBinderAlive()) throw new RemoteException("fingerprint service died during read");
            synchronized (mGuard) {
                if (epoch != mEpoch) return;
                mExpectedUser = user;
                mRetries = 0;
                publishLocked(new Snapshot(user, enabled, enabled ? "qualified" : "policy_or_hardware_disabled"));
            }
        } catch (RemoteException | RuntimeException error) {
            synchronized (mGuard) {
                if (epoch != mEpoch) return;
                publishLocked(new Snapshot(UserHandle.USER_NULL, false, "read_or_registration_unknown"));
                if (++mRetries <= MAX_RETRIES) scheduleLocked(1000);
            }
            Slog.w(TAG, "FOD dream eligibility remains unknown", error);
        } finally {
            mDreamHandler.removeCallbacks(deadline);
        }
    }
}
