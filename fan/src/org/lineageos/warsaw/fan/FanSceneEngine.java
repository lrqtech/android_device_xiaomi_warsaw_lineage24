package org.lineageos.warsaw.fan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class FanSceneEngine {
    static final Set<String> SPECIAL_C_COMMUNICATION_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
            "com.tencent.mm", "com.tencent.mobileqq", "com.alibaba.android.rimet",
            "com.tencent.wework", "com.whatsapp", "com.ss.android.lark")));
    private static final String[] ELEMENTS = {"SZForegroundGroup", "HighFpsTopActivity",
        "Screen", "Call", "Camera", "PowerMode", "IEC", "SpecialCScenario", "Fps",
        "LowTempCharge", "SPTM_2", "SpecialForeground_3", "ReverseChargeState", "ExtraVideo"};
    private static final int DIMENSIONS = ELEMENTS.length;
    private static final int MAX_ASSIGNMENTS = 16384;
    private final List<Rule> rules;
    private final Map<String, Integer> foreground;
    private final Map<String, Integer> activities;
    private final Set<String> possibleSpecialGames = new HashSet<>();
    private final String winplay;
    private final int iecState;
    private final int powerModeState;

    private static final class Rule {
        final int id;
        final int[] values;
        final String category;
        Rule(JSONObject json) throws Exception {
            id = json.getInt("id");
            category = json.getString("category");
            if (id < 0 || !("NORMAL".equals(category) || "GAME".equals(category)
                    || "NO_LIMITS".equals(category))) throw new IllegalArgumentException("Invalid scenario");
            JSONArray vector = json.getJSONArray("defined");
            if (vector.length() != DIMENSIONS) throw new IllegalArgumentException("Scenario dimensions");
            values = new int[DIMENSIONS];
            for (int i = 0; i < DIMENSIONS; i++) values[i] = vector.getInt(i);
        }
        boolean matches(int[] input) {
            for (int i = 0; i < DIMENSIONS; i++) {
                if (values[i] != 0 && values[i] != input[i]) return false;
            }
            return true;
        }
    }

    private static Map<String, Integer> integers(JSONObject object) throws Exception {
        Map<String, Integer> result = new HashMap<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext();) {
            String key = keys.next();
            result.put(key, object.getInt(key));
        }
        return result;
    }

    FanSceneEngine(JSONObject asset) throws Exception {
        if (asset.getInt("schema_version") != 1 || asset.getBoolean("sptm_enabled")) {
            throw new IllegalArgumentException("Unsupported scene producer configuration");
        }
        JSONObject sealed = asset.getJSONObject("sealed_input_contract");
        iecState = sealed.getInt("iec_state");
        if (sealed.getBoolean("iec_feature_enabled") || iecState != 99
                || sealed.getBoolean("dynamic_cloud_updates")) {
            throw new IllegalArgumentException("Unsupported IEC input contract");
        }
        powerModeState = sealed.getInt("power_mode_initial_state");
        if (powerModeState != 98
                || !"ultimate".equals(sealed.getString("power_mode_user_configure_status"))
                || sealed.getBoolean("power_mode_dynamic_updates")) {
            throw new IllegalArgumentException("Unsupported PowerMode input contract");
        }
        JSONArray elements = asset.getJSONArray("elements");
        if (elements.length() != DIMENSIONS) throw new IllegalArgumentException("Element count");
        for (int i = 0; i < DIMENSIONS; i++) {
            if (!ELEMENTS[i].equals(elements.getString(i))) throw new IllegalArgumentException("Element order");
        }
        Map<String, Rule> leaves = new LinkedHashMap<>();
        Map<Integer, String> categories = new HashMap<>();
        JSONArray list = asset.getJSONArray("rules");
        if (list.length() != sealed.getInt("cached_scenario_count")) {
            throw new IllegalArgumentException("Cached scenario count");
        }
        boolean fallback = false;
        for (int i = 0; i < list.length(); i++) {
            Rule rule = new Rule(list.getJSONObject(i));
            String prior = categories.put(rule.id, rule.category);
            if (prior != null && !prior.equals(rule.category)) throw new IllegalArgumentException("Ambiguous scene id");
            leaves.put(Arrays.toString(rule.values), rule);
        }
        rules = new ArrayList<>(leaves.values());
        for (Rule rule : rules) {
            boolean allZero = true;
            for (int value : rule.values) allZero &= value == 0;
            fallback |= allZero;
        }
        if (!fallback) throw new IllegalArgumentException("No fallback scenario");
        rules.sort((a, b) -> Integer.compare(b.id, a.id));
        foreground = integers(asset.getJSONObject("foreground_groups"));
        activities = integers(asset.getJSONObject("activity_groups"));
        JSONArray special = asset.getJSONArray("special_game_candidates");
        for (int i = 0; i < special.length(); i++) possibleSpecialGames.add(special.getString(i));
        winplay = asset.getString("winplay_package");
    }

    private static int[] one(int value) { return new int[]{value}; }

    String category(String packageName, String className, int[] screen, boolean offHook,
            int[] camera, int[] specialC, int[] reverseCharge) {
        if (packageName == null || className == null) return "UNKNOWN";
        int[][] input = new int[DIMENSIONS][];
        input[0] = one(foreground.getOrDefault(packageName, 97));
        input[1] = one(activities.getOrDefault(className, 97));
        input[2] = screen;
        input[3] = one(offHook ? 98 : 99);
        input[4] = camera;
        input[5] = one(powerModeState);
        input[6] = one(iecState);
        input[7] = specialC;
        input[8] = new int[]{99, 1};
        input[9] = new int[]{99, 1};
        input[10] = one(99);
        input[11] = one(winplay.equals(packageName) ? 7 : 97);
        input[12] = reverseCharge;
        input[13] = new int[]{99, 1};
        return resolve(input);
    }

    int[] specialCStates(String foregroundPackage, boolean communication,
            boolean communicationPossible) {
        int active = possibleSpecialGames.contains(foregroundPackage) ? 2 : 1;
        if (communication) return one(active);
        return communicationPossible ? new int[]{99, active} : one(99);
    }

    String resolve(int[][] domains) {
        if (domains.length != DIMENSIONS) throw new IllegalArgumentException("Input dimensions");
        long assignments = 1;
        for (int[] domain : domains) {
            if (domain == null || domain.length == 0) return "UNKNOWN";
            assignments *= domain.length;
            if (assignments > MAX_ASSIGNMENTS) return "UNKNOWN";
        }
        Set<String> result = new HashSet<>();
        visit(domains, new int[DIMENSIONS], 0, result);
        return result.size() == 1 ? result.iterator().next() : "UNKNOWN";
    }

    private void visit(int[][] domains, int[] input, int position, Set<String> result) {
        if (result.size() > 1) return;
        if (position == DIMENSIONS) {
            for (Rule rule : rules) {
                if (rule.matches(input)) {
                    result.add(rule.category);
                    return;
                }
            }
            result.add("UNKNOWN");
            return;
        }
        for (int value : domains[position]) {
            input[position] = value;
            visit(domains, input, position + 1, result);
            if (result.size() > 1) return;
        }
    }
}
