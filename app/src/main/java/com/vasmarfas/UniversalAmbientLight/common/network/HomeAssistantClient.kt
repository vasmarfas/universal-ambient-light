package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.ZoneColorExtractor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Выводит цвета экрана на лампы Home Assistant через его REST API: приложение зовёт
 * `light.turn_on` для сущностей, привязанных к зонам экрана. Своей интеграции на стороне
 * HA не нужно — работает с любой лампой, которую HA умеет включать.
 *
 * Ритм задаёт [HomeAssistantUpdatePolicy]: умным лампам обновления шлются редко и только
 * когда цвет зоны реально изменился, иначе Zigbee-сеть захлёбывается. Плавность между
 * редкими обновлениями обеспечивает сам HA параметром transition.
 */
class HomeAssistantClient(
    host: String,
    port: Int,
    token: String,
    lampsSpec: String?,
    updateIntervalMs: Long,
    changeThreshold: Int,
    private val mTransitionMs: Int,
    private val mBrightnessMode: String,
    private val mBrightnessMax: Int,
    darkOffEnabled: Boolean,
    darkThreshold: Int,
    private val mTurnOffLights: Boolean,
) : HyperionClient, StreamingClient {

    private val mBaseUrl = baseUrl(host, port)
    private val mToken = token.trim()

    /** Сущности по зонам — лампы одной зоны включаются одним вызовом. */
    private val mEntitiesByZone: Map<HomeAssistantZone, List<String>> =
        HomeAssistantLamp.parseList(lampsSpec)
            .groupBy({ it.zone }, { it.entityId })

    private val mPolicy = HomeAssistantUpdatePolicy(
        updateIntervalMs, changeThreshold, darkOffEnabled, darkThreshold
    )

    private val mZoneColors = IntArray(ZoneColorExtractor.ZONE_COUNT * 3)

    @Volatile
    private var mConnected = false

    @Volatile
    private var mPaused = false

    // Лампы уже погашены нами: гашение зовётся серией (пять чёрных кадров подряд при
    // остановке), а Zigbee-сети хватает и одного turn_off
    @Volatile
    private var mLightsOff = false

    private var mFailures = 0

    init {
        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        if (mToken.isEmpty()) {
            throw IOException("Home Assistant access token is not set")
        }
        if (mEntitiesByZone.isEmpty()) {
            throw IOException("No Home Assistant lights are assigned to zones")
        }

        // GET /api/ и отвечает быстро, и проверяет токен; на стороне HA ничего не меняет
        val code = request("GET", "/api/", null)
        when {
            code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    code == HttpURLConnection.HTTP_FORBIDDEN ->
                throw IOException("Home Assistant rejected the access token (HTTP $code)")

            code !in 200..299 ->
                throw IOException("Home Assistant answered HTTP $code at $mBaseUrl")
        }

        mConnected = true
        Log.i(TAG, "Connected to Home Assistant at $mBaseUrl, zones: ${mEntitiesByZone.keys}")
    }

    override fun isConnected(): Boolean = mConnected

    override fun pauseSending() {
        mPaused = true
    }

    override fun resumeSending() {
        mPaused = false
        // После паузы лампы могли гаситься и переключаться руками — первый кадр уходит заново
        mPolicy.reset()
    }

    @Throws(IOException::class)
    override fun disconnect() {
        if (mConnected && mTurnOffLights && !mLightsOff) {
            turnOffAll()
        }
        mConnected = false
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        // Гашение ленты (выключение экрана, остановка): лампам это «выключиться», но
        // только если пользователь не просил оставлять их в покое
        if (mTurnOffLights && !mLightsOff) {
            turnOffAll()
            mLightsOff = true
            mPolicy.reset()
        }
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
        if (!mConnected || mPaused) return
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        sendTurnOn(mEntitiesByZone.values.flatten(), r, g, b)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(
        data: ByteArray,
        width: Int,
        height: Int,
        priority: Int,
        duration_ms: Int,
    ) {
        if (!mConnected || mPaused) return
        if (!ZoneColorExtractor.extract(data, width, height, mZoneColors)) return

        val updates = mPolicy.plan(System.currentTimeMillis(), mZoneColors, mEntitiesByZone.keys)
        for (update in updates) {
            val entities = mEntitiesByZone[update.zone] ?: continue
            if (update.turnOff) {
                sendTurnOff(entities)
            } else {
                sendTurnOn(entities, update.red, update.green, update.blue)
            }
        }
    }

    private fun turnOffAll() {
        sendTurnOff(mEntitiesByZone.values.flatten())
    }

    private fun sendTurnOn(entities: List<String>, r: Int, g: Int, b: Int) {
        if (entities.isEmpty()) return
        mLightsOff = false
        val body = JSONObject()
        body.put("entity_id", JSONArray(entities))
        if (mTransitionMs > 0) body.put("transition", mTransitionMs / 1000.0)

        if (mBrightnessMode == BRIGHTNESS_MODE_SCREEN) {
            // Яркость лампы следует за яркостью зоны, а цвет нормализуется до чистого тона:
            // HA принимает их раздельно, и тёмно-красный — это красный на малой яркости
            val luma = maxOf(r, g, b)
            body.put("brightness", (luma * mBrightnessMax / 255).coerceAtLeast(1))
            if (luma > 0) {
                body.put(
                    "rgb_color",
                    JSONArray(intArrayOf(r * 255 / luma, g * 255 / luma, b * 255 / luma))
                )
            }
        } else {
            body.put("rgb_color", JSONArray(intArrayOf(r, g, b)))
        }

        callService("turn_on", body)
    }

    private fun sendTurnOff(entities: List<String>) {
        if (entities.isEmpty()) return
        val body = JSONObject()
        body.put("entity_id", JSONArray(entities))
        if (mTransitionMs > 0) body.put("transition", mTransitionMs / 1000.0)
        callService("turn_off", body)
    }

    private fun callService(service: String, body: JSONObject) {
        try {
            val code = request("POST", "/api/services/light/$service", body.toString())
            if (code in 200..299) {
                mFailures = 0
            } else {
                registerFailure("HTTP $code from light.$service")
            }
        } catch (e: IOException) {
            registerFailure(e.message ?: "I/O error")
        }
    }

    /**
     * Один сбой — не повод хоронить соединение: HA мог просто перезапускаться. Смерть
     * объявляется после нескольких подряд, дальше клиент пересоздаст общий механизм
     * восстановления в HyperionThread.
     */
    private fun registerFailure(reason: String) {
        mFailures++
        Log.w(TAG, "Home Assistant call failed ($mFailures/$MAX_FAILURES): $reason")
        if (mFailures >= MAX_FAILURES) {
            mConnected = false
        }
    }

    @Throws(IOException::class)
    private fun request(method: String, path: String, body: String?): Int {
        val connection = URL(mBaseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $mToken")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            // Тело дочитывается, чтобы соединение вернулось в пул keep-alive
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.use { it.readBytes() }
            return code
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "HomeAssistantClient"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 5000
        private const val MAX_FAILURES = 3

        const val BRIGHTNESS_MODE_SCREEN = "screen"
        const val BRIGHTNESS_MODE_KEEP = "keep"

        /**
         * Базовый URL из настроек. Хост с явной схемой (Nabu Casa, свой https) берётся как
         * есть, порт тогда не подставляется; голый адрес превращается в http://host:port.
         */
        fun baseUrl(host: String, port: Int): String {
            val trimmed = host.trim().trimEnd('/')
            return if (trimmed.contains("://")) trimmed else "http://$trimmed:$port"
        }

        /**
         * Список ламп из HA: пары entity_id → отображаемое имя. Блокирует — звать только
         * с фонового потока. Используется диалогом выбора ламп в настройках.
         */
        @Throws(IOException::class)
        fun fetchLights(host: String, port: Int, token: String): List<Pair<String, String>> {
            val connection =
                URL(baseUrl(host, port) + "/api/states").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    code == HttpURLConnection.HTTP_FORBIDDEN
                ) {
                    throw IOException("HTTP $code — check the access token")
                }
                if (code !in 200..299) throw IOException("HTTP $code")

                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val states = JSONArray(text)
                val lights = ArrayList<Pair<String, String>>()
                for (i in 0 until states.length()) {
                    val state = states.optJSONObject(i) ?: continue
                    val entityId = state.optString("entity_id")
                    if (!entityId.startsWith("light.")) continue
                    val name = state.optJSONObject("attributes")
                        ?.optString("friendly_name")
                        ?.takeIf { it.isNotBlank() }
                        ?: entityId.removePrefix("light.")
                    lights.add(entityId to name)
                }
                lights.sortBy { it.second.lowercase() }
                return lights
            } catch (e: JSONException) {
                throw IOException("Unexpected answer from Home Assistant", e)
            } finally {
                connection.disconnect()
            }
        }
    }
}
