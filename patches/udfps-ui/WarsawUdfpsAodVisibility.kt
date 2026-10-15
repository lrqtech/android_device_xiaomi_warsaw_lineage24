package com.android.systemui.biometrics

import android.os.Looper
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

object WarsawUdfpsAodVisibility {
    private val suppressed = MutableStateFlow(false)
    private val temporaryDozeVisible = MutableStateFlow(false)

    @JvmStatic
    fun setTemporaryDozeVisible(visible: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        temporaryDozeVisible.value = visible
    }

    @JvmStatic
    fun publish(hideFingerprint: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        suppressed.value = hideFingerprint
    }

    @JvmStatic
    fun applyTo(
        platformAlpha: Flow<Float>,
        iconType: Flow<DeviceEntryIconView.IconType>,
        stableDozing: Flow<Boolean> = flowOf(false),
    ): Flow<Float> =
        combine(platformAlpha, iconType, suppressed, temporaryDozeVisible, stableDozing) {
            alpha, type, hide, drawing, dozing ->
            when {
                type != DeviceEntryIconView.IconType.FINGERPRINT -> alpha
                hide -> 0f
                drawing && dozing -> 1f
                else -> alpha
            }
        }
}
