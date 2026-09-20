package com.vasmarfas.UniversalAmbientLight.common.network

import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Лампы WiZ (Philips WiZ, Trio, Lidl и другие на той же платформе): JSON по UDP на порт
 * 38899, облако не нужно. Лампа в списке - это её IP-адрес.
 */
class WizClient(
    lampsSpec: String?,
    settings: LampSettings,
) : ZoneLampClient(lampsSpec, settings) {

    private var mSocket: DatagramSocket? = null
    private val mAddresses = HashMap<String, InetAddress>()

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
        val params = JSONObject().put("state", true).put("r", r).put("g", g).put("b", b)
        // Ниже 10% WiZ яркость не принимает
        if (brightness != null) params.put("dimming", (brightness * 100 / 255).coerceIn(10, 100))
        send(lamps, JSONObject().put("method", "setPilot").put("params", params))
    }

    @Throws(IOException::class)
    override fun sendOff(lamps: List<HomeAssistantLamp>) {
        send(lamps, JSONObject().put("method", "setPilot").put("params", JSONObject().put("state", false)))
    }

    override fun close() {
        mSocket?.close()
        mSocket = null
    }

    private fun send(lamps: List<HomeAssistantLamp>, message: JSONObject) {
        val socket = mSocket ?: throw IOException("Socket is closed")
        val data = message.toString().toByteArray(Charsets.UTF_8)
        for (lamp in lamps) {
            val address = mAddresses[lamp.entityId] ?: continue
            socket.send(DatagramPacket(data, data.size, address, PORT))
        }
    }

    companion object {
        const val PORT = 38899
        private const val FLASH_STEP_MS = 400L

        /** Лампа дважды гаснет и загорается, чтобы её было видно среди соседних. */
        @Throws(IOException::class)
        fun flash(host: String) {
            DatagramSocket().use { socket ->
                val address = InetAddress.getByName(host)
                for (on in booleanArrayOf(false, true, false, true)) {
                    val data = JSONObject().put("method", "setPilot")
                        .put("params", JSONObject().put("state", on)).toString().toByteArray()
                    socket.send(DatagramPacket(data, data.size, address, PORT))
                    SystemClock.sleep(FLASH_STEP_MS)
                }
            }
        }

        /**
         * Лампы WiZ в сети: широковещательный getPilot, отвечает каждая лампа. Имя в сети лампа
         * не сообщает - показываем модуль и MAC. Блокирует на [timeoutMs].
         */
        fun discover(timeoutMs: Int = 2000): List<Pair<String, String>> {
            val found = LinkedHashMap<String, String>()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val request = """{"method":"getPilot","params":{}}""".toByteArray()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), PORT))
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val host = packet.address.hostAddress ?: continue
                    val reply = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull()
                    val mac = reply?.optJSONObject("result")?.optString("mac").orEmpty()
                    found[host] = if (mac.isEmpty()) "WiZ $host" else "WiZ ${mac.takeLast(6).uppercase()}"
                }
            }
            return found.map { it.key to it.value }
        }
    }
}
