package com.android.server.vibrator;

import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.WarsawHapticPatterns;
import android.util.Slog;

import java.util.function.LongSupplier;

final class WarsawHapticHooks {
    private static final String PREFIX = "TAG=haptic_feedback_infinite_intensity;intensity=";
    private static final String HAL = "android.hardware.vibrator.IVibrator/default";
    private static final String EXT = "vendor.hardware.vibratorfeature.IVibratorExt";
    private WarsawHapticHooks() {}

    static long perform(PrebakedSegment effect, HalVibration vibration, LongSupplier nativePerform) {
        String reason = vibration.callerInfo.reason;
        if (!"warsaw".equals(Build.DEVICE) || !"com.android.camera".equals(vibration.callerInfo.opPkg)
                || reason == null || !reason.startsWith(PREFIX)) {
            return nativePerform.getAsLong();
        }
        IBinder extension = null;
        boolean configured = false;
        try {
            if (!WarsawHapticPatterns.isSupported(effect.getEffectId()) || reason.length() > 2048) return 0;
            int end = reason.indexOf(';', PREFIX.length());
            if (end < 0) return 0;
            float gain = Float.parseFloat(reason.substring(PREFIX.length(), end));
            if (!Float.isFinite(gain) || gain < 0 || gain > 1.5f) return 0;
            IBinder vibrator = ServiceManager.checkService(HAL);
            if (vibrator == null) return 0;
            extension = vibrator.getExtension();
            if (extension == null || !EXT.equals(extension.getInterfaceDescriptor())) return 0;
            amplitude(extension, gain);
            configured = true;
            return nativePerform.getAsLong();
        } catch (RemoteException | RuntimeException failure) {
            Slog.e("WarsawHaptic", "Camera gain/perform rejected", failure);
            return 0;
        } finally {
            if (configured) {
                try {
                    amplitude(extension, -1.0f);
                } catch (RemoteException | RuntimeException failure) {
                    Slog.e("WarsawHaptic", "Could not clear camera's one-shot gain", failure);
                }
            }
        }
    }

    private static void amplitude(IBinder extension, float value) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(EXT);
            request.writeFloat(value);
            request.writeInt(1);
            if (!extension.transact(5, request, reply, 0)) throw new RemoteException("setAmplitudeExt is unimplemented");
            reply.readException();
        } finally { reply.recycle(); request.recycle(); }
    }
}
