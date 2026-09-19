package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Голый RGB по UDP, без заголовка: по три байта на светодиод одним пакетом. Так принимают
 * приёмник Hyperion в WLED, ESPixelStick и простые самодельные прошивки. Номеров и смещений
 * в протоколе нет, поэтому лента обязана влезть в один пакет без фрагментации.
 */
class UdpRawClient(
    context: Context,
    host: String,
    port: Int,
    private val mColorOrder: String,
    smoothing: SmoothingSettings,
) : LedStreamClient(context, smoothing) {

    private val mLink = UdpLink(host, if (port > 0) port else DEFAULT_PORT)
    private var mBuffer = ByteArray(0)
    private var mTruncationLogged = false

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() = mLink.open()

    @Throws(IOException::class)
    override fun write(leds: Array<ColorRgb>) {
        val count = minOf(leds.size, MAX_LEDS)
        if (leds.size > MAX_LEDS && !mTruncationLogged) {
            mTruncationLogged = true
            Log.w(TAG, "UDP raw fits $MAX_LEDS LEDs in one packet, got ${leds.size}; the rest is dropped")
        }
        if (mBuffer.size != count * 3) mBuffer = ByteArray(count * 3)
        var offset = 0
        for (i in 0 until count) offset = ColorOrder.write(mBuffer, offset, leds[i], mColorOrder)
        mLink.send(mBuffer)
    }

    override fun close() = mLink.close()

    companion object {
        private const val TAG = "UdpRawClient"
        const val DEFAULT_PORT = 5568

        /** 1470 байт данных: больше в пакет Ethernet без фрагментации не влезает. */
        private const val MAX_LEDS = 490
    }
}
