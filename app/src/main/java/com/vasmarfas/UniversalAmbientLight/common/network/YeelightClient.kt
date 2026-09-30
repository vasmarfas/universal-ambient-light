package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Лампы и ленты Yeelight по их локальному протоколу: JSON по TCP на порт 55443. В
 * приложении Yeelight у лампы должен быть включён «Контроль по локальной сети».
 *
 * Обычное соединение лампа ограничивает минутной квотой команд, поэтому каждая лампа
 * переводится в «музыкальный режим»: она сама подключается к нам и дальше принимает
 * команды без квоты. Если лампа его не умеет, остаётся обычное соединение.
 */
class YeelightClient(
    lampsSpec: String?,
    settings: LampSettings,
) : ZoneLampClient(lampsSpec, settings) {

    private val mLinks = HashMap<String, Link>()
    private var mNextId = 1

    // set_rgb выключенная лампа отвергает, поэтому без яркости по картинке её надо сначала
    // включить; по TCP команда не теряется, и включать каждый раз незачем
    private val mPowered = HashSet<String>()

    private class Link(val control: Socket, var output: OutputStream, var music: Socket? = null)

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() {
        for (lamp in mLampsByZone.values.flatten()) {
            mLinks[lamp.entityId] = connect(lamp.entityId)
        }
    }

    @Throws(IOException::class)
    override fun sendColor(lamps: List<HomeAssistantLamp>, r: Int, g: Int, b: Int, brightness: Int?) {
        val rgb = ((r shl 16) or (g shl 8) or b).coerceAtLeast(1)
        val command = if (brightness != null) {
            // set_scene включает лампу и задаёт цвет с яркостью одной командой
            command("set_scene", JSONArray().put("color").put(rgb).put((brightness * 100 / 255).coerceIn(1, 100)))
        } else {
            val off = lamps.filter { it.entityId !in mPowered }
            if (off.isNotEmpty()) send(off, command("set_power", JSONArray().put("on").put(effect()).put(duration())))
            command("set_rgb", JSONArray().put(rgb).put(effect()).put(duration()))
        }
        send(lamps, command)
        for (lamp in lamps) mPowered.add(lamp.entityId)
    }

    @Throws(IOException::class)
    override fun sendOff(lamps: List<HomeAssistantLamp>) {
        send(lamps, command("set_power", JSONArray().put("off").put(effect()).put(duration())))
        for (lamp in lamps) mPowered.remove(lamp.entityId)
    }

    override fun close() {
        for (link in mLinks.values) {
            closeQuietly(link.music)
            closeQuietly(link.control)
        }
        mLinks.clear()
    }

    private fun send(lamps: List<HomeAssistantLamp>, line: String) {
        val data = line.toByteArray(Charsets.UTF_8)
        for (lamp in lamps) {
            val link = mLinks[lamp.entityId] ?: continue
            link.output.write(data)
            link.output.flush()
        }
    }

    private fun command(method: String, params: JSONArray): String =
        JSONObject().put("id", mNextId++).put("method", method).put("params", params).toString() + "\r\n"

    // Плавный переход короче 30 мс лампа не принимает
    private fun effect() = if (transitionMs >= MIN_SMOOTH_MS) "smooth" else "sudden"

    private fun duration() = transitionMs.coerceAtLeast(MIN_SMOOTH_MS)

    @Throws(IOException::class)
    private fun connect(host: String): Link {
        val control = Socket()
        control.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
        control.tcpNoDelay = true
        val link = Link(control, control.getOutputStream())
        try {
            ServerSocket(0).use { server ->
                server.soTimeout = MUSIC_TIMEOUT_MS
                // Лампа подключается к адресу, с которого мы к ней пришли
                val self = control.localAddress.hostAddress
                val request = command("set_music", JSONArray().put(1).put(self).put(server.localPort))
                link.output.write(request.toByteArray(Charsets.UTF_8))
                link.output.flush()
                val music = server.accept()
                music.tcpNoDelay = true
                link.music = music
                link.output = music.getOutputStream()
            }
        } catch (_: SocketTimeoutException) {
            // Старые лампы музыкального режима не знают: остаётся обычное соединение и квота
            Log.i(TAG, "Yeelight $host did not open music mode, using the command connection")
        }
        return link
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Соединение уже закрыла лампа.
        }
    }

    companion object {
        private const val TAG = "YeelightClient"
        const val PORT = 55443
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val MUSIC_TIMEOUT_MS = 3000
        private const val MIN_SMOOTH_MS = 30

        /**
         * Короткий цветовой поток: синий, потом красный, три раза, и лампа возвращается к
         * прежнему состоянию. Так её видно среди соседних.
         */
        @Throws(IOException::class)
        fun flash(host: String) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                val params = JSONArray().put("cf").put(6).put(0).put("500,1,100,100,500,1,16711696,10")
                val line = JSONObject().put("id", 1).put("method", "set_scene").put("params", params).toString() + "\r\n"
                socket.getOutputStream().write(line.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
        }

        private const val DISCOVERY_ADDRESS = "239.255.255.250"
        private const val DISCOVERY_PORT = 1982

        /**
         * Лампы Yeelight в сети: мультикаст-запрос в духе SSDP, отвечает каждая лампа с
         * включённым локальным контролем. Блокирует примерно на [timeoutMs].
         */
        fun discover(timeoutMs: Int = 2000): List<Pair<String, String>> {
            val found = LinkedHashMap<String, String>()
            MulticastSocket().use { socket ->
                socket.soTimeout = 300
                val request = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $DISCOVERY_ADDRESS:$DISCOVERY_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "ST: wifi_bulb\r\n").toByteArray()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(DISCOVERY_ADDRESS), DISCOVERY_PORT))
                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val headers = String(packet.data, 0, packet.length).lines()
                        .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 } }
                        .associate { (key, value) -> key.trim().lowercase() to value.trim() }
                    val host = headers["location"]?.substringAfter("//")?.substringBefore(':')
                        ?: packet.address.hostAddress ?: continue
                    val name = headers["name"].orEmpty().ifEmpty { "Yeelight ${headers["model"].orEmpty()}".trim() }
                    found[host] = name
                }
            }
            return found.map { it.key to it.value }
        }
    }
}
