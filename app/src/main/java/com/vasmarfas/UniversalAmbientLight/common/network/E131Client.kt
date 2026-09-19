package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import java.io.IOException
import java.util.UUID

/**
 * sACN, он же E1.31: DMX по сети. Понимают его ESPixelStick, WLED в режиме E1.31,
 * контроллеры Falcon и FPP, большинство DMX-узлов. Лента режется на вселенные по
 * [ledsPerUniverse] светодиодов, каждая уходит своим пакетом.
 *
 * Без адреса пакеты идут мультикастом 239.255.x.y - так приёмник ищет их по умолчанию.
 */
class E131Client(
    context: Context,
    private val mHost: String,
    port: Int,
    private val mFirstUniverse: Int,
    private val mLedsPerUniverse: Int,
    private val mColorOrder: String,
    smoothing: SmoothingSettings,
) : LedStreamClient(context, smoothing) {

    private val mPort = if (port > 0) port else DEFAULT_PORT
    private val mLinks = HashMap<Int, UdpLink>()
    private val mCid = uuidBytes(UUID.randomUUID())
    private var mSequence = 0

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() {
        // Первая вселенная открывается сразу: ошибка адреса видна на подключении, а не молча
        link(mFirstUniverse.coerceIn(1, MAX_UNIVERSE))
    }

    @Throws(IOException::class)
    override fun write(leds: Array<ColorRgb>) {
        val sequence = mSequence++ and 0xFF
        val perUniverse = mLedsPerUniverse.coerceIn(1, MAX_LEDS_PER_UNIVERSE)
        var index = 0
        var universe = mFirstUniverse.coerceIn(1, MAX_UNIVERSE)
        while (index < leds.size) {
            val count = minOf(perUniverse, leds.size - index)
            link(universe).send(packet(mCid, universe, sequence, leds, index, count, mColorOrder))
            index += count
            universe++
        }
    }

    override fun close() {
        for (link in mLinks.values) link.close()
        mLinks.clear()
    }

    private fun link(universe: Int): UdpLink = mLinks.getOrPut(universe) {
        val host = mHost.ifBlank { "239.255.${(universe shr 8) and 0xFF}.${universe and 0xFF}" }
        UdpLink(host, mPort).also { it.open() }
    }

    companion object {
        const val DEFAULT_PORT = 5568
        const val DEFAULT_LEDS_PER_UNIVERSE = 170
        private const val MAX_LEDS_PER_UNIVERSE = 170
        private const val MAX_UNIVERSE = 63999
        private const val HEADER_SIZE = 126
        private const val PRIORITY = 100

        private val ACN_ID = byteArrayOf(
            0x41, 0x53, 0x43, 0x2D, 0x45, 0x31, 0x2E, 0x31, 0x37, 0x00, 0x00, 0x00
        )
        private val SOURCE_NAME = "Universal Ambient Light".toByteArray(Charsets.UTF_8)

        /** Пакет данных E1.31 для одной вселенной: светодиоды с [from], [count] штук. */
        internal fun packet(
            cid: ByteArray,
            universe: Int,
            sequence: Int,
            leds: Array<ColorRgb>,
            from: Int,
            count: Int,
            colorOrder: String,
        ): ByteArray {
            val channels = count * 3
            val packet = ByteArray(HEADER_SIZE + channels)
            // Корневой уровень
            packet[1] = 0x10
            ACN_ID.copyInto(packet, 4)
            putFlagsLength(packet, 16, packet.size - 16)
            packet[21] = 0x04
            cid.copyInto(packet, 22)
            // Уровень кадра
            putFlagsLength(packet, 38, packet.size - 38)
            packet[43] = 0x02
            SOURCE_NAME.copyInto(packet, 44, 0, minOf(SOURCE_NAME.size, 63))
            packet[108] = PRIORITY.toByte()
            packet[111] = sequence.toByte()
            packet[113] = (universe shr 8).toByte()
            packet[114] = universe.toByte()
            // Уровень DMP
            putFlagsLength(packet, 115, packet.size - 115)
            packet[117] = 0x02
            packet[118] = 0xA1.toByte()
            packet[122] = 0x01
            val values = channels + 1
            packet[123] = (values shr 8).toByte()
            packet[124] = values.toByte()
            var offset = HEADER_SIZE
            for (i in from until from + count) {
                offset = ColorOrder.write(packet, offset, leds[i], colorOrder)
            }
            return packet
        }

        private fun putFlagsLength(packet: ByteArray, offset: Int, length: Int) {
            val value = 0x7000 or (length and 0x0FFF)
            packet[offset] = (value shr 8).toByte()
            packet[offset + 1] = value.toByte()
        }

        private fun uuidBytes(uuid: UUID): ByteArray {
            val out = ByteArray(16)
            for (i in 0 until 8) {
                out[i] = (uuid.mostSignificantBits shr (56 - i * 8)).toByte()
                out[8 + i] = (uuid.leastSignificantBits shr (56 - i * 8)).toByte()
            }
            return out
        }
    }
}
