package com.vasmarfas.UniversalAmbientLight.common.network

import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Лампы Zigbee через Zigbee2MQTT: цвет публикуется в тему `<база>/<имя>/set` брокера MQTT,
 * сам Zigbee2MQTT уже передаёт его лампе. Лампа в списке - её friendly_name.
 */
class Zigbee2MqttClient(
    host: String,
    port: Int,
    username: String,
    password: String,
    private val mBaseTopic: String,
    lampsSpec: String?,
    settings: LampSettings,
) : ZoneLampClient(lampsSpec, settings) {

    private val mLink = MqttLink(host, port, clientId(), username, password)
    private val mPing = Executors.newSingleThreadScheduledExecutor { Thread(it, "mqtt-ping") }

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() {
        mLink.connect()
        // На статичной картинке лампам нечего слать, а брокер рвёт молчащее соединение
        mPing.scheduleWithFixedDelay({
            try {
                mLink.ping()
            } catch (e: IOException) {
                Log.w(TAG, "MQTT ping failed: ${e.message}")
            }
        }, PING_PERIOD_S, PING_PERIOD_S, TimeUnit.SECONDS)
    }

    @Throws(IOException::class)
    override fun sendColor(lamps: List<HomeAssistantLamp>, r: Int, g: Int, b: Int, brightness: Int?) {
        val state = JSONObject()
            .put("state", "ON")
            .put("color", JSONObject().put("r", r).put("g", g).put("b", b))
        if (brightness != null) state.put("brightness", brightness.coerceIn(1, 254))
        if (transitionMs > 0) state.put("transition", transitionMs / 1000.0)
        publish(lamps, state)
    }

    @Throws(IOException::class)
    override fun sendOff(lamps: List<HomeAssistantLamp>) {
        val state = JSONObject().put("state", "OFF")
        if (transitionMs > 0) state.put("transition", transitionMs / 1000.0)
        publish(lamps, state)
    }

    override fun close() {
        mPing.shutdownNow()
        mLink.close()
    }

    @Throws(IOException::class)
    private fun publish(lamps: List<HomeAssistantLamp>, payload: JSONObject) {
        val text = payload.toString()
        for (lamp in lamps) mLink.publish(setTopic(mBaseTopic, lamp.entityId), text)
    }

    companion object {
        private const val TAG = "Zigbee2MqttClient"
        const val DEFAULT_BASE_TOPIC = "zigbee2mqtt"
        private const val PING_PERIOD_S = 30L
        private const val FLASH_STEP_MS = 400L

        private fun clientId(): String =
            "uamblight-${Build.MODEL.filter { it.isLetterOrDigit() }.take(12)}-${Random.nextInt(0x10000).toString(16)}"

        private fun setTopic(baseTopic: String, name: String) = "${baseTopic.trim().trimEnd('/')}/$name/set"

        /**
         * Лампы из сохранённого на брокере списка устройств `<база>/bridge/devices`.
         * Блокирует до [timeoutMs], звать с фонового потока.
         */
        @Throws(IOException::class)
        fun lights(
            host: String,
            port: Int,
            username: String,
            password: String,
            baseTopic: String,
            timeoutMs: Int = 4000,
        ): List<Pair<String, String>> {
            val link = MqttLink(host, port, clientId(), username, password)
            try {
                link.connect()
                link.subscribe("${baseTopic.trim().trimEnd('/')}/bridge/devices")
                val (_, payload) = link.receive(timeoutMs)
                    ?: throw IOException("Zigbee2MQTT did not publish its device list under \"$baseTopic\"")
                return parseLights(String(payload, Charsets.UTF_8))
            } finally {
                link.close()
            }
        }

        /** Устройства со светом: у них в definition.exposes есть элемент с type = light. */
        @Throws(IOException::class)
        internal fun parseLights(json: String): List<Pair<String, String>> {
            val devices = try {
                JSONArray(json)
            } catch (e: JSONException) {
                throw IOException("Unexpected device list from Zigbee2MQTT", e)
            }
            val lights = ArrayList<Pair<String, String>>()
            for (i in 0 until devices.length()) {
                val device = devices.optJSONObject(i) ?: continue
                val name = device.optString("friendly_name")
                val definition = device.optJSONObject("definition") ?: continue
                val exposes = definition.optJSONArray("exposes") ?: continue
                val isLight = (0 until exposes.length()).any { exposes.optJSONObject(it)?.optString("type") == "light" }
                if (name.isEmpty() || !isLight) continue
                val description = definition.optString("description").ifEmpty { definition.optString("model") }
                lights += name to "$name · $description".trimEnd(' ', '·')
            }
            return lights.sortedBy { it.first.lowercase() }
        }

        /** Лампа дважды гаснет и загорается, чтобы её было видно среди соседних. */
        @Throws(IOException::class)
        fun flash(host: String, port: Int, username: String, password: String, baseTopic: String, name: String) {
            val link = MqttLink(host, port, clientId(), username, password)
            try {
                link.connect()
                for (on in booleanArrayOf(false, true, false, true)) {
                    link.publish(setTopic(baseTopic, name), JSONObject().put("state", if (on) "ON" else "OFF").toString())
                    SystemClock.sleep(FLASH_STEP_MS)
                }
            } finally {
                link.close()
            }
        }
    }
}
