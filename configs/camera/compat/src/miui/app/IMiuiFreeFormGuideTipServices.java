package miui.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IMiuiFreeFormGuideTipServices extends IInterface {
    String DESCRIPTOR = "miui.app.IMiuiFreeFormGuideTipServices";

    boolean hasTipViewType(int type) throws RemoteException;
    void removeFreeFormTipView(int type) throws RemoteException;
    void showFreeFormGuideDialog(int type) throws RemoteException;
    void showFreeFormTipView(int type, int x, int y, int gravity) throws RemoteException;
    void showFreeFormTipViewByClassName(int type, int x, int y, int gravity,
            String className) throws RemoteException;
    void showPinTipView(int type, int x, int y, int gravity) throws RemoteException;

    abstract class Stub extends Binder implements IMiuiFreeFormGuideTipServices {
        public Stub() { attachInterface(this, DESCRIPTOR); }

        public static IMiuiFreeFormGuideTipServices asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IMiuiFreeFormGuideTipServices
                    ? (IMiuiFreeFormGuideTipServices) local : null;
        }

        @Override public IBinder asBinder() { return this; }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
