package org.lineageos.warsaw.fan;
import android.content.Context;
import android.os.Build;
import com.android.settings.core.BasePreferenceController;
public final class FanEntryController extends BasePreferenceController {
    public FanEntryController(Context context, String key) { super(context, key); }
    @Override public int getAvailabilityStatus() {
        return "warsaw".equals(Build.DEVICE) ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }
}
