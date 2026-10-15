package org.lineageos.warsaw.fan;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.preference.SwitchPreferenceCompat;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.widget.SeekBarPreference;
import com.android.settingslib.search.SearchIndexable;

@SearchIndexable
public final class FanSettings extends DashboardFragment {
    private SwitchPreferenceCompat enabled;
    private SeekBarPreference pwm;
    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { render(); }
    };
    @Override protected String getLogTag() { return "CoolingFanSettings"; }
    @Override public int getMetricsCategory() { return 0; }
    @Override protected int getPreferenceScreenResId() { return R.xml.warsaw_fan_settings; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        enabled = findPreference(FanPreferences.ENABLE);
        pwm = findPreference(FanPreferences.PWM);
        enabled.setOnPreferenceChangeListener((preference, value) -> save(
                FanPreferences.ENABLE, Boolean.TRUE.equals(value) ? 1 : 0));
        pwm.setContinuousUpdates(false);
        pwm.setOnPreferenceChangeListener((preference, value) -> save(
                FanPreferences.PWM, (Integer) value));
    }
    private boolean save(String key, int value) {
        boolean saved = Settings.System.putInt(getContext().getContentResolver(), key, value);
        if (!saved) Toast.makeText(getContext(), R.string.warsaw_fan_save_failed,
                Toast.LENGTH_LONG).show();
        return saved;
    }
    @Override public void onResume() {
        super.onResume();
        getContext().startServiceAsUser(new Intent(getContext(), FanService.class), UserHandle.SYSTEM);
        for (String key : new String[] {FanPreferences.ENABLE, FanPreferences.PWM}) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, observer);
        }
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(FanPreferences.STATUS), false, observer);
        render();
    }
    @Override public void onPause() {
        getContext().getContentResolver().unregisterContentObserver(observer);
        super.onPause();
    }
    private void render() {
        if (!isAdded()) return;
        boolean on = Settings.System.getInt(getContext().getContentResolver(),
                FanPreferences.ENABLE, 1) == 1;
        int duty = Settings.System.getInt(getContext().getContentResolver(),
                FanPreferences.PWM, FanPreferences.DEFAULT_PWM);
        enabled.setChecked(on);
        String status = Settings.Global.getString(getContext().getContentResolver(),
                FanPreferences.STATUS);
        enabled.setSummary("unavailable".equals(status)
                ? getString(R.string.warsaw_fan_control_unavailable) : null);
        pwm.setProgress(Math.max(0, Math.min(100, duty)));
        pwm.setEnabled(on);
        pwm.setSummary(getString(R.string.warsaw_fan_pwm_value, Math.max(0, Math.min(100, duty))));
    }
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.warsaw_fan_settings) {
                @Override protected boolean isPageSearchEnabled(Context context) {
                    return "warsaw".equals(android.os.Build.DEVICE);
                }
            };
}
