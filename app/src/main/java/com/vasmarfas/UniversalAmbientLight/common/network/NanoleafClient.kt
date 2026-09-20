package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Панели Nanoleaf (Aurora/Light Panels, Canvas, Shapes, Elements, Lines) через их Open API:
 * по HTTP включается режим внешнего управления, дальше цвета панелей идут по UDP.
 *
 * Панели висят на стене как маленький экран, поэтому каждая берёт цвет того места кадра,
 * где она сама на стене относительно остальных: левая верхняя - левого верхнего угла и так
 * далее. Сами панели плавно переходят между цветами, и десяти кадров в секунду им хватает.
 */
class NanoleafClient(
    private val mHost: String,
    port: Int,
    private val mToken: String,
) : HyperionClient, StreamingClient {

    private class Panel(val id: Int, val u: Float, val v: Float)

    private val mPort = if (port > 0) port else DEFAULT_PORT
    private val mBase get() = "http://${mHost.trim()}:$mPort/api/v1/${mToken.trim()}"
    private var mPanels: List<Panel> = emptyList()
    private val mLink = UdpLink(mHost.trim(), STREAM_PORT)
    private var mLastSendMs = 0L

    @Volatile
    private var mConnected = false

    @Volatile
    private var mPaused = false

    init {
        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        if (mToken.isBlank()) throw IOException("Nanoleaf is not paired")
        val info = try {
            JSONObject(request("GET", mBase, null))
        } catch (e: JSONException) {
            throw IOException("Unexpected answer from Nanoleaf", e)
        }
        val positions = info.optJSONObject("panelLayout")?.optJSONObject("layout")
            ?.optJSONArray("positionData") ?: throw IOException("Nanoleaf did not report its layout")
        val raw = (0 until positions.length()).mapNotNull { positions.optJSONObject(it) }
            .filter { it.optInt("shapeType") !in CONTROLLER_SHAPES }
        if (raw.isEmpty()) throw IOException("No Nanoleaf panels found")
        val minX = raw.minOf { it.optInt("x") }
        val maxX = raw.maxOf { it.optInt("x") }
        val minY = raw.minOf { it.optInt("y") }
        val maxY = raw.maxOf { it.optInt("y") }
        mPanels = raw.map {
            Panel(
                it.optInt("panelId"),
                if (maxX > minX) (it.optInt("x") - minX).toFloat() / (maxX - minX) else 0.5f,
                // Ось y у Nanoleaf смотрит вверх, у кадра - вниз
                if (maxY > minY) 1f - (it.optInt("y") - minY).toFloat() / (maxY - minY) else 0.5f
            )
        }
        val display = JSONObject().put(
            "write",
            JSONObject().put("command", "display").put("animType", "extControl").put("extControlVersion", "v2")
        )
        request("PUT", "$mBase/effects", display.toString())
        mLink.open()
        mConnected = true
        Log.i(TAG, "Nanoleaf streaming to ${mPanels.size} panels")
    }

    override fun isConnected(): Boolean = mConnected

    override fun pauseSending() {
        mPaused = true
    }

    override fun resumeSending() {
        mPaused = false
    }

    @Throws(IOException::class)
    override fun disconnect() {
        if (mConnected) {
            try {
                mLink.send(frame { 0 })
            } catch (e: IOException) {
                Log.w(TAG, "Final black frame failed: ${e.message}")
            }
        }
        mConnected = false
        mLink.close()
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        if (mConnected) mLink.send(frame { 0 })
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(0)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        if (mConnected && !mPaused) mLink.send(frame { color and 0xFFFFFF })
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (!mConnected || mPaused || width <= 0 || height <= 0) return
        val now = System.currentTimeMillis()
        if (now - mLastSendMs < FRAME_INTERVAL_MS) return
        mLastSendMs = now
        mLink.send(frame { panel -> sample(data, width, height, panel) })
    }

    private fun frame(colorOf: (Panel) -> Int): ByteArray {
        val packet = ByteArray(2 + mPanels.size * 8)
        packet[0] = (mPanels.size shr 8).toByte()
        packet[1] = mPanels.size.toByte()
        var offset = 2
        for (panel in mPanels) {
            val color = colorOf(panel)
            packet[offset] = (panel.id shr 8).toByte()
            packet[offset + 1] = panel.id.toByte()
            packet[offset + 2] = (color shr 16).toByte()
            packet[offset + 3] = (color shr 8).toByte()
            packet[offset + 4] = color.toByte()
            // Белый канал и переход в десятых долях секунды
            packet[offset + 5] = 0
            packet[offset + 6] = 0
            packet[offset + 7] = TRANSITION.toByte()
            offset += 8
        }
        return packet
    }

    /** Средний цвет квадрата кадра вокруг места панели на стене. */
    private fun sample(data: ByteArray, width: Int, height: Int, panel: Panel): Int {
        val half = maxOf(width, height) / 10
        val cx = (panel.u * (width - 1)).toInt()
        val cy = (panel.v * (height - 1)).toInt()
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in (cy - half).coerceAtLeast(0)..(cy + half).coerceAtMost(height - 1)) {
            var i = (y * width + (cx - half).coerceAtLeast(0)) * 3
            for (x in (cx - half).coerceAtLeast(0)..(cx + half).coerceAtMost(width - 1)) {
                if (i + 2 >= data.size) break
                r += data[i].toInt() and 0xFF
                g += data[i + 1].toInt() and 0xFF
                b += data[i + 2].toInt() and 0xFF
                count++
                i += 3
            }
        }
        if (count == 0) return 0
        return ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
    }

    companion object {
        private const val TAG = "NanoleafClient"
        const val DEFAULT_PORT = 16021
        private const val STREAM_PORT = 60222
        private const val FRAME_INTERVAL_MS = 100L
        private const val TRANSITION = 1

        /**
         * Без светодиодов: модуль Rhythm, контроллер Shapes, соединитель Lines, заглушка
         * контроллера и разъём питания. Им цвет не шлётся.
         */
        private val CONTROLLER_SHAPES = setOf(1, 12, 16, 19, 20)

        /**
         * Выдаёт ключ доступа: работает в течение 30 секунд после того, как на панелях
         * подержали кнопку питания 5–7 секунд, иначе Nanoleaf отвечает 403.
         */
        @Throws(IOException::class)
        fun pair(host: String, port: Int = DEFAULT_PORT): String {
            val reply = request("POST", "http://${host.trim()}:$port/api/v1/new", null)
            return try {
                JSONObject(reply).getString("auth_token")
            } catch (e: JSONException) {
                throw IOException("Unexpected answer from Nanoleaf", e)
            }
        }

        @Throws(IOException::class)
        private fun request(method: String, url: String, body: String?): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 3000
                connection.readTimeout = 5000
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code == HttpURLConnection.HTTP_FORBIDDEN) throw PairingException()
                if (code !in 200..299) throw IOException("HTTP $code from Nanoleaf")
                return text
            } finally {
                connection.disconnect()
            }
        }

        /** Кнопку питания на панелях не держали, или ключ устарел. */
        class PairingException : IOException("Hold the power button on the Nanoleaf controller for 5 to 7 seconds")
    }
}
