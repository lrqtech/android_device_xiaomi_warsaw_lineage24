PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/display/display_id_4630946230211585427.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/displayconfig/display_id_4630946230211585427.xml \
    $(DEVICE_PATH)/configs/input/gpio-keys.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/gpio-keys.kl \
    $(DEVICE_PATH)/configs/input/uinput-xiaomi.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/uinput-xiaomi.kl \
    $(DEVICE_PATH)/configs/input/uinput-xiaomi.idc:$(TARGET_COPY_OUT_VENDOR)/usr/idc/uinput-xiaomi.idc

# OS4 declares the Qualcomm framework and ODM integration explicitly.
# The camera HAL uses this to select warsaw.xml instead of netrani_gsi.xml.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.vendor.qti.va_aosp.support=1

PRODUCT_ODM_PROPERTIES += \
    ro.vendor.qti.va_odm.support=1

# Original OS4 carrier display names and matching rules.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/telephony/miui-spn-conf.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/miui-spn-conf.xml
