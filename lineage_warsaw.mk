$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)
$(call inherit-product, device/xiaomi/warsaw/device.mk)

PRODUCT_NAME := lineage_warsaw
PRODUCT_DEVICE := warsaw
PRODUCT_BRAND := Redmi
PRODUCT_MANUFACTURER := Xiaomi
PRODUCT_MODEL := M332BF

# Official device-specific ODM overrides the common vendor launch API to 36.
PRODUCT_SHIPPING_API_LEVEL := 36
