package com.vasmarfas.UniversalAmbientLight.ui.home

import android.content.Context
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.effect.Effect
import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantLamp
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.ui.devices.titleRes
import com.vasmarfas.UniversalAmbientLight.ui.effects.titleRes

/** Куда уходит свет: «WLED · 192.168.1.50:19446». */
internal fun describeTarget(context: Context, prefs: Preferences): String {
    val type = OutputType.of(prefs.getString(R.string.pref_key_connection_type))
    val label = context.getString(type.titleRes())
    val host = prefs.getString(R.string.pref_key_host)?.trim().orEmpty()
    val lampsKey = type.lampsKey
    return when {
        type == OutputType.ADALIGHT -> "$label · USB"
        lampsKey != null && !type.needsHost -> {
            val lamps = HomeAssistantLamp.parseList(prefs.getString(lampsKey, "")).size
            "$label · ${context.getString(R.string.devices_lamps_count, lamps)}"
        }

        host.isEmpty() && type == OutputType.E131 -> "$label · ${context.getString(R.string.home_target_multicast)}"
        host.isEmpty() -> "$label · ${context.getString(R.string.home_target_no_host)}"
        type.defaultPort == 0 || type == OutputType.HOME_ASSISTANT -> "$label · $host"
        else -> "$label · $host:${prefs.getInt(R.string.pref_key_port)}"
    }
}

/**
 * Чего не хватает, чтобы подсветке было куда светить; null - контроллер настроен.
 * Проверяет то же, что сервис перед запуском, но отвечает коротко, для главного экрана.
 */
internal fun setupIssue(context: Context, prefs: Preferences): String? {
    val type = OutputType.of(prefs.getString(R.string.pref_key_connection_type))
    fun blank(key: Int) = prefs.getString(key).isNullOrBlank()
    val lampsKey = type.lampsKey
    return when {
        type.needsHost && blank(R.string.pref_key_host) -> context.getString(R.string.home_setup_no_host)
        type == OutputType.HUE && blank(R.string.pref_key_hue_username) ||
                type == OutputType.NANOLEAF && blank(R.string.pref_key_nanoleaf_token) ->
            context.getString(R.string.home_setup_not_paired, context.getString(type.titleRes()))

        type == OutputType.HOME_ASSISTANT && blank(R.string.pref_key_ha_token) ->
            context.getString(R.string.home_setup_no_token)

        lampsKey != null && HomeAssistantLamp.parseList(prefs.getString(lampsKey, "")).isEmpty() ->
            context.getString(R.string.home_setup_no_lamps)

        else -> null
    }
}

/** Откуда берётся картинка: камера, эффект или способ захвата экрана без технических хвостов. */
internal fun describeSource(context: Context, prefs: Preferences): String {
    val source = prefs.getString(R.string.pref_key_capture_source) ?: "screen"
    if (source == "effect") {
        val effect = Effect.byId(prefs.getString(R.string.pref_key_effect))
        return context.getString(R.string.home_source_effect, context.getString(effect.titleRes()))
    }
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
