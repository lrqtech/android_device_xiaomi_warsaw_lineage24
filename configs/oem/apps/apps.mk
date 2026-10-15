PRODUCT_PACKAGES += \
    extphonelib \
    qcrilhook \
    qti-telephony-hidl-wrapper \
    vendor.qti.ims.datachannelservice-V3-java \
    com.qti.location.sdk \
    qti-telephony-hidl-wrapper-prd \
    uimremoteserverlibrary \
    uimremoteclientlibrary \
    uimgbalibrary \
    uimservicelibrary \
    QtiTelephony \
    qcrilmsgtunnel \
    atfwd \
    ImsDataChannelService \
    imssettings \
    DeviceInfo \
    PowerOffAlarm \
    QtiWifiService \
    com.qualcomm.location \
    com.qualcomm.qti.services.systemhelper \
    Xpan \
    QAS_DVC_MSP \
    CneApp \
    IWlanService \
    TxPwrAdmin \
    CACertService \
    TimeService \
    uimremoteserver \
    uimremoteclient \
    uimgbaservice \
    com.qualcomm.qti.gpudrivers.sun.api35 \
    XpanResTarget_Vendor

PRODUCT_COPY_FILES += \
    device/xiaomi/warsaw/configs/oem/apps/permissions/privapp-permissions-warsaw-oem-system_ext.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-warsaw-oem-system_ext.xml \
    device/xiaomi/warsaw/configs/oem/apps/permissions/qcrilhook.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/qcrilhook.xml \
    device/xiaomi/warsaw/configs/oem/apps/permissions/vendor.qti.imsdcservice.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/vendor.qti.imsdcservice.xml \
    device/xiaomi/warsaw/configs/oem/apps/permissions/com.qti.location.sdk.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/com.qti.location.sdk.xml \
    device/xiaomi/warsaw/configs/oem/apps/permissions/uimremoteserver.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/uimremoteserver.xml \
    device/xiaomi/warsaw/configs/oem/apps/permissions/uimremoteclient.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/uimremoteclient.xml \
    device/xiaomi/warsaw/configs/oem/apps/permissions/UimGba.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/UimGba.xml \
    device/xiaomi/warsaw/configs/oem/apps/permissions/UimService.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/UimService.xml \
    device/xiaomi/warsaw/configs/oem/apps/sysconfig/system-warsaw-oem-apps-hidden-api.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/warsaw-oem-apps-hidden-api.xml \
    device/xiaomi/warsaw/configs/oem/apps/sysconfig/system-warsaw-oem-apps-power-save.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/warsaw-oem-apps-power-save.xml \
    device/xiaomi/warsaw/configs/oem/apps/sysconfig/system_ext-warsaw-oem-apps-hidden-api.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/sysconfig/warsaw-oem-apps-hidden-api.xml \
    device/xiaomi/warsaw/configs/oem/apps/sysconfig/system_ext-warsaw-oem-apps-power-save.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/sysconfig/warsaw-oem-apps-power-save.xml \
    device/xiaomi/warsaw/configs/oem/apps/sysconfig/system_ext-warsaw-oem-apps-user-types.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/sysconfig/warsaw-oem-apps-user-types.xml \
    device/xiaomi/warsaw/configs/oem/apps/default-permissions/default-permission-xpan.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/default-permissions/default-permission-xpan.xml \
    device/xiaomi/warsaw/configs/oem/apps/default-permissions/vendor.qti.data.txpwradmin.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default-permissions/vendor.qti.data.txpwradmin.xml
