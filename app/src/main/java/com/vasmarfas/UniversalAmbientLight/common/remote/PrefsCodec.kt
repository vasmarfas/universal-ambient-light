package com.vasmarfas.UniversalAmbientLight.common.remote

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Настройки ⇄ JSON с явным типом значения. Тип нужен: `Preferences` хранит числа строками,
 * но часть старых ключей лежит настоящими Int, и зеркало на телефоне должно повторять ТВ
 * байт в байт — иначе чтение обёрткой разойдётся с чтением на самом ТВ.
 */
object PrefsCodec {

    // Состояние, а не настройки: флаг «подсветка работала» принадлежит автозапуску ТВ,
    // доступ с телефона включают и выключают только на самом ТВ, а результат автоподстройки
    // камеры — канал от сервиса к экрану ТВ
    private val LOCAL_ONLY = setOf(
        "pref_key_lighting_was_active",
        "pref_key_remote_access",
        "pref_key_camera_detect_result",
    )

    fun isSynced(key: String): Boolean = key.startsWith("pref_key_") && key !in LOCAL_ONLY

    fun encodeAll(prefs: SharedPreferences): JSONArray {
        val out = JSONArray()
        for ((key, value) in prefs.all) {
            if (!isSynced(key)) continue
            encode(key, value)?.let { out.put(it) }
        }
        return out
    }

    /** null означает «ключ удалён». */
    fun encode(key: String, value: Any?): JSONObject? {
        val entry = JSONObject().put("k", key)
        when (value) {
            null -> entry.put("t", "n")
            is String -> entry.put("t", "s").put("v", value)
            is Boolean -> entry.put("t", "b").put("v", value)
            is Int -> entry.put("t", "i").put("v", value)
            is Long -> entry.put("t", "l").put("v", value)
            is Float -> entry.put("t", "f").put("v", value.toDouble())
            is Set<*> -> entry.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
            else -> return null
        }
        return entry
    }

    fun decodeValue(entry: JSONObject): Any? = when (entry.optString("t")) {
        "s" -> entry.optString("v")
        "b" -> entry.optBoolean("v")
        "i" -> entry.optInt("v")
        "l" -> entry.optLong("v")
        "f" -> entry.optDouble("v").toFloat()
        "ss" -> {
            val array = entry.optJSONArray("v") ?: JSONArray()
            (0 until array.length()).map { array.optString(it) }.toSet()
        }

        else -> null
    }

    fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
        }
    }
}
