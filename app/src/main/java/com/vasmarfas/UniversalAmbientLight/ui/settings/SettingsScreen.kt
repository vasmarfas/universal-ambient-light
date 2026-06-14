package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.MtkThalCaptureEncoder
import com.vasmarfas.UniversalAmbientLight.common.util.AdbAutoPair
import com.vasmarfas.UniversalAmbientLight.common.util.AdbKeyHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AppAdbConnectionManager
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import com.vasmarfas.UniversalAmbientLight.common.util.DebugInfoHelper
import com.vasmarfas.UniversalAmbientLight.common.util.DevOptionsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onLedLayoutClick: () -> Unit = {},
    onCameraSetupClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }

    LaunchedEffect(Unit) {
        AnalyticsHelper.logSettingsOpened(context)
    }

    // State for dependencies
    var captureSource by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_capture_source) ?: "screen")
    }
    var connectionType by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_connection_type) ?: "hyperion")
    }
    var reconnectEnabled by remember {
        mutableStateOf(prefs.getBoolean(R.string.pref_key_reconnect))
    }
    var wledProtocol by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_wled_protocol) ?: "udp_raw")
    }
    var smoothingPreset by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_smoothing_preset) ?: "off")
    }

    // State for host/port to trigger recomposition
    var currentHost by remember { mutableStateOf(prefs.getString(R.string.pref_key_host) ?: "") }
    var currentPort by remember { mutableStateOf(prefs.getString(R.string.pref_key_port) ?: "") }

    // State for color processing visibility
    var colorProcessingEnabled by remember {
        mutableStateOf(prefs.getBoolean(R.string.pref_key_color_processing_enabled, true))
    }

    // State for device scan dialog
    var showScanDialog by remember { mutableStateOf(false) }

    // State for debug dialog
    var showDebugDialog by remember { mutableStateOf(false) }

    // State for ADB pairing
    var showAdbPairingDialog by remember { mutableStateOf(false) }
    rememberCoroutineScope()

    var captureMethod by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_capture_method) ?: "media_projection")
    }

    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var previousCaptureMethod by remember { mutableStateOf(captureMethod) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pref_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsGroup(title = stringResource(R.string.pref_group_connection)) {

                key(connectionType) {
                    ListPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_connection_type,
                        title = stringResource(R.string.pref_title_connection_type),
                        entriesRes = R.array.pref_list_connection_type,
                        entryValuesRes = R.array.pref_list_connection_type_values,
                        recomposeKey = connectionType,
                        onValueChange = { newType ->
                            val oldType = connectionType
                            connectionType = newType
                            AnalyticsHelper.logProtocolChanged(context, oldType, newType)
                            AnalyticsHelper.logSettingChanged(context, "connection_type", newType)
                            AnalyticsHelper.updateProtocolProperty(context, newType)
                            val defaultPort = when (newType) {
                                "hyperion" -> "19400"
                                "wled" -> if (wledProtocol == "ddp") "4048" else "19446"
                                else -> null
                            }
                            if (defaultPort != null) {
                                prefs.putString(R.string.pref_key_port, defaultPort)
                            }
                        }
                    )
                }

                val isNetwork = connectionType == "hyperion" || connectionType == "wled"
                val isAdalight = connectionType == "adalight"
                val isWled = connectionType == "wled"
                val isHyperion = connectionType == "hyperion"

                if (isNetwork) {
                    ClickablePreference(
                        title = stringResource(R.string.scanner_scan_devices),
                        summary = stringResource(R.string.scanner_description),
                        onClick = { showScanDialog = true }
                    )

                    key(connectionType) {
                        EditTextPreference(
                            prefs = prefs,
                            keyRes = R.string.pref_key_host,
                            title = stringResource(R.string.pref_title_host),
                            summaryProvider = { it },
                            recomposeKey = currentHost,
                            onValueChange = { newHost ->
                                currentHost = newHost
                                AnalyticsHelper.logHostChanged(context, newHost)
                                AnalyticsHelper.logSettingChanged(context, "host", newHost)
                            }
                        )
                    }

                    // Show WLED protocol selector between host and port for WLED connections
                    if (isWled) {
                        key(wledProtocol) {
                            ListPreference(
                                prefs = prefs,
                                keyRes = R.string.pref_key_wled_protocol,
                                title = stringResource(R.string.pref_title_wled_protocol),
                                entriesRes = R.array.pref_list_wled_protocol,
                                entryValuesRes = R.array.pref_list_wled_protocol_values,
                                onValueChange = { newProtocol ->
                                    wledProtocol
                                    wledProtocol = newProtocol
                                    AnalyticsHelper.logSettingChanged(
                                        context,
                                        "wled_protocol",
                                        newProtocol
                                    )
                                    // Auto-set port when WLED protocol changes
                                    val defaultPort = if (newProtocol == "ddp") "4048" else "19446"
                                    prefs.putString(R.string.pref_key_port, defaultPort)
                                    currentPort = defaultPort
                                }
                            )
                        }
                    }

                    // Use key to force recomposition when connection type or WLED protocol changes
                    key("${connectionType}_${wledProtocol}") {
                        EditTextPreference(
                            prefs = prefs,
                            keyRes = R.string.pref_key_port,
                            title = stringResource(R.string.pref_title_port),
                            summaryProvider = { it },
                            keyboardType = KeyboardType.Number,
                            recomposeKey = currentPort,
                            onValueChange = { newPort ->
                                currentPort = newPort
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
                                reconnectEnabled = enabled
                                AnalyticsHelper.logAutoReconnectEnabled(context, enabled)
                                AnalyticsHelper.logSettingChanged(
                                    context,
                                    "reconnect",
                                    enabled.toString()
                                )
                                AnalyticsHelper.updateAutoReconnectProperty(context, enabled)
                            }
                        )
                        if (reconnectEnabled) {
                            EditTextPreference(
                                prefs = prefs,
                                keyRes = R.string.pref_key_reconnect_delay,
                                title = stringResource(R.string.pref_title_reconnect_delay),
                                summaryProvider = { it },
                                keyboardType = KeyboardType.Number,
                                onValueChange = { newDelay ->
                                    val delayInt = newDelay.toIntOrNull() ?: 0
                                    AnalyticsHelper.logReconnectDelayChanged(context, delayInt)
                                    AnalyticsHelper.logSettingChanged(
                                        context,
                                        "reconnect_delay",
                                        newDelay
                                    )
                                }
                            )
                        }
                    }
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
                        onValueChange = { enabled ->
                            AnalyticsHelper.logRgbwChanged(context, enabled)
                            AnalyticsHelper.logSettingChanged(
                                context,
                                "wled_rgbw",
                                enabled.toString()
                            )
                        }
                    )
                }
            }

            // Capturing Group
            SettingsGroup(title = stringResource(R.string.pref_group_capturing)) {
                // Capture Source (Screen / Camera)
                key(captureSource) {
                    ListPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_capture_source,
                        title = stringResource(R.string.pref_title_capture_source),
                        entriesRes = R.array.pref_list_capture_source,
                        entryValuesRes = R.array.pref_list_capture_source_values,
                        recomposeKey = captureSource,
                        onValueChange = { newSource ->
                            captureSource = newSource
                            AnalyticsHelper.logSettingChanged(context, "capture_source", newSource)
                        }
                    )
                }

                // Capture Method (MediaProjection, Screencap, Accessibility)
                if (captureSource == "screen") {
                    ListPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_capture_method,
                        title = stringResource(R.string.pref_title_capture_method),
                        entriesRes = R.array.pref_list_capture_method,
                        entryValuesRes = R.array.pref_list_capture_method_values,
                        recomposeKey = captureMethod,
                        disabledIndices = remember {
                            val entryValues =
                                context.resources.getStringArray(R.array.pref_list_capture_method_values)
                            val disabled = mutableSetOf<Int>()
                            entryValues.forEachIndexed { index, value ->
                                when (value) {
                                    "mtk_thal_capture" -> if (!MtkThalCaptureEncoder.isAvailable()) disabled.add(
                                        index
                                    )
                                }
                            }
                            disabled
                        },
                        onValueChange = { newMethod ->
                            if (newMethod == "accessibility") {
                                // Check if service is already enabled
                                if (AccessibilityCaptureService.getInstance() == null) {
                                    // Show disclosure dialog BEFORE applying fully or opening settings
                                    // Note: ListPreference already saved the value to prefs, so we might need to revert if denied
                                    previousCaptureMethod =
                                        captureMethod // save old method (which is actually current before update in state)
                                    // Ideally ListPreference shouldn't update automatically, but here we intercept
                                    showAccessibilityDisclosure = true
                                } else {
                                    captureMethod = newMethod
                                    AnalyticsHelper.logSettingChanged(
                                        context,
                                        "capture_method",
                                        newMethod
                                    )
                                }
                            } else {
                                captureMethod = newMethod
                                AnalyticsHelper.logSettingChanged(
                                    context,
                                    "capture_method",
                                    newMethod
                                )
                            }
                        }
                    )

                    if (captureMethod == "adb_local" || captureMethod == "adb_stream" || captureMethod == "scrcpy") {
                        EditTextPreference(
                            prefs = prefs,
                            keyRes = R.string.pref_key_adb_port,
                            title = stringResource(R.string.pref_title_adb_port),
                            summaryProvider = { it },
                            keyboardType = KeyboardType.Number
                        )
                        ClickablePreference(
                            title = stringResource(R.string.pref_btn_adb_pair),
                            summary = stringResource(R.string.pref_summary_adb_pair),
                            onClick = { showAdbPairingDialog = true }
                        )
                    }
                }

                // Camera corner setup (only when camera source is selected)
                if (captureSource == "camera") {
                    ClickablePreference(
                        title = stringResource(R.string.pref_title_camera_setup),
                        summary = stringResource(R.string.pref_summary_camera_setup),
                        onClick = { onCameraSetupClick() }
                    )
                }

                ClickablePreference(
                    title = stringResource(R.string.pref_title_led_layout),
                    summary = stringResource(R.string.pref_summary_led_layout),
                    onClick = {
                        AnalyticsHelper.logLedLayoutOpened(context)
                        onLedLayoutClick()
                    }
                )
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_framerate,
                    title = stringResource(R.string.pref_title_framerate),
                    entriesRes = R.array.pref_list_framerate,
                    entryValuesRes = R.array.pref_list_framerate_values,
                    onValueChange = { newFramerate ->
                        val framerateInt = newFramerate.toIntOrNull() ?: 10
                        AnalyticsHelper.logFramerateChanged(context, framerateInt)
                        AnalyticsHelper.logSettingChanged(context, "framerate", newFramerate)
                    }
                )
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_capture_quality,
                    title = stringResource(R.string.pref_title_capture_quality),
                    entriesRes = R.array.pref_list_capture_quality,
                    entryValuesRes = R.array.pref_list_capture_quality_values,
                    onValueChange = { newQuality ->
                        val qualityInt = newQuality.toIntOrNull() ?: 128
                        AnalyticsHelper.logCaptureQualityChanged(context, qualityInt)
                        AnalyticsHelper.logSettingChanged(context, "capture_quality", newQuality)
                    }
                )
                CheckBoxPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_use_avg_color,
                    title = stringResource(R.string.pref_title_use_avg_color),
                    onValueChange = { enabled ->
                        AnalyticsHelper.logUseAvgColorChanged(context, enabled)
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "use_avg_color",
                            enabled.toString()
                        )
                    }
                )

                // Color processing settings
                CheckBoxPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_color_processing_enabled,
                    title = stringResource(R.string.pref_title_color_processing_enabled),
                    onValueChange = { enabled ->
                        colorProcessingEnabled = enabled
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "color_processing_enabled",
                            enabled.toString()
                        )
                    }
                )

                if (colorProcessingEnabled) {
                    // Bumped on every per-channel/global color pref change; drives the live preview below.
                    var colorPrefsVersion by remember { mutableIntStateOf(0) }

                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_color_brightness,
                        title = stringResource(R.string.pref_title_color_brightness),
                        summaryProvider = { "${it}%" },
                        keyboardType = KeyboardType.Number,
                        onValueChange = { newValue ->
                            colorPrefsVersion++
                            AnalyticsHelper.logSettingChanged(context, "color_brightness", newValue)
                        }
                    )
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_color_contrast,
                        title = stringResource(R.string.pref_title_color_contrast),
                        summaryProvider = { "${it}%" },
                        keyboardType = KeyboardType.Number,
                        onValueChange = { newValue ->
                            colorPrefsVersion++
                            AnalyticsHelper.logSettingChanged(context, "color_contrast", newValue)
                        }
                    )
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_color_black_level,
                        title = stringResource(R.string.pref_title_color_black_level),
                        summaryProvider = { "${it}%" },
                        keyboardType = KeyboardType.Number,
                        onValueChange = { newValue ->
                            colorPrefsVersion++
                            AnalyticsHelper.logSettingChanged(
                                context,
                                "color_black_level",
                                newValue
                            )
                        }
                    )
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_color_white_level,
                        title = stringResource(R.string.pref_title_color_white_level),
                        summaryProvider = { "${it}%" },
                        keyboardType = KeyboardType.Number,
                        onValueChange = { newValue ->
                            colorPrefsVersion++
                            AnalyticsHelper.logSettingChanged(
                                context,
                                "color_white_level",
                                newValue
                            )
                        }
                    )
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_color_saturation,
                        title = stringResource(R.string.pref_title_color_saturation),
                        summaryProvider = { "${it}%" },
                        keyboardType = KeyboardType.Number,
                        onValueChange = { newValue ->
                            colorPrefsVersion++
                            AnalyticsHelper.logSettingChanged(context, "color_saturation", newValue)
                        }
                    )

                    // Per-channel correction subsection (issue #21).
                    Text(
                        text = stringResource(R.string.pref_group_color_per_channel),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                    )
                    listOf(
                        R.string.pref_key_color_brightness_r to R.string.pref_title_color_brightness_r,
                        R.string.pref_key_color_brightness_g to R.string.pref_title_color_brightness_g,
                        R.string.pref_key_color_brightness_b to R.string.pref_title_color_brightness_b
                    ).forEach { (keyRes, titleRes) ->
                        val keyName = stringResource(keyRes)
                        EditTextPreference(
                            prefs = prefs,
                            keyRes = keyRes,
                            title = stringResource(titleRes),
                            summaryProvider = { "${it}%" },
                            keyboardType = KeyboardType.Number,
                            onValueChange = { newValue ->
                                colorPrefsVersion++
                                AnalyticsHelper.logSettingChanged(
                                    context,
                                    keyName,
                                    newValue
                                )
                            }
                        )
                    }
                    listOf(
                        R.string.pref_key_color_gamma_r to R.string.pref_title_color_gamma_r,
                        R.string.pref_key_color_gamma_g to R.string.pref_title_color_gamma_g,
                        R.string.pref_key_color_gamma_b to R.string.pref_title_color_gamma_b
                    ).forEach { (keyRes, titleRes) ->
                        val keyName = stringResource(keyRes)
                        EditTextPreference(
                            prefs = prefs,
                            keyRes = keyRes,
                            title = stringResource(titleRes),
                            summaryProvider = { "${it}%" },
                            keyboardType = KeyboardType.Number,
                            onValueChange = { newValue ->
                                colorPrefsVersion++
                                AnalyticsHelper.logSettingChanged(
                                    context,
                                    keyName,
                                    newValue
                                )
                            }
                        )
                    }

                    ColorProcessingPreview(prefs = prefs, version = colorPrefsVersion)
                }
            }

            // Letterbox (black bar) detection (issue #23).
            SettingsGroup(title = stringResource(R.string.pref_group_border_detection)) {
                val rgbUnit = stringResource(R.string.unit_rgb)
                val framesUnit = stringResource(R.string.unit_frames)
                CheckBoxPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_border_detection_enabled,
                    title = stringResource(R.string.pref_title_border_detection_enabled),
                    summary = stringResource(R.string.pref_summary_border_detection_enabled),
                    onValueChange = { enabled ->
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "border_detection_enabled",
                            enabled.toString()
                        )
                    }
                )
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_border_threshold,
                    title = stringResource(R.string.pref_title_border_threshold),
                    summaryProvider = { "$it $rgbUnit" },
                    keyboardType = KeyboardType.Number,
                    onValueChange = { newValue ->
                        AnalyticsHelper.logSettingChanged(context, "border_threshold", newValue)
                    }
                )
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_border_check_interval,
                    title = stringResource(R.string.pref_title_border_check_interval),
                    summaryProvider = { "$it $framesUnit" },
                    keyboardType = KeyboardType.Number,
                    onValueChange = { newValue ->
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "border_check_interval",
                            newValue
                        )
                    }
                )
            }

            // Smoothing Group
            SettingsGroup(title = stringResource(R.string.pref_group_smoothing)) {
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
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_smoothing_preset,
                    title = stringResource(R.string.pref_title_smoothing_preset),
                    entriesRes = R.array.pref_list_smoothing_preset,
                    entryValuesRes = R.array.pref_list_smoothing_preset_values,
                    onValueChange = { preset ->
                        smoothingPreset = preset
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
                key(smoothingPreset) {
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_settling_time,
                        title = stringResource(R.string.pref_title_settling_time),
                        summaryProvider = { value ->
                            val ms = value?.toIntOrNull() ?: 50
                            "$ms мс"
                        },
                        keyboardType = KeyboardType.Number,
                        recomposeKey = smoothingPreset
                    )
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_output_delay,
                        title = stringResource(R.string.pref_title_output_delay),
                        summaryProvider = { value ->
                            val ms = value?.toIntOrNull() ?: 0
                            "$ms мс"
                        },
                        keyboardType = KeyboardType.Number,
                        recomposeKey = smoothingPreset
                    )
                    ListPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_update_frequency,
                        title = stringResource(R.string.pref_title_update_frequency),
                        entriesRes = R.array.pref_list_update_frequency,
                        entryValuesRes = R.array.pref_list_update_frequency_values,
                        recomposeKey = smoothingPreset
                    )
                }
            }

            // General Group
            SettingsGroup(title = stringResource(R.string.pref_group_general)) {
                CheckBoxPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_boot,
                    title = stringResource(R.string.pref_title_boot),
                    onValueChange = { enabled ->
                        AnalyticsHelper.logBootStartEnabled(context, enabled)
                        AnalyticsHelper.logSettingChanged(context, "boot_start", enabled.toString())
                    }
                )
                CheckBoxPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_standby_keepalive,
                    title = stringResource(R.string.pref_title_standby_keepalive),
                    summary = stringResource(R.string.pref_summary_standby_keepalive),
                    onValueChange = { enabled ->
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "standby_keepalive",
                            enabled.toString()
                        )
                    }
                )
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_language,
                    title = stringResource(R.string.pref_title_language),
                    entriesRes = R.array.pref_list_language,
                    entryValuesRes = R.array.pref_list_language_values,
                    onValueChange = { language ->
                        AnalyticsHelper.logLanguageChanged(context, language)
                        AnalyticsHelper.logSettingChanged(context, "language", language)
                        AnalyticsHelper.updateLanguageProperty(context, language)
                        LocaleHelper.setLocale(context, language)
                        (context as? Activity)?.recreate()
                    }
                )
            }

            // Debug Group
            SettingsGroup(title = "Debug") {
                ClickablePreference(
                    title = "Device Info",
                    summary = "Show device information for debugging",
                    onClick = { showDebugDialog = true }
                )
            }
        }
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = {
                // Revert change if dismissed without accepting
                captureMethod = previousCaptureMethod
                prefs.putString(R.string.pref_key_capture_method, previousCaptureMethod)
                showAccessibilityDisclosure = false
            },
            title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
            text = { Text(stringResource(R.string.accessibility_disclosure_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessibilityDisclosure = false
                        // Apply change
                        captureMethod = "accessibility"
                        prefs.putString(R.string.pref_key_capture_method, "accessibility")
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "capture_method",
                            "accessibility"
                        )

                        // Open settings
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
                        // Revert change
                        captureMethod = previousCaptureMethod
                        prefs.putString(R.string.pref_key_capture_method, previousCaptureMethod)
                        showAccessibilityDisclosure = false
                    }
                ) {
                    Text(stringResource(R.string.accessibility_disclosure_button_deny))
                }
            }
        )
    }

    if (showDebugDialog) {
        val debugInfo = remember { DebugInfoHelper.getDebugInfo(context) }
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = { Text("Debug Info") },
            text = {
                val scrollState = rememberScrollState()
                val focusRequester = remember { FocusRequester() }
                val dpadScope = rememberCoroutineScope()
                // Grab focus so the D-pad (TV remote) scrolls the content immediately.
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
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDebugDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showScanDialog) {
        DeviceScanDialog(
            onDismiss = { showScanDialog = false },
            onDeviceSelected = { device ->
                val oldConnectionType = connectionType

                when (device.type) {
                    com.vasmarfas.UniversalAmbientLight.common.network.DeviceDetector.DeviceType.WLED -> {
                        val newConnectionType = "wled"
                        prefs.putString(R.string.pref_key_connection_type, newConnectionType)
                        connectionType = newConnectionType

                        val protocol = when (device.protocol) {
                            "ddp" -> "ddp"
                            "udp_raw" -> "udp_raw"
                            else -> "ddp"
                        }
                        wledProtocol = protocol
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
                        connectionType = newConnectionType

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

                currentHost = device.host
                currentPort = device.port.toString()

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
    if (showAdbPairingDialog) {
        AdbPairingDialog(
            context = context,
            prefs = prefs,
            onDismiss = { showAdbPairingDialog = false }
        )
    }
}

/**
 * Live preview of the color-processing pipeline.
 * Reads all relevant prefs each time [version] changes (incremented by the
 * surrounding EditTextPreference fields) and renders three pure R/G/B swatches
 * plus a grayscale gradient, all after [ColorProcessor.processColor].
 */
@Composable
private fun ColorProcessingPreview(prefs: Preferences, version: Int) {
    val preview = remember(version) {
        val brightness = prefs.getInt(R.string.pref_key_color_brightness, 100)
        val contrast = prefs.getInt(R.string.pref_key_color_contrast, 100)
        val blackLevel = prefs.getInt(R.string.pref_key_color_black_level, 0)
        val whiteLevel = prefs.getInt(R.string.pref_key_color_white_level, 100)
        val saturation = prefs.getInt(R.string.pref_key_color_saturation, 100)
        val bR = prefs.getInt(R.string.pref_key_color_brightness_r, 100)
        val bG = prefs.getInt(R.string.pref_key_color_brightness_g, 100)
        val bB = prefs.getInt(R.string.pref_key_color_brightness_b, 100)
        val gR = prefs.getInt(R.string.pref_key_color_gamma_r, 100)
        val gG = prefs.getInt(R.string.pref_key_color_gamma_g, 100)
        val gB = prefs.getInt(R.string.pref_key_color_gamma_b, 100)

        val process: (Int, Int, Int) -> Color = { r, g, b ->
            val (ro, go, bo) = ColorProcessor.processColor(
                r, g, b,
                brightness, contrast, blackLevel, whiteLevel, saturation,
                bR, bG, bB, gR, gG, gB
            )
            Color(ro, go, bo)
        }
        Triple(
            process(255, 0, 0),
            process(0, 255, 0),
            process(0, 0, 255)
        ) to (0..8).map { step -> process(step * 32, step * 32, step * 32) }
    }
    val swatches = preview.first
    val ramp = preview.second

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.pref_color_preview_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.first))
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.second))
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.third))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(Brush.horizontalGradient(ramp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.pref_color_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AdbPairingDialog(
    context: Context,
    prefs: Preferences,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }

    var manualExpanded by remember { mutableStateOf(false) }
    var showAutoPairConsent by remember { mutableStateOf(false) }

    val devEnabled = remember { DevOptionsHelper.isDeveloperOptionsEnabled(context) }
    val adbEnabled = remember { DevOptionsHelper.isAdbEnabled(context) }
    val onLabel = stringResource(R.string.adb_status_on)
    val offLabel = stringResource(R.string.adb_status_off)

    // When the user returns after enabling Accessibility (from the auto-pair flow), re-open
    // the consent dialog so pairing continues without re-tapping the button.
    val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                AccessibilityCaptureService.consumeAutoPairPending() &&
                AccessibilityCaptureService.isAvailable()
            ) {
                showAutoPairConsent = true
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // Legacy connect (Android 10 and older, or after `adb tcpip 5555`): plain RSA over TCP.
    fun runLegacyConnect() {
        testing = true
        status = null
        scope.launch(Dispatchers.IO) {
            try {
                val port =
                    prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555
                val keyPair = AdbKeyHelper.getKeyPair(context)
                val dadb = Dadb.create("127.0.0.1", port, keyPair)
                dadb.shell("echo ok")
                dadb.close()
                withContext(Dispatchers.Main) {
                    testing = false
                    status = "✓ ${context.getString(R.string.adb_test_success)}"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    testing = false
                    status =
                        String.format(context.getString(R.string.adb_test_failed), e.message ?: "?")
                }
            }
        }
    }

    // Accessibility-assisted auto pairing. Only runs after explicit consent (see consent dialog).
    fun runAutoPair() {
        testing = true
        status = null
        scope.launch(Dispatchers.IO) {
            if (!AccessibilityCaptureService.isAvailable()) {
                // Ask the service to bounce back to us once the user enables it,
                // and to re-open the consent dialog so pairing continues automatically.
                AccessibilityCaptureService.requestReturnToAppOnConnect()
                AccessibilityCaptureService.markAutoPairPending()
                withContext(Dispatchers.Main) {
                    testing = false
                    status = context.getString(R.string.adb_autopair_need_accessibility)
                    openAccessibilitySettings(context)
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.adb_enable_accessibility_toast,
                            context.getString(R.string.app_name)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                status = context.getString(R.string.adb_autopair_waiting)
                DevOptionsHelper.openWirelessDebugging(context)
            }
            val result = AdbAutoPair.run(context)
            withContext(Dispatchers.Main) {
                testing = false
                status = when (result) {
                    is AdbAutoPair.Result.Paired ->
                        "✓ ${context.getString(R.string.adb_pair_success)}"

                    is AdbAutoPair.Result.NeedsAccessibility ->
                        context.getString(R.string.adb_autopair_need_accessibility)

                    is AdbAutoPair.Result.Timeout ->
                        context.getString(R.string.adb_autopair_timeout)

                    is AdbAutoPair.Result.Failed ->
                        String.format(context.getString(R.string.adb_pair_failed), result.message)
                }
                status?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                if (result is AdbAutoPair.Result.Paired) {
                    val launch =
                        context.packageManager.getLaunchIntentForPackage(context.packageName)
                    launch?.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    if (launch != null) try {
                        context.startActivity(launch)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    if (showAutoPairConsent) {
        AlertDialog(
            onDismissRequest = { showAutoPairConsent = false },
            title = { Text(stringResource(R.string.adb_autopair_consent_title)) },
            text = { Text(stringResource(R.string.adb_autopair_consent_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showAutoPairConsent = false
                    runAutoPair()
                }) { Text(stringResource(R.string.adb_autopair_consent_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showAutoPairConsent = false }) {
                    Text(stringResource(R.string.scanner_cancel))
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text(stringResource(R.string.adb_pair_title)) },
        text = {
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.adb_pair_instruction),
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(
                            R.string.adb_status_label,
                            if (devEnabled) onLabel else offLabel,
                            if (adbEnabled) onLabel else offLabel
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (devEnabled && adbEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (devEnabled) {
                                if (!DevOptionsHelper.openDeveloperOptions(context)) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.adb_toast_cannot_open_dev),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.adb_toast_dev_options_help),
                                    Toast.LENGTH_LONG
                                ).show()
                                DevOptionsHelper.openAboutDeviceForBuildNumber(context)
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (devEnabled) R.string.adb_btn_open_dev_options
                                else R.string.adb_btn_how_to_enable_dev_options
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (!DevOptionsHelper.openWirelessDebugging(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.adb_toast_cannot_open_wireless),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    ) { Text(stringResource(R.string.adb_btn_open_wireless_debug)) }

                    // Legacy connection FIRST — Android 10 and older, or after `adb tcpip 5555`.
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.adb_legacy_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { runLegacyConnect() }
                    ) { Text(stringResource(R.string.adb_legacy_btn)) }

                    // Android 11+ pairing: shown below the legacy option. One-time pairing; the
                    // automatic button reads the code via Accessibility (after explicit consent).
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.adb_pair_code_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // One-tap path: shows a consent dialog first, then the app reads the code via
                        // Accessibility, finds the port via mDNS and pairs without leaving the screen.
                        OutlinedButton(
                            enabled = !testing,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showAutoPairConsent = true }
                        ) { Text(stringResource(R.string.adb_autopair_btn)) }

                        Spacer(modifier = Modifier.height(6.dp))
                        // Manual entry hidden behind a spoiler so it doesn't clutter D-pad navigation.
                        TextButton(
                            onClick = { manualExpanded = !manualExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = (if (manualExpanded) "▾ " else "▸ ") +
                                        stringResource(R.string.adb_pair_manual_label)
                            )
                        }
                        if (manualExpanded) {
                            Text(
                                text = stringResource(R.string.adb_pair_code_instruction),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pairPort,
                                onValueChange = {
                                    pairPort = it.filter { c -> c.isDigit() }.take(5)
                                },
                                label = { Text(stringResource(R.string.adb_pair_port_hint)) },
                                singleLine = true,
                                enabled = !testing,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = pairCode,
                                onValueChange = {
                                    pairCode = it.filter { c -> c.isDigit() }.take(6)
                                },
                                label = { Text(stringResource(R.string.adb_pair_code_hint)) },
                                singleLine = true,
                                enabled = !testing,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                enabled = !testing && pairCode.length == 6 && pairPort.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    testing = true
                                    status = null
                                    val code = pairCode
                                    val port = pairPort.toIntOrNull() ?: 0
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val mgr = AppAdbConnectionManager.getInstance(context)
                                            // adbd's pairing server binds to the LAN IP; fall back to loopback.
                                            val host =
                                                io.github.muntashirakon.adb.android.AndroidUtils.getHostIpAddress(
                                                    context
                                                ) ?: "127.0.0.1"
                                            val ok = mgr.pair(host, port, code)
                                            withContext(Dispatchers.Main) {
                                                testing = false
                                                status =
                                                    if (ok) "✓ ${context.getString(R.string.adb_pair_success)}"
                                                    else String.format(
                                                        context.getString(R.string.adb_pair_failed),
                                                        "?"
                                                    )
                                            }
                                        } catch (e: Throwable) {
                                            withContext(Dispatchers.Main) {
                                                testing = false
                                                status = String.format(
                                                    context.getString(R.string.adb_pair_failed),
                                                    e.message ?: "?"
                                                )
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(if (testing) R.string.adb_pairing else R.string.adb_pair_btn))
                            }
                        } // end if (manualExpanded)
                    }
                }

                // Always-visible status + progress, pinned below the scroll area.
                if (status != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = status!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status!!.startsWith("✓"))
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
                if (testing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    status = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val port =
                                prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull()
                                    ?: 5555
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                // Android 11+: TLS connect (auto-discovers the connect port; falls back to the entered port).
                                val mgr = AppAdbConnectionManager.getInstance(context)
                                if (!mgr.isConnected) {
                                    val auto = try {
                                        mgr.autoConnect(context, 8000)
                                    } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                                        throw e
                                    } catch (_: Exception) {
                                        false
                                    }
                                    if (!auto && port > 0) mgr.connect("127.0.0.1", port)
                                }
                                val ok = mgr.isConnected
                                withContext(Dispatchers.Main) {
                                    testing = false
                                    status =
                                        if (ok) "✓ ${context.getString(R.string.adb_test_success)}"
                                        else String.format(
                                            context.getString(R.string.adb_test_failed),
                                            "not connected"
                                        )
                                }
                            } else {
                                // Android <= 10: legacy RSA over TCP (port 5555).
                                val keyPair = AdbKeyHelper.getKeyPair(context)
                                val dadb = Dadb.create("127.0.0.1", port, keyPair)
                                dadb.shell("echo ok")
                                dadb.close()
                                withContext(Dispatchers.Main) {
                                    testing = false
                                    status = "✓ ${context.getString(R.string.adb_test_success)}"
                                }
                            }
                        } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                            withContext(Dispatchers.Main) {
                                testing = false
                                status = context.getString(R.string.error_adb_pairing_required)
                            }
                        } catch (e: Throwable) {
                            withContext(Dispatchers.Main) {
                                testing = false
                                status = String.format(
                                    context.getString(R.string.adb_test_failed),
                                    e.message ?: "unknown"
                                )
                            }
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.pref_btn_adb_pair))
            }
        },
        dismissButton = {
            TextButton(enabled = !testing, onClick = onDismiss) {
                Text(stringResource(R.string.scanner_cancel))
            }
        }
    )
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun CheckBoxPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    summary: String? = null,
    onValueChange: ((Boolean) -> Unit)? = null,
) {
    var checked by remember { mutableStateOf(prefs.getBoolean(keyRes)) }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Checkbox,
                onValueChange = {
                    checked = it
                    prefs.putBoolean(keyRes, it)
                    onValueChange?.invoke(it)
                }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun EditTextPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    summaryProvider: (String) -> String = { it },
    keyboardType: KeyboardType = KeyboardType.Text,
    externalValue: String? = null,
    onValueChange: ((String) -> Unit)? = null,
    recomposeKey: Any? = null,
) {
    // For numeric prefs the xml defaults live in <integer pref_default_*>; getString()
    // doesn't see them. Fall back to getInt() so the UI shows the resource default
    // instead of an empty field on first launch.
    fun readInitial(): String {
        val stored = prefs.getString(keyRes)
        if (!stored.isNullOrEmpty()) return stored
        return if (keyboardType == KeyboardType.Number) prefs.getInt(keyRes).toString() else ""
    }

    var value by remember(keyRes, recomposeKey) { mutableStateOf(readInitial()) }

    LaunchedEffect(externalValue, recomposeKey) {
        externalValue?.let { value = it }
        recomposeKey?.let { value = readInitial() }
    }

    // Reset dialog state when recomposeKey changes (e.g., when navigating away)
    // This ensures dialogs are closed when the screen is navigated away from
    var showDialog by remember(recomposeKey) { mutableStateOf(false) }

    // Close dialog when component is disposed (e.g., when navigating away)
    DisposableEffect(Unit) {
        onDispose {
            // Force close dialog when leaving the screen
            showDialog = false
        }
    }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { showDialog = true }
            )
            .padding(16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summaryProvider(value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDialog) {
        var tempValue by remember(showDialog) { mutableStateOf(value) }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(showDialog) {
            if (showDialog) {
                tempValue = value
            }
        }

        fun applyValue() {
            value = tempValue
            prefs.putString(keyRes, value)
            onValueChange?.invoke(value)
            keyboardController?.hide()
            showDialog = false
        }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { applyValue() }
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { applyValue() }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    keyboardController?.hide()
                    showDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ListPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    entriesRes: Int,
    entryValuesRes: Int,
    onValueChange: ((String) -> Unit)? = null,
    recomposeKey: Any? = null,
    disabledIndices: Set<Int> = emptySet(),
) {
    val entries = stringArrayResource(entriesRes)
    val entryValues = stringArrayResource(entryValuesRes)

    var value by remember(keyRes, recomposeKey) {
        mutableStateOf(
            prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: ""
        )
    }

    LaunchedEffect(recomposeKey) {
        recomposeKey?.let { value = prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: "" }
    }
    // Reset dialog state when recomposeKey changes (e.g., when navigating away)
    // This ensures dialogs are closed when the screen is navigated away from
    var showDialog by remember(recomposeKey) { mutableStateOf(false) }

    // Close dialog when component is disposed (e.g., when navigating away)
    DisposableEffect(Unit) {
        onDispose {
            // Force close dialog when leaving the screen
            showDialog = false
        }
    }
    val interactionSource = remember { MutableInteractionSource() }

    val summary = entries.getOrNull(entryValues.indexOf(value)) ?: value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { showDialog = true }
            )
            .padding(16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEachIndexed { index, entry ->
                        val isDisabled = index in disabledIndices
                        val interactionSource = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isDisabled) Modifier
                                    else Modifier.clickable(
                                        interactionSource = interactionSource,
                                        indication = LocalIndication.current,
                                        onClick = {
                                            val newValue = entryValues[index]
                                            value = newValue
                                            prefs.putString(keyRes, newValue)
                                            onValueChange?.invoke(newValue)
                                            showDialog = false
                                        }
                                    )
                                )
                                .padding(12.dp)
                                .alpha(if (isDisabled) 0.38f else 1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value == entryValues[index],
                                onClick = null,
                                enabled = !isDisabled
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = entry)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ClickablePreference(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (summary != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

