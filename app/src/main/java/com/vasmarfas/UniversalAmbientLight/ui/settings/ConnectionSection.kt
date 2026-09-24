package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Группа «Подключение»: тип контроллера, адрес, порт и зависящие от протокола настройки.
 */
@Composable
internal fun ColumnScope.ConnectionSection(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.pref_group_connection)) {

        key(state.connectionType) {
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_connection_type,
                title = stringResource(R.string.pref_title_connection_type),
                entriesRes = R.array.pref_list_connection_type,
                entryValuesRes = R.array.pref_list_connection_type_values,
                recomposeKey = state.connectionType,
                onValueChange = { newType ->
                    val oldType = state.connectionType
                    state.connectionType = newType
                    AnalyticsHelper.logProtocolChanged(context, oldType, newType)
                    AnalyticsHelper.logSettingChanged(context, "connection_type", newType)
                    AnalyticsHelper.updateProtocolProperty(context, newType)
                    val defaultPort = when (newType) {
                        "hyperion" -> "19400"
                        "wled" -> if (state.wledProtocol == "ddp") "4048" else "19446"
                        "homeassistant" -> "8123"
                        else -> null
                    }
                    if (defaultPort != null) {
                        prefs.putString(R.string.pref_key_port, defaultPort)
                        state.currentPort = defaultPort
                    }
                }
            )
        }

        val isWled = state.connectionType == "wled"
        val isHyperion = state.connectionType == "hyperion"
        val isAdalight = state.connectionType == "adalight"
        val isHomeAssistant = state.connectionType == "homeassistant"
        val isNetwork = isHyperion || isWled || isHomeAssistant

        if (isHyperion || isWled) {
            ClickablePreference(
                title = stringResource(R.string.scanner_scan_devices),
                summary = stringResource(R.string.scanner_description),
                onClick = { state.showScanDialog = true }
            )
        }

        if (isNetwork) {

            key(state.connectionType) {
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_host,
                    title = stringResource(R.string.pref_title_host),
                    summaryProvider = { it },
                    recomposeKey = state.currentHost,
                    onValueChange = { newHost ->
                        state.currentHost = newHost
                        AnalyticsHelper.logHostChanged(context, newHost)
                        AnalyticsHelper.logSettingChanged(context, "host", newHost)
                    }
                )
            }

            // Для WLED выбор протокола показываем между адресом и портом
            if (isWled) {
                key(state.wledProtocol) {
                    ListPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_wled_protocol,
                        title = stringResource(R.string.pref_title_wled_protocol),
                        entriesRes = R.array.pref_list_wled_protocol,
                        entryValuesRes = R.array.pref_list_wled_protocol_values,
                        onValueChange = { newProtocol ->
                            state.wledProtocol = newProtocol
                            AnalyticsHelper.logSettingChanged(
                                context,
                                "wled_protocol",
                                newProtocol
                            )
                            // При смене протокола WLED порт подставляем сами
                            val defaultPort = if (newProtocol == "ddp") "4048" else "19446"
                            prefs.putString(R.string.pref_key_port, defaultPort)
                            state.currentPort = defaultPort
                        }
                    )
                }
            }

            // key заставляет пересобрать поле при смене типа подключения или протокола WLED
            key("${state.connectionType}_${state.wledProtocol}") {
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_port,
                    title = stringResource(R.string.pref_title_port),
                    summaryProvider = { it },
                    keyboardType = KeyboardType.Number,
                    recomposeKey = state.currentPort,
                    onValueChange = { newPort ->
                        state.currentPort = newPort
                        val portInt = newPort.toIntOrNull() ?: 0
                        AnalyticsHelper.logPortChanged(context, portInt)
                        AnalyticsHelper.logSettingChanged(context, "port", newPort)
                    }
                )
            }
            if (isHyperion) {
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_priority,
                    title = stringResource(R.string.pref_title_priority),
                    summaryProvider = { it },
                    keyboardType = KeyboardType.Number,
                    onValueChange = { newPriority ->
                        val priorityInt = newPriority.toIntOrNull() ?: 100
                        AnalyticsHelper.logPriorityChanged(context, priorityInt)
                        AnalyticsHelper.logSettingChanged(context, "priority", newPriority)
                    }
                )
                CheckBoxPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_reconnect,
                    title = stringResource(R.string.pref_title_reconnect),
                    onValueChange = { enabled ->
                        state.reconnectEnabled = enabled
                        AnalyticsHelper.logAutoReconnectEnabled(context, enabled)
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "reconnect",
                            enabled.toString()
                        )
                        AnalyticsHelper.updateAutoReconnectProperty(context, enabled)
                    }
                )
                if (state.reconnectEnabled) {
                    val secondsUnit = stringResource(R.string.unit_seconds)
                    SliderPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_reconnect_delay,
                        title = stringResource(R.string.pref_title_reconnect_delay),
                        range = 1..60,
                        summaryProvider = { "$it $secondsUnit" },
                        onValueChange = { delay ->
                            AnalyticsHelper.logReconnectDelayChanged(context, delay)
                            AnalyticsHelper.logSettingChanged(
                                context,
                                "reconnect_delay",
                                delay.toString()
                            )
                        }
                    )
                }
            }
        }

        if (isHomeAssistant) {
            HomeAssistantSection(
                prefs = prefs,
                analyticsPrefix = "ha",
                keyToken = R.string.pref_key_ha_token,
                lampsSpec = state.haLampsSpec,
                onLampsClick = { state.showHaLampsDialog = true },
                keyUpdateInterval = R.string.pref_key_ha_update_interval,
                keyChangeThreshold = R.string.pref_key_ha_change_threshold,
                keyTransition = R.string.pref_key_ha_transition,
                keyBrightnessMode = R.string.pref_key_ha_brightness_mode,
                keyBrightness = R.string.pref_key_ha_brightness,
                keyDarkOff = R.string.pref_key_ha_dark_off,
                keyDarkThreshold = R.string.pref_key_ha_dark_threshold,
                keyTurnOffLights = R.string.pref_key_ha_turn_off_lights,
            )
        }

        if (isAdalight) {
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_adalight_baudrate,
                title = stringResource(R.string.pref_title_adalight_baudrate),
                entriesRes = R.array.pref_list_adalight_baudrate,
                entryValuesRes = R.array.pref_list_adalight_baudrate_values,
                onValueChange = { newBaudrate ->
                    val baudrateInt = newBaudrate.toIntOrNull() ?: 115200
                    AnalyticsHelper.logBaudrateChanged(context, baudrateInt)
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "adalight_baudrate",
                        newBaudrate
                    )
                }
            )
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_adalight_protocol,
                title = stringResource(R.string.pref_title_adalight_protocol),
                entriesRes = R.array.pref_list_adalight_protocol,
                entryValuesRes = R.array.pref_list_adalight_protocol_values,
                onValueChange = { newProtocol ->
                    AnalyticsHelper.logAdalightProtocolChanged(context, newProtocol)
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "adalight_protocol",
                        newProtocol
                    )
                }
            )
        }

        if (isWled) {
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_wled_color_order,
                title = stringResource(R.string.pref_title_wled_color_order),
                entriesRes = R.array.pref_list_wled_color_order,
                entryValuesRes = R.array.pref_list_wled_color_order_values,
                onValueChange = { newColorOrder ->
                    AnalyticsHelper.logColorOrderChanged(context, newColorOrder)
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "wled_color_order",
                        newColorOrder
                    )
                }
            )
            CheckBoxPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_wled_rgbw,
                title = stringResource(R.string.pref_title_wled_rgbw),
                summary = if (state.currentPort == "19446") {
                    stringResource(R.string.pref_summary_wled_rgbw_raw)
                } else {
                    stringResource(R.string.pref_summary_wled_rgbw_white_mode)
                },
                onValueChange = { enabled ->
                    AnalyticsHelper.logRgbwChanged(context, enabled)
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "wled_rgbw",
                        enabled.toString()
                    )
                }
            )
            val brightnessMaxSummary = stringResource(R.string.pref_summary_wled_brightness_max)
            SliderPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_wled_brightness,
                title = stringResource(R.string.pref_title_wled_brightness),
                range = 0..255,
                // 255 — не «максимальная яркость», а «не трогать цвета вовсе», это стоит
                // проговорить: иначе значение по умолчанию выглядит как обычный максимум
                summaryProvider = { value ->
                    if (value == 255) brightnessMaxSummary else value.toString()
                },
                onValueChange = { newBrightness ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "wled_brightness",
                        newBrightness.toString()
                    )
                }
            )
        }
    }
}
