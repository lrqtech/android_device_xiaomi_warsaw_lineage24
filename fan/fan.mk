# Fan control is compiled into Settings. Only the official camera protocol
# receiver needs its original PowerKeeper package identity.
PRODUCT_PACKAGES += WarsawPowerKeeperBridge
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/fan/permissions/privapp-permissions-com.android.settings-warsaw.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-com.android.settings-warsaw.xml
