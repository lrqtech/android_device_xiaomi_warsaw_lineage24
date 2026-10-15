ifneq ($(shell grep -F -c '&& (mHbmData.timeMaxMillis > 0)' frameworks/base/services/core/java/com/android/server/display/HighBrightnessModeController.java),1)
$(error warsaw display thermal integration is missing or stale)
endif

define warsaw_platform_require_source
ifneq ($$(shell cmp -s $(1) $(2) && echo matched),matched)
$$(error warsaw original platform source missing or stale: $(1))
endif
endef
$(eval $(call warsaw_platform_require_source,frameworks/native/services/surfaceflinger/WarsawGameFrameRateLease.h,$(DEVICE_PATH)/patches/display-lease/WarsawGameFrameRateLease.h))
$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/warsaw/WarsawCameraService.java,$(DEVICE_PATH)/patches/camera-framework/WarsawCameraService.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/vibrator/WarsawHapticHooks.java,$(DEVICE_PATH)/patches/camera-framework/WarsawHapticHooks.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/core/java/android/os/vibrator/WarsawHapticPatterns.java,$(DEVICE_PATH)/patches/camera-framework/WarsawHapticPatterns.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/display/WarsawThermalBrightness.java,$(DEVICE_PATH)/patches/display-thermal/WarsawThermalBrightness.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/display/WarsawThermalDisplayListener.java,$(DEVICE_PATH)/patches/display-thermal/WarsawThermalDisplayListener.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/display/WarsawThermalPolicy.java,$(DEVICE_PATH)/patches/display-thermal/WarsawThermalPolicy.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/display/WarsawThermalTables.java,$(DEVICE_PATH)/patches/display-thermal/WarsawThermalTables.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/wm/WarsawFanSceneHooks.java,$(DEVICE_PATH)/patches/fan-scene/WarsawFanSceneHooks.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/audio/WarsawFanAudioHooks.java,$(DEVICE_PATH)/patches/fan-audio/WarsawFanAudioHooks.java))

ifneq ($(shell grep -F -c 'boolean isWarsawFanKeyguardOccluded(int userId);' frameworks/base/core/java/android/view/IWindowManager.aidl),1)
$(error warsaw fan scene integration is missing or stale)
endif
ifneq ($(shell grep -F -c 'return WarsawFanSceneHooks.isKeyguardOccluded(mContext, mPolicy, userId);' frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java),1)
$(error warsaw fan scene WMS getter integration is missing or repeated)
endif
ifneq ($(shell grep -F -c 'isWarsawFanKeyguardOccluded_enforcePermission();' frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java),1)
$(error warsaw fan scene WMS permission check is missing or repeated)
endif

ifneq ($(shell grep -F -c 'boolean hasWarsawFanTrackedRecorder(int uid);' frameworks/base/media/java/android/media/IAudioService.aidl),1)
$(error warsaw fan audio integration is missing or stale)
endif
ifneq ($(shell grep -F -c 'hasWarsawFanTrackedRecorder_enforcePermission();' frameworks/base/services/core/java/com/android/server/audio/AudioService.java),1)
$(error warsaw fan audio generated permission check is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawFanAudioHooks.enforceCallerAndTarget(mContext, uid);' frameworks/base/services/core/java/com/android/server/audio/AudioService.java),1)
$(error warsaw fan audio caller and target guard is missing or repeated)
endif
ifneq ($(shell grep -F -c 'return mRecordMonitor.hasWarsawFanTrackedRecorder(uid);' frameworks/base/services/core/java/com/android/server/audio/AudioService.java),1)
$(error warsaw fan audio service delegation is missing or repeated)
endif
ifneq ($(shell grep -F -c 'boolean hasWarsawFanTrackedRecorder(int uid) {' frameworks/base/services/core/java/com/android/server/audio/RecordingActivityMonitor.java),1)
$(error warsaw fan recorder lifecycle getter is missing or repeated)
endif

ifneq ($(shell grep -F -c 'clampedState = mWarsawThermalBrightness.clamp' frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java),1)
$(error warsaw display thermal integration is missing or stale)
endif
ifneq ($(shell grep -F -c 'animateValue = mWarsawThermalBrightness.limitBrightness(animateValue);' frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java),1)
$(error warsaw thermal limit is missing from the final HDR path)
endif
ifneq ($(shell grep -F -c 'sdrAnimateValue = mWarsawThermalBrightness.limitBrightness(sdrAnimateValue);' frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java),1)
$(error warsaw thermal limit is missing from the final SDR path)
endif
ifneq ($(shell grep -F -c 'mWarsawThermalBrightness.isBrightnessLimited(animateValue, sdrAnimateValue)' frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java),1)
$(error warsaw final HDR thermal reason and animation-rate hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mWarsawThermalBrightness = WarsawThermalBrightness.create' frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java),1)
$(error warsaw thermal limiter construction is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mWarsawThermalBrightness.updateDisplayToken' frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java),1)
$(error warsaw thermal limiter display-replacement hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'if (mWarsawThermalBrightness != null) mWarsawThermalBrightness.stop();' frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java),1)
$(error warsaw thermal limiter cleanup hook is missing or repeated)
endif

ifneq ($(shell grep -F -c 'mWarsawGameFrameRates->setBase(uid, frameRate);' frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp),1)
$(error warsaw D2 frame-rate base arbitration hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mSystemServiceManager.startService(com.android.server.warsaw.WarsawCameraService.class);' frameworks/base/services/java/com/android/server/SystemServer.java),1)
$(error warsaw camera display service registration is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawHapticHooks.perform(prebaked, getVibration(), () -> vibrator.on(getVibration().id, stepId, prebaked))' frameworks/base/services/core/java/com/android/server/vibrator/PerformPrebakedVibratorStep.java),1)
$(error warsaw admitted camera vibration hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawHapticPatterns.isSupported(mEffectId)' frameworks/base/core/java/android/os/vibrator/PrebakedSegment.java),1)
$(error warsaw supported vibrator effect validation hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'android.os.vibrator.WarsawHapticPatterns.frameworkUi(effectId)' frameworks/base/services/core/java/com/android/server/vibrator/HapticFeedbackVibrationProvider.java),1)
$(error warsaw framework haptic effect mapping is missing or repeated)
endif
include $(DEVICE_PATH)/patches/camera-fps/camera-fps.mk
include $(DEVICE_PATH)/patches/keyguard-sensors/keyguard-sensors.mk
include $(DEVICE_PATH)/patches/camera-exposure/camera-exposure.mk

$(eval $(call warsaw_platform_require_source,frameworks/base/services/core/java/com/android/server/warsaw/WarsawCameraMindLifecycle.java,$(DEVICE_PATH)/patches/camera-framework/WarsawCameraMindLifecycle.java))

$(eval $(call warsaw_platform_require_source,frameworks/base/telephony/java/android/telephony/TelephonyBaseUtilsStub.java,$(DEVICE_PATH)/patches/ims-rom-boundary/TelephonyBaseUtilsStub.java))
