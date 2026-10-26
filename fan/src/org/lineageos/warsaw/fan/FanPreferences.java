package org.lineageos.warsaw.fan;

/** Settings keys used by the selected manual PWM control. */
final class FanPreferences {
    static final String ENABLE = "cooling_fan_enable";
    static final String PWM = "warsaw_fan_pwm";
    static final String CONFIRMED_PWM = "warsaw_fan_confirmed_pwm";
    static final String STATUS = "warsaw_fan_status";
    static final int DEFAULT_PWM = 50;

    private FanPreferences() { }
}
