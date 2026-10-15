WARSAW_SOURCE_REPLACEMENT_PACKAGES := \
    IPACM_Filter_cfg.xml \
    aidl_audio_set_configurations_bfbs \
    aidl_audio_set_scenarios_bfbs \
    hfp_codec_capabilities_xml \
    init.qti.display_boot.rc \
    init.qti.display_boot.sh \
    libclang_rt.ubsan_standalone.vendor \
    rfs_apq_gnss_hlos_symlink \
    rfs_apq_gnss_ramdumps_symlink \
    rfs_apq_gnss_readonly_firmware_symlink \
    rfs_apq_gnss_readonly_vendor_firmware_symlink \
    rfs_apq_gnss_readwrite_symlink \
    rfs_apq_gnss_shared_symlink \
    rfs_mdm_adsp_hlos_symlink \
    rfs_mdm_adsp_ramdumps_symlink \
    rfs_mdm_adsp_readonly_firmware_symlink \
    rfs_mdm_adsp_readonly_vendor_firmware_symlink \
    rfs_mdm_adsp_readwrite_symlink \
    rfs_mdm_adsp_shared_symlink \
    rfs_mdm_cdsp_hlos_symlink \
    rfs_mdm_cdsp_ramdumps_symlink \
    rfs_mdm_cdsp_readonly_firmware_symlink \
    rfs_mdm_cdsp_readonly_vendor_firmware_symlink \
    rfs_mdm_cdsp_readwrite_symlink \
    rfs_mdm_cdsp_shared_symlink \
    rfs_mdm_mpss_hlos_symlink \
    rfs_mdm_mpss_ramdumps_symlink \
    rfs_mdm_mpss_readonly_firmware_symlink \
    rfs_mdm_mpss_readonly_vendor_firmware_symlink \
    rfs_mdm_mpss_readwrite_symlink \
    rfs_mdm_mpss_shared_symlink \
    rfs_mdm_ois_hlos_symlink \
    rfs_mdm_ois_ramdumps_symlink \
    rfs_mdm_ois_readonly_firmware_symlink \
    rfs_mdm_ois_readonly_vendor_firmware_symlink \
    rfs_mdm_ois_readwrite_symlink \
    rfs_mdm_ois_shared_symlink \
    rfs_mdm_slpi_hlos_symlink \
    rfs_mdm_slpi_ramdumps_symlink \
    rfs_mdm_slpi_readonly_firmware_symlink \
    rfs_mdm_slpi_readonly_vendor_firmware_symlink \
    rfs_mdm_slpi_readwrite_symlink \
    rfs_mdm_slpi_shared_symlink \
    rfs_mdm_tn_hlos_symlink \
    rfs_mdm_tn_ramdumps_symlink \
    rfs_mdm_tn_readonly_firmware_symlink \
    rfs_mdm_tn_readonly_vendor_firmware_symlink \
    rfs_mdm_tn_readwrite_symlink \
    rfs_mdm_tn_shared_symlink \
    rfs_mdm_wpss_hlos_symlink \
    rfs_mdm_wpss_ramdumps_symlink \
    rfs_mdm_wpss_readonly_firmware_symlink \
    rfs_mdm_wpss_readonly_vendor_firmware_symlink \
    rfs_mdm_wpss_readwrite_symlink \
    rfs_mdm_wpss_shared_symlink \
    rfs_msm_adsp_hlos_symlink \
    rfs_msm_adsp_ramdumps_symlink \
    rfs_msm_adsp_readonly_firmware_symlink \
    rfs_msm_adsp_readonly_vendor_firmware_symlink \
    rfs_msm_adsp_readwrite_symlink \
    rfs_msm_adsp_shared_symlink \
    rfs_msm_cdsp_hlos_symlink \
    rfs_msm_cdsp_ramdumps_symlink \
    rfs_msm_cdsp_readonly_firmware_symlink \
    rfs_msm_cdsp_readonly_vendor_firmware_symlink \
    rfs_msm_cdsp_readwrite_symlink \
    rfs_msm_cdsp_shared_symlink \
    rfs_msm_mpss_hlos_symlink \
    rfs_msm_mpss_ramdumps_symlink \
    rfs_msm_mpss_readonly_firmware_symlink \
    rfs_msm_mpss_readonly_vendor_firmware_symlink \
    rfs_msm_mpss_readwrite_symlink \
    rfs_msm_mpss_shared_symlink \
    rfs_msm_ois_hlos_symlink \
    rfs_msm_ois_ramdumps_symlink \
    rfs_msm_ois_readonly_firmware_symlink \
    rfs_msm_ois_readonly_vendor_firmware_symlink \
    rfs_msm_ois_readwrite_symlink \
    rfs_msm_ois_shared_symlink \
    rfs_msm_slpi_hlos_symlink \
    rfs_msm_slpi_ramdumps_symlink \
    rfs_msm_slpi_readonly_firmware_symlink \
    rfs_msm_slpi_readonly_vendor_firmware_symlink \
    rfs_msm_slpi_readwrite_symlink \
    rfs_msm_slpi_shared_symlink \
    rfs_msm_wpss_hlos_symlink \
    rfs_msm_wpss_ramdumps_symlink \
    rfs_msm_wpss_readonly_firmware_symlink \
    rfs_msm_wpss_readonly_vendor_firmware_symlink \
    rfs_msm_wpss_readwrite_symlink \
    rfs_msm_wpss_shared_symlink \
    system_core_charger_res_images_battery_fail.png_default_vendor \
    system_core_charger_res_images_battery_scale.png_default_vendor \
    vendor.qti.hardware.display.composer-service.rc \
    warsaw_system_dlkm_modprobe_sh \
    wpa_supplicant.conf \
    vndservicemanager

PRODUCT_PACKAGES += $(WARSAW_SOURCE_REPLACEMENT_PACKAGES)

# Native install owners with exact OTA configuration inputs.
PRODUCT_PACKAGES += IPACM_cfg.xml usb_compositions.conf

# Keep firmware mount targets in the native LineageOS image install list.
PRODUCT_PACKAGES += \
    vendor_bt_firmware_mountpoint \
    vendor_dsp_mountpoint \
    vendor_firmware_mnt_mountpoint \
    warsaw_vendor_modem_firmware_mountpoint \
    warsaw_product_vm_system_mountpoint
