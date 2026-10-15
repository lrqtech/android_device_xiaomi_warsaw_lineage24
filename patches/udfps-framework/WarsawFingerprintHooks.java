package com.android.server.biometrics.sensors;

import android.app.ActivityManager;
import android.app.UserSwitchObserver;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

public final class WarsawFingerprintHooks {
    private static final String TAG = "WarsawFingerprint";
    private static final String DESCRIPTOR =
            "vendor.xiaomi.hardware.fingerprintextension.IXiaomiFingerprint";
    private static final int EXT_CMD_TRANSACTION = 1;
    private static final int CMD_MONITOR = 4;
    private static final int CMD_KEYGUARD_LOW_BRIGHTNESS = 7;
    private static final int CMD_AUTH_CLIENT = 18;
    private static final int ENROLL_START = 1;
    private static final int ENROLL_STOP = 2;
    private static final int AUTH_START = 3;
    private static final int AUTH_STOP = 4;
    private static final long RECONNECT_MS = 1000;
    private static final boolean ENABLED = "warsaw".equals(Build.DEVICE)
            && "2.0".equals(SystemProperties.get("ro.hardware.fp.fod.touch.ctl.version"));

    private static final class Holder {
        static final WarsawFingerprintHooks INSTANCE = new WarsawFingerprintHooks();
    }

    private final Object mLock = new Object();
    private final Object mIoLock = new Object();
    private final Handler mWorker;
    private final Runnable mDrain = this::drain;
    private BaseClientMonitor mClient;
    private Desired mDesired;
    private Desired mDelivered;
    private long mGeneration;
    private IBinder mBinder;
    private IBinder.DeathRecipient mDeath;
    private int mSwitchingToUser = UserHandle.USER_NULL;
    private volatile boolean mUserObserverRegistered;
    private boolean mUserObserverFailureReported;

    private static final class Desired {
        final long generation;
        final int monitor;
        final boolean keyguard;
        final int owner;
        final int foregroundUser;
        final boolean readyForHal;
        Desired(long generation, int monitor, boolean keyguard, int owner, int foregroundUser,
                boolean readyForHal) {
            this.generation = generation;
            this.monitor = monitor;
            this.keyguard = keyguard;
            this.owner = owner;
            this.foregroundUser = foregroundUser;
            this.readyForHal = readyForHal;
        }
    }

