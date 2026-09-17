package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.ui.components.focusHighlight
import com.vasmarfas.UniversalAmbientLight.ui.devices.icon
import com.vasmarfas.UniversalAmbientLight.ui.devices.titleRes

/**
 * Группа «Подключение»: выбранный контроллер (сменить - на экране выбора) и настройки,
 * которые нужны именно ему.
 */
@Composable
internal fun ColumnScope.ConnectionSection(
    prefs: Preferences,
    state: SettingsScreenState,
    onControllerClick: () -> Unit,
) {
    val context = LocalContext.current
    val type = OutputType.of(state.connectionType)
    SettingsGroup(title = stringResource(R.string.pref_group_connection)) {

        val address = when {
            !type.needsHost -> null
            state.currentHost.isBlank() -> stringResource(R.string.controller_no_address)
            type.defaultPort > 0 -> "${state.currentHost}:${state.currentPort}"
            else -> state.currentHost
        }
        ControllerCard(type, address, onControllerClick)

        if (type.needsHost) {
            val noAddressSummary = stringResource(R.string.controller_no_address)
            key(state.connectionType) {
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_host,
                    title = stringResource(R.string.pref_title_host),
                    summaryProvider = { it.ifBlank { noAddressSummary } },
                    recomposeKey = state.currentHost,
                    onValueChange = { newHost ->
                        state.currentHost = newHost
                        AnalyticsHelper.logHostChanged(context, newHost)
                        AnalyticsHelper.logSettingChanged(context, "host", newHost)
                    }
                )
            }
        }

        // Для WLED выбор протокола показываем между адресом и портом
        if (type == OutputType.WLED) {
            key(state.wledProtocol) {
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_wled_protocol,
                    title = stringResource(R.string.pref_title_wled_protocol),
                    entriesRes = R.array.pref_list_wled_protocol,
                    entryValuesRes = R.array.pref_list_wled_protocol_values,
                    onValueChange = { newProtocol ->
                        state.wledProtocol = newProtocol
                        AnalyticsHelper.logSettingChanged(context, "wled_protocol", newProtocol)
                        // При смене протокола WLED порт подставляем сами
                        val defaultPort = if (newProtocol == "ddp") "4048" else "19446"
                        prefs.putString(R.string.pref_key_port, defaultPort)
                        state.currentPort = defaultPort
                    }
                )
            }
        }

        if (type.defaultPort > 0) {
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
                        AnalyticsHelper.logPortChanged(context, newPort.toIntOrNull() ?: 0)
                        AnalyticsHelper.logSettingChanged(context, "port", newPort)
                    }
                )
            }
        }

        when (type) {
            OutputType.WLED -> WledSettings(prefs, state)
            OutputType.ADALIGHT -> {
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_adalight_baudrate,
                    title = stringResource(R.string.pref_title_adalight_baudrate),
                    entriesRes = R.array.pref_list_adalight_baudrate,
                    entryValuesRes = R.array.pref_list_adalight_baudrate_values,
                    onValueChange = { newBaudrate ->
                        AnalyticsHelper.logBaudrateChanged(context, newBaudrate.toIntOrNull() ?: 115200)
                        AnalyticsHelper.logSettingChanged(context, "adalight_baudrate", newBaudrate)
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
                        AnalyticsHelper.logSettingChanged(context, "adalight_protocol", newProtocol)
                    }
                )
            }

            OutputType.HOME_ASSISTANT -> HomeAssistantSection(
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

            OutputType.HYPERION -> HyperionSettings(prefs, state)
        }
    }
}

@Composable
private fun ColumnScope.WledSettings(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    ListPreference(
        prefs = prefs,
        keyRes = R.string.pref_key_wled_color_order,
        title = stringResource(R.string.pref_title_wled_color_order),
        entriesRes = R.array.pref_list_wled_color_order,
        entryValuesRes = R.array.pref_list_wled_color_order_values,
        onValueChange = { newColorOrder ->
            AnalyticsHelper.logColorOrderChanged(context, newColorOrder)
            AnalyticsHelper.logSettingChanged(context, "wled_color_order", newColorOrder)
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
            AnalyticsHelper.logSettingChanged(context, "wled_rgbw", enabled.toString())
        }
    )
    val brightnessMaxSummary = stringResource(R.string.pref_summary_wled_brightness_max)
    SliderPreference(
        prefs = prefs,
        keyRes = R.string.pref_key_wled_brightness,
        title = stringResource(R.string.pref_title_wled_brightness),
        range = 0..255,
        // 255 - не «максимальная яркость», а «не трогать цвета вовсе», это стоит
        // проговорить: иначе значение по умолчанию выглядит как обычный максимум
        summaryProvider = { value ->
            if (value == 255) brightnessMaxSummary else value.toString()
        },
        onValueChange = { newBrightness ->
            AnalyticsHelper.logSettingChanged(context, "wled_brightness", newBrightness.toString())
        }
    )
}

@Composable
private fun ColumnScope.HyperionSettings(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    EditTextPreference(
        prefs = prefs,
        keyRes = R.string.pref_key_priority,
        title = stringResource(R.string.pref_title_priority),
        summaryProvider = { it },
        keyboardType = KeyboardType.Number,
        onValueChange = { newPriority ->
            AnalyticsHelper.logPriorityChanged(context, newPriority.toIntOrNull() ?: 100)
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
            AnalyticsHelper.logSettingChanged(context, "reconnect", enabled.toString())
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
                AnalyticsHelper.logSettingChanged(context, "reconnect_delay", delay.toString())
            }
        )
    }
}

/** Текущий контроллер крупно, с иконкой: с него начинается настройка подключения. */
@Composable
private fun ControllerCard(type: OutputType, address: String?, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusHighlight(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = stringResource(R.string.controller_change),
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 16.dp)
    ) {
        Icon(
            imageVector = type.group.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(type.titleRes()), style = MaterialTheme.typography.titleMedium)
            if (address != null) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.controller_change),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
