package org.lineageos.warsaw.fan;

import org.json.JSONException;
import org.json.JSONObject;

final class FanPolicy {
    static final long CLEAN_IDLE_MS = 2592000000L;
    static final long CLEAN_WAIT_MS = 300000L;
    static final long CLEAN_RUN_MS = 8000L;
    final boolean enabled;
    private final Curve[][] curves = new Curve[2][3];

    FanPolicy(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        enabled = root.getBoolean("funcSwitch");
        JSONObject scenes = root.getJSONObject("senerioConfig");
        for (int scene = 0; scene < 2; scene++) {
            JSONObject modes = scenes.getJSONObject(scene == 0 ? "target" : "other");
            for (int mode = -1; mode <= 1; mode++) {
                curves[scene][mode + 1] = new Curve(modes.getString(Integer.toString(mode)));
            }
        }
    }

    static final class Inputs {
        boolean game, noLimits, navigation, navigationForeground, migration;
        boolean recording, assistantRecording, locked, screenOff, charging;
        boolean call, earpiece, quickCharge;
        int temperatureMilliC;
    }

    int level(FanPreferences p, Inputs i, int current, int previousTemperature) {
        if (!enabled || !p.enabled) return 0;
        if (p.stopRecording && i.assistantRecording && !i.game && !i.noLimits) return 0;
        if (p.stopRecording && i.recording && !i.game
                && !(i.navigation && i.navigationForeground)) return 0;
        if (i.locked && !i.quickCharge) return 0;
        if (i.call && i.earpiece && !i.game) return 0;
        boolean target = (i.game && p.gaming) || (i.quickCharge && p.rapidCharge)
                || (i.navigation && p.navigation) || i.noLimits || i.migration;
        if (!target && !p.otherScenes) return 0;
        if (p.mode == 2) return 4;
        if (p.mode == 3) return p.manualLevel;
        return curves[target ? 0 : 1][p.mode + 1].next(
                current, i.temperatureMilliC, previousTemperature);
    }

    private static final class Curve {
        final int fixed;
        final int[] levels, up, down;
        Curve(String value) {
            if (!value.contains("#")) {
                fixed = checkedLevel(Integer.parseInt(value));
                levels = up = down = new int[0];
                return;
            }
            fixed = -1;
            String[] entries = value.split(",", -1);
            levels = new int[entries.length]; up = new int[entries.length];
            down = new int[entries.length];
            for (int n = 0; n < entries.length; n++) {
                String[] fields = entries[n].split("[#:]", -1);
                if (fields.length != 3) throw new IllegalArgumentException("Invalid curve");
                levels[n] = checkedLevel(Integer.parseInt(fields[0]));
                up[n] = Integer.parseInt(fields[1]); down[n] = Integer.parseInt(fields[2]);
                if (levels[n] == 0 || down[n] > up[n]
                        || (n > 0 && (levels[n] <= levels[n-1] || up[n] < up[n-1]))) {
                    throw new IllegalArgumentException("Unordered curve");
                }
            }
        }
        int next(int current, int temperature, int previous) {
            checkedLevel(current);
            if (fixed >= 0) return fixed;
            int celsius = temperature / 1000;
            if (temperature <= previous && current != 0) {
                for (int n = 0; n < levels.length; n++) {
                    if (celsius < down[n] && levels[n] <= current) return levels[n] - 1;
                }
            } else {
                for (int n = levels.length - 1; n >= 0; n--) {
                    if (celsius >= up[n] && levels[n] > current) return levels[n];
                }
            }
            return current;
        }
    }
    static int checkedLevel(int value) {
        if (value < 0 || value > 4) throw new IllegalArgumentException("Invalid fan level");
        return value;
    }
}
