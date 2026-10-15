package org.lineageos.warsaw.fan;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.IAudioService;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telecom.TelecomManager;
import android.view.WindowManagerGlobal;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

final class FanEnvironment implements AutoCloseable {
    static final String THERMAL = "/sys/devices/virtual/thermal/thermal_message/";
    private static final String CAMERA_RECORD_ACTION =
            "org.lineageos.warsaw.fan.action.CAMERA_RECORD_STATE";
    private static final String CAMERA_RECORD_PERMISSION =
            "org.lineageos.warsaw.fan.permission.CAMERA_RECORD_STATE";
    private static final String CAMERA_RECORD_BRIDGE = "com.miui.powerkeeper";
    private static final Set<String> NAVIGATION = new HashSet<>(Arrays.asList(
            "com.google.android.apps.maps", "com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map"));
    private final Context context;
    private final Handler handler;
    private final Runnable changed;
    private final AudioManager audio;
    private final IAudioService audioService;
    private final CameraManager camera;
    private final KeyguardManager keyguard;
    private final SubscriptionManager subscriptions;
    private final TelephonyManager phone;
    private final FanSceneEngine scenes;
    private final List<PhoneWatch> phones = new ArrayList<>();
    private final Set<String> cameraIds = new HashSet<>();
    private final Set<String> cameraStatusSeen = new HashSet<>();
    private final Set<String> openCameras = new HashSet<>();
    private int cameraRecordState = 99;
    private boolean charging;
    private Exception callbackFailure;
    private boolean closed;
    private final BroadcastReceiver receiver;
    private final BroadcastReceiver cameraRecordReceiver;
    private final CameraManager.AvailabilityCallback cameraAvailability;
    private final TaskStackListener tasks;
    private final AudioManager.AudioPlaybackCallback playbacks;
    private final AudioManager.AudioRecordingCallback recordings;
    private final AudioManager.OnCommunicationDeviceChangedListener communication;
    private final KeyguardManager.KeyguardLockedStateListener lock;
    private final SubscriptionManager.OnSubscriptionsChangedListener subscriptionListener;

