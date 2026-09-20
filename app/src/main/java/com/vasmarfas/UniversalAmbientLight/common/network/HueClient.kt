package com.vasmarfas.UniversalAmbientLight.common.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow

/**
 * Philips Hue через REST API моста. Лампа в списке - её номер на мосту, доступ даёт имя
 * пользователя, полученное нажатием кнопки на мосту (см. [pair]).
 *
 * Мост принимает около десяти команд в секунду на все лампы вместе, поэтому ритм задаёт
 * общая политика ламп, а плавность между командами даёт сам мост параметром transitiontime.
 */
class HueClient(
    host: String,
    private val mUsername: String,
    lampsSpec: String?,
    settings: LampSettings,
) : ZoneLampClient(lampsSpec, settings) {

    private val mBase = "http://${host.trim()}/api/${mUsername.trim()}"
    private val mGamuts = HashMap<String, FloatArray>()

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() {
        if (mUsername.isBlank()) throw IOException("The Hue bridge is not paired")
        val lights = request("GET", "$mBase/lights", null)
        val json = try {
            JSONObject(lights)
        } catch (e: JSONException) {
            // Неверное имя пользователя мост возвращает массивом с ошибкой, а не объектом
            throw IOException("The Hue bridge rejected the pairing: ${errorOf(lights)}", e)
        }
        for (id in json.keys()) {
            val light = json.optJSONObject(id) ?: continue
            val gamut = light.optJSONObject("capabilities")?.optJSONObject("control")?.optJSONArray("colorgamut")
            mGamuts[id] = if (gamut != null) {
                FloatArray(6) { gamut.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
            } else {
                // Старые мосты охват не сообщают, у первых поколений ламп он узнаётся по модели
                when (light.optString("modelid")) {
                    in GAMUT_A_MODELS -> GAMUT_A
                    in GAMUT_B_MODELS -> GAMUT_B
                    else -> GAMUT_C
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun sendColor(lamps: List<HomeAssistantLamp>, r: Int, g: Int, b: Int, brightness: Int?) {
        for (lamp in lamps) {
            val xyb = rgbToXy(r, g, b, mGamuts[lamp.entityId] ?: GAMUT_C)
            val state = JSONObject()
                .put("on", true)
                .put("xy", JSONArray().put(xyb[0].toDouble()).put(xyb[1].toDouble()))
                .put("transitiontime", transitionMs / 100)
            // Без режима «яркость по картинке» лампа держит яркость, выставленную в Hue
            if (brightness != null) state.put("bri", brightness.coerceIn(1, 254))
            request("PUT", "$mBase/lights/${lamp.entityId}/state", state.toString())
        }
    }

    @Throws(IOException::class)
    override fun sendOff(lamps: List<HomeAssistantLamp>) {
        val state = JSONObject().put("on", false).put("transitiontime", transitionMs / 100).toString()
        for (lamp in lamps) request("PUT", "$mBase/lights/${lamp.entityId}/state", state)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 5000
        private const val ERROR_LINK_BUTTON = 101

        /** Треугольник Gamut C - у большинства ламп Hue последних поколений. */
        private val GAMUT_C = floatArrayOf(0.6915f, 0.3083f, 0.17f, 0.7f, 0.1532f, 0.0475f)
        private val GAMUT_A = floatArrayOf(0.704f, 0.296f, 0.2151f, 0.7106f, 0.138f, 0.08f)
        private val GAMUT_B = floatArrayOf(0.675f, 0.322f, 0.409f, 0.518f, 0.167f, 0.04f)

        /** Лампы и светильники первых поколений с треугольниками A и B. */
        private val GAMUT_A_MODELS = setOf(
            "LLC001", "LLC005", "LLC006", "LLC007", "LLC010", "LLC011", "LLC012", "LLC013", "LLC014", "LST001"
        )
        private val GAMUT_B_MODELS = setOf("LCT001", "LCT002", "LCT003", "LCT007", "LLM001")

        /** Кнопку на мосту не нажали - повторить после нажатия. */
        class LinkButtonException : IOException("Press the button on the Hue bridge")

        /**
         * Регистрирует приложение на мосту. Работает в течение 30 секунд после нажатия
         * круглой кнопки на мосту; до этого бросает [LinkButtonException].
         */
        @Throws(IOException::class)
        fun pair(host: String, deviceName: String): String {
            val body = JSONObject().put("devicetype", "uamblight#${deviceName.take(19)}").toString()
            val reply = request("POST", "http://${host.trim()}/api", body)
            val entry = try {
                JSONArray(reply).optJSONObject(0)
            } catch (e: JSONException) {
                throw IOException("Unexpected answer from the Hue bridge", e)
            }
            entry?.optJSONObject("success")?.optString("username")?.takeIf { it.isNotEmpty() }?.let { return it }
            if (entry?.optJSONObject("error")?.optInt("type") == ERROR_LINK_BUTTON) throw LinkButtonException()
            throw IOException(errorOf(reply))
        }

        /** Один «вдох» лампы, чтобы найти её в комнате. */
        @Throws(IOException::class)
        fun flash(host: String, username: String, id: String) {
            request("PUT", "http://${host.trim()}/api/${username.trim()}/lights/$id/state", """{"alert":"select"}""")
        }

        /** Лампы моста: номер и имя. Блокирует, звать с фонового потока. */
        @Throws(IOException::class)
        fun lights(host: String, username: String): List<Pair<String, String>> {
            val reply = request("GET", "http://${host.trim()}/api/${username.trim()}/lights", null)
            val json = try {
                JSONObject(reply)
            } catch (e: JSONException) {
                throw IOException(errorOf(reply), e)
            }
            return json.keys().asSequence()
                .map { id -> id to (json.optJSONObject(id)?.optString("name").orEmpty().ifEmpty { "Hue $id" }) }
                .sortedBy { it.second.lowercase() }
                .toList()
        }

        /**
         * RGB в координаты xy цветового пространства CIE 1931 по формуле Philips: линеаризация
         * sRGB, матрица Wide RGB D65 и проекция внутрь треугольника цветов лампы.
         * Возвращает x, y и яркость Y в 0..1.
         */
        fun rgbToXy(r: Int, g: Int, b: Int, gamut: FloatArray = GAMUT_C): FloatArray {
            val red = linear(r / 255f)
            val green = linear(g / 255f)
            val blue = linear(b / 255f)
            val x = red * 0.664511f + green * 0.154324f + blue * 0.162028f
            val y = red * 0.283881f + green * 0.668433f + blue * 0.047685f
            val z = red * 0.000088f + green * 0.07231f + blue * 0.986039f
            val sum = x + y + z
            if (sum <= 0f) return floatArrayOf(0.3127f, 0.329f, 0f)
            val point = clampToGamut(x / sum, y / sum, gamut)
            return floatArrayOf(point[0], point[1], y.coerceIn(0f, 1f))
        }

        private fun linear(value: Float): Float =
            if (value > 0.04045f) ((value + 0.055f) / 1.055f).pow(2.4f) else value / 12.92f

        /** Точка вне треугольника лампы переезжает на ближайшую точку его границы. */
        internal fun clampToGamut(x: Float, y: Float, gamut: FloatArray): FloatArray {
            if (inTriangle(x, y, gamut)) return floatArrayOf(x, y)
            var best = floatArrayOf(gamut[0], gamut[1])
            var bestDistance = Float.MAX_VALUE
            for (i in 0 until 3) {
                val ax = gamut[i * 2]
                val ay = gamut[i * 2 + 1]
                val bx = gamut[(i * 2 + 2) % 6]
                val by = gamut[(i * 2 + 3) % 6]
                val dx = bx - ax
                val dy = by - ay
                val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
                val px = ax + t * dx
                val py = ay + t * dy
                val distance = (x - px) * (x - px) + (y - py) * (y - py)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = floatArrayOf(px, py)
                }
            }
            return best
        }

        private fun inTriangle(x: Float, y: Float, t: FloatArray): Boolean {
            fun side(ax: Float, ay: Float, bx: Float, by: Float) = (x - bx) * (ay - by) - (ax - bx) * (y - by)
            val d1 = side(t[0], t[1], t[2], t[3])
            val d2 = side(t[2], t[3], t[4], t[5])
            val d3 = side(t[4], t[5], t[0], t[1])
            val negative = d1 < 0 || d2 < 0 || d3 < 0
            val positive = d1 > 0 || d2 > 0 || d3 > 0
            return !(negative && positive)
        }

        private fun errorOf(reply: String): String = try {
            JSONArray(reply).optJSONObject(0)?.optJSONObject("error")?.optString("description")
                ?.takeIf { it.isNotEmpty() } ?: reply.take(120)
        } catch (_: JSONException) {
            reply.take(120)
        }

        @Throws(IOException::class)
        private fun request(method: String, url: String, body: String?): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code !in 200..299) throw IOException("HTTP $code from the Hue bridge")
                return text
            } finally {
                connection.disconnect()
            }
        }
    }
}
