WARSAW_BOOT_DIR := vendor/xiaomi/warsaw/boot
WARSAW_BOOT_CONFIG := device/xiaomi/warsaw/configs/boot

# GKI is built separately with the pinned Kleaf workspace, then its verified
# Image and signed modules are consumed as one input set by the Android build.
WARSAW_SOURCE_GKI_DIR := $(WARSAW_BOOT_DIR)/gki-no-kunit
WARSAW_USE_OTA_GKI ?= false
ifeq ($(WARSAW_USE_OTA_GKI),true)
TARGET_PREBUILT_KERNEL := $(WARSAW_BOOT_DIR)/Image
BOARD_PREBUILT_BOOTIMAGE := $(WARSAW_BOOT_DIR)/boot.img
BOARD_PREBUILT_SYSTEM_DLKMIMAGE := $(WARSAW_BOOT_DIR)/system_dlkm.img
else
TARGET_PREBUILT_KERNEL := $(WARSAW_SOURCE_GKI_DIR)/Image
BOARD_SYSTEM_DLKM_SRC := $(WARSAW_SOURCE_GKI_DIR)/system_dlkm
endif

# The unchanged OS4 userspace contains five 4 KiB-only IMS/data interface
# libraries. Their per-module alignment exceptions are valid only with this
# selected 4 KiB kernel; the rest of the system retains 16 KiB ELF alignment.
WARSAW_SELECTED_KERNEL_PAGE_SIZE := $(strip $(shell python3 $(WARSAW_BOOT_CONFIG)/kernel-page-size.py $(TARGET_PREBUILT_KERNEL)))
ifneq ($(WARSAW_SELECTED_KERNEL_PAGE_SIZE),4096)
$(error warsaw stock IMS/data libraries require a 4 KiB kernel; replace those libraries before changing kernel page size)
endif
# ABL reads the SPL from AVB because the stock GKI header OS version is zero.
# Keep the OTA boot SPL baseline for the matching source GKI and OEM modules.
BOOT_SECURITY_PATCH := 2026-09-01
# The generic ramdisk is rebuilt from the current platform sources.
INIT_BOOT_SECURITY_PATCH := $(PLATFORM_SECURITY_PATCH)
TARGET_KERNEL_VERSION := 6.6
BOARD_KERNEL_IMAGE_NAME := Image
BOARD_BOOT_HEADER_VERSION := 4
BOARD_INIT_BOOT_HEADER_VERSION := 4
BOARD_USES_GENERIC_KERNEL_IMAGE := true
BOARD_EXCLUDE_KERNEL_FROM_RECOVERY_IMAGE := true
BOARD_MOVE_GSI_AVB_KEYS_TO_VENDOR_BOOT := true
BOARD_KERNEL_PAGESIZE := 4096
BOARD_RAMDISK_USE_LZ4 := true

BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_OFFSET := 0x00008000
BOARD_RAMDISK_OFFSET := 0x01000000
BOARD_TAGS_OFFSET := 0x00000100
BOARD_DTB_OFFSET := 0x01f00000
# Preserve OTA parameters consumed by the retained OEM kernel modules.
BOARD_KERNEL_CMDLINE += $(shell cat $(WARSAW_BOOT_CONFIG)/kernel-cmdline.txt)
BOARD_BOOTCONFIG += $(shell cat $(WARSAW_BOOT_CONFIG)/vendor-bootconfig.txt)

BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_INIT_ARGS += --header_version $(BOARD_INIT_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_ARGS += --kernel_offset $(BOARD_KERNEL_OFFSET)
BOARD_MKBOOTIMG_ARGS += --ramdisk_offset $(BOARD_RAMDISK_OFFSET)
BOARD_MKBOOTIMG_ARGS += --tags_offset $(BOARD_TAGS_OFFSET)
BOARD_MKBOOTIMG_ARGS += --dtb_offset $(BOARD_DTB_OFFSET)

BOARD_INCLUDE_DTB_IN_BOOTIMG := true
BOARD_PREBUILT_DTBIMAGE_DIR := $(WARSAW_BOOT_DIR)/dtb
BOARD_PREBUILT_DTBOIMAGE := $(WARSAW_BOOT_DIR)/dtbo.img

BOARD_DO_NOT_STRIP_VENDOR_RAMDISK_MODULES := true
BOARD_VENDOR_RAMDISK_KERNEL_MODULES := $(wildcard $(WARSAW_BOOT_DIR)/vendor_ramdisk/lib/modules/*.ko)
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD := $(strip $(shell cat $(WARSAW_BOOT_CONFIG)/modules.load))
BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD := $(strip $(shell cat $(WARSAW_BOOT_CONFIG)/modules.load.recovery))
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_BLOCKLIST_FILE := $(WARSAW_BOOT_CONFIG)/modules.blocklist

BOARD_RECOVERY_KERNEL_MODULES :=
BOARD_RECOVERY_KERNEL_MODULES_LOAD :=

# Keep the OTA vendor modules, firmware and first-stage load order.
BOARD_PREBUILT_VENDOR_DLKMIMAGE := $(WARSAW_BOOT_DIR)/vendor_dlkm.img

BOARD_MKBOOTIMG_ARGS += --os_version 0 --os_patch_level 0
BOARD_MKBOOTIMG_INIT_ARGS += --os_version 0 --os_patch_level 0

# Diagnostic userdebug/eng images must boot permissive in both Android and
# recovery. Keep the complete policy and neverallow checks so AVCs remain useful.
ifneq ($(filter userdebug eng,$(TARGET_BUILD_VARIANT)),)
BOARD_BOOTCONFIG += androidboot.selinux=permissive
# This device has a kernel-less recovery; the generic build omits its cmdline.
# Set it explicitly so recovery also requests permissive when booted separately.
BOARD_RECOVERY_MKBOOTIMG_ARGS := $(BOARD_MKBOOTIMG_ARGS) --cmdline "androidboot.selinux=permissive"
endif
