package com.android.systemui.keyguard;

final class WarsawKeyguardSensorPolicy {
    enum Action { NONE, WAKE, SLEEP }

    static final class State {
        final int user;
        final boolean interactive, showing, occluded, hiding;
        State(int user, boolean interactive, boolean showing, boolean occluded, boolean hiding) {
            this.user = user;
            this.interactive = interactive;
            this.showing = showing;
            this.occluded = occluded;
            this.hiding = hiding;
        }
        boolean sameAs(State other) {
            return user == other.user && interactive == other.interactive
                    && showing == other.showing && occluded == other.occluded
                    && hiding == other.hiding;
        }
    }

    private boolean wokeByPickup;

    boolean wantsPickup(State state, boolean settingEnabled) {
        return settingEnabled && (!state.interactive || (state.showing && wokeByPickup));
    }

    boolean wantsPalm(State state) { return state.interactive && state.showing; }

    Action pickup(float value, State state, boolean settingEnabled, boolean lidShowing) {
        if (!wantsPickup(state, settingEnabled) || lidShowing) return Action.NONE;
        if (value == 1f && !state.interactive) return Action.WAKE;
        if ((value == 0f || value == 2f) && wokeByPickup && state.showing
                && !state.occluded && !state.hiding) return Action.SLEEP;
        return Action.NONE;
    }

    Action palm(float value, State state, boolean keepScreenOn) {
        return value == 1f && wantsPalm(state) && !state.occluded
                && !state.hiding && !keepScreenOn
                ? Action.SLEEP : Action.NONE;
    }

    void wakeRequestAccepted() { wokeByPickup = true; }
    void reset() { wokeByPickup = false; }
}
