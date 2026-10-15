package org.lineageos.warsaw.fan;

import android.content.ContentResolver;
import android.provider.Settings;

final class FanPreferences {
    static final String PWM = "warsaw_fan_pwm", CONFIRMED_PWM = "warsaw_fan_confirmed_pwm";
    static final int DEFAULT_PWM = 50;
    static final String ENABLE = "cooling_fan_enable", MODE = "fan_mode", RANGE = "fan_mode_range";
    static final String GAME = "fan_mode_scene_gaming", CHARGE = "fan_mode_scene_rapid_charge";
    static final String NAVIGATION = "fan_mode_scene_navigation", RECORD = "fan_smart_stop_on_recording";
    static final String CLEAN = "fan_self_clean", MANUAL_LEVEL = "warsaw_fan_manual_level";
    static final String[] KEYS = {ENABLE, MODE, RANGE, GAME, CHARGE, NAVIGATION, RECORD, CLEAN, MANUAL_LEVEL};
    static final String STATUS = "warsaw_fan_status", LEVEL = "warsaw_fan_confirmed_level";
    static final String UPDATED = "warsaw_fan_updated_elapsed", USER = "warsaw_fan_active_user";
    static final String SPEED = "warsaw_fan_speed";
    final int user, mode, manualLevel;
    final boolean enabled, otherScenes, gaming, rapidCharge, navigation, stopRecording, selfClean;

    FanPreferences(ContentResolver resolver, int userId) {
        user = userId;
        mode = Settings.System.getIntForUser(resolver, MODE, -1, user);
        if (mode < -1 || mode > 3) throw new IllegalArgumentException("Unknown fan mode");
        manualLevel = Settings.System.getIntForUser(resolver, MANUAL_LEVEL, 2, user);
        if (manualLevel < 1 || manualLevel > 4) throw new IllegalArgumentException("Invalid manual fan level");
        enabled = flag(resolver, ENABLE, 1);
        otherScenes = flag(resolver, RANGE, 0);
        gaming = flag(resolver, GAME, 1);
        rapidCharge = flag(resolver, CHARGE, 0);
        navigation = flag(resolver, NAVIGATION, 0);
        stopRecording = flag(resolver, RECORD, 1);
        selfClean = flag(resolver, CLEAN, 1);
    }
    private boolean flag(ContentResolver resolver, String key, int fallback) {
        int value = Settings.System.getIntForUser(resolver, key, fallback, user);
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid " + key);
        return value == 1;
    }
}
