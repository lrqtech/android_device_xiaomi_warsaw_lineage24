# The user requests the stock IR client in the default product.
# The OTA APK is an input to normal JNI unpacking/alignment and ROM signing.
# It remains a non-privileged application; no MIUI account/security app is added.
WARSAW_INCLUDE_MI_REMOTE ?= true
ifeq ($(WARSAW_INCLUDE_MI_REMOTE),true)
PRODUCT_PACKAGES += XMRemoteController
# Optional TV integration is absent; local infrared control does not require it.
PRODUCT_MISSING_USES_LIBRARIES += com.xiaomi.mitv.api
endif
