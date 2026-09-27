package com.vasmarfas.UniversalAmbientLight.common.network

import com.vasmarfas.UniversalAmbientLight.R

/**
 * Куда подсветка отдаёт цвета. [id] - значение настройки pref_key_connection_type, оно же
 * имя протокола в аналитике; менять его у существующих типов нельзя.
 */
enum class OutputType(
    val id: String,
    val group: Group,
    /** Порт по умолчанию; 0 - порт в настройках не задаётся. */
    val defaultPort: Int,
    /** Нужен ли адрес контроллера. Лампы WiZ, Yeelight, LIFX и Govee адресуются каждая своим. */
    val needsHost: Boolean = true,
) {
    WLED("wled", Group.STRIP, 19446),
    DDP("ddp", Group.STRIP, 4048),
    E131("e131", Group.STRIP, E131Client.DEFAULT_PORT, needsHost = false),
    ARTNET("artnet", Group.STRIP, ArtNetClient.DEFAULT_PORT),
    TPM2NET("tpm2net", Group.STRIP, Tpm2NetClient.DEFAULT_PORT),
    UDP_RAW("udpraw", Group.STRIP, UdpRawClient.DEFAULT_PORT),
    OPC("opc", Group.STRIP, OpcClient.DEFAULT_PORT),
    ADALIGHT("adalight", Group.USB, 0, needsHost = false),
    HUE("hue", Group.LAMPS, 0),
    NANOLEAF("nanoleaf", Group.LAMPS, NanoleafClient.DEFAULT_PORT),
    WIZ("wiz", Group.LAMPS, 0, needsHost = false),
    YEELIGHT("yeelight", Group.LAMPS, 0, needsHost = false),
    LIFX("lifx", Group.LAMPS, 0, needsHost = false),
    GOVEE("govee", Group.LAMPS, 0, needsHost = false),
    ZIGBEE2MQTT("z2m", Group.LAMPS, MqttLink.DEFAULT_PORT),
    HOME_ASSISTANT("homeassistant", Group.LAMPS, 8123),
    HYPERION("hyperion", Group.SERVER, 19400);

    enum class Group { STRIP, USB, LAMPS, SERVER }

    /** Лампы с привязкой к зонам экрана вместо адресной ленты. */
    val isLamps: Boolean
        get() = this == HUE || this == WIZ || this == YEELIGHT || this == LIFX || this == GOVEE ||
                this == ZIGBEE2MQTT || this == HOME_ASSISTANT

    /** Где лежат лампы с зонами; у Home Assistant - свой ключ с его сущностями. */
    val lampsKey: Int?
        get() = when (this) {
            HUE -> R.string.pref_key_hue_lamps
            WIZ -> R.string.pref_key_wiz_lamps
            YEELIGHT -> R.string.pref_key_yeelight_lamps
            LIFX -> R.string.pref_key_lifx_lamps
            GOVEE -> R.string.pref_key_govee_lamps
            ZIGBEE2MQTT -> R.string.pref_key_z2m_lamps
            HOME_ASSISTANT -> R.string.pref_key_ha_lamps
            else -> null
        }

    companion object {
        /** Неизвестное значение (настройки от будущей версии) читается как Hyperion, как и раньше. */
        fun of(id: String?): OutputType = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: HYPERION
    }
}
