package org.lineageos.warsaw.fan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.Log;

/** The OS4 MiuiBatteryIntelligence fan tachometer path, owned by Settings. */
final class FanSpeedMonitor implements SensorEventListener, AutoCloseable {
    static final String RPM = "warsaw_fan_measured_rpm";
    static final String UPDATED = "warsaw_fan_measured_elapsed";
    static final String STATUS = "warsaw_fan_tachometer_status";
    private static final String TAG = "WarsawFanTachometer";
    private static final int SENSOR_TYPE = 33171234;
    private final Context context;
    private final Handler handler;
    private final SensorManager sensors;
    private final MiChargeClient hardware;
    private Sensor sensor;
    private boolean plugged, running, registered;
    private volatile boolean closed;
    private volatile long generation;
    private int pendingRpm;
    private long pendingTime, pendingSequence, sentSequence;
    private long lastPublishedTime = -1;
    private String lastStatus;
    private String driverError;
    private final UEventObserver events = new UEventObserver() {
        @Override public void onUEvent(UEvent event) {
            String enabled = event.get("MCA_FAN_ENABLE");
            String failure = event.get("MCA_FAN_ERROR_INFO");
            handler.post(() -> {
                if (closed) return;
                if (enabled != null) {
                    // Only a fresh driver enable event confirms recovery from a driver fault.
                    if ("1".equals(enabled)) driverError = null;
                    setRunning("1".equals(enabled));
                    if (registered) status("waiting");
                }
                if (failure != null) {
                    Log.e(TAG, "Fan driver reported " + failure);
                    driverError = "driver_error:" + failure;
                    if ("FAN_LDO_OCP".equals(failure)) setRunning(false);
                    status(driverError);
                }
            });
        }
    };
    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            updateRegistration();
        }
    };
    FanSpeedMonitor(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
        sensors = context.getSystemService(SensorManager.class);
        hardware = new MiChargeClient(handler, () -> status("hal_unavailable"));
        events.startObserving("MCA_FAN_ENABLE=");
        events.startObserving("MCA_FAN_ERROR_INFO=");
        Intent initial = context.registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                null, handler, Context.RECEIVER_NOT_EXPORTED);
        plugged = initial != null && initial.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        status("waiting");
    }
    void setRunning(boolean value) {
        if (closed) return;
        running = value;
        updateRegistration();
    }
    private void updateRegistration() {
        if (closed) return;
        boolean needed = plugged && running && !"driver_error:FAN_LDO_OCP".equals(driverError);
        if (registered && !needed) {
            generation++;
            sensors.unregisterListener(this, sensor);
            registered = false;
            status("inactive");
        } else if (needed && !registered) {
            sensor = sensors == null ? null : sensors.getDefaultSensor(SENSOR_TYPE);
            registered = sensor != null && sensors.registerListener(this, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL, handler);
            if (!registered) Log.e(TAG, "Cannot register official fan speed sensor");
            status(registered ? "waiting" : "sensor_unavailable");
        } else if (!needed) {
            status("inactive");
        }
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (closed || !registered || event.sensor.getType() != SENSOR_TYPE) return;
        float value = event.values[0];
        if (!Float.isFinite(value) || value < 0 || value > 40000) {
            Log.e(TAG, "Invalid measured fan speed: " + value);
            status("invalid_sample");
            return;
        }
        // The official firmware passes the sensor's measured value unchanged.
        pendingRpm = (int)value;
        pendingTime = SystemClock.elapsedRealtime();
        pendingSequence++;
        forwardSample();
    }
    private void forwardSample() {
        if (closed || !registered || hardware.isBusy() || sentSequence == pendingSequence) return;
        final long revision = generation, sequence = pendingSequence, time = pendingTime;
        final int rpm = pendingRpm;
        sentSequence = sequence;
        hardware.setMeasuredSpeed(rpm, () -> !closed && generation == revision, (observed, error) -> {
            if (closed || generation != revision) return;
            if (error != null) {
                Log.e(TAG, "Cannot deliver measured fan speed", error);
                status("write_failed");
            } else {
                if (lastPublishedTime < 0 || time - lastPublishedTime >= 1000) {
                    boolean rpmSaved = Settings.Global.putInt(context.getContentResolver(), RPM, observed);
                    boolean timeSaved = Settings.Global.putLong(context.getContentResolver(), UPDATED, time);
                    if (rpmSaved && timeSaved) {
                        lastPublishedTime = time;
                        status("measured");
                    } else {
                        Log.e(TAG, "Cannot publish measured fan speed");
                        status("publish_failed");
                    }
                }
            }
            forwardSample();
        });
    }
    private void status(String value) {
        if (driverError != null && !closed) value = driverError;
        if (value.equals(lastStatus)) return;
        if (Settings.Global.putString(context.getContentResolver(), STATUS, value)) lastStatus = value;
        else Log.e(TAG, "Cannot publish tachometer status");
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override public void close() {
        closed = true;
        generation++;
        events.stopObserving();
        context.unregisterReceiver(battery);
        if (registered) sensors.unregisterListener(this, sensor);
        registered = false;
        hardware.close();
        status("inactive");
    }
}
