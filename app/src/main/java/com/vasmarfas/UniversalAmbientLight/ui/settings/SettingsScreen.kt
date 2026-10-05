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
import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantClient
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.DebugInfoHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.devices.HueAreaDialog
import com.vasmarfas.UniversalAmbientLight.ui.devices.PairDialog
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
    onDelayClick: () -> Unit = {},
    onControllerClick: () -> Unit = {},
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
        key(remote?.tv?.id, remote?.revision, state.resetRevision) {
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
                ConnectionSection(prefs, state, onControllerClick)
                HomeAssistantSecondarySection(prefs, state)
                CaptureSection(prefs, state, onLedLayoutClick, onCameraSetupClick)
                CameraIdleSection(prefs, state)
                BorderDetectionSection(prefs, state)
                SmoothingSection(prefs, state, onDelayClick)
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

    if (state.showResetPictureDialog) {
        AlertDialog(
            onDismissRequest = { state.showResetPictureDialog = false },
            title = { Text(stringResource(R.string.pref_title_reset_picture)) },
            text = { Text(stringResource(R.string.pref_summary_reset_picture)) },
            confirmButton = {
                TextButton(onClick = {
                    state.showResetPictureDialog = false
                    state.resetPicture(prefs)
                    AnalyticsHelper.logSettingChanged(context, "picture_settings", "reset")
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { state.showResetPictureDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
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
        ZoneLampsDialog(
            prefs = prefs,
            title = stringResource(R.string.ha_lamps_dialog_title),
            hint = stringResource(R.string.ha_lamps_hint),
            keyLamps = R.string.pref_key_ha_lamps,
            fetch = { homeAssistantLights(prefs, R.string.pref_key_host, R.string.pref_key_port, R.string.pref_key_ha_token) },
            onSaved = { state.haLampsSpec = it },
            onDismiss = { state.showHaLampsDialog = false },
            identify = { homeAssistantFlash(prefs, R.string.pref_key_host, R.string.pref_key_port, R.string.pref_key_ha_token, it) }
        )
    }
    if (state.showLampsDialog) {
        OutputLampsDialog(
            prefs = prefs,
            type = OutputType.of(state.connectionType),
            onSaved = { state.lampsSpec = it },
            onDismiss = { state.showLampsDialog = false }
        )
    }
    if (state.showPairDialog) {
        val type = OutputType.of(state.connectionType)
        PairDialog(
            prefs = prefs,
            type = type,
            onPaired = {
                state.showPairDialog = false
                state.pairingKey = prefs.getString(
                    if (type == OutputType.HUE) R.string.pref_key_hue_username else R.string.pref_key_nanoleaf_token
                ).orEmpty()
                // Мосту сразу нужны лампы или зона: без них подсветке нечего включать
                if (type == OutputType.HUE && state.lampsSpec.isBlank() && state.hueArea.isBlank()) {
                    state.showHueAreaDialog = true
                }
            },
            onDismiss = { state.showPairDialog = false }
        )
    }
    if (state.showHueAreaDialog) {
        HueAreaDialog(
            prefs = prefs,
            onPicked = { area ->
                state.showHueAreaDialog = false
                state.hueArea = area.orEmpty()
                state.hueAreaName = prefs.getString(R.string.pref_key_hue_area_name).orEmpty()
                if (area == null && state.lampsSpec.isBlank()) state.showLampsDialog = true
            },
            onRepair = {
                state.showHueAreaDialog = false
                state.showPairDialog = true
            },
            onDismiss = { state.showHueAreaDialog = false }
        )
    }
    if (state.showHa2LampsDialog) {
        ZoneLampsDialog(
            prefs = prefs,
            title = stringResource(R.string.ha_lamps_dialog_title),
            hint = stringResource(R.string.ha_lamps_hint),
            keyLamps = R.string.pref_key_ha2_lamps,
            fetch = { homeAssistantLights(prefs, R.string.pref_key_ha2_host, R.string.pref_key_ha2_port, R.string.pref_key_ha2_token) },
            onSaved = { state.ha2LampsSpec = it },
            onDismiss = { state.showHa2LampsDialog = false },
            identify = { homeAssistantFlash(prefs, R.string.pref_key_ha2_host, R.string.pref_key_ha2_port, R.string.pref_key_ha2_token, it) }
        )
    }
}

private fun homeAssistantLights(prefs: Preferences, keyHost: Int, keyPort: Int, keyToken: Int) =
    HomeAssistantClient.fetchLights(
        prefs.getString(keyHost, "")?.trim().orEmpty(),
        prefs.getInt(keyPort, 8123),
        prefs.getString(keyToken, "").orEmpty()
    )

private fun homeAssistantFlash(prefs: Preferences, keyHost: Int, keyPort: Int, keyToken: Int, entityId: String) =
    HomeAssistantClient.flash(
        prefs.getString(keyHost, "")?.trim().orEmpty(),
        prefs.getInt(keyPort, 8123),
        prefs.getString(keyToken, "").orEmpty(),
        entityId
    )
