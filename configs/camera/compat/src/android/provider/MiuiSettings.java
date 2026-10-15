package android.provider;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import java.util.List;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

public final class MiuiSettings {
    private MiuiSettings() {}

    public static final class Global {
        private Global() {}
        public static boolean getBoolean(ContentResolver resolver, String key) {
            return Settings.Global.getInt(resolver, key, 0) != 0;
        }
    }

    public static final class System {
        private System() {}
        public static boolean getBoolean(ContentResolver resolver, String key, boolean fallback) {
            return Settings.System.getInt(resolver, key, fallback ? 1 : 0) != 0;
        }
    }

    public static final class SettingsCloudData {
        private static final String TAG = "WarsawCameraSettings";
        private static final Uri ALL = Uri.parse(
                "content://com.android.settings.cloud.CloudSettings/cloud_all_data");
        private static final Uri SINGLE = Uri.withAppendedPath(ALL, "single");
        private SettingsCloudData() {}

        public static List<CloudData> getCloudDataList(ContentResolver resolver, String module) {
            try (Cursor cursor = resolver.query(ALL, new String[] {module}, null, null, null)) {
                if (cursor == null) return null;
                Bundle extras = cursor.getExtras();
                if (extras == null || extras.isEmpty()) return null;
                extras.setClassLoader(CloudData.class.getClassLoader());
                return extras.getParcelableArrayList("productData", CloudData.class);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Original cloud provider query failed; retaining caller fallback", exception);
                return null;
            }
        }

        public static String getCloudDataString(ContentResolver resolver, String module,
                String key, String fallback) {
            CloudData data = getCloudDataSingle(resolver, module, null, null, false);
            return data == null ? fallback : data.getString(key, fallback);
        }

        public static CloudData getCloudDataSingle(ContentResolver resolver, String module,
                String key, String propertyName, boolean cache) {
            Objects.requireNonNull(module, "moduleName is null");
            if (cache && (key == null || propertyName == null)) {
                throw new NullPointerException("Want cache, but key or propertyName is null");
            }
            String[] projection = {module, key, propertyName, String.valueOf(cache)};
            try (Cursor cursor = resolver.query(SINGLE, projection, null, null, null)) {
                if (cursor == null) return null;
                Bundle extras = cursor.getExtras();
                if (extras == null || extras.isEmpty()) return null;
                extras.setClassLoader(CloudData.class.getClassLoader());
                return extras.getParcelable("productData", CloudData.class);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Original cloud provider query failed; retaining caller fallback", exception);
                return null;
            }
        }

        public static final class CloudData implements Parcelable {
            private final String data;
            private JSONObject json;
            public CloudData(String data) { this.data = data; }
            private JSONObject object() throws JSONException {
                if (json == null) json = new JSONObject(data);
                return json;
            }
            public String getString(String key, String fallback) {
                try { return object().has(key) ? object().getString(key) : fallback; }
                catch (Exception exception) { return fallback; }
            }
            public JSONObject json() {
                try { return object(); } catch (JSONException exception) { return null; }
            }
            @Override public String toString() { return data.toString(); }
            @Override public int hashCode() { return Objects.hashCode(data); }
            @Override public boolean equals(Object other) {
                return other instanceof CloudData && Objects.equals(data, ((CloudData) other).data);
            }
            @Override public int describeContents() { return 0; }
            @Override public void writeToParcel(Parcel target, int flags) { target.writeString(data); }
            public static final Parcelable.Creator<CloudData> CREATOR = new Parcelable.Creator<>() {
                @Override public CloudData createFromParcel(Parcel source) {
                    return new CloudData(source.readString());
                }
                @Override public CloudData[] newArray(int size) { return new CloudData[size]; }
            };
        }
    }
}
