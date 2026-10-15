AB_OTA_UPDATER := true
TARGET_OTA_ALLOW_NON_AB := false

# Verified official OTA inventory, excluding the audited optional mi_ext and empty mi_product volumes.
AB_OTA_PARTITIONS := \
    abl aop aop_config bluetooth boot cpucp \
    cpucp_dtb devcfg dsp dtbo featenabler hyp \
    idmanager imagefv init_boot keymaster modem modemfirmware \
    multiimgqti odm pdp pdp_cdb product pvmfw \
    qupfw recovery shrm soccp_dcd soccp_debug spuservice \
    system system_dlkm system_ext tz uefi uefisecapp \
    vbmeta vbmeta_system vendor vendor_boot vendor_dlkm vm-bootsys \
    xbl xbl_config xbl_ramdump
