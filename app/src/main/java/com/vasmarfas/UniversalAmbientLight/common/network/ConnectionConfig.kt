package com.vasmarfas.UniversalAmbientLight.common.network

import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.ExtraOutputs
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/**
 * Параметры подключения к контроллеру, собранные из настроек одним объектом.
 * Раньше те же значения уходили в [HyperionThread] семнадцатью позиционными
 * аргументами подряд — перепутать соседние Int или String было слишком легко.
 *
 * Значения по умолчанию повторяют прежние дефолты конструктора [HyperionThread].
 */
data class ConnectionConfig(
    val host: String,
    val port: Int,
    val priority: Int,
    val reconnect: Boolean,
    val reconnectDelaySeconds: Int,
    val connectionType: String,
    val baudRate: Int,
    val wledColorOrder: String,
    val wledProtocol: String = "ddp",
    val wledRgbw: Boolean = false,
    /** 0..255; 255 — отправлять цвета как есть. */
    val wledBrightness: Int = 255,
    val adalightProtocol: String = "ada",
    val smoothingEnabled: Boolean = true,
    val smoothingPreset: String = "balanced",
    val settlingTime: Int = 200,
    val outputDelayMs: Long = 80L,
    val updateFrequency: Int = 25,
    val haToken: String = "",
    /** Лампы с зонами в формате [HomeAssistantLamp.serialize]. */
    val haLamps: String = "",
    val haUpdateIntervalMs: Long = 500L,
    val haChangeThreshold: Int = 10,
    val haTransitionMs: Int = 300,
    val haBrightnessMode: String = HomeAssistantClient.BRIGHTNESS_MODE_SCREEN,
    /** 0..255 — потолок яркости ламп в режиме «следует за картинкой». */
    val haBrightnessMax: Int = 255,
    val haDarkOffEnabled: Boolean = true,
    val haDarkThreshold: Int = 10,
    val haTurnOffLights: Boolean = true,
    val dmxUniverse: Int = 1,
    val dmxLedsPerUniverse: Int = 170,
    val opcChannel: Int = 0,
    /** Лампы основного подключения с зонами - тем же форматом, что и у Home Assistant. */
    val lamps: String = "",
    val hueUsername: String = "",
    val nanoleafToken: String = "",
    val hueClientKey: String = "",
    val hueArea: String = "",
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val z2mBaseTopic: String = Zigbee2MqttClient.DEFAULT_BASE_TOPIC,
) {

    companion object {

        /**
         * Собирает подключение из настроек контроллера [prefs]. Сглаживание общее для всех
         * контроллеров: лента за ТВ и лента на стене должны двигаться вместе.
         */
        fun from(prefs: Preferences, outputDelayMs: Long): ConnectionConfig {
            val connectionType =
                prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
            val outputType = OutputType.of(connectionType)
            val wledProtocol = prefs.getString(R.string.pref_key_wled_protocol, "ddp") ?: "ddp"
            val shared = prefs.forOutput(ExtraOutputs.MAIN)

            // Выбор E1.31 в списке контроллеров не сохраняет пустой адрес, если старый тоже был
            // пустым: отсутствующий ключ значит то же, что пустое поле, то есть мультикаст
            val host = prefs.getString(R.string.pref_key_host, null)?.trim()
                ?: if (outputType == OutputType.E131) "" else "localhost"
            val port = prefs.getInt(R.string.pref_key_port, -1).takeIf { it > 0 } ?: when (outputType) {
                OutputType.WLED -> if (wledProtocol == "ddp") OutputType.DDP.defaultPort
                    else outputType.defaultPort
                else -> outputType.defaultPort.takeIf { it > 0 } ?: OutputType.HYPERION.defaultPort
            }

            return ConnectionConfig(
                host = host,
                port = port,
                // Безопасный парсинг приоритета (на случай пустой или некорректной строки)
                priority = prefs.getString(R.string.pref_key_priority, "100")?.toIntOrNull() ?: 100,
                reconnect = prefs.getBoolean(R.string.pref_key_reconnect),
                reconnectDelaySeconds = prefs.getInt(R.string.pref_key_reconnect_delay),
                connectionType = connectionType,
                baudRate = prefs.getInt(R.string.pref_key_adalight_baudrate),
                wledColorOrder = prefs.getString(R.string.pref_key_wled_color_order, "rgb") ?: "rgb",
                wledProtocol = wledProtocol,
                wledRgbw = prefs.getBoolean(R.string.pref_key_wled_rgbw, false),
                wledBrightness = prefs.getInt(R.string.pref_key_wled_brightness, 255),
                adalightProtocol = prefs.getString(R.string.pref_key_adalight_protocol, "ada") ?: "ada",
                smoothingEnabled = shared.getBoolean(R.string.pref_key_smoothing_enabled, false),
                smoothingPreset = shared.getString(R.string.pref_key_smoothing_preset, "off") ?: "off",
                settlingTime = shared.getInt(R.string.pref_key_settling_time, 50),
                outputDelayMs = outputDelayMs,
                updateFrequency = shared.getInt(R.string.pref_key_update_frequency, 60),
                haToken = prefs.getString(R.string.pref_key_ha_token, "") ?: "",
                haLamps = prefs.getString(R.string.pref_key_ha_lamps, "") ?: "",
                haUpdateIntervalMs = prefs.getInt(R.string.pref_key_ha_update_interval, 500)
                    .coerceAtLeast(100).toLong(),
                haChangeThreshold = prefs.getInt(R.string.pref_key_ha_change_threshold, 10)
                    .coerceIn(0, 255),
                haTransitionMs = prefs.getInt(R.string.pref_key_ha_transition, 300)
                    .coerceIn(0, 10_000),
                haBrightnessMode = prefs.getString(
                    R.string.pref_key_ha_brightness_mode,
                    HomeAssistantClient.BRIGHTNESS_MODE_SCREEN
                ) ?: HomeAssistantClient.BRIGHTNESS_MODE_SCREEN,
                haBrightnessMax = prefs.getInt(R.string.pref_key_ha_brightness, 255).coerceIn(1, 255),
                haDarkOffEnabled = prefs.getBoolean(R.string.pref_key_ha_dark_off, true),
                haDarkThreshold = prefs.getInt(R.string.pref_key_ha_dark_threshold, 10)
                    .coerceIn(1, 255),
                haTurnOffLights = prefs.getBoolean(R.string.pref_key_ha_turn_off_lights, true),
                dmxUniverse = prefs.getInt(R.string.pref_key_dmx_universe),
                dmxLedsPerUniverse = prefs.getInt(R.string.pref_key_dmx_leds_per_universe),
                opcChannel = prefs.getInt(R.string.pref_key_opc_channel),
                lamps = outputType.lampsKey?.let { prefs.getString(it, "") }.orEmpty(),
                hueUsername = prefs.getString(R.string.pref_key_hue_username, "").orEmpty(),
                nanoleafToken = prefs.getString(R.string.pref_key_nanoleaf_token, "").orEmpty(),
                hueClientKey = prefs.getString(R.string.pref_key_hue_clientkey, "").orEmpty(),
                hueArea = prefs.getString(R.string.pref_key_hue_area, "").orEmpty(),
                mqttUsername = prefs.getString(R.string.pref_key_mqtt_username, "").orEmpty(),
                mqttPassword = prefs.getString(R.string.pref_key_mqtt_password, "").orEmpty(),
                z2mBaseTopic = prefs.getString(R.string.pref_key_z2m_base_topic, null)?.trim()
                    ?.ifEmpty { null } ?: Zigbee2MqttClient.DEFAULT_BASE_TOPIC
            )
        }
    }
}
