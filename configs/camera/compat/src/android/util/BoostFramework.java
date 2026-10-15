package android.util;

/** Performance-lock entry points requested by the OS4 MiuiCamera fallback. */
public final class BoostFramework {
    private final com.qualcomm.qti.Performance mPerformance = new com.qualcomm.qti.Performance();

    public BoostFramework() {}

    public int perfLockAcquire(int duration, int... resources) {
        return mPerformance.perfLockAcquire(duration, resources);
    }

    public int perfLockRelease() {
        return mPerformance.perfLockRelease();
    }

    public int perfLockReleaseHandler(int handle) {
        return mPerformance.perfLockReleaseHandler(handle);
    }
}
