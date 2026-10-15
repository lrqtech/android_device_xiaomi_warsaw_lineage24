BOARD_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_INIT_BOOT_IMAGE_PARTITION_SIZE := 8388608
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 104857600
BOARD_DTBOIMG_PARTITION_SIZE := 18874368
BOARD_PVMFWIMAGE_PARTITION_SIZE := 1048576
BOARD_SUPER_PARTITION_SIZE := 13421772800
BOARD_SUPER_PARTITION_GROUPS := qti_dynamic_partitions
BOARD_QTI_DYNAMIC_PARTITIONS_SIZE := 13411287040

BOARD_QTI_DYNAMIC_PARTITIONS_PARTITION_LIST := \
    odm product system system_dlkm system_ext vendor vendor_dlkm

BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_ODMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDOR_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE := f2fs
BOARD_USES_METADATA_PARTITION := true

TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_ODM := odm
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm
TARGET_COPY_OUT_SYSTEM_DLKM := system_dlkm
BOARD_USES_VENDOR_DLKMIMAGE := true
BOARD_USES_SYSTEM_DLKMIMAGE := true

AB_OTA_UPDATER := true
AB_OTA_PARTITIONS += \
    boot init_boot vendor_boot recovery dtbo vbmeta vbmeta_system \
    odm product system system_dlkm system_ext vendor vendor_dlkm

# Size rebuilt logical partitions from their contents. The dynamic group above
# remains the hard capacity limit; stock OS4 userspace image lengths are not
# minimum allocations for a smaller LineageOS system.
# Preserve exact lengths for the unchanged, signed kernel module images.
BOARD_SYSTEM_DLKMIMAGE_PARTITION_SIZE := 15294464
BOARD_VENDOR_DLKMIMAGE_PARTITION_SIZE := 88510464

TARGET_USERIMAGES_SPARSE_EROFS_DISABLED := true
BOARD_EROFS_BLOCKSIZE := 4096
BOARD_EROFS_COMPRESSOR := lz4hc,12


BOARD_PACK_RADIOIMAGES := abl aop aop_config bluetooth cpucp cpucp_dtb devcfg dsp featenabler hyp idmanager imagefv keymaster modem modemfirmware multiimgqti pdp pdp_cdb pvmfw qupfw shrm soccp_dcd soccp_debug spuservice tz uefi uefisecapp vm-bootsys xbl xbl_config xbl_ramdump
