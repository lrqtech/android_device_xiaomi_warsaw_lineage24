import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Transcribed region/scene contract of the pinned MiuiCamera w2.l0.F and q9.b. */
public final class BeautyRegionContractTest {
    public static void main(String[] args) throws Exception {
        Path vendor = Path.of(args[0]);
        JSONArray cases = new JSONArray(Files.readString(Path.of(args[1])));
        JSONArray results = new JSONArray();
        String[] scenes = {"FrontCapture", "FrontPortrait", "FrontRecordVideo",
                "FrontAIWatermark", "FrontSuperNight", "RearCapture",
                "RearPortrait", "RearRecordVideo", "RearAIWatermark"};
        for (int n = 0; n < cases.length(); ++n) {
            JSONObject test = cases.getJSONObject(n);
            JSONObject root = new JSONObject(Files.readString(vendor.resolve(test.getString("file"))));
            JSONObject selected = root;
            try {
                selected = root.getJSONObject(test.getString("app_region"));
            } catch (JSONException missingRegion) {
                // China uses the legacy root-scene layout. Other regions fall back to IN/GL.
                if (!test.getBoolean("china")) {
                    selected = root.getJSONObject(test.getBoolean("india") ? "INRegion" : "GLRegion");
                }
            }
            for (String scene : scenes) {
                JSONObject result = new JSONObject().put("case", test.getString("name") + ":" + scene);
                try {
                    // These entries are deliberately absent from the global OEM table.
                    // Do not turn unsupported UI features on to silence a parser warning.
                    if (test.getString("app_region").equals("GLRegion") && scene.endsWith("AIWatermark")) {
                        if (selected.has(scene)) throw new AssertionError("global capability changed");
                        results.put(result.put("passed", true).put("unsupported_in_oem_table", true));
                        continue;
                    }
                    JSONObject settings = selected.getJSONObject(scene);
                    JSONArray options = settings.getJSONArray("Options");
                    if (options.length() == 0) throw new AssertionError("empty beauty controls");
                    JSONArray values = new JSONArray();
                    for (int i = 0; i < options.length(); ++i) {
                        JSONObject option = options.getJSONObject(i);
                        int id = option.optInt("Id", 1), value = option.optInt("Val", 0);
                        if (id < 1 || id > 17 || value < 0 || value > 100) {
                            throw new AssertionError("unrecognised control/range: " + id + "/" + value);
                        }
                        values.put(new JSONArray().put(id).put(value));
                    }
                    int mode = settings.optInt("BeautyMode", 2);
                    if (mode != 1 && mode != 2) throw new AssertionError("unsupported beauty mode");
                    result.put("passed", true).put("mode", mode).put("options", values);
                    // q9.b.d returns -1 when BeautyOn is absent; preserve that OEM default.
                    result.put("default_on", settings.has("BeautyOn") ? settings.optInt("BeautyOn", 0) : -1);
                } catch (JSONException | AssertionError failure) {
                    result.put("passed", false).put("error", failure.toString());
                }
                results.put(result);
            }
        }
        System.out.println(results);
    }
}
