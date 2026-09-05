package com.vasmarfas.UniversalAmbientLight.ui.delay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.input.TvApps
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteClient
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.DelayProfiles
import com.vasmarfas.UniversalAmbientLight.common.util.ForegroundApp
import com.vasmarfas.UniversalAmbientLight.ui.components.ValueSlider
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberSettingsPreferences
import com.vasmarfas.UniversalAmbientLight.ui.settings.AdbPairingDialog
import com.vasmarfas.UniversalAmbientLight.ui.settings.ClickablePreference
import com.vasmarfas.UniversalAmbientLight.ui.settings.LocalAdbActions
import com.vasmarfas.UniversalAmbientLight.ui.settings.RemoteAdbActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Задержка подсветки: общая, своя для отдельных приложений и автоподбор камерой телефона.
 * На телефоне-пульте правит настройки телевизора, как и остальные экраны настроек.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DelayScreen(onBackClick: () -> Unit, onCalibrateClick: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val remote = LocalRemote.current
    val prefs = rememberSettingsPreferences()
    var globalDelay by remember(prefs) { mutableIntStateOf(prefs.getInt(R.string.pref_key_output_delay, 0)) }
    var profiles by remember(prefs) {
        mutableStateOf(DelayProfiles.parse(prefs.getString(R.string.pref_key_delay_profiles, "")))
    }
    // Названия приложений ТВ: на самом ТВ - сразу, на телефоне - списком с телевизора
    var labels by remember { mutableStateOf<Map<String, String>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var showAdb by remember { mutableStateOf(false) }
    val usageAccess = remote?.caps?.usageAccess ?: remember { ForegroundApp.hasAccess(context) }

    LaunchedEffect(remote?.tv?.id) {
        labels = withContext(Dispatchers.IO) {
            runCatching {
                if (remote != null) {
                    val list = RemoteSession.call(RemoteProtocol.OP_APPS, timeoutMs = RemoteClient.LONG_TIMEOUT_MS)
                        .optJSONArray("apps")
                    (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }
                        .associate { it.optString("pkg") to it.optString("label") }
                } else {
                    TvApps.labels(context)
                }
            }.getOrDefault(emptyMap())
        }
    }

    fun saveProfiles(updated: Map<String, Int>) {
        profiles = updated
        prefs.putString(R.string.pref_key_delay_profiles, DelayProfiles.serialize(updated))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.delay_title))
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.delay_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ValueSlider(
                title = stringResource(R.string.delay_global),
                value = globalDelay,
                onValueChange = {
                    globalDelay = it
                    prefs.putInt(R.string.pref_key_output_delay, it)
                },
                range = 0..DelayProfiles.MAX_DELAY_MS,
                step = 5,
                valueText = { resources.getString(R.string.unit_ms, it) }
            )

            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.delay_calibration_title), style = MaterialTheme.typography.titleMedium)
                    if (remote != null) {
                        Text(stringResource(R.string.delay_calibration_summary), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onCalibrateClick) { Text(stringResource(R.string.delay_calibration_open)) }
                    } else {
                        Text(stringResource(R.string.delay_calibration_on_phone), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text(
                text = stringResource(R.string.delay_apps_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp)
            )
            if (!usageAccess) {
                Text(
                    text = stringResource(R.string.delay_no_usage_access),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = { showAdb = true }) { Text(stringResource(R.string.adb_grant_btn)) }
            }
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.delay_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for ((pkg, delay) in profiles) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ValueSlider(
                        title = labels?.get(pkg) ?: pkg,
                        value = delay,
                        onValueChange = { saveProfiles(profiles + (pkg to it)) },
                        range = 0..DelayProfiles.MAX_DELAY_MS,
                        step = 5,
                        valueText = { resources.getString(R.string.unit_ms, it) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { saveProfiles(profiles - pkg) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delay_apps_remove))
                    }
                }
            }
            OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.delay_apps_add)) }
        }
    }

    if (picking) {
        AppPicker(
            labels = labels,
            exclude = profiles.keys,
            onPick = { pkg ->
                picking = false
                saveProfiles(profiles + (pkg to globalDelay))
            },
            onDismiss = { picking = false }
        )
    }
    if (showAdb) {
        val actions = remember(remote != null) {
            if (remote != null) RemoteAdbActions() else LocalAdbActions(context, prefs)
        }
        AdbPairingDialog(context = context, actions = actions, onDismiss = { showAdb = false })
    }
}

@Composable
private fun AppPicker(
    labels: Map<String, String>?,
    exclude: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delay_apps_add)) },
        text = {
            if (labels == null) {
                CircularProgressIndicator()
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for ((pkg, label) in labels.entries.sortedBy { it.value.lowercase() }) {
                        if (pkg in exclude) continue
                        ClickablePreference(title = label, onClick = { onPick(pkg) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
