package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
                
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_connection_type,
                    title = stringResource(R.string.pref_title_connection_type),
                    entriesRes = R.array.pref_list_connection_type,
                    entryValuesRes = R.array.pref_list_connection_type_values,
                    onValueChange = { newType ->
                        val oldType = connectionType
                        connectionType = newType
                        // Логируем изменение протокола
                        AnalyticsHelper.logProtocolChanged(context, oldType, newType)
                        AnalyticsHelper.logSettingChanged(context, "connection_type", newType)
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

                val isNetwork = connectionType == "hyperion" || connectionType == "wled"
                val isAdalight = connectionType == "adalight"
                val isWled = connectionType == "wled"
                val isHyperion = connectionType == "hyperion"

                if (isNetwork) {
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_host,
                        title = stringResource(R.string.pref_title_host),
                        summaryProvider = { it }
                    )

                    // Show WLED protocol selector between host and port for WLED connections
                    if (isWled) {
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

                    // Use key to force recomposition when connection type or WLED protocol changes
                    key("${connectionType}_${wledProtocol}") {
                        EditTextPreference(
                            prefs = prefs,
                            keyRes = R.string.pref_key_port,
                            title = stringResource(R.string.pref_title_port),
                            summaryProvider = { it },
                            keyboardType = KeyboardType.Number
                        )
                    }
                    if (isHyperion) {
                        EditTextPreference(
                            prefs = prefs,
                            keyRes = R.string.pref_key_priority,
                            title = stringResource(R.string.pref_title_priority),
                            summaryProvider = { it },
                            keyboardType = KeyboardType.Number
                        )
                        CheckBoxPreference(
                            prefs = prefs,
                            keyRes = R.string.pref_key_reconnect,
                            title = stringResource(R.string.pref_title_reconnect),
                            onValueChange = { enabled ->
                                reconnectEnabled = enabled
                                AnalyticsHelper.logAutoReconnectEnabled(context, enabled)
                                AnalyticsHelper.logSettingChanged(context, "reconnect", enabled.toString())
                            }
                        )
                        if (reconnectEnabled) {
                            EditTextPreference(
                                prefs = prefs,
                                keyRes = R.string.pref_key_reconnect_delay,
                                title = stringResource(R.string.pref_title_reconnect_delay),
                                summaryProvider = { it },
                                keyboardType = KeyboardType.Number
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
                        entryValuesRes = R.array.pref_list_adalight_baudrate_values
                    )
                    ListPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_adalight_protocol,
                        title = stringResource(R.string.pref_title_adalight_protocol),
                        entriesRes = R.array.pref_list_adalight_protocol,
                        entryValuesRes = R.array.pref_list_adalight_protocol_values
                    )
                }

                if (isWled) {
                    ListPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_wled_color_order,
                        title = stringResource(R.string.pref_title_wled_color_order),
                        entriesRes = R.array.pref_list_wled_color_order,
                        entryValuesRes = R.array.pref_list_wled_color_order_values
                    )
                    CheckBoxPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_wled_rgbw,
                        title = stringResource(R.string.pref_title_wled_rgbw)
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
                    entryValuesRes = R.array.pref_list_framerate_values
                )
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_capture_quality,
                    title = stringResource(R.string.pref_title_capture_quality),
                    entriesRes = R.array.pref_list_capture_quality,
                    entryValuesRes = R.array.pref_list_capture_quality_values
                )
                CheckBoxPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_use_avg_color,
                    title = stringResource(R.string.pref_title_use_avg_color)
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
                    }
                )
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_smoothing_preset,
                    title = stringResource(R.string.pref_title_smoothing_preset),
                    entriesRes = R.array.pref_list_smoothing_preset,
                    entryValuesRes = R.array.pref_list_smoothing_preset_values,
                    onValueChange = { preset ->
                        val enabled = prefs.getBoolean(R.string.pref_key_smoothing_enabled, true)
                        AnalyticsHelper.logSmoothingChanged(context, enabled, preset)
                        AnalyticsHelper.logSettingChanged(context, "smoothing_preset", preset)
                    }
                )
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_settling_time,
                    title = stringResource(R.string.pref_title_settling_time),
                    summaryProvider = { it },
                    keyboardType = KeyboardType.Number
                )
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_output_delay,
                    title = stringResource(R.string.pref_title_output_delay),
                    summaryProvider = { it },
                    keyboardType = KeyboardType.Number
                )
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_update_frequency,
                    title = stringResource(R.string.pref_title_update_frequency),
                    entriesRes = R.array.pref_list_update_frequency,
                    entryValuesRes = R.array.pref_list_update_frequency_values
                )
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
                        LocaleHelper.setLocale(context, language)
                        (context as? Activity)?.recreate()
                    }
                )
            }
        }
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
    onValueChange: ((String) -> Unit)? = null
) {
    // Read value from prefs, but allow it to be overridden during composition
    var value by remember(keyRes) { mutableStateOf(prefs.getString(keyRes) ?: "") }
    
    // Update value when external value changes (only if provided)
    LaunchedEffect(externalValue) {
        externalValue?.let { value = it }
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
        var tempValue by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        value = tempValue
                        onValueChange?.invoke(value) ?: prefs.putString(keyRes, value)
                        showDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
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
    onValueChange: ((String) -> Unit)? = null
) {
    val entries = stringArrayResource(entriesRes)
    val entryValues = stringArrayResource(entryValuesRes)
    
    var value by remember { mutableStateOf(prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: "") }
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
