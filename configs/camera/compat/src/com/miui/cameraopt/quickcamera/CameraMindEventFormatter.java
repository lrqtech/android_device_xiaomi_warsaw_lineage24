package com.miui.cameraopt.quickcamera;

import org.json.JSONException;
import org.json.JSONObject;

/** OS4 CamOptClientFormatMsg framing with single-use image-title pairing. */
final class CameraMindEventFormatter {
    private JSONObject pendingSnap;
    private String imageTitle;
    private int sequence;

    synchronized String format(int event, String parameter) throws JSONException {
        JSONObject data = new JSONObject(parameter);
        boolean ready = true;
        if (event == 2012) {
            imageTitle = data.getString("imageUniqueName");
            if (pendingSnap != null) {
                pendingSnap.put("imageUniqueName", imageTitle);
                data = pendingSnap;
                pendingSnap = null;
                // Both halves were consumed. The next capture needs a new title.
                imageTitle = null;
            } else ready = false;
        } else if (event == 2008) {
            if (imageTitle != null) {
                data.put("imageUniqueName", imageTitle);
                imageTitle = null;
            } else {
                pendingSnap = data;
                ready = false;
            }
        }
        if (sequence == Integer.MAX_VALUE) sequence = 0;
        JSONObject message = new JSONObject();
        message.put("id", sequence++);
        message.put("what", event == 2022 ? 1081 : 1021);
        message.put("time", System.currentTimeMillis());
        message.put("reply", 0);
        message.put("data", data);
        return ready ? message.toString() : null;
    }
}
