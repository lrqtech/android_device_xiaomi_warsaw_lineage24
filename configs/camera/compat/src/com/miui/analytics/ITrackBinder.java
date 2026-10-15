package com.miui.analytics;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.List;

public interface ITrackBinder extends IInterface {
    String DESCRIPTOR = "com.miui.analytics.ITrackBinder";

    void trackEvent(String appId, String configKey, String event, int flags)
            throws RemoteException;
    void trackEvents(String appId, String configKey, List<String> events, int flags)
            throws RemoteException;

    abstract class Stub extends Binder implements ITrackBinder {
        static final int TRANSACTION_trackEvent = IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_trackEvents = IBinder.FIRST_CALL_TRANSACTION + 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ITrackBinder asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof ITrackBinder) return (ITrackBinder) local;
            return new Proxy(binder);
        }

        @Override
        public IBinder asBinder() {
            return this;
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
            switch (code) {
                case TRANSACTION_trackEvent:
                    trackEvent(data.readString(), data.readString(), data.readString(), data.readInt());
                    reply.writeNoException();
                    return true;
                case TRANSACTION_trackEvents:
                    trackEvents(data.readString(), data.readString(), data.createStringArrayList(),
                            data.readInt());
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements ITrackBinder {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void trackEvent(String appId, String configKey, String event, int flags)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(appId);
                    data.writeString(configKey);
                    data.writeString(event);
                    data.writeInt(flags);
                    mRemote.transact(TRANSACTION_trackEvent, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void trackEvents(String appId, String configKey, List<String> events, int flags)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(appId);
                    data.writeString(configKey);
                    data.writeStringList(events);
                    data.writeInt(flags);
                    mRemote.transact(TRANSACTION_trackEvents, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
