package miui.util;

import android.app.ActivityThread;
import android.content.Context;
import android.os.Process;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

public final class HapticFeedbackUtil {
    private static final String TAG = "WarsawCameraHaptics";
    private final Context context;
    private final Vibrator vibrator;
    public HapticFeedbackUtil(Context context, boolean observeSettings) {
        this.context = context;
        vibrator = context.getSystemService(Vibrator.class);
    }
    public static double getCurVersion() { return 1.2; }
    private static Vibrator currentVibrator() {
        Context context = ActivityThread.currentApplication();
        return context == null ? null : context.getSystemService(Vibrator.class);
    }
    public static boolean isSupportLinearMotorVibrate() {
        Vibrator vibrator = currentVibrator();
        return WarsawHapticPatterns.isSupported(0) && vibrator != null && vibrator.hasVibrator();
    }
    public static boolean isSupportLinearMotorVibrate(int id) {
        int[] pattern = WarsawHapticPatterns.forUi(id);
        return WarsawHapticPatterns.isMotorUi(id) && pattern != null && supported(currentVibrator(), pattern[0]);
    }
    public boolean isSupportExtHapticFeedback(int id) { return supported(vibrator, id); }
    private static boolean supported(Vibrator vibrator, int id) {
        if (!WarsawHapticPatterns.isSupported(id) || vibrator == null || !vibrator.hasVibrator()) return false;
        int[] result = vibrator.areEffectsSupported(id);
        return result.length == 1 && result[0] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES;
    }
    private boolean disabled() {
        return Settings.System.getInt(context.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0
                || Settings.System.getInt(context.getContentResolver(), "haptic_feedback_disable", 0) != 0;
    }
    private boolean perform(VibrationAttributes attributes, int id, int strength, boolean always, Double gain, String reason) {
        if ((!always && disabled()) || !supported(vibrator, id) || strength < 0 || strength > 2) return false;
        if (gain != null && (!Double.isFinite(gain) || gain < 0 || gain > 1.5 || reason == null || reason.isEmpty())) return false;
        if (attributes == null) attributes = new VibrationAttributes.Builder().setUsage(always ? 50 : 18).build();
        try {
            VibrationEffect effect = VibrationEffect.get(id, false).applyEffectStrength(strength);
            String tag = gain == null ? "WarsawCameraHaptic" :
                    "TAG=haptic_feedback_infinite_intensity;intensity=" + gain + ";reason=" + reason + ";keyword=MIUI_SDK";
            vibrator.vibrate(Process.myUid(), context.getOpPackageName(), effect, tag, attributes);
            return true;
        } catch (IllegalArgumentException | SecurityException failure) {
            Log.e(TAG, "Camera haptic request rejected", failure);
            return false;
        }
    }
    private boolean ui(VibrationAttributes attributes, int id, boolean always, Integer strength, Double gain, String reason) {
        int[] pattern = WarsawHapticPatterns.forUi(id);
        if (pattern == null) return false;
        return perform(attributes, pattern[0], gain != null ? 1 : strength == null ? pattern[1] : strength, always, gain, reason);
    }
    public boolean performHapticFeedback(int id, boolean always) { return ui(null, id, always, null, null, null); }
    public boolean performHapticFeedback(int id, boolean always, int strength) { return ui(null, id, always, strength, null, null); }
    public boolean performHapticFeedback(int id, double gain, String reason) { return ui(null, id, false, null, gain, reason); }
    public boolean performHapticFeedback(VibrationAttributes attrs, int id, boolean always) { return ui(attrs, id, always, null, null, null); }
    public boolean performHapticFeedback(VibrationAttributes attrs, int id, boolean always, int strength) { return ui(attrs, id, always, strength, null, null); }
    public boolean performHapticFeedback(VibrationAttributes attrs, int id, double gain, String reason) { return ui(attrs, id, false, null, gain, reason); }
    public boolean performExtHapticFeedback(int id) { return perform(null, id, 1, false, null, null); }
    public boolean performExtHapticFeedback(int id, boolean always) { return perform(null, id, 1, always, null, null); }
    public boolean performExtHapticFeedback(int id, double gain, String reason) { return perform(null, id, 1, false, gain, reason); }
    public boolean performExtHapticFeedback(VibrationAttributes attrs, int id) { return perform(attrs, id, 1, false, null, null); }
    public boolean performExtHapticFeedback(VibrationAttributes attrs, int id, boolean always) { return perform(attrs, id, 1, always, null, null); }
    public boolean performExtHapticFeedback(VibrationAttributes attrs, int id, double gain, String reason) { return perform(attrs, id, 1, false, gain, reason); }
    public void stop() { if (vibrator != null) vibrator.cancel(); }
    public void release() {}
}
