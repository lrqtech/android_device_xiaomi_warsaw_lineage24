DEVICE_PATH := device/xiaomi/warsaw

$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_ramdisk.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/vabc_features.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/emulated_storage.mk)
$(call inherit-product, frameworks/native/build/phone-xhdpi-6144-dalvik-heap.mk)
PRODUCT_USE_DYNAMIC_PARTITIONS := true
PRODUCT_ENFORCE_SELINUX_TREBLE_LABELING := true

# No system_other odex is built; match OTA and skip its first-boot copy request.
PRODUCT_SYSTEM_EXT_PROPERTIES += ro.cp_system_other_odex=0

PRODUCT_EXTRA_VNDK_VERSIONS += 34

PRODUCT_VIRTUAL_AB_COMPRESSION_METHOD := lz4
PRODUCT_VIRTUAL_AB_COMPRESSION_FACTOR := 65536
PRODUCT_VENDOR_PROPERTIES += ro.virtual_ab.compression.threads=true

# Match the official OTA dm-user snapshot backend.
PRODUCT_VENDOR_PROPERTIES += ro.virtual_ab.ublk.enabled=false

# The Gunyah pVM firmware is an OEM firmware input from this OTA.
PRODUCT_BUILD_PVMFW_IMAGE := false

PRODUCT_SOONG_NAMESPACES += $(DEVICE_PATH) vendor/xiaomi/warsaw
PRODUCT_CHARACTERISTICS := nosdcard
PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := xxhdpi
DEVICE_PACKAGE_OVERLAYS += $(DEVICE_PATH)/overlay

# Use the official China SKU network-validation endpoints through the standard RRO.
PRODUCT_PACKAGES += NetworkStackOverlayWarsaw

# Preserve the OTA service-manager component and its native VINTF level rules.
PRODUCT_PACKAGES += \
    hwservicemanager \
    android.hardware.fastboot-service.example_recovery \
    android.hardware.boot-service.qti.recovery \
    android.hardware.health-service.qti_recovery \
    fastbootd \
    update_engine \
    update_engine_sideload \
    update_verifier

PRODUCT_PACKAGES_DEBUG += \
    update_engine_client

# Retained OTA camera and MIMD memory controls require the v1 hierarchy.
# Override only memory; retain the platform CPU, cpuset, I/O and freezer setup.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/boot/cgroups.json:$(TARGET_COPY_OUT_VENDOR)/etc/cgroups.json

PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.qcom \
    $(DEVICE_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/fstab.qcom \
    $(DEVICE_PATH)/rootdir/etc/charger/charger_fstab.xiaomi:$(TARGET_COPY_OUT_VENDOR)/etc/charger/charger_fstab.xiaomi \
    $(DEVICE_PATH)/rootdir/etc/charger_fstab.qti:recovery/root/vendor/etc/charger_fstab.qti \
    $(DEVICE_PATH)/rootdir/etc/init.recovery.qcom.rc:recovery/root/init.recovery.qcom.rc

# Preserve the OTA recovery kernel objects at their original paths.
PRODUCT_PACKAGES += warsaw_recovery_adsp_modules

# The touch probe requests these files before second-stage ueventd exists.
# Keep the original OTA bytes at the kernel direct firmware search path.
PRODUCT_COPY_FILES += \
    vendor/xiaomi/warsaw/proprietary/odm/firmware/goodix_firmware_warsaw.bin:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/lib/firmware/goodix_firmware_warsaw.bin \
    vendor/xiaomi/warsaw/proprietary/odm/firmware/goodix_cfg_group_warsaw.bin:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/lib/firmware/goodix_cfg_group_warsaw.bin

# Match the USB IDs used by the OTA recovery init.rc.
PRODUCT_VENDOR_PROPERTIES += \
    ro.recovery.usb.vid=18D1 \
    ro.recovery.usb.adb.pid=4E11 \
    ro.recovery.usb.fastboot.pid=D00D

include $(DEVICE_PATH)/configs/build/source-replacements.mk
include $(DEVICE_PATH)/configs/build/stock-symlinks/stock-symlinks.mk

include $(DEVICE_PATH)/configs/hardware/hardware.mk
include $(DEVICE_PATH)/configs/framework.mk
include $(DEVICE_PATH)/configs/oem/oem-libraries.mk
include $(DEVICE_PATH)/configs/ims/ims.mk
include $(DEVICE_PATH)/configs/security/security.mk
include $(DEVICE_PATH)/audio/audio.mk
include $(DEVICE_PATH)/configs/camera/camera.mk
include $(DEVICE_PATH)/configs/access/access.mk
include $(DEVICE_PATH)/configs/vm/vm.mk
include $(DEVICE_PATH)/configs/sensors/sensors.mk
include $(DEVICE_PATH)/fan/fan.mk
include $(DEVICE_PATH)/display/display.mk
include $(DEVICE_PATH)/configs/oem/udfps/udfps.mk
include $(DEVICE_PATH)/configs/oem/apps/apps.mk
include $(DEVICE_PATH)/configs/oem/apps/consumer-apps.mk
include $(DEVICE_PATH)/patches/platform-patches.mk
$(call inherit-product, vendor/xiaomi/warsaw/warsaw-vendor.mk)

# Apply the NFC data labels through the system init domain, including existing data.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/init.warsaw.nfc.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/init.warsaw.nfc.rc
