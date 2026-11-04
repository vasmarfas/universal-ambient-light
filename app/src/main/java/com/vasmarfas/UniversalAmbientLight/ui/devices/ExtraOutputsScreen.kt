package com.vasmarfas.UniversalAmbientLight.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.ExtraOutputs
import com.vasmarfas.UniversalAmbientLight.ui.home.describeAddress
import com.vasmarfas.UniversalAmbientLight.ui.home.describeOutput
import com.vasmarfas.UniversalAmbientLight.ui.home.describeTarget
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberSettingsPreferences
import com.vasmarfas.UniversalAmbientLight.ui.settings.CheckBoxPreference
import com.vasmarfas.UniversalAmbientLight.ui.settings.ClickablePreference
import com.vasmarfas.UniversalAmbientLight.ui.settings.ConnectionDialogs
import com.vasmarfas.UniversalAmbientLight.ui.settings.ConnectionSection
import com.vasmarfas.UniversalAmbientLight.ui.settings.EditTextPreference
import com.vasmarfas.UniversalAmbientLight.ui.settings.SettingsScreenState

/** Контроллеры, которые работают вместе с основным. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraOutputsScreen(
    onBackClick: () -> Unit,
    onOutputClick: (Int) -> Unit,
    onOutputAdded: (Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = rememberSettingsPreferences()
    val ids = remember(prefs) { ExtraOutputs.ids(prefs) }

    Scaffold(
        topBar = { OutputsTopBar(stringResource(R.string.extra_outputs_title), null, onBackClick) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val focusFirst = remember { FocusRequester() }
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                // С пульта фокус сразу на списке, а не на стрелке «Назад»
                if (inputModeManager.inputMode == InputMode.Keyboard) {
                    runCatching { focusFirst.requestFocus() }
                }
            }

            Hint(stringResource(R.string.extra_outputs_hint))
            ids.forEachIndexed { index, id ->
                val output = prefs.forOutput(id)
                val type = OutputType.of(output.getString(R.string.pref_key_connection_type))
                val enabled = output.getBoolean(R.string.pref_key_output_enabled)
                val name = output.getString(R.string.pref_key_output_name)?.trim().orEmpty()
                val address = if (name.isEmpty()) describeAddress(context, output) else describeTarget(context, output)
                DeviceRow(
                    icon = type.group.icon,
                    title = name.ifEmpty { stringResource(type.titleRes()) },
                    subtitle = if (enabled) address else "$address · ${stringResource(R.string.extra_output_off)}",
                    current = false,
                    onClick = { onOutputClick(id) },
                    modifier = if (index == 0) Modifier.focusRequester(focusFirst) else Modifier
                )
            }
            OutlinedButton(
                onClick = {
                    AnalyticsHelper.logSettingChanged(context, "extra_outputs", (ids.size + 1).toString())
                    onOutputAdded(ExtraOutputs.add(prefs))
                },
                modifier = if (ids.isEmpty()) Modifier.focusRequester(focusFirst) else Modifier
            ) {
                Text(stringResource(R.string.extra_outputs_add))
            }
        }
    }
}

/**
 * Настройки одного контроллера. Это тот же набор полей, что и у основного: секция работает с
 * настройками контроллера и не знает, что он дополнительный.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraOutputScreen(
    outputId: Int,
    onBackClick: () -> Unit,
    onControllerClick: () -> Unit,
    onLedLayoutClick: () -> Unit,
) {
    val context = LocalContext.current
    val remote = LocalRemote.current
    val prefs = rememberSettingsPreferences(outputId)
    val state = remember(prefs) { SettingsScreenState(prefs) }
    var name by remember(prefs) { mutableStateOf(prefs.getString(R.string.pref_key_output_name).orEmpty()) }
    val label = remember(prefs, name, state.currentHost, state.currentPort) { describeOutput(context, prefs) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { OutputsTopBar(stringResource(R.string.extra_output_title), label, onBackClick) }
    ) { paddingValues ->
        // Новый снимок настроек с ТВ пересоздаёт поля — они перечитывают значения
        key(remote?.tv?.id, remote?.revision) {
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                CheckBoxPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_output_enabled,
                    title = stringResource(R.string.pref_title_output_enabled)
                )
                val noName = stringResource(R.string.pref_summary_output_name_empty)
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_output_name,
                    title = stringResource(R.string.pref_title_output_name),
                    summaryProvider = { it.ifBlank { noName } },
                    onValueChange = { name = it }
                )
                ConnectionSection(prefs, state, onControllerClick, onLedLayoutClick)
                ClickablePreference(
                    title = stringResource(R.string.extra_output_delete),
                    onClick = { confirmDelete = true }
                )
            }
        }
    }

    ConnectionDialogs(prefs, state)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.extra_output_delete)) },
            text = { Text(stringResource(R.string.extra_output_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    ExtraOutputs.remove(prefs.forOutput(ExtraOutputs.MAIN), outputId)
                    onBackClick()
                }) { Text(stringResource(R.string.extra_output_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputsTopBar(title: String, subtitle: String?, onBackClick: () -> Unit) {
    val remote = LocalRemote.current
    TopAppBar(
        title = {
            Column {
                Text(title)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
