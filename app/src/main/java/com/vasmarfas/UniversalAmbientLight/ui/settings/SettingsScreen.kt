package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
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
import android.app.Activity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    
    // State for dependencies
    var connectionType by remember { 
        mutableStateOf(prefs.getString(R.string.pref_key_connection_type) ?: "hyperion") 
    }
    var reconnectEnabled by remember {
        mutableStateOf(prefs.getBoolean(R.string.pref_key_reconnect))
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
                    onValueChange = { connectionType = it }
                )

                val isNetwork = connectionType == "hyperion" || connectionType == "wled"
                val isAdalight = connectionType == "adalight"
                val isWled = connectionType == "wled"

                if (isNetwork) {
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_host,
                        title = stringResource(R.string.pref_title_host),
                        summaryProvider = { it }
                    )
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_port,
                        title = stringResource(R.string.pref_title_port),
                        summaryProvider = { it },
                        keyboardType = KeyboardType.Number
                    )
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
                        onValueChange = { reconnectEnabled = it }
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

                if (isAdalight) {
                    EditTextPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_adalight_baudrate,
                        title = stringResource(R.string.pref_title_adalight_baudrate),
                        summaryProvider = { it },
                        keyboardType = KeyboardType.Number
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
                    ListPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_wled_protocol,
                        title = stringResource(R.string.pref_title_wled_protocol),
                        entriesRes = R.array.pref_list_wled_protocol,
                        entryValuesRes = R.array.pref_list_wled_protocol_values
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
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_x_led,
                    title = stringResource(R.string.pref_title_x_led),
                    summaryProvider = { it },
                    keyboardType = KeyboardType.Number
                )
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_y_led,
                    title = stringResource(R.string.pref_title_y_led),
                    summaryProvider = { it },
                    keyboardType = KeyboardType.Number
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
                    title = stringResource(R.string.pref_title_smoothing_enabled)
                )
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_smoothing_preset,
                    title = stringResource(R.string.pref_title_smoothing_preset),
                    entriesRes = R.array.pref_list_smoothing_preset,
                    entryValuesRes = R.array.pref_list_smoothing_preset_values
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
                    title = stringResource(R.string.pref_title_boot)
                )
                ListPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_language,
                    title = stringResource(R.string.pref_title_language),
                    entriesRes = R.array.pref_list_language,
                    entryValuesRes = R.array.pref_list_language_values,
                    onValueChange = { language ->
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = {
                    checked = it
                    prefs.putBoolean(keyRes, it)
                    onValueChange?.invoke(it)
                },
                role = Role.Checkbox
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
    keyboardType: KeyboardType = KeyboardType.Text
) {
    var value by remember { mutableStateOf(prefs.getString(keyRes) ?: "") }
    var showDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
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
                        prefs.putString(keyRes, value)
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

    val summary = entries.getOrNull(entryValues.indexOf(value)) ?: value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newValue = entryValues[index]
                                    value = newValue
                                    prefs.putString(keyRes, newValue)
                                    onValueChange?.invoke(newValue)
                                    showDialog = false
                                }
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