    private WarsawFingerprintHooks() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mWorker = new Handler(thread.getLooper());
        registerUserObserver();
    }

    static void onClientStarted(BaseClientMonitor client) {
        if (!isWarsawFingerprint(client)) return;
        if (!(client instanceof AuthenticationClient) && !(client instanceof EnrollClient)) return;
        Holder.INSTANCE.started(client);
    }

    static void onClientFinished(BaseClientMonitor client) {
        if (!isWarsawFingerprint(client)) return;
        if (!(client instanceof AuthenticationClient) && !(client instanceof EnrollClient)) return;
        Holder.INSTANCE.finished(client);
    }

    static void onSchedulerReset(BiometricSchedulerOperation operation) {
        if (!ENABLED || operation == null || operation.getSensorId() != 5) return;
        Holder.INSTANCE.schedulerReset(operation);
    }

    public static void beforeHalStart(BaseClientMonitor client) {
        if (!isWarsawFingerprint(client)) return;
        Holder.INSTANCE.prepareHalStart(client);
    }

    public static void beforeHalStop(BaseClientMonitor client) {
        if (!isWarsawFingerprint(client)) return;
        Holder.INSTANCE.prepareHalStop(client);
    }

    private void prepareHalStart(BaseClientMonitor client) {
        started(client);
        synchronized (mLock) {
            if (mClient != client) return;
            Desired old = mDesired;
            mDesired = new Desired(++mGeneration, old.monitor, old.keyguard, old.owner,
                    old.foregroundUser, true);
        }
        drain();
    }

    private void prepareHalStop(BaseClientMonitor client) {
        synchronized (mLock) {
            if (mClient != client) return;
            stopLocked(client);
        }
        drain();
    }

    private final UserSwitchObserver mUserObserver = new UserSwitchObserver() {
        @Override public void onBeforeUserSwitching(int newUserId, IRemoteCallback reply)
                throws RemoteException {
            changingUser(newUserId);
            if (reply != null) reply.sendResult(null);
        }
        @Override public void onUserSwitching(int newUserId, IRemoteCallback reply)
                throws RemoteException {
            changingUser(newUserId);
            if (reply != null) reply.sendResult(null);
        }
        @Override public void onUserSwitchComplete(int newUserId) {
            synchronized (mLock) { mSwitchingToUser = UserHandle.USER_NULL; }
        }
    };

    private void registerUserObserver() {
        if (mUserObserverRegistered) return;
        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserObserver, TAG);
            mUserObserverRegistered = true;
            mUserObserverFailureReported = false;
            synchronized (mLock) { scheduleLocked(0); }
        } catch (RemoteException | RuntimeException failure) {
            if (!mUserObserverFailureReported) Slog.w(TAG, "User observer unavailable", failure);
            mUserObserverFailureReported = true;
            mWorker.postDelayed(this::registerUserObserver, RECONNECT_MS);
        }
    }

    private void changingUser(int newUserId) {
        synchronized (mLock) {
            mSwitchingToUser = newUserId;
            if (mClient != null && mDesired.foregroundUser != newUserId) stopLocked(mClient);
        }
    }

    private static boolean isWarsawFingerprint(BaseClientMonitor client) {
        if (!ENABLED || client == null || client.getSensorId() != 5) return false;
        String name = client.getClass().getName();
        return name.equals("com.android.server.biometrics.sensors.fingerprint.aidl."
                    + "FingerprintAuthenticationClient")
                || name.equals("com.android.server.biometrics.sensors.fingerprint.aidl."
                    + "FingerprintEnrollClient");
    }

    private void started(BaseClientMonitor client) {
        final int foregroundUser = ActivityManager.getCurrentUser();
        if (client.getTargetUserId() != foregroundUser) {
            try {
                UserManager users = client.getContext().getSystemService(UserManager.class);
                UserInfo parent = users == null ? null : users.getProfileParent(client.getTargetUserId());
                if (parent == null || parent.id != foregroundUser) return;
            } catch (RuntimeException failure) {
                Slog.w(TAG, "Cannot establish fingerprint client's foreground user", failure);
                return;
            }
        }
        synchronized (mLock) {
            if (mClient == client) return;
            if (mSwitchingToUser != UserHandle.USER_NULL && mSwitchingToUser != foregroundUser) return;
            mClient = client;
            boolean auth = client instanceof AuthenticationClient;
            boolean keyguard = auth && ((AuthenticationClient<?, ?>) client).isKeyguard();
            mDesired = new Desired(++mGeneration, auth ? AUTH_START : ENROLL_START,
                    keyguard, ownerCategory(client.getOwnerString()), foregroundUser, false);
        }
    }

    private void finished(BaseClientMonitor client) {
        synchronized (mLock) {
            if (mClient != client) return;
            stopLocked(client);
        }
    }

    private void schedulerReset(BiometricSchedulerOperation operation) {
        synchronized (mLock) {
            if (mClient != null && operation.isFor(mClient)) stopLocked(mClient);
        }
    }

    private void stopLocked(BaseClientMonitor client) {
        final int foregroundUser = mDesired.foregroundUser;
        mClient = null;
        mDesired = new Desired(++mGeneration,
                client instanceof EnrollClient ? ENROLL_STOP : AUTH_STOP, false, 0, foregroundUser, true);
        scheduleLocked(0);
    }

    private static int ownerCategory(String owner) {
        if ("com.android.systemui".equals(owner)) return 1;
        if ("com.miui.securitycenter".equals(owner)) return 2;
        if ("com.tencent.mm".equals(owner) || "com.eg.android.AlipayGphone".equals(owner)) return 3;
        return 4;
    }

    private void scheduleLocked(long delay) {
        mWorker.removeCallbacks(mDrain);
        mWorker.postDelayed(mDrain, delay);
    }

    private boolean current(Desired desired) {
        final int foregroundUser = ActivityManager.getCurrentUser();
        synchronized (mLock) {
            if (mDesired != desired) return false;
            if (mClient != null && (desired.foregroundUser != foregroundUser
                    || (mSwitchingToUser != UserHandle.USER_NULL
                            && mSwitchingToUser != desired.foregroundUser))) {
                stopLocked(mClient);
                return false;
            }
            return true;
        }
    }

    private void drain() {
        synchronized (mIoLock) { drainSerialized(); }
    }

    private void drainSerialized() {
        final Desired desired;
        synchronized (mLock) {
            desired = mDesired;
        }
        if (desired == null || !desired.readyForHal) return;
        if (!mUserObserverRegistered) return;
        IBinder binder = null;
        try {
            binder = connect();
            if (binder == null) throw new RemoteException("FOD extension unavailable");
            if (!current(desired)) return;
            synchronized (mLock) { if (mDelivered == desired) return; }
            command(binder, CMD_MONITOR, desired.monitor);
            if (!current(desired)) return;
            command(binder, CMD_KEYGUARD_LOW_BRIGHTNESS, desired.keyguard ? 1 : 0);
            if (desired.monitor == AUTH_START && current(desired)) {
                command(binder, CMD_AUTH_CLIENT, desired.owner);
            }
            synchronized (mLock) {
                if (mDesired == desired && mBinder == binder) mDelivered = desired;
            }
        } catch (RemoteException | RuntimeException failure) {
            Slog.w(TAG, "FOD lifecycle delivery failed; retrying latest state", failure);
            disconnect(binder);
            synchronized (mLock) {
                scheduleLocked(mDesired == desired ? RECONNECT_MS : 0);
            }
        }
    }

    private IBinder connect() throws RemoteException {
        synchronized (mLock) {
            if (mBinder != null) return mBinder;
        }
        IBinder binder = ServiceManager.checkService(DESCRIPTOR + "/default");
        if (binder == null) return null;
        Binder.allowBlocking(binder);
        IBinder.DeathRecipient death = () -> {
            synchronized (mLock) {
                if (mBinder != binder) return;
                mBinder = null;
                mDeath = null;
                mDelivered = null;
                scheduleLocked(0);
            }
        };
        synchronized (mLock) {
            binder.linkToDeath(death, 0);
            mBinder = binder;
            mDeath = death;
            mDelivered = null;
        }
        return binder;
    }

    private void disconnect(IBinder binder) {
        final IBinder.DeathRecipient death;
        synchronized (mLock) {
            if (binder == null || mBinder != binder) return;
            death = mDeath;
            mBinder = null;
            mDeath = null;
            mDelivered = null;
        }
        if (death != null) {
            try { binder.unlinkToDeath(death, 0); }
            catch (java.util.NoSuchElementException alreadyRemoved) {   }
            catch (RuntimeException failure) { Slog.w(TAG, "FOD death cleanup failed", failure); }
        }
    }

    private static void command(IBinder binder, int command, int value) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(command);
            request.writeInt(value);
            if (!binder.transact(EXT_CMD_TRANSACTION, request, reply, 0)) {
                throw new RemoteException("FOD extCmd transaction rejected");
            }
            reply.readException();
            if (reply.dataAvail() < Integer.BYTES) throw new RemoteException("Truncated FOD extCmd reply");
            int result = reply.readInt();
            if (result != 0) throw new RemoteException("FOD extCmd returned " + result);
        } finally {
            reply.recycle();
            request.recycle();
        }
    }
}
