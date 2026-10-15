package com.android.server.display;

import android.content.Context;
import android.hardware.display.BrightnessInfo;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.os.UserHandle;
import android.util.Slog;
import android.view.Display;
import android.view.SurfaceControlHdrLayerInfoListener;

import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.config.HighBrightnessModeData;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

final class WarsawThermalBrightness {
    private static final String TAG = "WarsawThermalBrightness";
    private static final String DIRECTORY = "/sys/class/thermal/thermal_message/";
    private static final String[] NODES = {
        "board_sensor_temp", "sconfig", "thermal_max_brightness"
    };
    private final Context mContext;
    private final Handler mDisplayHandler;
    private final Runnable mChanged;
    private final WarsawThermalPolicy mPolicy = new WarsawThermalPolicy();
    private final Object mReadLock = new Object();
    private final Object mHdrLock = new Object();
    private final FileObserver[] mObservers = new FileObserver[NODES.length];
    private HandlerThread mThread;
    private Handler mReader;
    private boolean mReadQueued;
    private boolean mReadAgain;
    private Snapshot mPendingSnapshot;
    private boolean mDeliveryQueued;
    private volatile boolean mStopped;
    private boolean mStarted;
    private boolean mInternal = true;
    private int mLastState = Display.STATE_UNKNOWN;
    private IBinder mToken;
    private SurfaceControlHdrLayerInfoListener mHdrListener;
    private long mTokenEpoch;
    private HdrSnapshot mPendingHdr;
    private HdrSnapshot mLastHdr;
    private boolean mHdrQueued;
    private int mWidth;
    private int mHeight;
    private float mMinimumHdrArea;
    private DisplayDeviceConfig mConfig;
    private boolean mHdr;
    private boolean mCameraHdr;
    private WarsawThermalDisplayListener.Subscription mDolbyListener;
    private boolean mClampedBrightness;
    private float mBrightnessCap = Float.NaN;
    private String mReadError = "not sampled";
    private long mLastReadUptime;

    static WarsawThermalBrightness create(Context context, Handler handler,
            Runnable changed, int displayId, boolean internal, IBinder token,
            int width, int height, DisplayDeviceConfig config) {
        if (!"warsaw".equals(Build.DEVICE) || displayId != Display.DEFAULT_DISPLAY
                || !internal) return null;
        return new WarsawThermalBrightness(context, handler, changed, token, width, height, config);
    }

    private WarsawThermalBrightness(Context context, Handler handler,
            Runnable changed, IBinder token, int width, int height, DisplayDeviceConfig config) {
        mContext = context;
        mDisplayHandler = handler;
        mChanged = changed;
        mToken = token;
        mWidth = width;
        mHeight = height;
        mMinimumHdrArea = minimumHdrArea(config);
        mConfig = config;
    }

    private void start() {
        if (mStarted || mStopped) return;
        mStarted = true;
        mThread = new HandlerThread(TAG);
        mThread.start();
        synchronized (mReadLock) { mReader = new Handler(mThread.getLooper()); }
        for (int i = 0; i < NODES.length; i++) {
            mObservers[i] = new FileObserver(new File(DIRECTORY + NODES[i]),
                    FileObserver.MODIFY) {
                @Override public void onEvent(int event, String path) {
                    if ((event & FileObserver.MODIFY) != 0) requestRead();
                }
            };
            mObservers[i].startWatching();
        }
        registerHdr();
        mDolbyListener = WarsawThermalDisplayListener.subscribe(mDisplayHandler, (known, enabled) -> {
            if (mStopped) return;
            float previous = mPolicy.capNits();
            mPolicy.setDolby(known, enabled);
            if (!sameFloat(previous, mPolicy.capNits())) mChanged.run();
        });
        requestRead();
    }

