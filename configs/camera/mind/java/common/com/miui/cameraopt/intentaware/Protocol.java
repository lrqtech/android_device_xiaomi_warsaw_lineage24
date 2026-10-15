package com.miui.cameraopt.intentaware;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

public final class Protocol {
    public static final int IDENTIFY = 1005;
    public static final int FORWARD = 2004;
    public static final int CONFIGURATION = 2006;
    private static final int MAX_ID = Integer.MAX_VALUE - 10;
    private static final long MAX_SERIAL = Long.MAX_VALUE - 10;
    private static final Map<Integer, Long> SERIALS = new HashMap<>();
    private static int nextId;
    private static int sessionId = 1000;
    private int what = -1;
    private int reply;
    private Map<String, ?> values;
    private JSONObject json;
    private String text;

    public Protocol setWhat(int value) { what = value; return this; }
    public Protocol setReply(int value) { reply = value; return this; }
    public Protocol setValue(Map<String, ?> value) { values = value; return this; }
    public Protocol setJson(JSONObject value) { json = value; return this; }
    public Protocol setStr(String value) { text = value; return this; }

    public static synchronized int nextSessionId() {
        sessionId = sessionId >= MAX_ID ? 1000 : sessionId + 1;
        return sessionId;
    }

    private static synchronized int nextMessageId() {
        int result = nextId;
        nextId = nextId >= MAX_ID ? 0 : nextId + 1;
        return result;
    }

    private static synchronized long nextSerial(int what) {
        long value = SERIALS.getOrDefault(what, 0L);
        if (value > MAX_SERIAL) value = 0;
        if (!SERIALS.containsKey(what) && SERIALS.size() >= 4096) {
            throw new IllegalStateException("CameraMind message-type counter limit");
        }
        SERIALS.put(what, value + 1);
        return value;
    }

    public String buildMessage() {
        try {
            JSONObject result = new JSONObject();
            result.put("id", nextMessageId()).put("what", what).put("serial", nextSerial(what))
                    .put("time", System.currentTimeMillis()).put("reply", reply);
            if (json != null) result.put("data", json);
            else if (values != null) {
                JSONObject data = new JSONObject();
                for (Map.Entry<String, ?> entry : values.entrySet()) data.put(entry.getKey(), entry.getValue());
                result.put("data", data);
            } else result.put("data", text == null ? "" : text);
            return result.toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid CameraMind message", e);
        }
    }

    public static JSONObject parse(String payload) throws JSONException {
        JSONObject value = new JSONObject(payload);
        value.getInt("what");
        return value;
    }
}
