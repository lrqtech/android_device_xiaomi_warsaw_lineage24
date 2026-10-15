PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/access/ueventd.vendor.rc:$(TARGET_COPY_OUT_VENDOR)/etc/ueventd.rc \
    $(DEVICE_PATH)/configs/access/ueventd.odm.rc:$(TARGET_COPY_OUT_ODM)/etc/ueventd.rc \
    $(DEVICE_PATH)/configs/access/fw_dir.ueventd.qcom.rc:$(TARGET_COPY_OUT_VENDOR)/etc/fw_dir.ueventd.qcom.rc
