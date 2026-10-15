package com.android.systemui.doze;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.view.Display;

import com.android.systemui.biometrics.WarsawUdfpsAodVisibility;
import com.android.systemui.biometrics.WarsawUdfpsTouchLifecycle;
import com.android.systemui.dock.DockManager;
import com.android.systemui.settings.UserTracker;

import java.io.PrintWriter;
import javax.inject.Inject;

public final class WarsawUdfpsDoze implements DozeMachine.Part {
    private static final long DRAW_TIMEOUT_MS = 3000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WarsawUdfpsDoze sOwner;
    private final DozeHost mHost;
    private final AmbientDisplayConfiguration mConfig;
    private final UserTracker mUsers;
    private final DockManager mDock;
    private final PowerManager.WakeLock mDraw;
    private DozeMachine mMachine;
    private DozeMachine.State mState = DozeMachine.State.UNINITIALIZED;
    private int mScreen = Display.STATE_UNKNOWN;
    private int mUser = UserHandle.USER_NULL;
    private long mRequestEpoch = -1;
    private boolean mStarted;
    private boolean mFinished;
    private boolean mSwitching;
    private boolean mWanted;
    private boolean mOwnsAod;
    private boolean mRequestingAod;
    private boolean mScheduled;

    @Inject
    public WarsawUdfpsDoze(Context context, DozeHost host,
            AmbientDisplayConfiguration config, UserTracker users, DockManager dock) {
        mHost = host;
        mConfig = config;
        mUsers = users;
        mDock = dock;
        mDraw = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.DRAW_WAKE_LOCK, "WarsawUdfps:temporaryDraw");
        mDraw.setReferenceCounted(false);
    }

    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("UDFPS Doze ownership is main-thread only");
        }
    }

    public static void publish(int user, long epoch, boolean visible) {
        mainThread();
        WarsawUdfpsDoze owner = sOwner;
        if (owner == null || !owner.mStarted || owner.mFinished) return;
        if (epoch < owner.mRequestEpoch) return;
        owner.mRequestEpoch = epoch;
        owner.mWanted = visible && !owner.mSwitching && user == owner.mUser
                && user == ActivityManager.getCurrentUser();
        if (!owner.mWanted) {
            owner.releaseDraw();
            WarsawUdfpsAodVisibility.setTemporaryDozeVisible(false);
        }
        owner.schedule();
    }

    @Override public void setDozeMachine(DozeMachine machine) { mMachine = machine; }

    @Override public void transitionTo(DozeMachine.State oldState, DozeMachine.State next) {
        mainThread();
        mState = next;
        if (next == DozeMachine.State.FINISH) {
            destroy();
            return;
        }
        if (next == DozeMachine.State.INITIALIZED && "warsaw".equals(Build.DEVICE)) {
            if (mFinished || mStarted) return;
            if (sOwner != null && sOwner != this) sOwner.destroy();
            sOwner = this;
            mStarted = true;
            mUser = mUsers.getUserId();
            mSwitching = mUsers.isUserSwitching();
            mHost.addCallback(mHostCallback);
            mUsers.addCallback(mUserCallback, MAIN::post);
            WarsawUdfpsTouchLifecycle.refreshDozeRequest();
        }
        if (mRequestingAod && next == DozeMachine.State.DOZE_AOD) mOwnsAod = true;
        if (next != DozeMachine.State.DOZE_AOD
                && next != DozeMachine.State.DOZE_AOD_PAUSING
                && next != DozeMachine.State.DOZE_AOD_PAUSED) {
            mOwnsAod = false;
        }
        if (!mOwnsAod) releaseDraw();
        publishDrawing();
        schedule();
    }

    @Override public void onScreenState(int state) {
        mainThread();
        mScreen = state;
        publishDrawing();
    }

    private boolean allowed() {
        return mStarted && !mFinished && sOwner == this && !mSwitching
                && mUser == ActivityManager.getCurrentUser() && mUser == mUsers.getUserId()
                && !mHost.isPowerSaveActive() && !mHost.isAlwaysOnSuppressed()
                && mHost.isProvisioned() && !mDock.isDocked();
    }

    private void publishDrawing() {
        if (sOwner != this) return;
        WarsawUdfpsAodVisibility.setTemporaryDozeVisible(allowed() && mWanted && mOwnsAod
                && mState == DozeMachine.State.DOZE_AOD
                && (mScreen == Display.STATE_DOZE || mScreen == Display.STATE_DOZE_SUSPEND));
    }

    private void schedule() {
        if (!mStarted || mFinished || mScheduled) return;
        mScheduled = true;
        MAIN.post(mReconcile);
    }

    private final Runnable mReconcile = this::reconcile;

    private void reconcile() {
        mScheduled = false;
        if (!mStarted || mFinished || sOwner != this || mMachine == null) return;
        if (mMachine.isExecutingTransition()) { schedule(); return; }
        if (!allowed() || !mWanted) {
            WarsawUdfpsAodVisibility.setTemporaryDozeVisible(false);
            boolean restore = mOwnsAod && !mDock.isDocked()
                    && !mConfig.alwaysOnEnabled(mUsers.getUserId())
                    && (mState == DozeMachine.State.DOZE_AOD
                        || mState == DozeMachine.State.DOZE_AOD_PAUSING
                        || mState == DozeMachine.State.DOZE_AOD_PAUSED);
            mOwnsAod = false;
            releaseDraw();
            if (restore) mMachine.requestState(DozeMachine.State.DOZE);
            return;
        }
        if (mState == DozeMachine.State.DOZE) {
            mDraw.acquire(DRAW_TIMEOUT_MS);
            mRequestingAod = true;
            try { mMachine.requestState(DozeMachine.State.DOZE_AOD); }
            finally { mRequestingAod = false; }
            if (!mOwnsAod) releaseDraw();
        }
        publishDrawing();
    }

    private final DozeHost.Callback mHostCallback = new DozeHost.Callback() {
        @Override public void onPowerSaveChanged(boolean active) {
            if (!mStarted || mFinished || sOwner != WarsawUdfpsDoze.this) return;
            if (active) { releaseDraw(); WarsawUdfpsAodVisibility.setTemporaryDozeVisible(false); }
            schedule();
        }
        @Override public void onAlwaysOnSuppressedChanged(boolean suppressed) {
            if (!mStarted || mFinished || sOwner != WarsawUdfpsDoze.this) return;
            if (suppressed) { releaseDraw(); WarsawUdfpsAodVisibility.setTemporaryDozeVisible(false); }
            schedule();
        }
        @Override public void onDozingChanged(boolean dozing) {
            if (!mStarted || mFinished || sOwner != WarsawUdfpsDoze.this) return;
            if (!dozing) {
                mWanted = false;
                releaseDraw();
                WarsawUdfpsAodVisibility.setTemporaryDozeVisible(false);
                schedule();
            }
        }
    };

    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override public void onBeforeUserSwitching(int newUser, Runnable completion) {
            try {
                if (!mStarted || mFinished || sOwner != WarsawUdfpsDoze.this) return;
                mSwitching = true;
                mWanted = false;
                releaseDraw();
                WarsawUdfpsAodVisibility.setTemporaryDozeVisible(false);
                schedule();
            } finally { completion.run(); }
        }
        @Override public void onUserChanged(int user, Context context) {
            if (!mStarted || mFinished || sOwner != WarsawUdfpsDoze.this) return;
            mUser = user;
            mSwitching = false;
            mWanted = false;
            schedule();
            WarsawUdfpsTouchLifecycle.refreshDozeRequest();
        }
    };

    private void releaseDraw() { if (mDraw.isHeld()) mDraw.release(); }

    @Override public void destroy() {
        mainThread();
        mFinished = true;
        mWanted = false;
        mOwnsAod = false;
        MAIN.removeCallbacks(mReconcile);
        mScheduled = false;
        releaseDraw();
        if (mStarted) {
            mHost.removeCallback(mHostCallback);
            mUsers.removeCallback(mUserCallback);
        }
        mStarted = false;
        if (sOwner == this) {
            WarsawUdfpsAodVisibility.setTemporaryDozeVisible(false);
            sOwner = null;
        }
    }

    @Override public void dump(PrintWriter writer) {
        writer.println(" WarsawUdfpsDoze started=" + mStarted + " finished=" + mFinished
                + " user=" + mUser + " epoch=" + mRequestEpoch + " requested=" + mWanted
                + " ownsTemporaryAod=" + mOwnsAod + " state=" + mState + " screen=" + mScreen
                + " drawLockHeld=" + mDraw.isHeld());
    }
}
