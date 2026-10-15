package miui.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IFreeformCallback extends IInterface {
    String DESCRIPTOR = "miui.app.IFreeformCallback";

    void dispatchFreeFormStackModeChanged(int action,
            MiuiFreeFormManager$MiuiFreeFormStackInfo stackInfo) throws RemoteException;

    class Default implements IFreeformCallback {
        @Override
        public void dispatchFreeFormStackModeChanged(int action,
                MiuiFreeFormManager$MiuiFreeFormStackInfo stackInfo) {}
        @Override public IBinder asBinder() { return null; }
    }

    abstract class Stub extends Binder implements IFreeformCallback {
        static final int TRANSACTION_dispatchFreeFormStackModeChanged = FIRST_CALL_TRANSACTION;

        public Stub() { attachInterface(this, DESCRIPTOR); }

        public static IFreeformCallback asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof IFreeformCallback) return (IFreeformCallback) local;
            return new Proxy(binder);
        }

        @Override public IBinder asBinder() { return this; }

        public static String getDefaultTransactionName(int transactionCode) {
            return transactionCode == TRANSACTION_dispatchFreeFormStackModeChanged
                    ? "dispatchFreeFormStackModeChanged" : null;
        }

        @Override public String getTransactionName(int transactionCode) {
            return getDefaultTransactionName(transactionCode);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
            }
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_dispatchFreeFormStackModeChanged) {
                int action = data.readInt();
                MiuiFreeFormManager$MiuiFreeFormStackInfo info = data.readTypedObject(
                        MiuiFreeFormManager$MiuiFreeFormStackInfo.CREATOR);
                dispatchFreeFormStackModeChanged(action, info);
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static final class Proxy implements IFreeformCallback {
            private final IBinder mRemote;
            Proxy(IBinder remote) { mRemote = remote; }
            @Override public IBinder asBinder() { return mRemote; }
            @Override
            public void dispatchFreeFormStackModeChanged(int action,
                    MiuiFreeFormManager$MiuiFreeFormStackInfo info) throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(action);
                    data.writeTypedObject(info, 0);
                    mRemote.transact(TRANSACTION_dispatchFreeFormStackModeChanged, data, null,
                            IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }
        }
    }
}
