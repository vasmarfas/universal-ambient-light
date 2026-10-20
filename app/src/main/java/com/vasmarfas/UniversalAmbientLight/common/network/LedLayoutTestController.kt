package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import com.vasmarfas.UniversalAmbientLight.common.util.LedLayout
import com.vasmarfas.UniversalAmbientLight.common.util.LedLayoutTestPattern
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/**
 * Отправляет адресные цвета только при изменении раскладки. Старые позиции гасим
 * явно: ряд контроллеров не очищает хвост после сокращения числа LED.
 */
internal class LedLayoutTestController(
    private val mContext: Context,
    private val mSend: (Array<ColorRgb>) -> Unit,
) {
    @Volatile
    var enabled = false
        private set

    private var mLayout: LedLayout? = null
    private var mMaxLedCount = 0

    @Synchronized
    fun setEnabled(value: Boolean, previousLedCount: Int = 0) {
        if (enabled == value) return
        enabled = value
        if (value) {
            mMaxLedCount = previousLedCount.coerceAtLeast(0)
            refresh()
        } else {
            if (mMaxLedCount > 0) {
                mSend(Array(mMaxLedCount) { ColorRgb(0, 0, 0) })
            }
            mLayout = null
            mMaxLedCount = 0
        }
    }

    @Synchronized
    fun refresh() {
        if (!enabled) return
        val layout = LedLayout.from(Preferences(mContext))
        if (layout == mLayout) return
        val colors = LedLayoutTestPattern.build(layout)
        val count = maxOf(mMaxLedCount, colors.size)
        if (count > 0) {
            mSend(Array(count) { i -> if (i < colors.size) colors[i] else ColorRgb(0, 0, 0) })
        }
        mMaxLedCount = count
        mLayout = layout
    }
}
