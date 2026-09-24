package com.vasmarfas.UniversalAmbientLight.ui.home

import android.content.Context
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/** Куда уходит свет: «WLED · 192.168.1.50:19446». */
internal fun describeTarget(context: Context, prefs: Preferences): String {
    val type = prefs.getString(R.string.pref_key_connection_type) ?: "hyperion"
    val label = entryFor(
        context,
        R.array.pref_list_connection_type,
        R.array.pref_list_connection_type_values,
        type
    )
    val host = prefs.getString(R.string.pref_key_host)?.trim().orEmpty()
    return when {
        type.equals("adalight", ignoreCase = true) -> "$label · USB"
        host.isEmpty() -> "$label · ${context.getString(R.string.home_target_no_host)}"
        type.equals("homeassistant", ignoreCase = true) -> "$label · $host"
        else -> "$label · $host:${prefs.getInt(R.string.pref_key_port)}"
    }
}

/** Откуда берётся картинка: камера или способ захвата экрана без технических хвостов. */
internal fun describeSource(context: Context, prefs: Preferences): String {
    val source = prefs.getString(R.string.pref_key_capture_source) ?: "screen"
    if (source == "camera") {
        return entryFor(
            context,
            R.array.pref_list_capture_source,
            R.array.pref_list_capture_source_values,
            source
        )
    }
    val method = prefs.getString(R.string.pref_key_capture_method) ?: "media_projection"
    // В списке методов рядом с названием задержка и звёзды — на главном экране они лишние
    return entryFor(
        context,
        R.array.pref_list_capture_method,
        R.array.pref_list_capture_method_values,
        method
    ).substringBefore(" (")
}

private fun entryFor(context: Context, entriesRes: Int, valuesRes: Int, value: String): String {
    val values = context.resources.getStringArray(valuesRes)
    val entries = context.resources.getStringArray(entriesRes)
    return entries.getOrNull(values.indexOf(value)) ?: value
}
