package com.android.systemui.keyguard;

import static com.android.systemui.keyguard.WarsawKeyguardSensorPolicy.Action.*;
import static org.junit.Assert.*;
import org.junit.Test;

public class WarsawKeyguardSensorPolicyTest {
    private WarsawKeyguardSensorPolicy.State state(boolean interactive, boolean showing,
            boolean occluded, boolean hiding) {
        return new WarsawKeyguardSensorPolicy.State(10, interactive, showing, occluded, hiding);
    }

    @Test public void putDownCannotSleepAnOrdinaryWake() {
        WarsawKeyguardSensorPolicy policy = new WarsawKeyguardSensorPolicy();
        assertEquals(NONE, policy.pickup(2f, state(true, true, false, false), true, false));
        assertFalse(policy.wantsPickup(state(true, true, false, false), true));
    }

    @Test public void wakeMustBeAcceptedBeforePutDownOwnsSleep() {
        WarsawKeyguardSensorPolicy policy = new WarsawKeyguardSensorPolicy();
        assertEquals(WAKE, policy.pickup(1f, state(false, true, false, false), true, false));
        assertEquals(NONE, policy.pickup(0f, state(true, true, false, false), true, false));
        policy.wakeRequestAccepted();
        assertEquals(SLEEP, policy.pickup(2f, state(true, true, false, false), true, false));
        assertEquals(SLEEP, policy.pickup(0f, state(true, true, false, false), true, false));
        policy.reset();
        assertEquals(NONE, policy.pickup(2f, state(true, true, false, false), true, false));
    }

    @Test public void coverSettingsAndCurrentLockscreenGuardActions() {
        WarsawKeyguardSensorPolicy policy = new WarsawKeyguardSensorPolicy();
        assertEquals(NONE, policy.pickup(1f, state(false, true, false, false), false, false));
        assertEquals(NONE, policy.pickup(1f, state(false, true, false, false), true, true));
        policy.wakeRequestAccepted();
        assertEquals(NONE, policy.pickup(2f, state(true, true, true, false), true, false));
        assertEquals(NONE, policy.pickup(2f, state(true, false, false, false), true, false));
        assertEquals(NONE, policy.pickup(2f, state(true, true, false, true), true, false));
    }

    @Test public void unrecognizedSamplesHaveNoAction() {
        WarsawKeyguardSensorPolicy policy = new WarsawKeyguardSensorPolicy();
        policy.wakeRequestAccepted();
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 3f, 1.01f}) {
            assertEquals(NONE, policy.pickup(value, state(true, true, false, false), true, false));
            assertEquals(NONE, policy.palm(value, state(true, true, false, false), false));
        }
    }

    @Test public void palmRequiresVisibleStableLockscreenAndNoProtectedApp() {
        WarsawKeyguardSensorPolicy policy = new WarsawKeyguardSensorPolicy();
        assertEquals(SLEEP, policy.palm(1f, state(true, true, false, false), false));
        assertEquals(NONE, policy.palm(1f, state(true, true, true, false), true));
        assertEquals(NONE, policy.palm(1f, state(true, true, true, false), false));
        assertEquals(NONE, policy.palm(1f, state(true, true, false, true), false));
        assertEquals(NONE, policy.palm(1f, state(true, false, false, false), false));
        assertEquals(NONE, policy.palm(1f, state(false, true, false, false), false));
    }
}
