package com.android.server.biometrics.sensors;

import android.app.ActivityManager;
import android.app.UserSwitchObserver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseIntArray;

public final class WarsawUdfpsService extends Binder {
    private static final String TAG = "WarsawUdfpsService";
    private static final String SERVICE = "warsaw.udfps.ui";
    private static final String DESCRIPTOR = "org.lineageos.warsaw.IUdfpsUi";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final int SET_STATE = IBinder.FIRST_CALL_TRANSACTION;
    private static final long RETRY_MS = 1000;
    private static WarsawUdfpsService sInstance;

    public static synchronized void publish(Context context) {
        if (!"warsaw".equals(Build.DEVICE) || !"2.0".equals(SystemProperties.get(
                "ro.hardware.fp.fod.touch.ctl.version"))) return;
        if (sInstance != null) throw new IllegalStateException("Warsaw UDFPS service already published");
        WarsawUdfpsService service = new WarsawUdfpsService(context);
        ServiceManager.addService(SERVICE, service);
        sInstance = service;
    }

    private final Context mContext;
    private final Object mLock = new Object();
    private final Handler mWorker;
    private final Runnable mDrain = this::drain;
    private final HalWire mTouch = new HalWire("vendor.xiaomi.hw.touchfeature.ITouchFeature", 9, true);
    private final HalWire mExtension = new HalWire(
            "vendor.xiaomi.hardware.fingerprintextension.IXiaomiFingerprint", 1, false);
    private IBinder mOwner;
    private IBinder.DeathRecipient mOwnerDeath;
    private int mOwnerUid = -1;
    private int mSwitchingToUser = UserHandle.USER_NULL;
    private State mState;
    private boolean mUserObserverRegistered;
    private boolean mObserverFailureReported;

    private static final class State {
        final int user;
        final boolean icon;
        final boolean lowlight;
        final int nonUi;
        State(int user, boolean icon, boolean lowlight, int nonUi) {
            this.user = user;
            this.icon = icon;
            this.lowlight = lowlight;
            this.nonUi = nonUi;
        }
    }

    private WarsawUdfpsService(Context context) {
        mContext = context;
        attachInterface(null, DESCRIPTOR);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mWorker = new Handler(thread.getLooper());
        mWorker.post(this::registerUserObserver);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code != SET_STATE) return super.onTransact(code, data, reply, flags);
        if ((flags & IBinder.FLAG_ONEWAY) != 0 || reply == null) {
            throw new IllegalArgumentException("State submission requires a reply");
        }
        data.enforceInterface(DESCRIPTOR);
        final int callingUid = Binder.getCallingUid();
        final IBinder owner = data.readStrongBinder();
        if (data.dataAvail() < 4 * Integer.BYTES) throw new IllegalArgumentException("Truncated state");
        final int user = data.readInt();
        final int icon = data.readInt();
        final int lowlight = data.readInt();
        final int nonUi = data.readInt();
        data.enforceNoDataAvail();
        if (owner == null || user < 0 || (icon != 0 && icon != 1)
                || (lowlight != 0 && lowlight != 1) || nonUi < 0 || nonUi > 2
                || (icon == 0 && nonUi != 0)) {
            throw new IllegalArgumentException("Invalid fixed UDFPS state");
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            enforceSystemUi(callingUid);
            if (user != ActivityManager.getCurrentUser()) {
                throw new SecurityException("State is for a different foreground user");
            }
            submit(owner, callingUid, new State(user, icon == 1, lowlight == 1, nonUi));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        reply.writeNoException();
        reply.writeInt(0);
        return true;
    }

    private void enforceSystemUi(int uid) {
        final PackageManager packages = mContext.getPackageManager();
        try {
            int packageUid = packages.getPackageUidAsUser(SYSTEM_UI, PackageManager.MATCH_SYSTEM_ONLY,
                    UserHandle.getUserId(uid));
            if (uid != packageUid || packages.checkSignatures(Process.SYSTEM_UID, uid)
                    != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException("Only platform-signed SystemUI may control this state");
            }
        } catch (PackageManager.NameNotFoundException absent) {
            throw new SecurityException("SystemUI is not installed for the calling UID", absent);
        }
    }

    private void submit(IBinder owner, int uid, State state) throws RemoteException {
        final int foregroundUser = ActivityManager.getCurrentUser();
        synchronized (mLock) {
            if (state.user != foregroundUser
                    || (mSwitchingToUser != UserHandle.USER_NULL && state.user != mSwitchingToUser)) {
                throw new SecurityException("Foreground user changed during submission");
            }
            if (mOwner != null && (mOwner != owner || mOwnerUid != uid)) {
                throw new IllegalStateException("Another SystemUI owner is still active");
            }
            if (mOwner == null) {
                IBinder.DeathRecipient death = () -> ownerDied(owner);
                owner.linkToDeath(death, 0);
                mOwner = owner;
                mOwnerUid = uid;
                mOwnerDeath = death;
            }
            mState = state;
            scheduleLocked(0);
        }
    }

    private void ownerDied(IBinder owner) {
        synchronized (mLock) {
            if (mOwner != owner) return;
            mOwner = null;
            mOwnerUid = -1;
            mOwnerDeath = null;
            mState = new State(UserHandle.USER_NULL, false, false, 0);
            scheduleLocked(0);
        }
    }

