package com.miui.misight;

import java.io.PrintWriter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MiEvent {
    private final int mEventId;
    private final JSONObject mMiEvent;
    private final long mTimestamp;
    @SuppressWarnings("unused")
    private long mTraceId;

    public MiEvent(int eventId) {
        mMiEvent = new JSONObject();
        mEventId = eventId;
        mTimestamp = System.currentTimeMillis();
    }

    private MiEvent put(String name, Object value) {
        try {
            mMiEvent.put(name, value);
        } catch (JSONException exception) {
            printStackTrace(exception);
        }
        return this;
    }

    private void printStackTrace(Exception exception) {
        exception.printStackTrace();
    }

    public MiEvent addBool(String name, boolean value) { return put(name, value); }
    public MiEvent addFloat(String name, float value) { return put(name, (double) value); }
    public MiEvent addInt(String name, int value) { return put(name, value); }
    public MiEvent addLong(String name, long value) { return put(name, value); }
    public MiEvent addShort(String name, short value) { return put(name, (int) value); }
    public MiEvent addStr(String name, String value) { return put(name, value); }
    public MiEvent addMiEvent(String name, MiEvent value) {
        return value == null ? this : put(name, value.mMiEvent);
    }

    public MiEvent addBoolArray(String name, boolean[] values) {
        JSONArray array = new JSONArray();
        if (values != null) for (boolean value : values) array.put(value);
        return put(name, array);
    }

    public MiEvent addFloatArray(String name, float[] values) {
        JSONArray array = new JSONArray();
        if (values != null) for (float value : values) array.put(Double.valueOf(value));
        return put(name, array);
    }

    public MiEvent addIntArray(String name, int[] values) {
        JSONArray array = new JSONArray();
        if (values != null) for (int value : values) array.put(value);
        return put(name, array);
    }

    public MiEvent addLongArray(String name, long[] values) {
        JSONArray array = new JSONArray();
        if (values != null) for (long value : values) array.put(value);
        return put(name, array);
    }

    public MiEvent addShortArray(String name, short[] values) {
        JSONArray array = new JSONArray();
        if (values != null) for (short value : values) array.put((int) value);
        return put(name, array);
    }

    public MiEvent addStrArray(String name, String[] values) {
        JSONArray array = new JSONArray();
        if (values != null) for (String value : values) array.put(value);
        return put(name, array);
    }

    public MiEvent addMiEventArray(String name, MiEvent[] values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (MiEvent value : values) if (value != null) array.put(value.mMiEvent);
        }
        return put(name, array);
    }

    public int getEventId() { return mEventId; }
    public String getMiEventStr() { return mMiEvent == null ? "" : mMiEvent.toString(); }

    public void dump(PrintWriter writer) {
        writer.println("EventID: " + mEventId);
        writer.println("paras: ");
        writer.println(getMiEventStr());
    }

    long getTimestamp() { return mTimestamp; }
}
