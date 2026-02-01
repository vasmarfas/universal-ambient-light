package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import android.app.Activity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onLedLayoutClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    
    // Логируем открытие настроек
    LaunchedEffect(Unit) {
        AnalyticsHelper.logSettingsOpened(context)
    }
    
    // State for dependencies
    var connectionType by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_connection_type) ?: "hyperion")
    }
    var reconnectEnabled by remember {
        mutableStateOf(prefs.getBoolean(R.string.pref_key_reconnect))
    }
    var wledProtocol by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_wled_protocol) ?: "ddp")
    }
    var smoothingPreset by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_smoothing_preset) ?: "balanced")
    }
    
    // State for device scan dialog
    var showScanDialog by remember { mutableStateOf(false) }

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
            // Connection Group
            SettingsGroup(title = stringResource(R.string.pref_group_connection)) {
                
                // Используем key для принудительного обновления при изменении connectionType
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
                            // Логируем изменение протокола
                            AnalyticsHelper.logProtocolChanged(context, oldType, newType)
                            AnalyticsHelper.logSettingChanged(context, "connection_type", newType)
                            AnalyticsHelper.updateProtocolProperty(context, newType)
                            // Auto-set port when connection type changes
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

                // Кнопка сканирования устройств (только для сетевых подключений)
                if (isNetwork) {
                    ClickablePreference(
                        title = stringResource(R.string.scanner_scan_devices),
                        summary = stringResource(R.string.scanner_description),
                        onClick = { showScanDialog = true }
                    )
                    
                    // Используем key для принудительного обновления при изменении connectionType
                    key(connectionType) {
                        EditTextPreference(
                            prefs = prefs,
                            keyRes = R.string.pref_key_host,
                            title = stringResource(R.string.pref_title_host),
                            summaryProvider = { it },
                            onValueChange = { newHost ->
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
                                    val oldProtocol = wledProtocol
                                    wledProtocol = newProtocol
                                    AnalyticsHelper.logSettingChanged(context, "wled_protocol", newProtocol)
                                    // Auto-set port when WLED protocol changes
                                    val defaultPort = if (newProtocol == "ddp") "4048" else "19446"
                                    prefs.putString(R.string.pref_key_port, defaultPort)
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
                            onValueChange = { newPort ->
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
                        AnalyticsHelper.logSettingChanged(context, "reconnect", enabled.toString())
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
                                    AnalyticsHelper.logSettingChanged(context, "reconnect_delay", newDelay)
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

                if (isWled) {
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
                        onValueChange = { enabled ->
                            AnalyticsHelper.logRgbwChanged(context, enabled)
                            AnalyticsHelper.logSettingChanged(context, "wled_rgbw", enabled.toString())
                        }
                    )
                }
            }

            // Capturing Group
            SettingsGroup(title = stringResource(R.string.pref_group_capturing)) {
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
                        AnalyticsHelper.logSettingChanged(context, "use_avg_color", enabled.toString())
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
                        val preset = prefs.getString(R.string.pref_key_smoothing_preset, "balanced")
                        AnalyticsHelper.logSmoothingChanged(context, enabled, preset)
                        AnalyticsHelper.logSettingChanged(context, "smoothing_enabled", enabled.toString())
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
                        val enabled = prefs.getBoolean(R.string.pref_key_smoothing_enabled, true)
                        AnalyticsHelper.logSmoothingChanged(context, enabled, preset)
                        AnalyticsHelper.logSettingChanged(context, "smoothing_preset", preset)
                        
                        // Обновляем значения настроек в соответствии с пресетом
                        val presetValues = when (preset.lowercase()) {
                            "off" -> Triple(50, 0, 60)
                            "responsive" -> Triple(50, 0, 60)
                            "balanced" -> Triple(200, 80, 25)
                            "smooth" -> Triple(500, 200, 20)
                            else -> Triple(200, 80, 25) // balanced по умолчанию
                        }
                        
                        prefs.putInt(R.string.pref_key_settling_time, presetValues.first)
                        prefs.putInt(R.string.pref_key_output_delay, presetValues.second)
                        prefs.putInt(R.string.pref_key_update_frequency, presetValues.third)
                        
                        // Если пресет "off", выключаем сглаживание
                        if (preset.lowercase() == "off") {
                            prefs.putBoolean(R.string.pref_key_smoothing_enabled, false)
                        }
                    }
                )
                // Используем key для принудительной перекомпозиции при изменении пресета
                key(smoothingPreset) {
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_settling_time,
                        title = stringResource(R.string.pref_title_settling_time),
                        summaryProvider = { value -> 
                            val ms = value?.toIntOrNull() ?: 200
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
                            val ms = value?.toIntOrNull() ?: 80
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
        }
    }
    
    // Device scan dialog (вне основного Column для правильной работы)
    if (showScanDialog) {
        DeviceScanDialog(
            onDismiss = { showScanDialog = false },
                            onDeviceSelected = { device ->
                                val oldConnectionType = connectionType
                                
                                // Устанавливаем тип подключения ПЕРВЫМ, чтобы UI обновился
                                when (device.type) {
                                    com.vasmarfas.UniversalAmbientLight.common.network.DeviceDetector.DeviceType.WLED -> {
                                        val newConnectionType = "wled"
                                        prefs.putString(R.string.pref_key_connection_type, newConnectionType)
                                        connectionType = newConnectionType
                                        
                                        // Устанавливаем протокол WLED
                                        val protocol = when (device.protocol) {
                                            "ddp" -> "ddp"
                                            "udp_raw" -> "udp_raw"
                                            else -> "ddp"
                                        }
                                        wledProtocol = protocol
                                        prefs.putString(R.string.pref_key_wled_protocol, protocol)
                                        
                                        AnalyticsHelper.logProtocolChanged(context, oldConnectionType, newConnectionType)
                                        AnalyticsHelper.updateProtocolProperty(context, newConnectionType)
                                    }
                                    com.vasmarfas.UniversalAmbientLight.common.network.DeviceDetector.DeviceType.HYPERION -> {
                                        val newConnectionType = "hyperion"
                                        prefs.putString(R.string.pref_key_connection_type, newConnectionType)
                                        connectionType = newConnectionType
                                        
                                        AnalyticsHelper.logProtocolChanged(context, oldConnectionType, newConnectionType)
                                        AnalyticsHelper.updateProtocolProperty(context, newConnectionType)
                                    }
                                    else -> {}
                                }
                                
                                // Устанавливаем найденное устройство ПОСЛЕ установки типа подключения
                                prefs.putString(R.string.pref_key_host, device.host)
                                prefs.putString(R.string.pref_key_port, device.port.toString())
                                
                                AnalyticsHelper.logHostChanged(context, device.host)
                                AnalyticsHelper.logPortChanged(context, device.port)
                                AnalyticsHelper.logSettingChanged(context, "device_scanned", "${device.type}:${device.host}:${device.port}")
                            }
        )
    }
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
    onValueChange: ((Boolean) -> Unit)? = null
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
    recomposeKey: Any? = null
) {
    // Read value from prefs, but allow it to be overridden during composition
    var value by remember(keyRes, recomposeKey) { mutableStateOf(prefs.getString(keyRes) ?: "") }
    
    // Update value when external value changes (only if provided) or when recomposeKey changes
    LaunchedEffect(externalValue, recomposeKey) {
        externalValue?.let { value = it }
        // Перечитываем значение из preferences при изменении recomposeKey
        recomposeKey?.let { value = prefs.getString(keyRes) ?: "" }
    }
    
    var showDialog by remember { mutableStateOf(false) }
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
        
        // Обновляем tempValue при открытии диалога
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
    recomposeKey: Any? = null
) {
    val entries = stringArrayResource(entriesRes)
    val entryValues = stringArrayResource(entryValuesRes)
    
    var value by remember(keyRes, recomposeKey) { mutableStateOf(prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: "") }
    
    // Обновляем значение при изменении recomposeKey
    LaunchedEffect(recomposeKey) {
        recomposeKey?.let { value = prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: "" }
    }
    var showDialog by remember { mutableStateOf(false) }
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
                        val interactionSource = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
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
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value == entryValues[index],
                                onClick = null
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
    onClick: () -> Unit
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