    private void clearOwnerLocked() {
        final IBinder owner = mOwner;
        final IBinder.DeathRecipient death = mOwnerDeath;
        mOwner = null;
        mOwnerDeath = null;
        mOwnerUid = -1;
        if (owner != null && death != null) {
            try { owner.unlinkToDeath(death, 0); }
            catch (java.util.NoSuchElementException alreadyRemoved) {   }
            catch (RuntimeException failure) { Slog.w(TAG, "Owner cleanup failed", failure); }
        }
        mState = new State(UserHandle.USER_NULL, false, false, 0);
        scheduleLocked(0);
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

    private void changingUser(int newUserId) {
        synchronized (mLock) {
            mSwitchingToUser = newUserId;
            if (mOwner != null && mState.user != newUserId) clearOwnerLocked();
        }
    }

    private void registerUserObserver() {
        if (mUserObserverRegistered) return;
        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserObserver, TAG);
            mUserObserverRegistered = true;
            mObserverFailureReported = false;
            synchronized (mLock) { scheduleLocked(0); }
        } catch (RemoteException | RuntimeException failure) {
            if (!mObserverFailureReported) Slog.e(TAG, "User observer unavailable", failure);
            mObserverFailureReported = true;
            mWorker.postDelayed(this::registerUserObserver, RETRY_MS);
        }
    }

    private void scheduleLocked(long delay) {
        mWorker.removeCallbacks(mDrain);
        mWorker.postDelayed(mDrain, delay);
    }

    private boolean current(State state) {
        final int user = ActivityManager.getCurrentUser();
        synchronized (mLock) {
            if (state != mState) return false;
            if (mOwner != null && (state.user != user
                    || (mSwitchingToUser != UserHandle.USER_NULL && state.user != mSwitchingToUser))) {
                clearOwnerLocked();
                return false;
            }
            return true;
        }
    }

    private void drain() {
        if (!mUserObserverRegistered) return;
        final State state;
        synchronized (mLock) { state = mState; }
        if (state == null || !current(state)) return;
        boolean ok = mTouch.send(16, state.icon ? 1 : 0);
        if (!current(state)) return;
        ok &= mTouch.send(17, state.nonUi);
        if (!current(state)) return;
        ok &= mExtension.send(6, state.lowlight ? 1 : 0);
        if (!ok) {
            synchronized (mLock) { scheduleLocked(mState == state ? RETRY_MS : 0); }
        }
    }

    private final class HalWire {
        private final String mDescriptor;
        private final int mTransaction;
        private final boolean mTouchDeviceId;
        private final SparseIntArray mSent = new SparseIntArray();
        private IBinder mBinder;
        private IBinder.DeathRecipient mDeath;
        private boolean mFailureReported;
        HalWire(String descriptor, int transaction, boolean touchDeviceId) {
            mDescriptor = descriptor;
            mTransaction = transaction;
            mTouchDeviceId = touchDeviceId;
        }
        boolean send(int mode, int value) {
            try {
                if (mBinder == null) {
                    final IBinder binder = ServiceManager.checkService(mDescriptor + "/default");
                    if (binder == null) throw new RemoteException("HAL service unavailable");
                    Binder.allowBlocking(binder);
                    IBinder.DeathRecipient death = () -> mWorker.post(() -> {
                        if (mBinder != binder) return;
                        mBinder = null;
                        mDeath = null;
                        mSent.clear();
                        synchronized (mLock) { scheduleLocked(0); }
                    });
                    binder.linkToDeath(death, 0);
                    mBinder = binder;
                    mDeath = death;
                    mSent.clear();
                }
                if (mSent.indexOfKey(mode) >= 0 && mSent.get(mode) == value) return true;
                Parcel request = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    request.writeInterfaceToken(mDescriptor);
                    if (mTouchDeviceId) request.writeInt(0);
                    request.writeInt(mode);
                    request.writeInt(value);
                    if (!mBinder.transact(mTransaction, request, reply, 0)) {
                        throw new RemoteException("HAL transaction unsupported");
                    }
                    reply.readException();
                    if (reply.dataAvail() < Integer.BYTES || reply.readInt() != 0) {
                        throw new RemoteException("HAL state rejected or truncated");
                    }
                    mSent.put(mode, value);
                    mFailureReported = false;
                    return true;
                } finally { reply.recycle(); request.recycle(); }
            } catch (RemoteException | RuntimeException failure) {
                if (!mFailureReported) Slog.e(TAG, mDescriptor + " delivery failed", failure);
                mFailureReported = true;
                final IBinder binder = mBinder;
                final IBinder.DeathRecipient death = mDeath;
                mBinder = null;
                mDeath = null;
                mSent.clear();
                if (binder != null && death != null) {
                    try { binder.unlinkToDeath(death, 0); }
                    catch (java.util.NoSuchElementException alreadyRemoved) {   }
                    catch (RuntimeException cleanupFailure) { Slog.w(TAG, "HAL cleanup failed", cleanupFailure); }
                }
                return false;
            }
        }
    }
}
