package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import java.io.IOException

/**
 * tpm2.net: кадр TPM2 по UDP, порт 65506. Его принимают Jinx!, контроллеры на ESP с
 * прошивками tpm2 и часть самодельных. Длинная лента делится на пакеты по
 * [ledsPerPacket] светодиодов; номер пакета и их число приёмник берёт из заголовка.
 */
class Tpm2NetClient(
    context: Context,
    host: String,
    port: Int,
    private val mLedsPerPacket: Int,
    private val mColorOrder: String,
    smoothing: SmoothingSettings,
) : LedStreamClient(context, smoothing) {

    private val mLink = UdpLink(host, if (port > 0) port else DEFAULT_PORT)

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() = mLink.open()

    @Throws(IOException::class)
    override fun write(leds: Array<ColorRgb>) {
        for (packet in packets(leds, mLedsPerPacket, mColorOrder)) mLink.send(packet)
    }

    override fun close() = mLink.close()

    companion object {
        const val DEFAULT_PORT = 65506
        const val DEFAULT_LEDS_PER_PACKET = 170
        private const val MAX_LEDS_PER_PACKET = 490
        private const val HEADER_SIZE = 6
        private const val BLOCK_START = 0x9C.toByte()
        private const val DATA_FRAME = 0xDA.toByte()
        private const val BLOCK_END = 0x36.toByte()

        /** Кадр целиком, нарезанный на пакеты по [ledsPerPacket] светодиодов. */
        internal fun packets(leds: Array<ColorRgb>, ledsPerPacket: Int, colorOrder: String): List<ByteArray> {
            val perPacket = ledsPerPacket.coerceIn(1, MAX_LEDS_PER_PACKET)
            val total = (leds.size + perPacket - 1) / perPacket
            return (0 until total).map { index ->
                val from = index * perPacket
                val count = minOf(perPacket, leds.size - from)
                val length = count * 3
                val packet = ByteArray(HEADER_SIZE + length + 1)
                packet[0] = BLOCK_START
                packet[1] = DATA_FRAME
                packet[2] = (length shr 8).toByte()
                packet[3] = length.toByte()
                packet[4] = (index + 1).toByte()
                packet[5] = total.toByte()
                var offset = HEADER_SIZE
                for (i in from until from + count) offset = ColorOrder.write(packet, offset, leds[i], colorOrder)
                packet[offset] = BLOCK_END
                packet
            }
        }
    }
}
