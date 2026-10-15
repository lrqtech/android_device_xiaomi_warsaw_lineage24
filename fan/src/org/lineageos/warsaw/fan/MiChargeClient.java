package org.lineageos.warsaw.fan;

import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

final class MiChargeClient {
    static final String DESCRIPTOR = "vendor.xiaomi.hardware.micharge.IMiCharge";
    static final String SERVICE = DESCRIPTOR + "/default";
    static final int GET_PATH = 18, GET_QUICK_CHARGE = 23, SET_PATH = 43;
    static final long TIMEOUT_MS = 2000;
    interface Callback<T> { void done(T value, Exception error); }
    interface Operation<T> { T run() throws Exception; }
    static final class State {
        final int level;
        final String speed;
        State(int level, String speed) {
            this.level = level; this.speed = speed;
        }
    }
    static final class NormalInputs {
        final int quickChargeType;
        final int reverseQuickCharge;
        NormalInputs(int quickChargeType, int reverseQuickCharge) {
            if (quickChargeType < 0 || reverseQuickCharge < 0) {
                throw new IllegalArgumentException("Invalid charge state");
            }
            this.quickChargeType = quickChargeType;
            this.reverseQuickCharge = reverseQuickCharge;
        }
    }
    private final Handler owner;
    private final Runnable uncertain;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private IBinder binder;
    private long operationDeadline;
    private boolean busy, closed;

    MiChargeClient(Handler owner, Runnable uncertain) {
        this.owner = owner; this.uncertain = uncertain;
    }
    boolean isBusy() { return busy; }
    private IBinder connection() throws RemoteException {
        if (binder != null && binder.isBinderAlive()) return binder;
        IBinder candidate = ServiceManager.checkService(SERVICE);
        if (candidate == null || !DESCRIPTOR.equals(candidate.getInterfaceDescriptor())) {
            throw new RemoteException("MiCharge unavailable or descriptor mismatch");
        }
        candidate.linkToDeath(() -> owner.post(() -> { if (!closed) uncertain.run(); }), 0);
        binder = candidate;
        return binder;
    }
    private String read(int transaction, String key) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (key != null) data.writeString(key);
            IBinder service = connection();
            checkDeadline();
            if (!service.transact(transaction, data, reply, 0)) {
                throw new RemoteException("MiCharge unsupported transaction");
            }
            reply.readException();
            String value = reply.readString();
            if (value == null) throw new RemoteException("MiCharge null reply");
            return value;
        } finally { reply.recycle(); data.recycle(); }
    }
    private void checkDeadline() throws RemoteException {
        if (SystemClock.elapsedRealtime() >= operationDeadline) throw new RemoteException("MiCharge deadline expired");
    }
    private void writePath(String key, int value, BooleanSupplier current) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(key);
            data.writeString(Integer.toString(value));
            IBinder service = connection();
            checkDeadline();
            if (!current.getAsBoolean()) throw new RemoteException("Fan decision superseded");
            if (!service.transact(SET_PATH, data, reply, 0)) {
                throw new RemoteException("MiCharge unsupported setter");
            }
            reply.readException();
            if (reply.readInt() != 0) throw new RemoteException("MiCharge rejected level");
        } finally { reply.recycle(); data.recycle(); }
    }
    void readState(Callback<State> callback) {
        submit(() -> {
            if (!"1".equals(read(GET_PATH, "fan_support").trim())) {
                throw new RemoteException("Fan unsupported");
            }
            int level = FanPolicy.checkedLevel(Integer.parseInt(read(GET_PATH, "target_level").trim()));
            return new State(level, read(GET_PATH, "fan_real_speed").trim());
        }, callback);
    }
    void readNormalInputs(Callback<NormalInputs> callback) {
        submit(() -> new NormalInputs(Integer.parseInt(read(GET_QUICK_CHARGE, null).trim()),
                Integer.parseInt(read(GET_PATH, "reverse_quick_charge").trim())), callback);
    }
    void setLevel(int level, BooleanSupplier current, Callback<Integer> callback) {
        FanPolicy.checkedLevel(level);
        submit(() -> {
            writePath("target_level", level, current);
            int observed = FanPolicy.checkedLevel(Integer.parseInt(read(GET_PATH, "target_level").trim()));
            if (observed != level) throw new RemoteException("Fan target readback mismatch");
            return observed;
        }, callback);
    }
    void setMeasuredSpeed(int rpm, BooleanSupplier current, Callback<Integer> callback) {
        if (rpm < 0 || rpm > 40000) throw new IllegalArgumentException("Invalid measured fan speed");
        submit(() -> {
            writePath("fan_real_speed", rpm, current);
            int observed = Integer.parseInt(read(GET_PATH, "fan_real_speed").trim());
            if (observed != rpm) throw new RemoteException("Fan speed readback mismatch");
            return observed;
        }, callback);
    }
    private <T> void submit(Operation<T> operation, Callback<T> callback) {
        if (closed || busy) throw new IllegalStateException("MiCharge already in flight");
        busy = true;
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        Runnable timeout = () -> { if (!closed) uncertain.run(); };
        owner.postDelayed(timeout, TIMEOUT_MS);
        worker.execute(() -> {
            operationDeadline = deadline;
            T value = null; Exception failure = null;
            try { value = operation.run(); } catch (Exception error) { failure = error; }
            T result = value; Exception error = failure;
            owner.post(() -> {
                owner.removeCallbacks(timeout);
                busy = false;
                if (closed) return;
                if (SystemClock.elapsedRealtime() >= deadline) {
                    callback.done(null, new RemoteException("MiCharge outcome unknown after timeout"));
                } else callback.done(result, error);
            });
        });
    }
    void close() { closed = true; worker.shutdown(); }
}
