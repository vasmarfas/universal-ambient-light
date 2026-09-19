package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import java.io.IOException

/**
 * Art-Net (ArtDMX): второй массовый DMX-по-сети после E1.31. Его принимают WLED, большинство
 * DMX-узлов и контроллеры пиксельных лент. Лента режется на вселенные по
 * [ledsPerUniverse] светодиодов, у каждой - свой пакет.
 */
class ArtNetClient(
    context: Context,
    host: String,
    port: Int,
    private val mFirstUniverse: Int,
    private val mLedsPerUniverse: Int,
    private val mColorOrder: String,
    smoothing: SmoothingSettings,
) : LedStreamClient(context, smoothing) {

    private val mLink = UdpLink(host, if (port > 0) port else DEFAULT_PORT)
    private var mSequence = 0

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() = mLink.open()

    @Throws(IOException::class)
    override fun write(leds: Array<ColorRgb>) {
        // 0 в поле последовательности выключает у приёмника проверку порядка - начинаем с 1
        mSequence = mSequence % 255 + 1
        val perUniverse = mLedsPerUniverse.coerceIn(1, MAX_LEDS_PER_UNIVERSE)
        var index = 0
        var universe = mFirstUniverse.coerceIn(0, MAX_UNIVERSE)
        while (index < leds.size) {
            val count = minOf(perUniverse, leds.size - index)
            mLink.send(packet(universe, mSequence, leds, index, count, mColorOrder))
            index += count
            universe++
        }
    }

    override fun close() = mLink.close()

    companion object {
        const val DEFAULT_PORT = 6454
        const val DEFAULT_LEDS_PER_UNIVERSE = 170
        private const val MAX_LEDS_PER_UNIVERSE = 170
        private const val MAX_UNIVERSE = 0x7FFF
        private const val HEADER_SIZE = 18
        private const val PROTOCOL_VERSION = 14
        private val ART_NET_ID = "Art-Net\u0000".toByteArray(Charsets.US_ASCII)

        /** ArtDMX для одной вселенной: светодиоды с [from], [count] штук. */
        internal fun packet(
            universe: Int,
            sequence: Int,
            leds: Array<ColorRgb>,
            from: Int,
            count: Int,
            colorOrder: String,
        ): ByteArray {
            // Длина данных по стандарту чётная
            val length = (count * 3 + 1) and 0x3FE
            val packet = ByteArray(HEADER_SIZE + length)
            ART_NET_ID.copyInto(packet)
            packet[8] = 0x00
            packet[9] = 0x50
            packet[11] = PROTOCOL_VERSION.toByte()
            packet[12] = sequence.toByte()
            packet[14] = universe.toByte()
            packet[15] = (universe shr 8).toByte()
            packet[16] = (length shr 8).toByte()
            packet[17] = length.toByte()
            var offset = HEADER_SIZE
            for (i in from until from + count) {
                offset = ColorOrder.write(packet, offset, leds[i], colorOrder)
            }
            return packet
        }
    }
}
