DEVICE_PATH := device/xiaomi/warsaw

TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-2a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := oryon
TARGET_BOARD_PLATFORM := sun
TARGET_BOOTLOADER_BOARD_NAME := sun
TARGET_NO_BOOTLOADER := true

BOARD_SHIPPING_API_LEVEL := 202404
BOARD_VNDK_VERSION := current
BOARD_USES_QCOM_HARDWARE := true

SOONG_CONFIG_NAMESPACES += rfs
SOONG_CONFIG_rfs += mpss_firmware_symlink_target
SOONG_CONFIG_rfs_mpss_firmware_symlink_target := modem_firmware

SOONG_CONFIG_NAMESPACES += warsaw_nfcinfo
SOONG_CONFIG_warsaw_nfcinfo += stock_prebuilt
SOONG_CONFIG_warsaw_nfcinfo_stock_prebuilt := true

include $(DEVICE_PATH)/configs/partitions.mk
include $(DEVICE_PATH)/configs/avb.mk
include $(DEVICE_PATH)/configs/ota.mk
include $(DEVICE_PATH)/configs/boot/boot.mk
include $(DEVICE_PATH)/vintf/vintf.mk
include $(DEVICE_PATH)/sepolicy.mk

TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/rootdir/etc/recovery.fstab
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888
TARGET_USERIMAGES_USE_F2FS := true
TARGET_USERIMAGES_USE_EXT4 := true
BOARD_FLASH_BLOCK_SIZE := 4096

TARGET_SCREEN_WIDTH := 1280
TARGET_SCREEN_HEIGHT := 2772
TARGET_SCREEN_DENSITY := 520
# Generate recovery text at the actual panel width.
TARGET_RECOVERY_UI_SCREEN_WIDTH := 1280
TARGET_SYSTEM_PROP += $(DEVICE_PATH)/properties/system.prop
TARGET_SYSTEM_PROP += $(DEVICE_PATH)/properties/capabilities-system.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/properties/capabilities-vendor.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/configs/telephony/boot.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/configs/usb/boot.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/configs/security/boot.prop
TARGET_ODM_PROP += $(DEVICE_PATH)/configs/audio/boot.prop
TARGET_ODM_PROP += $(DEVICE_PATH)/properties/identity-odm.prop
TARGET_ODM_PROP += $(DEVICE_PATH)/properties/haptics-odm.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/properties/haptics-vendor.prop
include $(DEVICE_PATH)/configs/audio/board.mk
TARGET_FS_CONFIG_GEN += $(DEVICE_PATH)/configs/access/config.fs

include vendor/xiaomi/warsaw/BoardConfigVendor.mk

# Scope the verified OEM ABI inputs and prebuilt selection to warsaw.
SOONG_CONFIG_NAMESPACES += warsaw_stock_abi
SOONG_CONFIG_warsaw_stock_abi += enabled include_mi_remote
SOONG_CONFIG_warsaw_stock_abi_enabled := true
# Match the requested default IR client and its explicit opt-out.
WARSAW_INCLUDE_MI_REMOTE ?= true
SOONG_CONFIG_warsaw_stock_abi_include_mi_remote := $(if $(filter true,$(WARSAW_INCLUDE_MI_REMOTE)),true,false)
