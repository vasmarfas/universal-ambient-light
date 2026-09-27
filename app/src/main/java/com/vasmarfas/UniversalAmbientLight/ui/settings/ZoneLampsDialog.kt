package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.GoveeClient
import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantLamp
import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantZone
import com.vasmarfas.UniversalAmbientLight.common.network.HueClient
import com.vasmarfas.UniversalAmbientLight.common.network.LifxClient
import com.vasmarfas.UniversalAmbientLight.common.network.MqttLink
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType
import com.vasmarfas.UniversalAmbientLight.common.network.WizClient
import com.vasmarfas.UniversalAmbientLight.common.network.YeelightClient
import com.vasmarfas.UniversalAmbientLight.common.network.Zigbee2MqttClient
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.ui.devices.titleRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "ZoneLampsDialog"

/**
 * Выбор ламп и их зон. Список ламп отдаёт [fetch]: Home Assistant, мост или поиск в сети.
 * Каждой лампе назначается зона экрана либо «не использовать». Сохранение пишет привязку
 * одной строкой в [keyLamps] - её разберёт клиент при следующем запуске вывода.
 */
@Composable
internal fun ZoneLampsDialog(
    prefs: Preferences,
    title: String,
    hint: String,
    keyLamps: Int,
    fetch: suspend () -> List<Pair<String, String>>,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
    errorRes: Int = R.string.ha_lamps_error,
    missingRes: Int = R.string.ha_lamps_lamp_missing,
    identify: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lights by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    // Текущая привязка: entity_id -> зона; null - лампа не используется
    val mapping = remember { mutableStateMapOf<String, HomeAssistantZone?>() }
    // Лампы, которые есть в сохранённой привязке, но HA сейчас их не отдал - например,
    // залипший тестовый стенд или устройство, отвалившееся от сети
    val missingIds = remember { mutableStateOf(setOf<String>()) }
    var zonePickerFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(loadAttempt) {
        loading = true
        error = null
        val saved = HomeAssistantLamp.parseList(prefs.getString(keyLamps, ""))

        try {
            val fetched = withContext(Dispatchers.IO) { fetch() }
            // Сохранённые лампы, которых HA больше не отдал, остаются в списке: пропавшая
            // из сети лампа не должна молча вылетать из привязки
            val known = fetched.map { it.first }.toSet()
            val missing = saved.filter { it.entityId !in known }
                .map { it.entityId to it.name.ifEmpty { it.entityId } }
            lights = fetched + missing
            missingIds.value = missing.map { it.first }.toSet()
            mapping.clear()
            for (lamp in saved) mapping[lamp.entityId] = lamp.zone
            loading = false
        } catch (e: Exception) {
            // Сюда попадает всё от неверного ключа доступа до недоступной сети - текст
            // уходит пользователю в диалог, повторить можно кнопкой
            error = e.message ?: e.javaClass.simpleName
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.ha_lamps_loading))
                }

                error != null -> Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(errorRes, error ?: ""),
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { loadAttempt++ }) {
                        Text(stringResource(R.string.scanner_retry_button))
                    }
                }

                lights.isEmpty() -> Text(stringResource(R.string.ha_lamps_empty))

                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(lights, key = { it.first }) { (entityId, name) ->
                        LampRow(
                            entityId = entityId,
                            name = name,
                            zone = mapping[entityId],
                            missingText = if (entityId in missingIds.value) stringResource(missingRes) else null,
                            onClick = { zonePickerFor = entityId to name },
                            onIdentify = identify?.let { blink ->
                                {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            blink(entityId)
                                        } catch (e: IOException) {
                                            // Лампа не ответила на вспышку, зону ей выбрать это не мешает
                                            Log.w(TAG, "Identify $entityId failed: ${e.message}")
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && error == null,
                onClick = {
                    val lamps = lights.mapNotNull { (entityId, name) ->
                        val zone = mapping[entityId] ?: return@mapNotNull null
                        HomeAssistantLamp(entityId, zone, name)
                    }
                    val serialized = HomeAssistantLamp.serialize(lamps)
                    prefs.putString(keyLamps, serialized)
                    onSaved(serialized)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )

    zonePickerFor?.let { (entityId, name) ->
        ZonePickerDialog(
            lampName = name,
            selected = mapping[entityId],
            onSelect = { zone ->
                mapping[entityId] = zone
                zonePickerFor = null
            },
            onDismiss = { zonePickerFor = null }
        )
    }
}

@Composable
private fun LampRow(
    entityId: String,
    name: String,
    zone: HomeAssistantZone?,
    missingText: String?,
    onClick: () -> Unit,
    onIdentify: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick
                )
                .padding(horizontal = 4.dp, vertical = 10.dp)
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entityId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (missingText != null) {
                Text(
                    text = missingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = zoneLabel(zone),
                style = MaterialTheme.typography.bodyMedium,
                color = if (zone == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
        if (onIdentify != null) {
            TextButton(onClick = onIdentify) {
                Text(stringResource(R.string.lamps_identify))
            }
        }
    }
}

@Composable
private fun ZonePickerDialog(
    lampName: String,
    selected: HomeAssistantZone?,
    onSelect: (HomeAssistantZone?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(lampName) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ZoneOption(null, selected, onSelect)
                for (zone in HomeAssistantZone.entries) {
                    ZoneOption(zone, selected, onSelect)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ZoneOption(
    zone: HomeAssistantZone?,
    selected: HomeAssistantZone?,
    onSelect: (HomeAssistantZone?) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { onSelect(zone) }
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = zone == selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(zoneLabel(zone))
    }
}

@Composable
private fun zoneLabel(zone: HomeAssistantZone?): String = stringResource(
    when (zone) {
        null -> R.string.ha_zone_none
        HomeAssistantZone.AVERAGE -> R.string.ha_zone_average
        HomeAssistantZone.LEFT -> R.string.ha_zone_left
        HomeAssistantZone.RIGHT -> R.string.ha_zone_right
        HomeAssistantZone.TOP -> R.string.ha_zone_top
        HomeAssistantZone.BOTTOM -> R.string.ha_zone_bottom
        HomeAssistantZone.TOP_LEFT -> R.string.ha_zone_top_left
        HomeAssistantZone.TOP_RIGHT -> R.string.ha_zone_top_right
        HomeAssistantZone.BOTTOM_LEFT -> R.string.ha_zone_bottom_left
        HomeAssistantZone.BOTTOM_RIGHT -> R.string.ha_zone_bottom_right
    }
)

/**
 * Лампы основного подключения. [found] - лампы, которые уже нашёл поиск устройств: тогда
 * сеть заново не опрашивается.
 */
@Composable
internal fun OutputLampsDialog(
    prefs: Preferences,
    type: OutputType,
    found: List<Pair<String, String>> = emptyList(),
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyLamps = type.lampsKey ?: return
    val hint = when (type) {
        OutputType.HUE -> stringResource(R.string.lamps_hint_hue)
        OutputType.YEELIGHT ->
            stringResource(R.string.lamps_hint_network) + " " + stringResource(R.string.output_yeelight_summary)

        OutputType.GOVEE ->
            stringResource(R.string.lamps_hint_network) + " " + stringResource(R.string.output_govee_summary)

        OutputType.ZIGBEE2MQTT -> stringResource(R.string.lamps_hint_z2m)

        else -> stringResource(R.string.lamps_hint_network)
    }
    ZoneLampsDialog(
        prefs = prefs,
        title = stringResource(R.string.lamps_dialog_title, stringResource(type.titleRes())),
        hint = hint,
        keyLamps = keyLamps,
        fetch = {
            found.ifEmpty {
                when (type) {
                    OutputType.HUE -> HueClient.lights(
                        prefs.getString(R.string.pref_key_host)?.trim().orEmpty(),
                        prefs.getString(R.string.pref_key_hue_username).orEmpty()
                    )

                    OutputType.WIZ -> WizClient.discover()
                    OutputType.YEELIGHT -> YeelightClient.discover()
                    OutputType.LIFX -> LifxClient.discover()
                    OutputType.GOVEE -> GoveeClient.discover()
                    OutputType.ZIGBEE2MQTT -> MqttAccess(prefs).run {
                        Zigbee2MqttClient.lights(host, port, username, password, baseTopic)
                    }

                    else -> emptyList()
                }
            }
        },
        onSaved = onSaved,
        onDismiss = onDismiss,
        errorRes = R.string.lamps_error,
        missingRes = R.string.lamps_lamp_missing,
        identify = { id ->
            when (type) {
                OutputType.HUE -> HueClient.flash(
                    prefs.getString(R.string.pref_key_host)?.trim().orEmpty(),
                    prefs.getString(R.string.pref_key_hue_username).orEmpty(),
                    id
                )

                OutputType.WIZ -> WizClient.flash(id)
                OutputType.YEELIGHT -> YeelightClient.flash(id)
                OutputType.LIFX -> LifxClient.flash(id)
                OutputType.GOVEE -> GoveeClient.flash(id)
                OutputType.ZIGBEE2MQTT -> MqttAccess(prefs).run {
                    Zigbee2MqttClient.flash(host, port, username, password, baseTopic, id)
                }

                else -> Unit
            }
        }
    )
}

/** Брокер из настроек: адрес и порт общие с остальными контроллерами. */
private class MqttAccess(prefs: Preferences) {
    val host = prefs.getString(R.string.pref_key_host)?.trim().orEmpty()
    val port = prefs.getInt(R.string.pref_key_port, MqttLink.DEFAULT_PORT)
    val username = prefs.getString(R.string.pref_key_mqtt_username).orEmpty()
    val password = prefs.getString(R.string.pref_key_mqtt_password).orEmpty()
    val baseTopic = prefs.getString(R.string.pref_key_z2m_base_topic)?.trim()?.ifEmpty { null }
        ?: Zigbee2MqttClient.DEFAULT_BASE_TOPIC
}
