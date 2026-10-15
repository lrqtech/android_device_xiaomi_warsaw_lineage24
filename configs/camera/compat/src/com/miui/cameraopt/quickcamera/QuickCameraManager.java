package com.miui.cameraopt.quickcamera;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import com.miui.cameraopt.intentaware.client.CamOptClient;
import org.json.JSONException;
import java.util.NoSuchElementException;

/** Camera application's existing fallback entry point; no system policy is impersonated. */
public final class QuickCameraManager {
    private static final String TAG = "WarsawQuickCamera";
    private static final String DESCRIPTOR = "vendor.xiaomi.hardware.quickcamera.IQuickCameraService";
    private static final String SERVICE = DESCRIPTOR + "/default";
    private static final QuickCameraManager INSTANCE = new QuickCameraManager();
    private final Object guard = new Object();
    private final CameraMindEventFormatter mind = new CameraMindEventFormatter();
    private Session session;

    private final class Session implements IBinder.DeathRecipient {
        final IBinder binder;
        Session(IBinder binder) { this.binder = binder; }
        @Override public void binderDied() {
            synchronized (guard) {
                if (session == this) session = null;
            }
            Log.w(TAG, "QuickCamera provider died; next request reconnects without replay");
        }
    }

    private QuickCameraManager() { }
    public static QuickCameraManager getInstance() { return INSTANCE; }
    public static boolean isEnabled() {
        return SystemProperties.getBoolean("persist.sys.miui.camera.quickcamera.supported", false);
    }
    public static boolean sendEvent(int event) { return sendEvent(event, null); }

    /** True is transport acceptance, never confirmation that a capture completed. */
    public static boolean sendEvent(int event, String parameter) {
        if (event >= 2001 && event <= 2022) return INSTANCE.sendMindEvent(event, parameter);
        if (!isEnabled()) return false;
        if (!SystemProperties.getBoolean("persist.sys.miui.camera.quickcamera.aidlsupported", true)) {
            Log.e(TAG, "This warsaw adapter requires the actual AIDL QuickCamera service");
            return false;
        }
        if (event != 0 && !((event >= 1 && event < 20) || (event > 65536 && event < 65549))) {
            Log.e(TAG, "Unsupported QuickCamera event " + event);
            return false;
        }
        Session target = null;
        try {
            target = INSTANCE.connect();
            if (target == null) {
                Log.e(TAG, "QuickCamera service unavailable; event not sent: " + event);
                return false;
            }
            Parcel data = Parcel.obtain(target.binder);
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(event);
                int transaction = 1;
                if (parameter != null) { data.writeString(parameter); transaction = 2; }
                if (!target.binder.transact(transaction, data, null, IBinder.FLAG_ONEWAY)) {
                    throw new RemoteException("QuickCamera transaction " + transaction + " unimplemented");
                }
            } finally { data.recycle(); }
            if (event == 0 && SystemProperties.getBoolean("persist.sys.miui.camera.cameramind.supported", false)) {
                CamOptClient.getInstance();
            }
            Log.d(TAG, "AIDL accepted event=" + event);
            return true;
        } catch (RemoteException | RuntimeException failure) {
            INSTANCE.invalidate(target);
            Log.e(TAG, "QuickCamera transport failed for event=" + event, failure);
            return false;
        }
    }

    private Session connect() throws RemoteException {
        synchronized (guard) {
            if (session != null && session.binder.isBinderAlive()) return session;
            invalidate(session);
            IBinder binder = ServiceManager.checkService(SERVICE);
            if (binder == null) return null;
            if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                throw new RemoteException("Unexpected QuickCamera descriptor");
            }
            int version;
            String hash;
            Parcel request = Parcel.obtain(binder);
            Parcel response = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                if (!binder.transact(16777215, request, response, 0)) {
                    throw new RemoteException("QuickCamera version query unimplemented");
                }
                response.readException();
                version = response.readInt();
            } finally { response.recycle(); request.recycle(); }
            if (version < 1) throw new RemoteException("Unsupported QuickCamera version " + version);
            request = Parcel.obtain(binder);
            response = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                if (!binder.transact(16777214, request, response, 0)) {
                    throw new RemoteException("QuickCamera hash query unimplemented");
                }
                response.readException();
                hash = response.readString();
            } finally { response.recycle(); request.recycle(); }
            if (hash == null || hash.isEmpty()) throw new RemoteException("Missing QuickCamera interface hash");
            // OS4 ships different client/server hashes with matching version-1 transaction layouts.
            // Keep the real hash visible; do not substitute the client's compiled-in value.
            Session candidate = new Session(binder);
            binder.linkToDeath(candidate, 0);
            if (!binder.isBinderAlive()) {
                binder.unlinkToDeath(candidate, 0);
                throw new RemoteException("QuickCamera died during connection");
            }
            session = candidate;
            Log.i(TAG, "Connected actual QuickCamera version=" + version + " hash=" + hash);
            return candidate;
        }
    }

    private void invalidate(Session target) {
        if (target == null) return;
        synchronized (guard) {
            if (session == target) session = null;
            try { target.binder.unlinkToDeath(target, 0); }
            catch (NoSuchElementException alreadyRemoved) {
                Log.d(TAG, "QuickCamera death recipient already removed");
            }
        }
    }

    private boolean sendMindEvent(int event, String parameter) {
        if (!SystemProperties.getBoolean("persist.sys.miui.camera.cameramind.supported", false)
                || !SystemProperties.getBoolean("persist.sys.miui.camera.appSocket.supported", true)) return false;
        try {
            String payload = mind.format(event, parameter);
            if (payload == null) {
                Log.d(TAG, "CameraMind event deferred for original image-title pairing: " + event);
                return false;
            }
            CamOptClient client = CamOptClient.getInstance();
            boolean queued = client.trySendEvent(payload);
            if (queued) Log.d(TAG, "CameraMind socket queued event=" + event);
            else Log.e(TAG, "CameraMind socket rejected event=" + event + " state=" + client.getConnectionState());
            return queued;
        } catch (JSONException | RuntimeException failure) {
            Log.e(TAG, "Invalid CameraMind event=" + event, failure);
            return false;
        }
    }
}
