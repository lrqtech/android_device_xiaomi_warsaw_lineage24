package com.miui.misight;

import android.util.Log;

public class MiSight {
    private static final String TAG = "MiSight:";
    private static boolean loadFlag;

    static {
        try {
            System.loadLibrary("misight_jni");
            loadFlag = true;
        } catch (UnsatisfiedLinkError error) {
            loadFlag = false;
            Log.e(TAG, "load library failed," + error);
        }
    }

    private static native boolean nativeSendEvent(int eventId, String eventJson);

    public static void sendEvent(MiEvent event) {
        if (!loadFlag || event == null) return;
        Log.d(TAG, " sendEvent id=" + event.getEventId());
        nativeSendEvent(event.getEventId(), event.getMiEventStr());
    }

    public static MiEvent constructEvent(int eventId, Object... parameters) {
        Log.d(TAG, " constructEvent id=" + eventId);
        MiEvent event = new MiEvent(eventId);
        if (parameters == null) return event;
        for (int i = 0; i + 1 < parameters.length; i += 2) {
            if (parameters[i] instanceof String) {
                constructEventParam(event, (String) parameters[i], parameters[i + 1]);
            }
        }
        return event;
    }

    private static void constructEventParam(MiEvent event, String name, Object value) {
        if (value instanceof Short) event.addShort(name, (Short) value);
        else if (value instanceof Integer) event.addInt(name, (Integer) value);
        else if (value instanceof Long) event.addLong(name, (Long) value);
        else if (value instanceof Float) event.addFloat(name, (Float) value);
        else if (value instanceof Boolean) event.addBool(name, (Boolean) value);
        else if (value instanceof String) event.addStr(name, (String) value);
        else if (value instanceof String[]) event.addStrArray(name, (String[]) value);
        else if (value instanceof int[]) event.addIntArray(name, (int[]) value);
        else if (value instanceof short[]) event.addShortArray(name, (short[]) value);
        else if (value instanceof long[]) event.addLongArray(name, (long[]) value);
        else if (value instanceof float[]) event.addFloatArray(name, (float[]) value);
        else if (value instanceof boolean[]) event.addBoolArray(name, (boolean[]) value);
        else if (value instanceof MiEvent) event.addMiEvent(name, (MiEvent) value);
        else if (value instanceof MiEvent[]) event.addMiEventArray(name, (MiEvent[]) value);
    }
}
