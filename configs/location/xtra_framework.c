/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

/* Only xtra-daemon loads this selector. LineageOS has no QCC system app;
 * the OTA's existing native HTTPS downloader supplies XTRA instead. Keep
 * the global enhanced-framework detector used by the camera unchanged.
 * Match the Qualcomm detector's int(void) ABI, including cross-DSO CFI.
 */
int isRunningWithVendorEnhancedFramework(void) {
    return 0;
}
