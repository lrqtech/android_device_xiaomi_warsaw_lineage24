// SPDX-License-Identifier: Apache-2.0
package com.miui.cameraopt.quickcamera;

import org.json.JSONException;
import org.json.JSONObject;

/** Host regressions for capture notifications arriving in either order. */
public final class CameraMindEventFormatterTest {
    private interface Case { void run() throws Exception; }
    private static int failures;
    private static int cases;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void deferred(String result) {
        check(result == null, "Capture must wait for its own image title");
    }
    private static String snap(CameraMindEventFormatter f, int id) throws JSONException {
        return f.format(2008, new JSONObject().put("capture", id).toString());
    }
    private static String title(CameraMindEventFormatter f, int id) throws JSONException {
        return f.format(2012, new JSONObject().put("imageUniqueName", "IMG_" + id).toString());
    }
    private static void paired(String result, int id) throws JSONException {
        check(result != null, "Both notifications must produce a message");
        JSONObject message = new JSONObject(result);
        JSONObject data = message.getJSONObject("data");
        check(message.getInt("what") == 1021, "Wrong capture message kind");
        check(message.getInt("reply") == 0, "Unexpected reply flag");
        check(data.getInt("capture") == id, "Capture data was replaced");
        check(data.getString("imageUniqueName").equals("IMG_" + id), "Stale image title");
    }
    private static void run(String name, Case test) {
        cases++;
        try { test.run(); System.out.println("PASS " + name); }
        catch (Exception | AssertionError failure) {
            failures++; System.out.println("FAIL " + name + ": " + failure);
        }
    }
    public static void main(String[] args) {
        run("titles before captures", () -> {
            var f = new CameraMindEventFormatter();
            for (int i = 0; i < 20; i++) { deferred(title(f, i)); paired(snap(f, i), i); }
        });
        run("captures before titles", () -> {
            var f = new CameraMindEventFormatter();
            for (int i = 0; i < 20; i++) { deferred(snap(f, i)); paired(title(f, i), i); }
        });
        run("notification order changes between captures", () -> {
            var f = new CameraMindEventFormatter();
            for (int i = 0; i < 40; i++) {
                if (i % 3 == 0) { deferred(title(f, i)); paired(snap(f, i), i); }
                else { deferred(snap(f, i)); paired(title(f, i), i); }
            }
        });
        run("unrelated event preserves pending capture", () -> {
            var f = new CameraMindEventFormatter(); deferred(snap(f, 1));
            JSONObject other = new JSONObject(f.format(2001, "{\"status\":7}"));
            check(other.getJSONObject("data").getInt("status") == 7, "Unrelated data changed");
            paired(title(f, 1), 1); deferred(snap(f, 2)); paired(title(f, 2), 2);
        });
        run("malformed title preserves pending capture", () -> {
            var f = new CameraMindEventFormatter(); deferred(snap(f, 1));
            try { f.format(2012, "{}"); throw new AssertionError("Missing title accepted"); }
            catch (JSONException expected) { }
            paired(title(f, 1), 1); deferred(snap(f, 2)); paired(title(f, 2), 2);
        });
        run("unrelated event preserves pending title", () -> {
            var f = new CameraMindEventFormatter(); deferred(title(f, 1));
            f.format(2001, "{}"); paired(snap(f, 1), 1);
        });
        run("event 2022 keeps its wire kind", () -> {
            var f = new CameraMindEventFormatter();
            check(new JSONObject(f.format(2022, "{}")).getInt("what") == 1081,
                    "Event mapping changed");
        });
        System.out.println("Cases=" + cases + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }
}
