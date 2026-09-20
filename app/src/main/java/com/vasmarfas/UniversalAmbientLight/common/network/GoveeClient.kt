package com.vasmarfas.UniversalAmbientLight.common.network

import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Лампы и ленты Govee через их LAN API: JSON по UDP, облако не нужно. В приложении Govee
 * Home у устройства должен быть включён LAN Control. Лампа в списке - её IP-адрес.
 */
class GoveeClient(
    lampsSpec: String?,
    settings: LampSettings,
) : ZoneLampClient(lampsSpec, settings) {

    private var mSocket: DatagramSocket? = null
    private val mAddresses = HashMap<String, InetAddress>()

    // Цвет выключенную лампу не включает; команда на включение уходит один раз, а после
    // гашения - снова
    private val mPowered = HashSet<String>()

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
        val off = lamps.filter { it.entityId !in mPowered }
        if (off.isNotEmpty()) send(off, command("turn", JSONObject().put("value", 1)))
        if (brightness != null) {
            send(lamps, command("brightness", JSONObject().put("value", (brightness * 100 / 255).coerceIn(1, 100))))
        }
        val color = JSONObject().put("r", r).put("g", g).put("b", b)
        send(lamps, command("colorwc", JSONObject().put("color", color).put("colorTemInKelvin", 0)))
        for (lamp in lamps) mPowered.add(lamp.entityId)
    }

    @Throws(IOException::class)
    override fun sendOff(lamps: List<HomeAssistantLamp>) {
        send(lamps, command("turn", JSONObject().put("value", 0)))
        for (lamp in lamps) mPowered.remove(lamp.entityId)
    }

    override fun close() {
        mSocket?.close()
        mSocket = null
    }

    private fun send(lamps: List<HomeAssistantLamp>, data: ByteArray) {
        val socket = mSocket ?: throw IOException("Socket is closed")
        for (lamp in lamps) {
            val address = mAddresses[lamp.entityId] ?: continue
            socket.send(DatagramPacket(data, data.size, address, CONTROL_PORT))
        }
    }

    companion object {
        private const val CONTROL_PORT = 4003
        private const val SCAN_PORT = 4001
        private const val REPLY_PORT = 4002
        private const val SCAN_ADDRESS = "239.255.255.250"
        private const val FLASH_STEP_MS = 400L

        private fun command(name: String, data: JSONObject): ByteArray =
            JSONObject().put("msg", JSONObject().put("cmd", name).put("data", data)).toString().toByteArray()

        /**
         * Устройства Govee в сети: мультикаст-запрос scan, ответы приходят на порт 4002
         * отправителя. Блокирует примерно на [timeoutMs].
         */
        @Throws(IOException::class)
        fun discover(timeoutMs: Int = 2000): List<Pair<String, String>> {
            val found = LinkedHashMap<String, String>()
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 300
                socket.bind(InetSocketAddress(REPLY_PORT))
                val request = command("scan", JSONObject().put("account_topic", "reserve"))
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(SCAN_ADDRESS), SCAN_PORT))
                val buffer = ByteArray(1024)
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val data = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull()
                        ?.optJSONObject("msg")?.takeIf { it.optString("cmd") == "scan" }
                        ?.optJSONObject("data") ?: continue
                    val host = data.optString("ip").ifEmpty { packet.address.hostAddress.orEmpty() }
                    if (host.isNotEmpty()) found[host] = "Govee ${data.optString("sku")}".trim()
                }
            }
            return found.map { it.key to it.value }
        }

        /** Лампа дважды гаснет и загорается, чтобы её было видно среди соседних. */
        @Throws(IOException::class)
        fun flash(host: String) {
            DatagramSocket().use { socket ->
                val address = InetAddress.getByName(host)
                for (on in intArrayOf(0, 1, 0, 1)) {
                    val data = command("turn", JSONObject().put("value", on))
                    socket.send(DatagramPacket(data, data.size, address, CONTROL_PORT))
                    SystemClock.sleep(FLASH_STEP_MS)
                }
            }
        }
    }
}
