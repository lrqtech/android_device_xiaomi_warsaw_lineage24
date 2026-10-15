package miui.telephony;

/** Retains actual subscription identity and Android's identifier permission checks. */
public final class TelephonyManager {
    private static final TelephonyManager INSTANCE = new TelephonyManager();
    private TelephonyManager() {}
    public static TelephonyManager getDefault() { return INSTANCE; }
    public String getSubscriberIdForSlot(int slotId) {
        int subId = android.telephony.SubscriptionManager.getSubscriptionId(slotId);
        return android.telephony.TelephonyManager.getDefault()
                .createForSubscriptionId(subId).getSubscriberId();
    }
    public String getMiuiDeviceId() {
        return android.telephony.TelephonyManager.getDefault().getDeviceId();
    }
}
