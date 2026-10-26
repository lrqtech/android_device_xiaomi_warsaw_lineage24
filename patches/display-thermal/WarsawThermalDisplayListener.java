package com.android.server.display;

import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import java.util.ArrayList;

final class WarsawThermalDisplayListener {
    private static final String TAG = "WarsawThermalDisplay";
    private static final String DESCRIPTOR = "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature";
    private static final String CALLBACK = DESCRIPTOR + "Callback";
    private static final String SERVICE = DESCRIPTOR + "/default";
    private static final int REGISTER = 2;
    private static final int UNREGISTER = 10;
    private static final int DOLBY_EVENT = 50000;
    private static final int MAX_ATTEMPTS = 5;
    private static WarsawThermalDisplayListener sInstance;

    interface Listener { void onDolbyState(boolean known, boolean enabled); }

    static synchronized Subscription subscribe(Handler target, Listener listener) {
        if (sInstance == null) sInstance = new WarsawThermalDisplayListener();
        Subscription result = sInstance.new Subscription(target, listener);
        sInstance.mWorker.post(() -> sInstance.attach(result));
        return result;
    }

    private static final class State {
        static final State UNKNOWN = new State(null, false);
        final Connection owner;
        final boolean known, enabled;
        State(Connection owner, boolean enabled) {
            this.owner = owner;
            this.known = owner != null;
            this.enabled = enabled;
        }
    }

    final class Subscription implements AutoCloseable {
        private final Handler mTarget;
        private final Listener mListener;
        private State mPending;
        private boolean mQueued;
        private volatile boolean mClosed;
        Subscription(Handler target, Listener listener) { mTarget = target; mListener = listener; }
        private final Runnable mDeliver = this::deliverPending;
        private void deliverPending() {
            State next;
            synchronized (this) { next = mPending; mPending = null; mQueued = false; }
            if (next != null && next.known && next.owner != mConnection) next = State.UNKNOWN;
            if (!mClosed && next != null) mListener.onDolbyState(next.known, next.enabled);
        }
        private synchronized void deliver(State state) {
            if (mClosed) return;
            mPending = state;
            if (!mQueued) {
                mQueued = true;
                if (!mTarget.post(mDeliver)) { mQueued = false; mPending = null; }
            }
        }
        void refresh() {
            if (!mClosed) requestRefresh();
        }
        @Override public void close() {
            synchronized (this) {
                if (mClosed) return;
                mClosed = true;
                mPending = null;
            }
            mTarget.removeCallbacks(mDeliver);
            mWorker.post(() -> {
                mSubscribers.remove(this);
                resetAttempts();
                reconcile();
            });
        }
    }

    private final Handler mWorker;
    private final ArrayList<Subscription> mSubscribers = new ArrayList<>();
    private final Object mEventLock = new Object();
    private Connection mPendingOwner;
    private int mPendingValue;
    private boolean mEventQueued;
    private boolean mRefreshQueued;
    private volatile Connection mConnection;
    private State mState = State.UNKNOWN;
    private int mAttempts;
    private boolean mRefreshRegistration;

