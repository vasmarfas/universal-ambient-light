package com.vasmarfas.UniversalAmbientLight.common.util

import android.os.Handler
import android.os.Looper
import com.vasmarfas.UniversalAmbientLight.common.MStarVssCaptureEncoder
import com.vasmarfas.UniversalAmbientLight.common.MtkThalCaptureEncoder

// Проверка OEM-доступности уже асинхронна; Handler перечитывает готовый результат,
// не добавляя корутину в Compose.
internal class CaptureAvailabilityMonitor(private val mOnAvailable: () -> Unit) {
    private val mHandler = Handler(Looper.getMainLooper())
    private var mChecksRemaining = 20
    private val mCheck = object : Runnable {
        override fun run() {
            if (isAvailable()) {
                mOnAvailable()
                return
            }
            if (--mChecksRemaining > 0) mHandler.postDelayed(this, 250L)
        }
    }

    fun start() {
        mHandler.post(mCheck)
    }

    fun stop() {
        mHandler.removeCallbacks(mCheck)
    }

    companion object {
        fun isAvailable(): Boolean =
            MStarVssCaptureEncoder.isAvailable() || MtkThalCaptureEncoder.isAvailable()
    }
}
