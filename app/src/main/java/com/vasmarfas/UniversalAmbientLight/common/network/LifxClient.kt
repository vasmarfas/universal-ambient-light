package com.vasmarfas.UniversalAmbientLight.common.network

import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Лампы LIFX по их локальному протоколу: двоичные сообщения по UDP на порт 56700, облако не
 * нужно. Лампа в списке - её IP-адрес.
 */
class LifxClient(
    lampsSpec: String?,
    settings: LampSettings,
) : ZoneLampClient(lampsSpec, settings) {

    private var mSocket: DatagramSocket? = null
    private val mAddresses = HashMap<String, InetAddress>()
    private var mSequence = 0

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() {
        mSocket = DatagramSocket()
        for (lamp in mLampsByZone.values.flatten()) {
            mAddresses[lamp.entityId] = InetAddress.getByName(lamp.entityId)
        }
    }

    @Throws(IOException::class)
    override fun sendColor(lamps: List<HomeAssistantLamp>, r: Int, g: Int, b: Int, brightness: Int?) {
        val hsbk = hsbk(r, g, b, brightness)
        // Питание включается отдельным сообщением: цвет выключенной лампе ничего не даёт
        send(lamps, message(TYPE_SET_POWER, powerPayload(true)))
        send(lamps, message(TYPE_SET_COLOR, colorPayload(hsbk, transitionMs)))
    }

    @Throws(IOException::class)
    override fun sendOff(lamps: List<HomeAssistantLamp>) {
        send(lamps, message(TYPE_SET_POWER, powerPayload(false)))
    }

    override fun close() {
        mSocket?.close()
        mSocket = null
    }

    private fun send(lamps: List<HomeAssistantLamp>, data: ByteArray) {
        val socket = mSocket ?: throw IOException("Socket is closed")
        for (lamp in lamps) {
            val address = mAddresses[lamp.entityId] ?: continue
            socket.send(DatagramPacket(data, data.size, address, PORT))
        }
    }

    private fun message(type: Int, payload: ByteArray): ByteArray {
        mSequence = (mSequence + 1) and 0xFF
        // MAC лампы мы не знаем, а нулевой адрес протокол принимает только с флагом tagged;
        // сообщение всё равно уходит на IP одной лампы
        return header(type, payload.size, tagged = true, sequence = mSequence).put(payload).array()
    }

    companion object {
        const val PORT = 56700
        private const val FLASH_STEP_MS = 400L

        /** Лампа дважды гаснет и загорается, чтобы её было видно среди соседних. */
        @Throws(IOException::class)
        fun flash(host: String) {
            DatagramSocket().use { socket ->
                val address = InetAddress.getByName(host)
                for ((index, on) in booleanArrayOf(false, true, false, true).withIndex()) {
                    val payload = powerPayload(on)
                    val data = header(TYPE_SET_POWER, payload.size, tagged = true, sequence = index).put(payload).array()
                    socket.send(DatagramPacket(data, data.size, address, PORT))
                    SystemClock.sleep(FLASH_STEP_MS)
                }
            }
        }

        private const val TYPE_GET_SERVICE = 2
        private const val TYPE_STATE_SERVICE = 3
        private const val TYPE_GET_LABEL = 23
        private const val TYPE_STATE_LABEL = 25
        private const val TYPE_SET_COLOR = 102
        private const val TYPE_SET_POWER = 117
        private const val HEADER_SIZE = 36
        private const val SOURCE = 0x55414C31
        /** Белый экрана по стандарту D65: так белое на картинке и на лампе совпадает. */
        private const val KELVIN = 6500

        /** Заголовок LIFX: кадр, адрес и тип сообщения; всё little-endian. */
        internal fun header(type: Int, payloadSize: Int, tagged: Boolean, sequence: Int): ByteBuffer {
            val buffer = ByteBuffer.allocate(HEADER_SIZE + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort((HEADER_SIZE + payloadSize).toShort())
            // protocol 1024, addressable, tagged для рассылки всем
            buffer.putShort((1024 or (1 shl 12) or (if (tagged) 1 shl 13 else 0)).toShort())
            buffer.putInt(SOURCE)
            buffer.putLong(0L)
            buffer.put(ByteArray(6))
            buffer.put(0)
            buffer.put(sequence.toByte())
            buffer.putLong(0L)
            buffer.putShort(type.toShort())
            buffer.putShort(0)
            return buffer
        }

        internal fun hsbk(r: Int, g: Int, b: Int, brightness: Int?): IntArray {
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val delta = maxC - minC
            val hue = when {
                delta == 0 -> 0f
                maxC == r -> 60f * (((g - b).toFloat() / delta).mod(6f))
                maxC == g -> 60f * ((b - r).toFloat() / delta + 2f)
                else -> 60f * ((r - g).toFloat() / delta + 4f)
            }
            val saturation = if (maxC == 0) 0f else delta.toFloat() / maxC
            val value = (brightness ?: maxC) / 255f
            return intArrayOf(
                (hue / 360f * 65535f).roundToInt().coerceIn(0, 65535),
                (saturation * 65535f).roundToInt(),
                (value * 65535f).roundToInt().coerceIn(0, 65535),
                KELVIN
            )
        }

        internal fun colorPayload(hsbk: IntArray, durationMs: Int): ByteArray =
            ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .put(0)
                .putShort(hsbk[0].toShort())
                .putShort(hsbk[1].toShort())
                .putShort(hsbk[2].toShort())
                .putShort(hsbk[3].toShort())
                .putInt(durationMs)
                .array()

        private fun powerPayload(on: Boolean): ByteArray =
            ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(if (on) 0xFFFF.toShort() else 0)
                .putInt(0)
                .array()

        /**
         * Лампы LIFX в сети: широковещательный GetService, у ответивших спрашиваем имя.
         * Блокирует примерно на [timeoutMs].
         */
        fun discover(timeoutMs: Int = 2000): List<Pair<String, String>> {
            val hosts = LinkedHashMap<String, String>()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val broadcast = InetAddress.getByName("255.255.255.255")
                val probe = header(TYPE_GET_SERVICE, 0, tagged = true, sequence = 0).array()
                socket.send(DatagramPacket(probe, probe.size, broadcast, PORT))
                val buffer = ByteArray(128)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (packet.length < HEADER_SIZE) continue
                    val reply = ByteBuffer.wrap(packet.data, 0, packet.length).order(ByteOrder.LITTLE_ENDIAN)
                    val host = packet.address.hostAddress ?: continue
                    when (reply.getShort(32).toInt() and 0xFFFF) {
                        TYPE_STATE_SERVICE -> if (host !in hosts) {
                            hosts[host] = "LIFX $host"
                            val ask = header(TYPE_GET_LABEL, 0, tagged = true, sequence = 1).array()
                            socket.send(DatagramPacket(ask, ask.size, packet.address, PORT))
                        }

                        TYPE_STATE_LABEL -> if (packet.length >= HEADER_SIZE + 32) {
                            val label = String(packet.data, HEADER_SIZE, 32, Charsets.UTF_8).trimEnd('\u0000')
                            if (label.isNotBlank()) hosts[host] = label
                        }
                    }
                }
            }
            return hosts.map { it.key to it.value }
        }
    }
}
