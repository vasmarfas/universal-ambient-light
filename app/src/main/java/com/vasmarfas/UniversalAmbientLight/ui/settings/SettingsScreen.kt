package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.DebugInfoHelper
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberSettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onLedLayoutClick: () -> Unit = {},
    onCameraSetupClick: () -> Unit = {},
    onRemoteHostClick: () -> Unit = {},
    onRemoteTvsClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val remote = LocalRemote.current
    // В режиме пульта — зеркало настроек телевизора: все секции ниже правят его, не зная об этом
    val prefs = rememberSettingsPreferences()

    LaunchedEffect(Unit) {
        AnalyticsHelper.logSettingsOpened(context)
    }

    val state = remember(prefs) { SettingsScreenState(prefs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.pref_title))
                        remote?.tv?.let {
                            Text(
                                text = stringResource(R.string.remote_banner_title, it.name),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        // Новый снимок настроек с ТВ пересоздаёт поля — они перечитывают значения. Ключ —
        // ревизия, а не сам prefs: объект пересоздаётся при каждом возврате на экран, и
        // прокрутка теряла бы сохранённое место
        key(remote?.tv?.id, remote?.revision) {
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                if (remote != null && remote.connection != RemoteSession.Connection.CONNECTED) {
                    Text(
                        text = stringResource(R.string.remote_settings_offline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                ConnectionSection(prefs, state)
                HomeAssistantSecondarySection(prefs, state)
                CaptureSection(prefs, state, onLedLayoutClick, onCameraSetupClick)
                CameraIdleSection(prefs, state)
                BorderDetectionSection(prefs, state)
                SmoothingSection(prefs, state)
                if (remote == null) RemoteSection(onRemoteHostClick, onRemoteTvsClick)
                GeneralSection(prefs, state)
            }
        }
    }

    if (state.showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = {
                // Отказ без подтверждения — возвращаем прежний метод
                state.captureMethod = state.previousCaptureMethod
                state.captureMethodRevision++
                prefs.putString(R.string.pref_key_capture_method, state.previousCaptureMethod)
                state.showAccessibilityDisclosure = false
            },
            title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
            text = { Text(stringResource(R.string.accessibility_disclosure_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.showAccessibilityDisclosure = false
                        // Применяем выбор
                        state.captureMethod = "accessibility"
                        prefs.putString(R.string.pref_key_capture_method, "accessibility")
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "capture_method",
                            "accessibility"
                        )

                        // Открываем настройки
                        openAccessibilitySettings(
                            context
                        )
                    }
                ) {
                    Text(stringResource(R.string.accessibility_disclosure_button_accept))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Возвращаем прежний метод
                        state.captureMethod = state.previousCaptureMethod
                        state.captureMethodRevision++
                        prefs.putString(R.string.pref_key_capture_method, state.previousCaptureMethod)
                        state.showAccessibilityDisclosure = false
                    }
                ) {
                    Text(stringResource(R.string.accessibility_disclosure_button_deny))
                }
            }
        )
    }

    if (state.showDebugDialog) {
        // Сбор информации читает /proc и перечисляет кодеки — на ТВ-приставках это сотни
        // миллисекунд, и синхронно в композиции он подвешивал бы кадр открытия диалога
        var debugInfo by remember { mutableStateOf("…") }
        LaunchedEffect(Unit) {
            debugInfo = withContext(Dispatchers.IO) {
                if (remote != null) {
                    // Отладка нужна про телевизор, а не про телефон-пульт
                    runCatching {
                        RemoteSession.call(RemoteProtocol.OP_DEBUG_INFO).optString("text")
                    }.getOrElse { it.message.orEmpty() }
                } else {
                    DebugInfoHelper.getDebugInfo(context)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { state.showDebugDialog = false },
            title = { Text(stringResource(R.string.debug_info_title)) },
            text = {
                val scrollState = rememberScrollState()
                val focusRequester = remember { FocusRequester() }
                val dpadScope = rememberCoroutineScope()
                // Забираем фокус, чтобы кнопки пульта сразу прокручивали содержимое.
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
                Column(
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionDown ->
                                    if (scrollState.canScrollForward) {
                                        dpadScope.launch { scrollState.scrollBy(250f) }; true
                                    } else false

                                Key.DirectionUp ->
                                    if (scrollState.canScrollBackward) {
                                        dpadScope.launch { scrollState.scrollBy(-250f) }; true
                                    } else false

                                else -> false
                            }
                        }
                        .verticalScroll(scrollState)
                ) {
                    SelectionContainer {
                        Text(
                            text = debugInfo,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Debug Info", debugInfo)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, R.string.debug_info_copied, Toast.LENGTH_SHORT)
                            .show()
                    }
                ) {
                    Text(stringResource(R.string.action_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.showDebugDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    if (state.showScanDialog) {
        DeviceScanDialog(
            onDismiss = { state.showScanDialog = false },
            onDeviceSelected = { device ->
                val oldConnectionType = state.connectionType

                when (device.type) {
                    com.vasmarfas.UniversalAmbientLight.common.network.DeviceDetector.DeviceType.WLED -> {
                        val newConnectionType = "wled"
                        prefs.putString(R.string.pref_key_connection_type, newConnectionType)
                        state.connectionType = newConnectionType

                        val protocol = when (device.protocol) {
                            "ddp" -> "ddp"
                            "udp_raw" -> "udp_raw"
                            else -> "ddp"
                        }
                        state.wledProtocol = protocol
                        prefs.putString(R.string.pref_key_wled_protocol, protocol)

                        AnalyticsHelper.logProtocolChanged(
                            context,
                            oldConnectionType,
                            newConnectionType
                        )
                        AnalyticsHelper.updateProtocolProperty(context, newConnectionType)
                    }

                    com.vasmarfas.UniversalAmbientLight.common.network.DeviceDetector.DeviceType.HYPERION -> {
                        val newConnectionType = "hyperion"
                        prefs.putString(R.string.pref_key_connection_type, newConnectionType)
                        state.connectionType = newConnectionType

                        AnalyticsHelper.logProtocolChanged(
                            context,
                            oldConnectionType,
                            newConnectionType
                        )
                        AnalyticsHelper.updateProtocolProperty(context, newConnectionType)
                    }

                    else -> {}
                }

                prefs.putString(R.string.pref_key_host, device.host)
                prefs.putString(R.string.pref_key_port, device.port.toString())

                state.currentHost = device.host
                state.currentPort = device.port.toString()

                AnalyticsHelper.logHostChanged(context, device.host)
                AnalyticsHelper.logPortChanged(context, device.port)
                AnalyticsHelper.logSettingChanged(
                    context,
                    "device_scanned",
                    "${device.type}:${device.host}:${device.port}"
                )
            }
        )
    }
    if (state.showAdbPairingDialog) {
        val actions = remember(remote != null) {
            if (remote != null) RemoteAdbActions() else LocalAdbActions(context, prefs)
        }
        AdbPairingDialog(
            context = context,
            actions = actions,
            onDismiss = { state.showAdbPairingDialog = false }
        )
    }
    if (state.showHaLampsDialog) {
        HomeAssistantLampsDialog(
            prefs = prefs,
            keyHost = R.string.pref_key_host,
            keyPort = R.string.pref_key_port,
            keyToken = R.string.pref_key_ha_token,
            keyLamps = R.string.pref_key_ha_lamps,
            onSaved = { state.haLampsSpec = it },
            onDismiss = { state.showHaLampsDialog = false }
        )
    }
    if (state.showHa2LampsDialog) {
        HomeAssistantLampsDialog(
            prefs = prefs,
            keyHost = R.string.pref_key_ha2_host,
            keyPort = R.string.pref_key_ha2_port,
            keyToken = R.string.pref_key_ha2_token,
            keyLamps = R.string.pref_key_ha2_lamps,
            onSaved = { state.ha2LampsSpec = it },
            onDismiss = { state.showHa2LampsDialog = false }
        )
    }
}
