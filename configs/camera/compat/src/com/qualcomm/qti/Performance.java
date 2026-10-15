package com.qualcomm.qti;

/** The performance-lock API used by the OS4 camera clients, backed by its real HAL client. */
public final class Performance {
    static { System.loadLibrary("warsaw_cameraperf"); }
    private int mHandle;

    public Performance() {}

    public synchronized int perfLockAcquire(int duration, int... resources) {
        // Duration zero requests the HAL's indefinite lock, as in the stock client.
        int result = nativeAcquire(mHandle, duration, resources);
        if (result > 0) mHandle = result;
        return result;
    }

    public synchronized int perfLockRelease() {
        int result = nativeRelease(mHandle);
        if (result == 0) mHandle = 0;
        return result;
    }

    public synchronized int perfLockReleaseHandler(int handle) {
        int result = nativeRelease(handle);
        if (result == 0 && handle == mHandle) mHandle = 0;
        return result;
    }

    private static native int nativeAcquire(int handle, int duration, int[] resources);
    private static native int nativeRelease(int handle);
}
