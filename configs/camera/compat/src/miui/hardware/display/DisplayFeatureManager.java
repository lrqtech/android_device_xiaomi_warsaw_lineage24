package miui.hardware.display;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

public final class DisplayFeatureManager {
    private static final DisplayFeatureManager INSTANCE = new DisplayFeatureManager();
    private static final String SERVICE = "warsaw.camera.display";
    private static final String DESCRIPTOR = "org.lineageos.warsaw.ICameraDisplay";
    private final IBinder token = new Binder();
    private DisplayFeatureManager() {}
    public static DisplayFeatureManager getInstance() { return INSTANCE; }
    public void setScreenEffect(int mode, int value) {
        if (mode != 14 || (value != 0 && value != 1)) {
            throw new IllegalArgumentException("Only warsaw camera brightness mode 14 is implemented");
        }
        IBinder service = ServiceManager.getService(SERVICE);
        if (service == null) throw new IllegalStateException("Camera display framework bridge unavailable");
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(mode);
            request.writeInt(value);
            request.writeStrongBinder(token);
            if (!service.transact(1, request, reply, 0)) {
                throw new IllegalStateException("Camera display transaction not implemented");
            }
            reply.readException();
            if (!reply.readBoolean()) throw new IllegalStateException("Camera display hardware request failed");
        } catch (RemoteException failure) {
            throw new IllegalStateException("Camera display transport failed", failure);
        } finally {
            reply.recycle();
            request.recycle();
        }
    }
}
