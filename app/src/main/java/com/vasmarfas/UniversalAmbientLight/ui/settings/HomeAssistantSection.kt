package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantClient
import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantLamp
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/**
 * Настройки вывода на лампы Home Assistant: токен, привязка ламп к зонам и ритм
 * обновлений — лампы не лента, им нельзя слать цвет на каждый кадр.
 *
 * Ключи настроек и префикс аналитики передаются параметрами: этот же набор полей
 * обслуживает и основное подключение (внутри группы «Подключение»), и дополнительное.
 */
@Composable
internal fun ColumnScope.HomeAssistantSection(
    prefs: Preferences,
    analyticsPrefix: String,
    keyToken: Int,
    lampsSpec: String,
    onLampsClick: () -> Unit,
    keyUpdateInterval: Int,
    keyChangeThreshold: Int,
    keyTransition: Int,
    keyBrightnessMode: Int,
    keyBrightness: Int,
    keyDarkOff: Int,
    keyDarkThreshold: Int,
    keyTurnOffLights: Int,
) {
    val context = LocalContext.current

    val tokenSetSummary = stringResource(R.string.pref_summary_ha_token_set)
    val tokenEmptySummary = stringResource(R.string.pref_summary_ha_token_empty)
    EditTextPreference(
        prefs = prefs,
        keyRes = keyToken,
        title = stringResource(R.string.pref_title_ha_token),
        // Сам токен в списке не светим — это ключ от всего умного дома
        summaryProvider = { value -> if (value.isBlank()) tokenEmptySummary else tokenSetSummary },
        onValueChange = {
            AnalyticsHelper.logSettingChanged(context, "${analyticsPrefix}_token", "set")
        }
    )

    val lampCount = HomeAssistantLamp.parseList(lampsSpec).size
    ClickablePreference(
        title = stringResource(R.string.pref_title_ha_lamps),
        summary = if (lampCount == 0) {
            stringResource(R.string.pref_summary_ha_lamps_empty)
        } else {
            stringResource(R.string.pref_summary_ha_lamps_count, lampCount)
        },
        onClick = onLampsClick
    )

    ListPreference(
        prefs = prefs,
        keyRes = keyUpdateInterval,
        title = stringResource(R.string.pref_title_ha_update_interval),
        entriesRes = R.array.pref_list_ha_update_interval,
        entryValuesRes = R.array.pref_list_ha_update_interval_values,
        onValueChange = {
            AnalyticsHelper.logSettingChanged(context, "${analyticsPrefix}_update_interval", it)
        }
    )

    SliderPreference(
        prefs = prefs,
        keyRes = keyChangeThreshold,
        title = stringResource(R.string.pref_title_ha_change_threshold),
        range = 0..255,
        onValueChange = {
            AnalyticsHelper.logSettingChanged(
                context,
                "${analyticsPrefix}_change_threshold",
                it.toString()
            )
        }
    )

    ListPreference(
        prefs = prefs,
        keyRes = keyTransition,
        title = stringResource(R.string.pref_title_ha_transition),
        entriesRes = R.array.pref_list_ha_transition,
        entryValuesRes = R.array.pref_list_ha_transition_values,
        onValueChange = {
            AnalyticsHelper.logSettingChanged(context, "${analyticsPrefix}_transition", it)
        }
    )

    var brightnessMode by remember {
        mutableStateOf(
            prefs.getString(keyBrightnessMode)
                ?: HomeAssistantClient.BRIGHTNESS_MODE_SCREEN
        )
    }
    ListPreference(
        prefs = prefs,
        keyRes = keyBrightnessMode,
        title = stringResource(R.string.pref_title_ha_brightness_mode),
        entriesRes = R.array.pref_list_ha_brightness_mode,
        entryValuesRes = R.array.pref_list_ha_brightness_mode_values,
        onValueChange = { newMode ->
            brightnessMode = newMode
            AnalyticsHelper.logSettingChanged(context, "${analyticsPrefix}_brightness_mode", newMode)
        }
    )
    if (brightnessMode == HomeAssistantClient.BRIGHTNESS_MODE_SCREEN) {
        val brightnessMaxSummary = stringResource(R.string.pref_summary_ha_brightness_max)
        SliderPreference(
            prefs = prefs,
            keyRes = keyBrightness,
            title = stringResource(R.string.pref_title_ha_brightness),
            range = 1..255,
            summaryProvider = { value ->
                if (value == 255) brightnessMaxSummary else value.toString()
            },
            onValueChange = {
                AnalyticsHelper.logSettingChanged(
                    context,
                    "${analyticsPrefix}_brightness",
                    it.toString()
                )
            }
        )
    }

    var darkOffEnabled by remember {
        mutableStateOf(prefs.getBoolean(keyDarkOff, true))
    }
    key(darkOffEnabled) {
        CheckBoxPreference(
            prefs = prefs,
            keyRes = keyDarkOff,
            title = stringResource(R.string.pref_title_ha_dark_off),
            summary = stringResource(R.string.pref_summary_ha_dark_off),
            onValueChange = { enabled ->
                darkOffEnabled = enabled
                AnalyticsHelper.logSettingChanged(context, "${analyticsPrefix}_dark_off", enabled.toString())
            }
        )
    }
    if (darkOffEnabled) {
        SliderPreference(
            prefs = prefs,
            keyRes = keyDarkThreshold,
            title = stringResource(R.string.pref_title_ha_dark_threshold),
            range = 1..255,
            onValueChange = {
                AnalyticsHelper.logSettingChanged(
                    context,
                    "${analyticsPrefix}_dark_threshold",
                    it.toString()
                )
            }
        )
    }

    CheckBoxPreference(
        prefs = prefs,
        keyRes = keyTurnOffLights,
        title = stringResource(R.string.pref_title_ha_turn_off_lights),
        summary = stringResource(R.string.pref_summary_ha_turn_off_lights),
        onValueChange = {
            AnalyticsHelper.logSettingChanged(context, "${analyticsPrefix}_turn_off_lights", it.toString())
        }
    )
}
