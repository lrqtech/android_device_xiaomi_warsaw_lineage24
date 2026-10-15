package android.telephony;

import android.os.SystemProperties;

/**
 * ROM identity API required by the unmodified vendor IMS application.
 *
 * @hide
 */
public final class TelephonyBaseUtilsStub {
    private TelephonyBaseUtilsStub() {}

    public static boolean isMiuiRom() {
        return !SystemProperties.get("ro.miui.ui.version.name", "").isEmpty()
                || !SystemProperties.get("ro.miui.ui.version.code", "").isEmpty();
    }
}
