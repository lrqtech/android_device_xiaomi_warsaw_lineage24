package miui.util;

import android.os.SystemProperties;

/* JADX INFO: loaded from: classes.dex */
public final class MiuiMultiDisplayTypeInfo {
    public static final int DEVICE_FLIP_TYPE = 4;
    public static final int DEVICE_FOLD_INSIDE_TYPE = 3;
    public static final int DEVICE_FOLD_OUTSIDE_TYPE = 5;
    public static final int DEVICE_INDEPENDENT_REAR_PHONE_TYPE = 6;
    public static final int DEVICE_PHONE_TYPE = 1;
    public static final int DEVICE_REAR_PHONE_TYPE = 2;
    private static final boolean IS_TABLET;
    private static final int sDeviceStateInfo;
    private static final int sDeviceType;

    static {
        IS_TABLET = "tablet".equals(SystemProperties.get("ro.build.characteristics", ""))
                || SystemProperties.getBoolean("ro.config.tablet", false);
        sDeviceStateInfo = SystemProperties.getInt("persist.sys.multi_display_type", 1);
        sDeviceType = sDeviceStateInfo & 255;
    }

    public static int getDeviceType() {
        return sDeviceType;
    }

    public static boolean isFoldDeviceInside() {
        return sDeviceType == 3;
    }

    public static boolean isFoldDeviceOutside() {
        return sDeviceType == 5;
    }

    public static boolean isFoldDevice() {
        return sDeviceType == 3 || sDeviceType == 5;
    }

    public static boolean isRearDevice() {
        return sDeviceType == 2;
    }

    public static boolean isFlipDevice() {
        return sDeviceType == 4;
    }

    public static boolean isIndependentRearDevice() {
        return sDeviceType == 6;
    }

    public static boolean isTableDevice() {
        return IS_TABLET;
    }
}