    private void registerHdr() {
        if (!mStarted || mStopped || !mInternal || mToken == null || mHdrListener != null) return;
        final IBinder token = mToken;
        final long epoch = mTokenEpoch;
        mHdrListener = new SurfaceControlHdrLayerInfoListener() {
            @Override public void onHdrInfoChanged(IBinder displayToken, int count,
                    int maxW, int maxH, int flags, float ratio) {
                synchronized (mHdrLock) {
                    if (mStopped || epoch != mTokenEpoch || displayToken != mToken) return;
                    mPendingHdr = new HdrSnapshot(displayToken, epoch, count, maxW, maxH, flags);
                    if (!mHdrQueued) {
                        mHdrQueued = true;
                        if (!mDisplayHandler.post(mDeliverHdr)) {
                            mHdrQueued = false;
                            mPendingHdr = null;
                        }
                    }
                }
            }
        };
        try {
            mHdrListener.register(token);
        } catch (RuntimeException e) {
            unregisterHdr();
            Slog.w(TAG, "HDR registration failed; retry on display wake/change", e);
        }
    }

    private void unregisterHdr() {
        SurfaceControlHdrLayerInfoListener listener = mHdrListener;
        mHdrListener = null;
        if (listener != null) {
            try { listener.unregisterAll(); }
            catch (RuntimeException e) { Slog.w(TAG, "HDR listener cleanup failed", e); }
        }
    }

    private static float minimumHdrArea(DisplayDeviceConfig config) {
        HighBrightnessModeData data = config.getHighBrightnessModeData();
        return data == null ? Float.NaN : data.minimumHdrPercentOfScreen;
    }

    private static final class HdrSnapshot {
        final IBinder token;
        final long epoch;
        final int count, width, height, flags;
        HdrSnapshot(IBinder token, long epoch, int count, int width, int height, int flags) {
            this.token = token;
            this.epoch = epoch;
            this.count = count;
            this.width = width;
            this.height = height;
            this.flags = flags;
        }
    }

    private final Runnable mDeliverHdr = () -> {
        synchronized (mHdrLock) {
            HdrSnapshot next = mPendingHdr;
            mPendingHdr = null;
            mHdrQueued = false;
            if (mStopped || next == null || next.epoch != mTokenEpoch || next.token != mToken) return;
            mLastHdr = next;
        }
        updateHdrPolicy();
    };

    private void updateHdrPolicy() {
        boolean present = mInternal && mLastHdr != null && mLastHdr.count > 0
                && mLastHdr.width >= 0 && mLastHdr.height >= 0 && mWidth > 0 && mHeight > 0
                && Float.isFinite(mMinimumHdrArea) && mMinimumHdrArea >= 0f
                && mMinimumHdrArea <= 1f
                && (float) ((long) mLastHdr.width * mLastHdr.height)
                        >= (float) ((long) mWidth * mHeight) * mMinimumHdrArea;
        boolean camera = mInternal && mLastHdr != null && (mLastHdr.flags & 0x100000) != 0;
        if (mHdr != present || mCameraHdr != camera) {
            mHdr = present;
            mCameraHdr = camera;
            mPolicy.setHdr(present, camera);
            mChanged.run();
        }
    }

    void updateDisplayToken(IBinder token, boolean internal, int width, int height,
            DisplayDeviceConfig config) {
        if (mStopped) return;
        float threshold = minimumHdrArea(config);
        boolean rebound = mToken != token || mInternal != internal;
        if (!rebound && mConfig == config && mWidth == width && mHeight == height
                && sameFloat(mMinimumHdrArea, threshold)) return;
        if (rebound) {
            synchronized (mHdrLock) {
                mTokenEpoch++;
                mToken = token;
                mPendingHdr = null;
                mLastHdr = null;
            }
            unregisterHdr();
        }
        mInternal = internal;
        mWidth = width;
        mHeight = height;
        mMinimumHdrArea = threshold;
        mConfig = config;
        mBrightnessCap = Float.NaN;
        updateHdrPolicy();
        registerHdr();
        if (mDolbyListener != null) mDolbyListener.refresh();
        requestRead();
        mChanged.run();
    }

