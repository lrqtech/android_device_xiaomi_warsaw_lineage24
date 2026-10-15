$(eval $(call warsaw_platform_require_source,frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/WarsawKeyguardSensors.java,$(DEVICE_PATH)/patches/keyguard-sensors/WarsawKeyguardSensors.java))
$(eval $(call warsaw_platform_require_source,frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/WarsawKeyguardSensorPolicy.java,$(DEVICE_PATH)/patches/keyguard-sensors/WarsawKeyguardSensorPolicy.java))

ifneq ($(shell grep -F -c 'new WarsawKeyguardSensors.StateSource()' frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java),1)
$(error warsaw keyguard sensors integration is missing or stale)
endif
ifneq ($(shell grep -F -c 'if (!warsawSensorState().sameAs(expected) || !valid.getAsBoolean()) return false;' frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java),1)
$(error warsaw keyguard power action gate is missing or repeated)
endif
ifneq ($(shell grep -F -c 'private synchronized WarsawKeyguardSensorPolicy.State warsawSensorState()' frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java),1)
$(error warsaw keyguard state source is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mWarsawKeyguardSensors.onInteractiveBoundary(true);' frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java),1)
$(error warsaw keyguard sleep boundary is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mWarsawKeyguardSensors.onInteractiveBoundary(false);' frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java),1)
$(error warsaw keyguard wake boundary is missing or repeated)
endif
ifneq ($(shell grep -F -c 'mWarsawKeyguardSensors.requestRefresh();' frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java),2)
$(error warsaw keyguard showing/occlusion updates are missing or repeated)
endif