    static String asset(Context context, String name) throws Exception {
        try (java.io.InputStream input = context.getAssets().open(name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    FanEnvironment(Context context, Handler handler, Runnable notifyChange) throws Exception {
        this.context = context; this.handler = handler;
        this.changed = () -> { if (!closed) notifyChange.run(); };
        audio = context.getSystemService(AudioManager.class);
        audioService = IAudioService.Stub.asInterface(
                ServiceManager.checkService(Context.AUDIO_SERVICE));
        if (audioService == null) throw new IllegalStateException("AudioService unavailable");
        camera = context.getSystemService(CameraManager.class);
        if (camera == null) throw new IllegalStateException("CameraManager unavailable");
        cameraIds.addAll(Arrays.asList(camera.getCameraIdList()));
        if (cameraIds.isEmpty()) throw new IllegalStateException("Camera inventory unavailable");
        keyguard = context.getSystemService(KeyguardManager.class);
        subscriptions = context.getSystemService(SubscriptionManager.class);
        phone = context.getSystemService(TelephonyManager.class);
        scenes = new FanSceneEngine(new JSONObject(asset(context, "fan-scenarios.json")));
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                }
                changed.run();
            }
        };
        IntentFilter filter = new IntentFilter();
        for (String action : new String[]{Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, Intent.ACTION_USER_SWITCHED,
                Intent.ACTION_TIME_CHANGED}) filter.addAction(action);
        Intent battery = context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
        if (battery != null) charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        cameraRecordReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!CAMERA_RECORD_ACTION.equals(intent.getAction())
                        || !isExactCameraBridgeSender(getSentFromUid(), getSentFromPackage())) {
                    return;
                }
                int state = intent.getIntExtra("state", Integer.MIN_VALUE);
                if (state != 1 && state != 2 && state != 99) return;
                cameraRecordState = state;
                changed.run();
            }
        };
        cameraAvailability = new CameraManager.AvailabilityCallback() {
            @Override public void onCameraAvailable(String cameraId) {
                cameraStatusSeen.add(cameraId);
                changed.run();
            }
            @Override public void onCameraUnavailable(String cameraId) {
                cameraStatusSeen.add(cameraId);
                changed.run();
            }
            @Override public void onCameraOpened(String cameraId, String packageId) {
                cameraStatusSeen.add(cameraId);
                openCameras.add(cameraId);
                changed.run();
            }
            @Override public void onCameraClosed(String cameraId) {
                cameraStatusSeen.add(cameraId);
                openCameras.remove(cameraId);
                changed.run();
            }
        };
        recordings = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> ignored) {
                changed.run();
            }
        };
        playbacks = new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> ignored) {
                changed.run();
            }
        };
        communication = device -> changed.run();
        lock = locked -> changed.run();
        tasks = new TaskStackListener() {
            @Override public void onTaskStackChanged() { handler.post(changed); }
        };
        subscriptionListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
            @Override public void onSubscriptionsChanged() { refreshPhones(); changed.run(); }
        };
        try {
            audio.registerAudioPlaybackCallback(playbacks, handler);
            audio.registerAudioRecordingCallback(recordings, handler);
            audio.addOnCommunicationDeviceChangedListener(handler::post, communication);
            keyguard.addKeyguardLockedStateListener(handler::post, lock);
            ActivityTaskManager.getService().registerTaskStackListener(tasks);
            subscriptions.addOnSubscriptionsChangedListener(handler::post, subscriptionListener);
            context.registerReceiver(cameraRecordReceiver, new IntentFilter(CAMERA_RECORD_ACTION),
                    CAMERA_RECORD_PERMISSION, handler, Context.RECEIVER_EXPORTED);
            camera.registerAvailabilityCallback(handler::post, cameraAvailability);
            refreshPhones();
        } catch (Exception error) { callbackFailure = error; }
    }
    private final class PhoneWatch extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        final TelephonyManager manager;
        PhoneWatch(int subId) { manager = phone.createForSubscriptionId(subId); }
        @Override public void onCallStateChanged(int state) { changed.run(); }
    }
    private void refreshPhones() {
        try {
            for (PhoneWatch watch : phones) watch.manager.unregisterTelephonyCallback(watch);
            phones.clear();
            for (int subId : subscriptions.getActiveSubscriptionIdList()) {
                PhoneWatch watch = new PhoneWatch(subId);
                phones.add(watch);
                watch.manager.registerTelephonyCallback(handler::post, watch);
            }
        } catch (Exception error) { callbackFailure = error; }
    }
    static int readInteger(String name) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(THERMAL + name))) {
            String value = reader.readLine();
            if (value == null || value.length() > 32) throw new IllegalStateException("Thermal input unavailable");
            return Integer.parseInt(value.trim());
        }
    }
    FanPolicy.Inputs readSafety(int quickChargeType) throws Exception {
        if (callbackFailure != null) throw new IllegalStateException("Environment callback unavailable", callbackFailure);
        FanPolicy.Inputs result = new FanPolicy.Inputs();
        result.screenOff = !context.getSystemService(PowerManager.class).isInteractive();
        result.locked = result.screenOff || keyguard.isKeyguardLocked();
        result.charging = charging;
        result.quickCharge = quickChargeType >= 3;
        for (PhoneWatch watch : phones) {
            result.call |= watch.manager.getCallStateForSubscription() != TelephonyManager.CALL_STATE_IDLE;
        }
        result.call |= context.getSystemService(TelecomManager.class).isInCall();
        AudioDeviceInfo device = audio.getCommunicationDevice();
        result.earpiece = device != null && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
        return result;
    }
    FanPolicy.Inputs read(int user, MiChargeClient.NormalInputs charge) throws Exception {
        FanPolicy.Inputs result = readSafety(charge.quickChargeType);
        String voice = Settings.Secure.getStringForUser(context.getContentResolver(), "voice_interaction_service", user);
        ComponentName assistant = voice == null ? null : ComponentName.unflattenFromString(voice);
        Set<Integer> activeRecordingUids = new HashSet<>();
        for (AudioRecordingConfiguration recording : audio.getActiveRecordingConfigurations()) {
            result.recording = true;
            int uid = recording.getClientUid();
            activeRecordingUids.add(uid);
            for (String name : packagesForUid(uid)) {
                if ("com.miui.voiceassist".equals(name)
                        || (assistant != null && assistant.getPackageName().equals(name))) {
                    result.assistantRecording = true;
                }
            }
        }
        Map<Integer, Set<String>> playbackPackages = new HashMap<>();
        Set<Integer> activePlaybackUids = new HashSet<>();
        for (AudioPlaybackConfiguration playback : audio.getActivePlaybackConfigurations()) {
            int uid = playback.getClientUid();
            if (!playbackPackages.containsKey(uid)) {
                playbackPackages.put(uid, packagesForUid(uid));
            }
            if (playback.isActive()) activePlaybackUids.add(uid);
        }
        ActivityManager am = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(1024);
        if (services.size() >= 1024) throw new IllegalStateException("Foreground service inventory truncated");
        for (ActivityManager.RunningServiceInfo service : services) {
            if (service.foreground && UserHandle.getUserId(service.uid) == user && service.service != null) {
                String name = service.service.getPackageName();
                result.navigation |= NAVIGATION.contains(name);
                result.migration |= "com.miui.huanji".equals(name);
            }
        }
        List<ActivityManager.RunningTaskInfo> running = am.getRunningTasks(1);
        ComponentName foreground = null;
        if (!running.isEmpty()) {
            ActivityManager.RunningTaskInfo task = running.get(0);
            if (task.userId == user) foreground = task.topActivity;
            result.navigationForeground = task.userId == user && task.topActivity != null
                    && NAVIGATION.contains(task.topActivity.getPackageName());
        }
        result.temperatureMilliC = readInteger("board_sensor_temp");
        boolean offHook = false;
        for (PhoneWatch watch : phones) {
            offHook |= watch.manager.getCallStateForSubscription() == TelephonyManager.CALL_STATE_OFFHOOK;
        }
        int[] screen = screenStates(user, result.screenOff);
        int[] cameraState = cameraStates();
        int[] specialC = specialCStates(foreground == null ? null : foreground.getPackageName(),
                playbackPackages, activePlaybackUids, activeRecordingUids);
        String category = foreground == null ? "UNKNOWN" : scenes.category(
                foreground.getPackageName(), foreground.getClassName(), screen, offHook,
                cameraState, specialC, new int[]{charge.reverseQuickCharge == 3 ? 98 : 99});
        if (!"NORMAL".equals(category) && !"GAME".equals(category) && !"NO_LIMITS".equals(category)) {
            throw new SceneUnavailableException();
        }
        result.game = "GAME".equals(category);
        result.noLimits = "NO_LIMITS".equals(category);
        return result;
    }
    private Set<String> packagesForUid(int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            throw new IllegalStateException("Audio client package unavailable for uid " + uid);
        }
        return new HashSet<>(Arrays.asList(packages));
    }
    private boolean isExactCameraBridgeSender(int uid, String senderPackage) {
        if (uid < android.os.Process.FIRST_APPLICATION_UID
                || !CAMERA_RECORD_BRIDGE.equals(senderPackage)) return false;
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        return packages != null && packages.length == 1
                && CAMERA_RECORD_BRIDGE.equals(packages[0])
                && pm.checkSignatures(context.getPackageName(), CAMERA_RECORD_BRIDGE)
                        == PackageManager.SIGNATURE_MATCH;
    }
    private int[] cameraStates() {
        if (!cameraStatusSeen.containsAll(cameraIds)) {
            return cameraRecordState == 99
                    ? new int[]{99, 98} : new int[]{cameraRecordState, 98};
        }
        return openCameras.isEmpty() ? new int[]{cameraRecordState} : new int[]{98};
    }
    private int[] specialCStates(String foreground, Map<Integer, Set<String>> playbackPackages,
            Set<Integer> activePlaybackUids, Set<Integer> activeRecordingUids) {
        if (foreground == null || (audio.getMode() != AudioManager.MODE_IN_CALL
                && audio.getMode() != AudioManager.MODE_IN_COMMUNICATION)) {
            return new int[]{99};
        }
        boolean exactCommunication = false;
        boolean recorderLifecycleUnavailable = false;
        for (Map.Entry<Integer, Set<String>> entry : playbackPackages.entrySet()) {
            int uid = entry.getKey();
            boolean reviewedCommunicationPackage = false;
            for (String name : entry.getValue()) {
                reviewedCommunicationPackage |=
                        FanSceneEngine.SPECIAL_C_COMMUNICATION_PACKAGES.contains(name);
            }
            if (!reviewedCommunicationPackage
                    || (!activePlaybackUids.contains(uid) && !activeRecordingUids.contains(uid))) {
                continue;
            }
            if (activeRecordingUids.contains(uid)) {
                exactCommunication = true;
                continue;
            }
            try {
                exactCommunication |= audioService.hasWarsawFanTrackedRecorder(uid);
            } catch (Exception unavailable) {
                recorderLifecycleUnavailable = true;
            }
        }
        if (exactCommunication) return scenes.specialCStates(foreground, true, false);
        return scenes.specialCStates(foreground, false, recorderLifecycleUnavailable);
    }
    private int[] screenStates(int user, boolean screenOff) {
        if (screenOff) return new int[]{99};
        if (!keyguard.isKeyguardLocked()) return new int[]{98};
        try {
            boolean occluded = WindowManagerGlobal.getWindowManagerService()
                    .isWarsawFanKeyguardOccluded(user);
            return new int[]{occluded ? 98 : 99};
        } catch (Exception unavailable) {
            return new int[]{98, 99};
        }
    }
    static final class SceneUnavailableException extends Exception { }
    boolean needsRecreate() { return callbackFailure != null; }
    @Override public void close() {
        closed = true;
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
        try { context.unregisterReceiver(cameraRecordReceiver); } catch (Exception ignored) { }
        try { camera.unregisterAvailabilityCallback(cameraAvailability); } catch (Exception ignored) { }
        try { audio.unregisterAudioPlaybackCallback(playbacks); } catch (Exception ignored) { }
        try { audio.unregisterAudioRecordingCallback(recordings); } catch (Exception ignored) { }
        try { audio.removeOnCommunicationDeviceChangedListener(communication); } catch (Exception ignored) { }
        try { keyguard.removeKeyguardLockedStateListener(lock); } catch (Exception ignored) { }
        try { ActivityTaskManager.getService().unregisterTaskStackListener(tasks); } catch (Exception ignored) { }
        try { subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener); } catch (Exception ignored) { }
        for (PhoneWatch watch : phones) {
            try { watch.manager.unregisterTelephonyCallback(watch); } catch (Exception ignored) { }
        }
        phones.clear();
    }
}
