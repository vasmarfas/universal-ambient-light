package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType

/**
 * Контроллеры, которые работают вместе с основным: вторая лента на стене, планки по бокам
 * ТВ, лампы. Кадр у всех один, а настройки и раскладка у каждого свои. Это те же ключи, что
 * у основного, только под префиксом контроллера ([Preferences.forOutput]): настраиваются
 * они теми же экранами, а телефон-пульт синхронизирует их как обычные настройки.
 */
object ExtraOutputs {

    /** Номер основного контроллера: у него нет ни префикса, ни места в списке. */
    const val MAIN = 0

    // Начало обязательно pref_key_: только такие ключи уходят на телефон-пульт
    private const val KEY_BASE = "pref_key_extra"
    private const val KEY_LIST = "pref_key_extra_outputs"
    private const val KEY_ENABLED = "pref_key_output_enabled"
    private const val KEY_TYPE = "pref_key_connection_type"
    private const val KEY_PORT = "pref_key_port"

    private const val HA2_BASE = "pref_key_ha2_"

    fun keyPrefix(id: Int): String = if (id == MAIN) "" else "$KEY_BASE$id."

    /** Ключ самого списка или настройка одного из контроллеров. */
    fun isOutputKey(key: String): Boolean = key.startsWith(KEY_BASE)

    fun parseIds(stored: String?): List<Int> =
        stored.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > MAIN }.distinct()

    fun ids(prefs: Preferences): List<Int> = parseIds(prefs.getString(R.string.pref_key_extra_outputs))

    /**
     * Новый контроллер. Чаще всего это вторая лента на WLED; DDP - потому что у него нет
     * предела на число светодиодов и порт не спорит с приёмником Hyperion.
     */
    fun add(prefs: Preferences): Int {
        val ids = ids(prefs)
        val id = nextId(ids)
        val output = prefs.forOutput(id)
        output.putString(R.string.pref_key_connection_type, OutputType.WLED.id)
        output.putString(R.string.pref_key_wled_protocol, "ddp")
        prefs.putString(R.string.pref_key_extra_outputs, (ids + id).joinToString(","))
        return id
    }

    fun remove(prefs: Preferences, id: Int) {
        prefs.putString(R.string.pref_key_extra_outputs, (ids(prefs) - id).joinToString(","))
        prefs.removeOutput(id)
    }

    /** Адреса, которые уже заняты другими контроллерами: второй поток на ту же ленту только мерцал бы. */
    fun busyHosts(prefs: Preferences, exceptId: Int): Set<String> =
        (ids(prefs) + MAIN).filter { it != exceptId }
            .mapNotNull { prefs.forOutput(it).getString(R.string.pref_key_host)?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Второе подключение Home Assistant раньше жило на своих ключах pref_key_ha2_*, теперь
     * это обычный дополнительный контроллер.
     *
     * @return что записать в настройки; null - стереть ключ
     */
    internal fun migrateHa2(stored: Map<String, *>): Map<String, Any?> {
        val old = stored.filterKeys { it.startsWith(HA2_BASE) }
        val changes = HashMap<String, Any?>()
        old.keys.forEach { changes[it] = null }

        val configured = listOf("host", "token", "lamps").any {
            (old[HA2_BASE + it] as? String).isNullOrBlank().not()
        }
        if (!configured) return changes

        val ids = parseIds(stored[KEY_LIST] as? String)
        val id = nextId(ids)
        val prefix = keyPrefix(id)
        for ((key, value) in old) {
            val target = when (val name = key.removePrefix(HA2_BASE)) {
                "enabled" -> KEY_ENABLED
                "host", "port" -> "pref_key_$name"
                else -> "pref_key_ha_$name"
            }
            changes[prefix + target] = value
        }
        changes[prefix + KEY_TYPE] = OutputType.HOME_ASSISTANT.id
        // Выключенное раньше подключение ключа могло и не иметь, а новый контроллер включён
        // по умолчанию. Порт тоже показывался умолчанием, а без него контроллер не запустится
        changes.getOrPut(prefix + KEY_ENABLED) { false }
        changes.getOrPut(prefix + KEY_PORT) { OutputType.HOME_ASSISTANT.defaultPort.toString() }
        changes[KEY_LIST] = (ids + id).joinToString(",")
        return changes
    }

    private fun nextId(ids: List<Int>): Int = (ids.maxOrNull() ?: MAIN) + 1
}
