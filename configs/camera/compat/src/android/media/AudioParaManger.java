package android.media;

import android.content.Context;
import android.media.audiofx.AudioEffect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import android.icu.text.DecimalFormat;
import android.icu.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AudioParaManger {
    private static final String TAG = "WarsawCameraAudio";
    private static final String PREFIX = "SetAudioCustomScene=audioZoom@shot@";
    private final AudioManager audio;
    private final boolean systemCamera;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, LvacfsEffect> effects = new HashMap<>();
    private int[] config = new int[6];
    private int recordType, shot, scene, windNoise, zoomFrameType;
    private boolean initialized, objectCreated;

    public interface EventListener {
        void onMicBlocking(int first, int second);
        default void onAudioLevel(int first, int second) {}
    }
    public interface TuneListener {
        void onTuneAudioData(byte[] data);
        void onTuneCtrlData(byte[] data);
    }

    public AudioParaManger(AudioRecord recorder, Context context) { this(context); }
    public AudioParaManger(MediaRecorder recorder, Context context) { this(context); }
    private AudioParaManger(Context context) {
        audio = context.getSystemService(AudioManager.class);
        systemCamera = "com.android.camera".equalsIgnoreCase(context.getPackageName());
    }

    public synchronized boolean init(int type, int shot, int scene, double level,
            double azimuth, double elevation, double width, double height, boolean windNoise) {
        if (!systemCamera || audio == null || SystemProperties.getInt("ro.vendor.audio.zoom.type", 0) != 4
                || SystemProperties.getInt("ro.vendor.audio.unite.record.type", 0) != 1) {
            Log.e(TAG, "LVACFS initialization requires the captured warsaw audio contract");
            return false;
        }
        String property;
        int function;
        switch (type) {
            case 0: function = 0; property = "ro.vendor.audio.special.video.frontfacingrecord.type"; break;
            case 1:
                function = windNoise ? 7 : 1;
                property = windNoise ? "persist.vendor.audio.special.video.wnd_ns.type" : "ro.vendor.audio.general.video.type";
                break;
            case 3: function = 3; property = "ro.vendor.audio.special.video.spatialsound.type"; break;
            case 2: case 4: case 5: case 6:
                function = 2; property = "ro.vendor.audio.special.video.zoom.type"; break;
            default: return false;
        }
        String[] pieces = SystemProperties.get(property, "").split(",");
        if (pieces.length != 6) return false;
        int[] parsed = new int[6];
        try {
            for (int i = 0; i < pieces.length; i++) parsed[i] = Integer.parseInt(pieces[i], 16);
        } catch (NumberFormatException exception) {
            Log.e(TAG, "Invalid recording configuration " + property, exception);
            return false;
        }
        if (parsed[0] != 0 && parsed[0] != 4) return false;
        if (parsed[0] == 4 && (type < 0 || type > 3 || shot < 0 || shot > 1 || scene < 1 || scene > 4)) return false;
        config = new int[] {function, parsed[0], parsed[1], parsed[2], parsed[3], parsed[4]};
        recordType = type;
        this.shot = shot;
        this.scene = scene;
        this.windNoise = windNoise ? 1 : 0;
        initialized = true;
        objectCreated = false;
        return true;
    }

    public synchronized int[] getAudioConfig() { return config.clone(); }
    public boolean isUnite() { return SystemProperties.getInt("ro.vendor.audio.unite.record.type", 0) == 1; }
    public synchronized void createAudioObject(EventListener listener, TuneListener tune) {
        requireInitialized();
        objectCreated = config[1] == 4;
    }
    public void createAudioObject(EventListener listener) { createAudioObject(listener, null); }
    public synchronized boolean createAudioEffects(int recordingSession) {
        requireInitialized();
        if (config[1] == 0) return true;
        if (!objectCreated || effects.containsKey(recordingSession)) return false;
        LvacfsEffect effect = null;
        try {
            effect = new LvacfsEffect(recordingSession);
            if (effect.bindLocalSession() != AudioEffect.SUCCESS || effect.setEnabled(true) != AudioEffect.SUCCESS) {
                effect.release();
                return false;
            }
            effects.put(recordingSession, effect);
            return true;
        } catch (RuntimeException exception) {
            if (effect != null) effect.release();
            Log.e(TAG, "Unable to activate the actual LVACFS effect", exception);
            return false;
        }
    }
    public synchronized void prepare() {
        requireInitialized();
        audio.setParameters(sceneParameter(recordType, windNoise));
    }
    public synchronized void releaseAudioEffect() {
        handler.removeCallbacksAndMessages(null);
        for (LvacfsEffect effect : effects.values()) {
            try { effect.setEnabled(false); } finally { effect.release(); }
        }
        effects.clear();
        if (initialized && config[1] == 0) audio.setParameters(sceneParameter(1, 0));
        objectCreated = false;
    }
    private String sceneParameter(int type, int wind) {
        return PREFIX + shot + "/scene@" + scene + "/recType@" + type + "/wnd_ns@" + wind + "/";
    }
    public synchronized void setAudioZoomLevel(double value) {
        requireInitialized();
        DecimalFormat format = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        audio.setParameters("AURISYS_SET_PARAM,DSP,RECORD,FV_SPH,KEY_VALUE,audioZoom,ZoomLv@"
                + format.format(value) + "/=SET");
    }
    public synchronized int setSensorAngleRange(int range) {
        requireInitialized();
        if (config[1] == 0) return -1;
        audio.setParameters("sensor_angle_range=" + range);
        return 0;
    }
    public int setFocusRegion(int x, int y, int width, int height) { return setFocusRegion(x, y, width, height, -1); }
    public int setFocusRegion(int x, int y, int width, int height, int mode) { return region(x, y, width, height, mode, false); }
    public int setViewRegion(int x, int y, int width, int height) { return setViewRegion(x, y, width, height, -1); }
    public int setViewRegion(int x, int y, int width, int height, int mode) { return region(x, y, width, height, mode, true); }
    private synchronized int region(int x, int y, int width, int height, int mode, boolean view) {
        requireInitialized();
        String requested = (view ? "view_region=" : "focus_region=") + x + "," + y + "," + width + "," + height;
        if (mode == -1) { audio.setParameters(requested); return 0; }
        switch (mode) {
            case 3:
                zoomFrameType = 3;
                handler.removeCallbacksAndMessages(null);
                audio.setParameters(view ? "view_region=0,0,1080,1920" : "focus_region=0,0,0,0");
                break;
            case 2: zoomFrameType = 2; break;
            case 1:
                if (zoomFrameType == 3) break;
                zoomFrameType = 1;
                handler.removeCallbacksAndMessages(null);
                audio.setParameters(requested);
                break;
            case 0:
                zoomFrameType = 0;
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(this::resetRegions, 3000);
                break;
            default: break;
        }
        return 0;
    }
    private synchronized void resetRegions() {
        audio.setParameters("focus_region=0,0,0,0");
        audio.setParameters("view_region=0,0,1080,1920");
    }
    public void setAudioFocusAzimuth(double value, int mode) { region(0, 0, 0, 0, mode, false); }
    public void setAudioFocusElevation(double value, int mode) { region(0, 0, 0, 0, mode, true); }

    public void setAudioFocusAzimuth(double value) {}
    public void setAudioFocusElevation(double value) {}
    public void setAudioFocusHeight(double value) {}
    public void setAudioFocusWidth(double value) {}
    public void setAudioWindNoise(boolean value) {}
    public void setAudioZoom(int type, boolean value) {}
    public void setRecordType(int value) {}
    public void setUserGain(double value) {}
    public void setUserMode(boolean value) {}
    public void setMaxSupportLevel(int value) {}
    public float getAudioLatency() { return 0; }
    public boolean creatOzoEffect(int session) { return false; }
    public void createOzo(EventListener listener) {}
    public void createOzo(EventListener listener, TuneListener tune) {}
    public void releaseOzoEffect() {}
    public void start() { Log.d(TAG, "Recording setup complete; original start method only reported OEM telemetry"); }

    private void requireInitialized() {
        if (!initialized) throw new IllegalStateException("Recording backend was not initialized");
    }
    private static final class LvacfsEffect extends AudioEffect {
        LvacfsEffect(int session) {
            super(UUID.fromString("56d6b082-1a83-455a-84a8-9db3a35cf534"),
                    UUID.fromString("7e384a3b-7850-4a64-a097-884250d8a838"), 0, session);
        }
        int bindLocalSession() { return setParameter(333333, "0".getBytes(StandardCharsets.UTF_8)); }
    }
}