    private void requestRead() {
        synchronized (mReadLock) {
            if (mStopped || mReader == null) return;
            if (mReadQueued) { mReadAgain = true; return; }
            mReadQueued = true;
            if (!mReader.post(mRead)) mReadQueued = false;
        }
    }

    private static String readNode(String node) throws IOException {
        try (FileInputStream in = new FileInputStream(DIRECTORY + node)) {
            byte[] bytes = new byte[128];
            int used = 0;
            while (used < bytes.length) {
                int count = in.read(bytes, used, bytes.length - used);
                if (count < 0) break;
                if (count == 0) throw new IOException("empty sysfs read");
                used += count;
            }
            if (used == 0 || used == bytes.length) throw new IOException("invalid sysfs length");
            return new String(bytes, 0, used, StandardCharsets.US_ASCII).trim();
        }
    }

    private static final class Snapshot {
        Float temperature;
        Integer condition;
        Float safety;
        String error = "";
        long uptime;
    }

    private final Runnable mRead = this::readSnapshot;
    private final Runnable mDeliver = this::deliverSnapshot;

    private void readSnapshot() {
        Snapshot next = new Snapshot();
        for (int i = 0; i < NODES.length && !mStopped; i++) {
            try {
                String raw = readNode(NODES[i]);
                if (i == 1) next.condition = Integer.valueOf(raw);
                else {
                    float value = Float.parseFloat(raw);
                    if (!Float.isFinite(value) || (i == 2 && value < 0f && value != -1f)) {
                        throw new NumberFormatException("non-finite or invalid sentinel");
                    }
                    if (i == 0) next.temperature = value;
                    else next.safety = value;
                }
            } catch (IOException | NumberFormatException e) {
                next.error += NODES[i] + ":" + e.getClass().getSimpleName() + " ";
            }
        }
        next.uptime = SystemClock.uptimeMillis();
        synchronized (mReadLock) {
            if (mStopped) return;
            if (mPendingSnapshot != null) {
                if (next.temperature == null) next.temperature = mPendingSnapshot.temperature;
                if (next.condition == null) next.condition = mPendingSnapshot.condition;
                if (next.safety == null) next.safety = mPendingSnapshot.safety;
            }
            mPendingSnapshot = next;
            if (!mDeliveryQueued) {
                mDeliveryQueued = true;
                if (!mDisplayHandler.post(mDeliver)) {
                    mDeliveryQueued = false;
                    mPendingSnapshot = null;
                }
            }
            mReadQueued = false;
            if (mReadAgain) {
                mReadAgain = false;
                mReadQueued = true;
                if (!mReader.post(mRead)) mReadQueued = false;
            }
        }
    }

    private void deliverSnapshot() {
        Snapshot next;
        synchronized (mReadLock) {
            next = mPendingSnapshot;
            mPendingSnapshot = null;
            mDeliveryQueued = false;
        }
        if (mStopped || next == null) return;
        if (!next.error.equals(mReadError) && !next.error.isEmpty()) {
            Slog.w(TAG, "Retaining previous values for failed reads: " + next.error);
        }
        mReadError = next.error;
        mLastReadUptime = next.uptime;
        float previous = mPolicy.capNits();
        if (next.temperature != null) mPolicy.setTemperature(next.temperature);
        if (next.condition != null) mPolicy.setCondition(next.condition);
        if (next.safety != null) mPolicy.setSafety(next.safety);
        if (!sameFloat(previous, mPolicy.capNits())) mChanged.run();
    }