    private WarsawThermalDisplayListener() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mWorker = new Handler(thread.getLooper());
    }

    private void attach(Subscription subscriber) {
        if (subscriber.mClosed) return;
        mSubscribers.add(subscriber);
        subscriber.deliver(mState);
        resetAttempts();
        reconcile();
    }

    private void publish(State next) {
        if (next.known == mState.known && next.enabled == mState.enabled) return;
        mState = next;
        for (Subscription subscriber : mSubscribers) subscriber.deliver(next);
    }

    private void requestRefresh() {
        synchronized (mEventLock) {
            if (mRefreshQueued) return;
            mRefreshQueued = true;
            mWorker.post(() -> {
                synchronized (mEventLock) { mRefreshQueued = false; }
                if (mSubscribers.isEmpty()) return;
                resetAttempts();
                mRefreshRegistration = !mState.known;
                reconcile();
            });
        }
    }

    private final Runnable mRetry = this::reconcile;

    private void resetAttempts() {
        mAttempts = 0;
        mWorker.removeCallbacks(mRetry);
    }

    private void retry(Exception error) {
        publish(State.UNKNOWN);
        Slog.w(TAG, "Display event observer unavailable; state remains unknown", error);
        if (++mAttempts < MAX_ATTEMPTS) {
            mWorker.removeCallbacks(mRetry);
            mWorker.postDelayed(mRetry, 1000L << (mAttempts - 1));
        } else {
            Slog.w(TAG, "Retry budget exhausted; retaining token until death or lifecycle refresh");
        }
    }

    private void reconcile() {
        Connection current = mConnection;
        if (current != null && !current.binder.isBinderAlive()) disconnect(current);
        current = mConnection;
        if (current != null && (mSubscribers.isEmpty() || !current.registered
                || mRefreshRegistration)) {
            try {
                // A failed reply may still mean the HAL removed the callback. Keep the
                // token for cleanup, but never reuse it as a confirmed registration.
                current.registered = false;
                if (current.mayOwnSlot) wire(current, UNREGISTER);
                current.mayOwnSlot = false;
                disconnect(current);
                mRefreshRegistration = false;
            } catch (RemoteException | RuntimeException e) {
                retry(e);
                return;
            }
        }
        if (mSubscribers.isEmpty() || mConnection != null) return;
        try {
            IBinder binder = ServiceManager.checkService(SERVICE);
            if (binder == null) throw new RemoteException("Display HAL not published");
            if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                throw new RemoteException("Unexpected display HAL descriptor");
            }
            Connection next = new Connection(binder);
            binder.linkToDeath(next, 0);
            mConnection = next;
            next.mayOwnSlot = true;
            wire(next, REGISTER);
            next.registered = true;
            mRefreshRegistration = false;
            mAttempts = 0;
        } catch (RemoteException | RuntimeException e) {
            retry(e);
        }
    }

    private static void wire(Connection connection, int operation) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(0);
            data.writeStrongBinder(connection.callback);
            if (!connection.binder.transact(operation, data, reply, 0)) {
                throw new RemoteException("Display observer transaction rejected");
            }
            if (reply.dataAvail() < Integer.BYTES) throw new RemoteException("Truncated display reply");
            reply.readException();
            reply.enforceNoDataAvail();
        } finally { data.recycle(); reply.recycle(); }
    }

    private void disconnect(Connection previous) {
        if (mConnection != previous) return;
        mConnection = null;
        synchronized (mEventLock) { mPendingOwner = null; }
        try { previous.binder.unlinkToDeath(previous, 0); }
        catch (RuntimeException e) { Slog.w(TAG, "Display death cleanup failed", e); }
        publish(State.UNKNOWN);
    }

    private final class Connection implements IBinder.DeathRecipient {
        final IBinder binder;
        final Callback callback = new Callback(this);
        boolean mayOwnSlot;
        boolean registered;
        Connection(IBinder binder) { this.binder = binder; }
        @Override public void binderDied() {
            mWorker.post(() -> {
                if (mConnection != this) return;
                disconnect(this);
                resetAttempts();
                reconcile();
            });
        }
    }

    private final Runnable mDeliverEvent = () -> {
        Connection owner;
        int value;
        synchronized (mEventLock) {
            owner = mPendingOwner;
            value = mPendingValue;
            mPendingOwner = null;
            mEventQueued = false;
        }
        if (owner != null && owner == mConnection && owner.registered && !mSubscribers.isEmpty()) {
            publish(new State(owner, value == 2));
        }
    };

    private final class Callback extends Binder implements IInterface {
        private final Connection mOwner;
        Callback(Connection owner) {
            mOwner = owner;
            markVintfStability();
            attachInterface(this, CALLBACK);
        }
        @Override public IBinder asBinder() { return this; }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK);
                return true;
            }
            if (code != 1) return super.onTransact(code, data, reply, flags);
            if ((flags & IBinder.FLAG_ONEWAY) == 0) return false;
            data.enforceInterface(CALLBACK);
            if (data.dataAvail() < 5 * Integer.BYTES) throw new IllegalArgumentException("Short display event");
            int event = data.readInt();
            int value = data.readInt();
            data.readFloat();
            data.readFloat();
            data.readFloat();
            data.enforceNoDataAvail();
            if (event != DOLBY_EVENT) return true;
            synchronized (mEventLock) {
                if (mOwner != mConnection) return true;
                mPendingOwner = mOwner;
                mPendingValue = value;
                if (!mEventQueued) {
                    mEventQueued = true;
                    mWorker.post(mDeliverEvent);
                }
            }
            return true;
        }
    }
}
