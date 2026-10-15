define warsaw_camera_fps_require_source
ifneq ($$(shell cmp -s $(1) $(2) && echo matched),matched)
$$(error warsaw camera FPS original source is missing or stale: $(1))
endif
endef
$(eval $(call warsaw_camera_fps_require_source,frameworks/native/services/surfaceflinger/WarsawFpsStatistics.h,$(DEVICE_PATH)/patches/camera-fps/WarsawFpsStatistics.h))
$(eval $(call warsaw_camera_fps_require_source,frameworks/native/services/surfaceflinger/WarsawCameraFps.h,$(DEVICE_PATH)/patches/camera-fps/WarsawCameraFps.h))
$(eval $(call warsaw_camera_fps_require_source,frameworks/native/services/surfaceflinger/WarsawCameraFps.inc,$(DEVICE_PATH)/patches/camera-fps/SurfaceFlinger.inc))
$(eval $(call warsaw_camera_fps_require_source,frameworks/native/libs/gui/WarsawCameraFpsClient.inc,$(DEVICE_PATH)/patches/camera-fps/SurfaceComposerClient.inc))
$(eval $(call warsaw_camera_fps_require_source,frameworks/native/libs/gui/include/gui/FpsMonitorCmd.h,$(DEVICE_PATH)/patches/camera-fps/FpsMonitorCmd.h))
$(eval $(call warsaw_camera_fps_require_source,frameworks/native/libs/gui/aidl/android/gui/FpsMonitorCmd.aidl,$(DEVICE_PATH)/patches/camera-fps/FpsMonitorCmd.aidl))
ifneq ($(shell grep -F -c '#include "WarsawCameraFps.inc"' frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp),1)
$(error warsaw camera fps integration is missing or stale)
endif
ifneq ($(shell grep -F -c '#include "WarsawCameraFpsClient.inc"' frameworks/native/libs/gui/SurfaceComposerClient.cpp),1)
$(error warsaw FPS libgui client integration missing or repeated)
endif
ifneq ($(shell grep -F -c 'mFlinger->recordWarsawCameraFrame(getName());' frameworks/native/services/surfaceflinger/Layer.cpp),1)
$(error warsaw camera arrival statistics hook missing or repeated)
endif
ifneq ($(shell grep -F -c 'FpsMonitorCmd sentWarsawFpsMonitorCmd(int command, String layerName, IBinder owner);' frameworks/native/libs/gui/aidl/android/gui/ISurfaceComposer.aidl),1)
$(error warsaw FPS typed AIDL method missing or repeated)
endif
ifneq ($(shell grep -F -c 'static status_t sentFpsMonitorCmd(int command, const String16& layerName, FpsMonitorCmd* output);' frameworks/native/libs/gui/include/gui/SurfaceComposerClient.h),1)
$(error warsaw FPS legacy ABI declaration missing or repeated)
endif
ifneq ($(shell grep -F -c 'sp<WarsawCameraFps> mWarsawCameraFps = sp<WarsawCameraFps>::make();' frameworks/native/services/surfaceflinger/SurfaceFlinger.h),1)
$(error warsaw FPS statistics owner missing or repeated)
endif
