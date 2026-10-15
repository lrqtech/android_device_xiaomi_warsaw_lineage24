package com.android.server.warsaw;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.server.SystemService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class WarsawCameraService extends SystemService {
    private static final String TAG = "WarsawCameraDisplay";
    private static final String CLIENT = "org.lineageos.warsaw.ICameraDisplay";
    private static final String HAL = "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature";
    private static final int CAMERA_MODE = 14;
    private static final int MAX_OWNERS = 8;
    private static final long REQUEST_TIMEOUT_MS = 750;
    private static final long INITIAL_RETRY_MS = 250;
    private static final long MAX_RETRY_MS = 5000;
    private final Handler worker;
    private final WarsawCameraMindLifecycle cameraMind;
    private final Runnable flushTask = this::flush;
    private final Object lock = new Object();
    private final ArrayMap<IBinder, Owner> owners = new ArrayMap<>();
    private int foregroundUser = UserHandle.USER_NULL;
    private boolean ready;
    private long epoch;
    private long revision;
    private long retryDelayMs = INITIAL_RETRY_MS;
    private CompletableFuture<Boolean> pending;
    private IBinder hal;
    private IBinder.DeathRecipient halDeath;
    private int applied = -1;

    private final class Owner implements IBinder.DeathRecipient {
        final IBinder token;
        final int uid;
        final long epoch;
        Owner(IBinder token, int uid, long epoch) {
            this.token = token; this.uid = uid; this.epoch = epoch;
        }
        @Override public void binderDied() {
            synchronized (lock) {
                if (owners.get(token) != this) return;
                owners.remove(token);
                changedLocked(null);
            }
        }
    }

    public WarsawCameraService(Context context) {
        super(context);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        worker = new Handler(thread.getLooper());
        cameraMind = new WarsawCameraMindLifecycle(context, worker);
    }

    private static int actualForegroundUser() {
        long identity = Binder.clearCallingIdentity();
        try { return ActivityManager.getCurrentUser(); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public void onStart() {
        if ("warsaw".equals(Build.DEVICE)) {
            publishBinderService("warsaw.camera.display", new Endpoint());
        }
    }

    @Override public void onBootPhase(int phase) {
        if (!"warsaw".equals(Build.DEVICE)) return;
        if (phase == PHASE_BOOT_COMPLETED) {
            cameraMind.start();
            return;
        }
        if (phase != PHASE_ACTIVITY_MANAGER_READY) return;
        int current = actualForegroundUser();
        synchronized (lock) {
            foregroundUser = current;
            ready = true;
            epoch++;
            clearOwnersLocked();
            changedLocked(null);
        }
    }

    @Override public void onUserSwitching(TargetUser from, TargetUser to) {
        synchronized (lock) {
            foregroundUser = to.getUserIdentifier();
            epoch++;
            clearOwnersLocked();
            changedLocked(null);
        }
    }

    private int enforceCamera() {
        synchronized (lock) {
            if (!ready) throw new SecurityException("Camera display service is not ready");
        }
        int uid = Binder.getCallingUid();
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        boolean named = false;
        if (packages != null) for (String pkg : packages) named |= "com.android.camera".equals(pkg);
        if (!named || pm.checkSignatures(uid, android.os.Process.SYSTEM_UID)
                != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Only the platform-signed camera may request camera display mode");
        }
        int actual = actualForegroundUser();
        synchronized (lock) {
            if (!ready || UserHandle.getUserId(uid) != foregroundUser || actual != foregroundUser) {
                throw new SecurityException("Camera display mode belongs to the current foreground user");
            }
        }
        return uid;
    }

    private final class Endpoint extends Binder {
        @Override protected boolean onTransact(int code, Parcel request, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(CLIENT); return true; }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, request, reply, flags);
            if ((flags & FLAG_ONEWAY) != 0 || reply == null) throw new SecurityException("Synchronous call required");
            request.enforceInterface(CLIENT);
            int uid = enforceCamera();
            int mode = request.readInt();
            int value = request.readInt();
            IBinder token = request.readStrongBinder();
            request.enforceNoDataAvail();
            if (mode != CAMERA_MODE || (value != 0 && value != 1) || token == null) {
                throw new IllegalArgumentException("Invalid camera brightness request");
            }
            CompletableFuture<Boolean> result = requestOwner(token, uid, value != 0);
            boolean ok = false;
            try { ok = result.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception failure) { Slog.w(TAG, "Camera display response unavailable", failure); }
            reply.writeNoException();
            reply.writeBoolean(ok);
            return true;
        }
    }

    private static void safeUnlink(IBinder token, IBinder.DeathRecipient death) {
        try { token.unlinkToDeath(death, 0); }
        catch (RuntimeException failure) { Slog.w(TAG, "Death recipient already removed", failure); }
    }

    private void clearOwnersLocked() {
        for (int i = 0; i < owners.size(); i++) {
            Owner owner = owners.valueAt(i);
            safeUnlink(owner.token, owner);
        }
        owners.clear();
    }

    private CompletableFuture<Boolean> requestOwner(IBinder token, int uid, boolean active) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        int actual = actualForegroundUser();
        synchronized (lock) {
            if (UserHandle.getUserId(uid) != foregroundUser || actual != foregroundUser) {
                result.complete(false); return result;
            }
            Owner owner = owners.get(token);
            if (owner != null && (owner.uid != uid || owner.epoch != epoch)) {
                result.complete(false); return result;
            }
            if (active && owner == null) {
                if (owners.size() >= MAX_OWNERS) { result.complete(false); return result; }
                owner = new Owner(token, uid, epoch);
                try {
                    token.linkToDeath(owner, 0);
                    if (!token.isBinderAlive()) {
                        safeUnlink(token, owner);
                        result.complete(false); return result;
                    }
                } catch (RemoteException | RuntimeException dead) {
                    result.complete(false); return result;
                }
                owners.put(token, owner);
            } else if (!active && owner != null) {
                owners.remove(token);
                safeUnlink(token, owner);
            }
            changedLocked(result);
        }
        return result;
    }

    private void changedLocked(CompletableFuture<Boolean> result) {
        revision++;
        if (pending != null) pending.complete(false);
        pending = result;
        retryDelayMs = INITIAL_RETRY_MS;
        worker.removeCallbacks(flushTask);
        worker.post(flushTask);
    }

    private void flush() {
        long wantedRevision;
        long wantedEpoch;
        int wantedUser;
        int desired;
        boolean wantedReady;
        CompletableFuture<Boolean> response;
        synchronized (lock) {
            wantedRevision = revision;
            wantedEpoch = epoch;
            wantedUser = foregroundUser;
            desired = owners.isEmpty() ? 0 : 1;
            wantedReady = ready;
            response = pending;
        }
        boolean ok = false;
        try {
            if (!wantedReady) return;
            if (desired != 0 && actualForegroundUser() != wantedUser) {
                synchronized (lock) {
                    if (epoch == wantedEpoch) {
                        epoch++;
                        clearOwnersLocked();
                        changedLocked(null);
                    }
                }
            } else if (connectHal()) {
                int actual = actualForegroundUser();
                synchronized (lock) {
                    if (revision != wantedRevision || epoch != wantedEpoch) return;
                    if (desired != 0 && actual != wantedUser) {
                        epoch++;
                        clearOwnersLocked();
                        changedLocked(null);
                        return;
                    }
                }
                ok = applied == desired || applyDesired(desired);
            }
        } catch (RuntimeException failure) {
            Slog.e(TAG, "Display worker rejected update", failure);
            clearHal();
        } finally {
            synchronized (lock) {
                if (response != null) response.complete(ok && revision == wantedRevision && epoch == wantedEpoch);
                if (pending == response) pending = null;
                worker.removeCallbacks(flushTask);
                if (revision != wantedRevision) {
                    retryDelayMs = INITIAL_RETRY_MS;
                    worker.post(flushTask);
                } else if (!ok && ready) {
                    worker.postDelayed(flushTask, retryDelayMs);
                    retryDelayMs = Math.min(MAX_RETRY_MS, retryDelayMs * 2);
                } else {
                    retryDelayMs = INITIAL_RETRY_MS;
                }
            }
        }
    }

    private void clearHal() {
        if (hal != null && halDeath != null) safeUnlink(hal, halDeath);
        hal = null; halDeath = null; applied = -1;
    }

    private boolean connectHal() {
        if (hal != null && hal.isBinderAlive()) return true;
        clearHal();
        try {
            IBinder connected = ServiceManager.checkService(HAL + "/default");
            if (connected == null || !HAL.equals(connected.getInterfaceDescriptor())) return false;
            IBinder.DeathRecipient death = () -> {
                synchronized (lock) { changedLocked(null); }
            };
            connected.linkToDeath(death, 0);
            if (!connected.isBinderAlive()) { safeUnlink(connected, death); return false; }
            hal = connected; halDeath = death;
            return true;
        } catch (RemoteException | RuntimeException failure) {
            clearHal();
            Slog.e(TAG, "Camera display HAL unavailable", failure);
            return false;
        }
    }

    private boolean applyDesired(int desired) {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(HAL);
            request.writeInt(0);
            request.writeInt(CAMERA_MODE);
            request.writeInt(desired);
            request.writeInt(255);
            if (!hal.transact(7, request, reply, 0)) return false;
            reply.readException();
            applied = desired;
            return true;
        } catch (RemoteException | RuntimeException failure) {
            clearHal();
            Slog.e(TAG, "Camera display HAL rejected request", failure);
            return false;
        } finally { reply.recycle(); request.recycle(); }
    }
}
