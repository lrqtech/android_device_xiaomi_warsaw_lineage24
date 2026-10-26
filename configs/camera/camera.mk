PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/camera/task_profiles.json:$(TARGET_COPY_OUT_VENDOR)/etc/task_profiles.json

PRODUCT_PACKAGES += \
    MiuiCamera \
    miui-cameraopt \
    CameraMind \
    libcameramind \
    libmisight_jni

# Both camera applications load miui-cameraopt through their shared-library
# declarations and its permissions XML.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/camera/privapp-permissions-warsaw-camera.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-warsaw-camera.xml \
    $(DEVICE_PATH)/configs/camera/compat/miui-cameraopt.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/miui-cameraopt.xml

PRODUCT_ODM_PROPERTIES += \
    ro.vendor.audio.zoom.type=4 \
    ro.vendor.audio.unite.record.type=1 \
    ro.vendor.audio.general.video.type=0,C,0,2,1,3 \
    ro.vendor.audio.special.video.zoom.type=4,C,0,1,1,1 \
    persist.vendor.audio.special.video.wnd_ns.type=4,C,0,1,1,2 \
    ro.vendor.audio.special.video.spatialsound.type=4,C,0,1,1,1 \
    ro.vendor.audio.special.video.frontfacingrecord.type=4,C,0,1,1,1 \
    persist.vendor.camera.cameramind.supported=true

# The pinned CN MiuiCamera defaults ro.miui.build.region to cn, while the
# OEM HAL defaults both region properties to 0 and then loads global beauty
# tables. Keep the APK and HAL on the same OEM region, including the 8 GB table.
# These keys already have exact build_prop labels readable by both domains.
PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.miui.build.region=cn \
    ro.miui.region=CN \
    persist.sys.miui.camera.cameramind.supported=true

# Real OS4 cloud settings provider used by CameraMind, with its update/rollback jobs.
PRODUCT_PACKAGES += WarsawCloudSettings
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/camera/cloud/privapp-permissions-warsaw-cloud.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-warsaw-cloud.xml

# CameraMind/Camera boost requests reach the original Qualcomm performance HAL.
PRODUCT_PACKAGES += libwarsaw_cameraperf

# OS4 QuickCamera transport and device low-battery capture protection.
PRODUCT_SYSTEM_EXT_PROPERTIES += \
    persist.sys.miui.camera.quickcamera.supported=true

PRODUCT_ODM_PROPERTIES += \
    persist.vendor.low.cutoff=true

# FastRPC opens this app-readable copy before its private DSP partition fallback.
# The shell is unchanged Hexagon firmware from the pinned stock DSP image.
PRODUCT_PACKAGES += warsaw_fastrpc_shell_unsigned_3

# The retained anchor plugin is prepared with an unconditional ECD bypass.
# Publish the disabled setting as well; saved properties cannot undo the fixup.
PRODUCT_ODM_PROPERTIES += persist.vendor.camera.plugin.ecdplugin.bypass=1
