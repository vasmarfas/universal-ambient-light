package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.R

/**
 * Своя задержка вывода для отдельных приложений. Плееры выводят картинку с разной задержкой:
 * YouTube, приложение с HDMI-входом и онлайн-кинотеатр на одном ТВ легко расходятся на
 * сотню миллисекунд, и одна общая задержка подходит только одному из них.
 *
 * Хранится одной строкой `пакет=мс;пакет=мс`: так настройка ездит на телефон-пульт тем же
 * путём, что и остальные, а в именах пакетов ни `=`, ни `;` не бывает.
 */
object DelayProfiles {

    fun parse(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (entry in raw.split(';')) {
            val pkg = entry.substringBefore('=', "").trim()
            val delay = entry.substringAfter('=', "").trim().toIntOrNull() ?: continue
            if (pkg.isNotEmpty()) result[pkg] = delay.coerceIn(0, MAX_DELAY_MS)
        }
        return result
    }

    fun serialize(profiles: Map<String, Int>): String =
        profiles.entries.joinToString(";") { "${it.key}=${it.value.coerceIn(0, MAX_DELAY_MS)}" }

    /** Задержка для [foregroundPackage]: своя, если задана, иначе общая из настроек. */
    fun effectiveDelay(prefs: Preferences, foregroundPackage: String?): Int {
        val global = prefs.getInt(R.string.pref_key_output_delay, 0)
        if (foregroundPackage == null) return global
        return parse(prefs.getString(R.string.pref_key_delay_profiles, ""))[foregroundPackage] ?: global
    }

    fun put(prefs: Preferences, pkg: String, delayMs: Int) {
        val profiles = LinkedHashMap(parse(prefs.getString(R.string.pref_key_delay_profiles, "")))
        profiles[pkg] = delayMs
        prefs.putString(R.string.pref_key_delay_profiles, serialize(profiles))
    }

    fun remove(prefs: Preferences, pkg: String) {
        val profiles = LinkedHashMap(parse(prefs.getString(R.string.pref_key_delay_profiles, "")))
        profiles.remove(pkg)
        prefs.putString(R.string.pref_key_delay_profiles, serialize(profiles))
    }

    const val MAX_DELAY_MS = 1000
}
