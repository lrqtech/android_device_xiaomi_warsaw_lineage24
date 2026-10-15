ifeq ($(wildcard frameworks/base/services/core/java/com/android/server/biometrics/sensors/WarsawFingerprintHooks.java),)
$(error warsaw udfps framework integration is missing or stale)
endif
ifneq ($(shell grep -F -c 'WarsawFingerprintHooks.onClientStarted(client);' frameworks/base/services/core/java/com/android/server/biometrics/sensors/BiometricStateCallback.java),1)
$(error warsaw requires exactly one FOD client-start integration)
endif
ifneq ($(shell grep -F -c 'WarsawFingerprintHooks.onClientFinished(client);' frameworks/base/services/core/java/com/android/server/biometrics/sensors/BiometricStateCallback.java),1)
$(error warsaw requires exactly one FOD client-finish integration)
endif

define warsaw_udfps_require_source
ifneq ($$(shell cmp -s $(1) $(2) && echo matched),matched)
$$(error warsaw UDFPS platform source missing or stale: $(1))
endif
endef
$(eval $(call warsaw_udfps_require_source,frameworks/base/services/core/java/com/android/server/biometrics/sensors/WarsawFingerprintHooks.java,$(DEVICE_PATH)/patches/udfps-framework/WarsawFingerprintHooks.java))
$(eval $(call warsaw_udfps_require_source,frameworks/base/services/core/java/com/android/server/biometrics/sensors/WarsawUdfpsService.java,$(DEVICE_PATH)/patches/udfps-framework/WarsawUdfpsService.java))
$(eval $(call warsaw_udfps_require_source,frameworks/base/packages/SystemUI/src/com/android/systemui/biometrics/WarsawUdfpsTouchLifecycle.java,$(DEVICE_PATH)/patches/udfps-ui/WarsawUdfpsTouchLifecycle.java))
$(eval $(call warsaw_udfps_require_source,frameworks/base/packages/SystemUI/src/com/android/systemui/biometrics/WarsawUdfpsAodVisibility.kt,$(DEVICE_PATH)/patches/udfps-ui/WarsawUdfpsAodVisibility.kt))
$(eval $(call warsaw_udfps_require_source,frameworks/base/packages/SystemUI/src/com/android/systemui/doze/WarsawUdfpsDoze.java,$(DEVICE_PATH)/patches/udfps-ui/WarsawUdfpsDoze.java))

ifneq ($(shell grep -F -c 'parts.add(warsawUdfpsDoze);' frameworks/base/packages/SystemUI/src/com/android/systemui/doze/dagger/DozeModule.java),1)
$(error warsaw UDFPS Doze state-machine integration is missing or repeated)
endif
$(eval $(call warsaw_udfps_require_source,frameworks/native/services/surfaceflinger/WarsawFodDisplayState.h,$(DEVICE_PATH)/patches/udfps-ui/WarsawFodDisplayState.h))

ifneq ($(shell grep -F -c 'WarsawFingerprintHooks.onSchedulerReset(mCurrentOperation);' frameworks/base/services/core/java/com/android/server/biometrics/sensors/BiometricScheduler.java),1)
$(error warsaw UDFPS scheduler-reset integration is missing or repeated)
endif
ifneq ($(shell grep -F -c 'com.android.server.biometrics.sensors.WarsawFingerprintHooks.beforeHalStart(this);' frameworks/base/services/core/java/com/android/server/biometrics/sensors/fingerprint/aidl/FingerprintAuthenticationClient.java),1)
$(error warsaw UDFPS pre-authenticate hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'com.android.server.biometrics.sensors.WarsawFingerprintHooks.beforeHalStop(this);' frameworks/base/services/core/java/com/android/server/biometrics/sensors/fingerprint/aidl/FingerprintAuthenticationClient.java),1)
$(error warsaw UDFPS pre-authenticate-cancel hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'com.android.server.biometrics.sensors.WarsawFingerprintHooks.beforeHalStart(this);' frameworks/base/services/core/java/com/android/server/biometrics/sensors/fingerprint/aidl/FingerprintEnrollClient.java),1)
$(error warsaw UDFPS pre-enroll hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'com.android.server.biometrics.sensors.WarsawFingerprintHooks.beforeHalStop(this);' frameworks/base/services/core/java/com/android/server/biometrics/sensors/fingerprint/aidl/FingerprintEnrollClient.java),1)
$(error warsaw UDFPS pre-enroll-cancel hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'com.android.server.biometrics.sensors.WarsawUdfpsService.publish(getContext());' frameworks/base/services/core/java/com/android/server/biometrics/sensors/fingerprint/FingerprintService.java),1)
$(error warsaw UDFPS authenticated UI bridge registration is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawUdfpsTouchLifecycle.configure(mContext, mStatusBarStateController, sensorProps);' frameworks/base/packages/SystemUI/src/com/android/systemui/biometrics/UdfpsController.java),1)
$(error warsaw UDFPS UI configuration hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawUdfpsTouchLifecycle.onAcquired' frameworks/base/packages/SystemUI/src/com/android/systemui/biometrics/UdfpsController.java),1)
$(error warsaw UDFPS real acquisition observation is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawUdfpsTouchLifecycle.observeUsers(warsawUserTracker);' frameworks/base/packages/SystemUI/src/com/android/systemui/biometrics/UdfpsController.java),1)
$(error warsaw UDFPS user-switch observation is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawUdfpsAodVisibility.applyTo' frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/ui/viewmodel/DeviceEntryIconViewModel.kt),1)
$(error warsaw UDFPS visual alpha integration is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawUdfpsTouchLifecycle.onOverlayAdded(view)' frameworks/base/packages/SystemUI/src/com/android/systemui/biometrics/UdfpsControllerOverlay.kt),1)
$(error warsaw UDFPS overlay attachment hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'WarsawUdfpsTouchLifecycle.onOverlayRemoved(this)' frameworks/base/packages/SystemUI/src/com/android/systemui/biometrics/UdfpsControllerOverlay.kt),1)
$(error warsaw UDFPS overlay removal hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mWarsawFodDisplay.prePowerMode' frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp),1)
$(error warsaw UDFPS pre-power display hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mWarsawFodDisplay.postPowerMode' frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp),1)
$(error warsaw UDFPS post-power display hook is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mWarsawFodDisplay.updateLayers' frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp),1)
$(error warsaw UDFPS input-layer display hook is missing or repeated)
endif

PRODUCT_ODM_PROPERTIES += \
    ro.hardware.fp.fod=true \
    ro.hardware.fp.fod.c=true \
    ro.hardware.fp.fod.touch.ctl.version=2.0 \
    persist.vendor.sys.fp.fod.location.X_Y=561,2003 \
    persist.vendor.sys.fp.fod.size.width_height=158,158 \
    ro.vendor.touchfeature.type=255