    DisplayBrightnessState clamp(DisplayBrightnessState state, DisplayDeviceConfig config,
            float ambientLux, int displayState) {
        if (mStopped) return state;
        start();
        if (displayState != mLastState && displayState != Display.STATE_OFF) {
            registerHdr();
            if (mDolbyListener != null && mLastState != Display.STATE_UNKNOWN) mDolbyListener.refresh();
            requestRead();
        }
        mLastState = displayState;
        boolean automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        mPolicy.setAmbient(ambientLux, automatic);
        mPolicy.setHdr(mHdr, mCameraHdr);
        float capNits = mPolicy.capNits();
        mClampedBrightness = false;
        mBrightnessCap = Float.NaN;
        if (!mInternal || !Float.isFinite(capNits) || capNits < 0f) return state;
        float cap = config.getBrightnessFromBacklight(config.getBacklightFromNits(capNits));
        if (!Float.isFinite(cap) || cap < 0f || cap > 1f) {
            Slog.wtf(TAG, "Panel nits conversion invalid for thermal cap " + capNits);
            return state;
        }
        mBrightnessCap = cap;
        DisplayBrightnessState.Builder out = DisplayBrightnessState.Builder.from(state);
        if (cap < state.getMaxBrightness()) {
            out.setMaxBrightness(cap);
            out.setMinBrightness(Math.min(state.getMinBrightness(), cap));
            out.setBrightnessMaxReason(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL);
        }
        float brightness = limitBrightness(state.getBrightness());
        float hdr = limitBrightness(state.getHdrBrightness());
        if (!sameFloat(brightness, state.getBrightness()) || !sameFloat(hdr, state.getHdrBrightness())) {
            mClampedBrightness = true;
            BrightnessReason reason = new BrightnessReason();
            reason.set(state.getBrightnessReason());
            reason.addModifier(BrightnessReason.MODIFIER_THROTTLED);
            out.setBrightnessReason(reason);
            out.setIsSlowChange(false);
        }
        return out.setBrightness(brightness).setHdrBrightness(hdr).build();
    }

    float limitBrightness(float requested) {
        if (!Float.isFinite(requested) || requested < 0f || !Float.isFinite(mBrightnessCap)) {
            return requested;
        }
        return Math.min(requested, mBrightnessCap);
    }

    static boolean sameFloat(float a, float b) {
        return a == b || (Float.isNaN(a) && Float.isNaN(b));
    }

    boolean isBrightnessLimited(float hdr, float sdr) {
        return mClampedBrightness || !sameFloat(hdr, limitBrightness(hdr))
                || !sameFloat(sdr, limitBrightness(sdr));
    }

    void stop() {
        mStopped = true;
        synchronized (mHdrLock) {
            mTokenEpoch++;
            mPendingHdr = null;
            mLastHdr = null;
            mHdrQueued = false;
        }
        unregisterHdr();
        if (mDolbyListener != null) { mDolbyListener.close(); mDolbyListener = null; }
        for (FileObserver observer : mObservers) if (observer != null) observer.stopWatching();
        synchronized (mReadLock) {
            mPendingSnapshot = null;
            mDeliveryQueued = false;
            mReadQueued = false;
            mReadAgain = false;
            if (mReader != null) mReader.removeCallbacksAndMessages(null);
        }
        mDisplayHandler.removeCallbacks(mDeliver);
        mDisplayHandler.removeCallbacks(mDeliverHdr);
        mBrightnessCap = Float.NaN;
        mClampedBrightness = false;
        if (mThread != null) mThread.quitSafely();
    }

    void dump(PrintWriter pw) {
        pw.println("Warsaw thermal limiter: " + mPolicy);
        pw.println("  readError=" + mReadError + " lastReadUptime=" + mLastReadUptime
                + " brightnessCap=" + mBrightnessCap + " internal=" + mInternal);
        pw.println("  hdr=" + mHdr + " geometry=" + mWidth + "x" + mHeight
                + " minimumHdrArea=" + mMinimumHdrArea
                + " sourceFlags=" + (mLastHdr == null ? "none" : mLastHdr.flags));
    }
}
