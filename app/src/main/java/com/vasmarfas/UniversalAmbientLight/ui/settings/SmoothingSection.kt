package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Группа сглаживания цвета.
 */
@Composable
internal fun ColumnScope.SmoothingSection(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.pref_group_smoothing)) {
        // key: пресет «off» выключает сглаживание записью в prefs, и без пересоздания
        // переключатель остался бы визуально включённым
        key(state.smoothingPreset) {
            CheckBoxPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_smoothing_enabled,
                title = stringResource(R.string.pref_title_smoothing_enabled),
                onValueChange = { enabled ->
                    val preset = prefs.getString(R.string.pref_key_smoothing_preset, "off")
                    AnalyticsHelper.logSmoothingChanged(context, enabled, preset)
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "smoothing_enabled",
                        enabled.toString()
                    )
                    AnalyticsHelper.updateSmoothingProperty(context, enabled)
                }
            )
        }
        ListPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_smoothing_preset,
            title = stringResource(R.string.pref_title_smoothing_preset),
            entriesRes = R.array.pref_list_smoothing_preset,
            entryValuesRes = R.array.pref_list_smoothing_preset_values,
            onValueChange = { preset ->
                state.smoothingPreset = preset
                val enabled = prefs.getBoolean(R.string.pref_key_smoothing_enabled, false)
                AnalyticsHelper.logSmoothingChanged(context, enabled, preset)
                AnalyticsHelper.logSettingChanged(context, "smoothing_preset", preset)

                val presetValues = when (preset.lowercase()) {
                    "off" -> Triple(50, 0, 60)
                    "responsive" -> Triple(50, 0, 60)
                    "balanced" -> Triple(200, 80, 25)
                    "smooth" -> Triple(500, 200, 20)
                    else -> Triple(200, 80, 25)
                }

                prefs.putInt(R.string.pref_key_settling_time, presetValues.first)
                prefs.putInt(R.string.pref_key_output_delay, presetValues.second)
                prefs.putInt(R.string.pref_key_update_frequency, presetValues.third)

                if (preset.lowercase() == "off") {
                    prefs.putBoolean(R.string.pref_key_smoothing_enabled, false)
                }
            }
        )
        key(state.smoothingPreset) {
            // Клиенты сглаживания всё равно зажимают оба значения в 0–1000 мс
            val resources = LocalResources.current
            SliderPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_settling_time,
                title = stringResource(R.string.pref_title_settling_time),
                range = 0..1000,
                step = 10,
                summaryProvider = { value -> resources.getString(R.string.unit_ms, value) },
                recomposeKey = state.smoothingPreset
            )
            SliderPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_output_delay,
                title = stringResource(R.string.pref_title_output_delay),
                range = 0..1000,
                step = 10,
                summaryProvider = { value -> resources.getString(R.string.unit_ms, value) },
                recomposeKey = state.smoothingPreset
            )
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_update_frequency,
                title = stringResource(R.string.pref_title_update_frequency),
                entriesRes = R.array.pref_list_update_frequency,
                entryValuesRes = R.array.pref_list_update_frequency_values,
                recomposeKey = state.smoothingPreset
            )
        }
    }
}
