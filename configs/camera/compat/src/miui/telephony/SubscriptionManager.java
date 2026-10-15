package miui.telephony;

/** Slot translation used by the original cloud updater. */
public final class SubscriptionManager {
    private static final SubscriptionManager INSTANCE = new SubscriptionManager();
    private SubscriptionManager() {}
    public static SubscriptionManager getDefault() { return INSTANCE; }
    public int getDefaultDataSlotId() {
        return android.telephony.SubscriptionManager.getSlotIndex(
                android.telephony.SubscriptionManager.getDefaultDataSubscriptionId());
    }
}
