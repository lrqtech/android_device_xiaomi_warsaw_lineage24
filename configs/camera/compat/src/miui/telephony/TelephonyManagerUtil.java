package miui.telephony;

public final class TelephonyManagerUtil {
    private TelephonyManagerUtil() {}
    public static String getDeviceId() { return TelephonyManager.getDefault().getMiuiDeviceId(); }
}
