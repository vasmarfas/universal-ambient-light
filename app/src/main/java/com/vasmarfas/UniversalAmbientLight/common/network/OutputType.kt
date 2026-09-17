package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Куда подсветка отдаёт цвета. [id] - значение настройки pref_key_connection_type, оно же
 * имя протокола в аналитике; менять его у существующих типов нельзя.
 */
enum class OutputType(
    val id: String,
    val group: Group,
    /** Порт по умолчанию; 0 - порт в настройках не задаётся. */
    val defaultPort: Int,
    /** Нужен ли адрес контроллера. */
    val needsHost: Boolean = true,
) {
    WLED("wled", Group.STRIP, 19446),
    ADALIGHT("adalight", Group.USB, 0, needsHost = false),
    HOME_ASSISTANT("homeassistant", Group.LAMPS, 8123),
    HYPERION("hyperion", Group.SERVER, 19400);

    enum class Group { STRIP, USB, LAMPS, SERVER }

    companion object {
        /** Неизвестное значение (настройки от будущей версии) читается как Hyperion, как и раньше. */
        fun of(id: String?): OutputType = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: HYPERION
    }
}
