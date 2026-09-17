package com.vasmarfas.UniversalAmbientLight.ui.devices

import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.LedDiscovery
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberSettingsPreferences
import kotlinx.coroutines.delay

private const val SEARCH_MS = 6000L

/**
 * Выбор контроллера: сверху то, что нашлось в сети, ниже все типы по группам. Выбор сразу
 * пишет тип, адрес и порт в настройки. С телефона-пульта ищет телефон, а пишется всё в
 * настройки телевизора.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DevicePickerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val remote = LocalRemote.current
    val prefs = rememberSettingsPreferences()
    var current by remember(prefs) { mutableStateOf(OutputType.of(prefs.getString(R.string.pref_key_connection_type))) }
    var currentHost by remember(prefs) { mutableStateOf(prefs.getString(R.string.pref_key_host)?.trim().orEmpty()) }

    var round by remember { mutableIntStateOf(0) }
    val found = remember(round) { mutableStateListOf<LedDiscovery.Found>() }
    val discovery = remember(round) { LedDiscovery(context) { found.add(it) } }
    var searching by remember(round) { mutableStateOf(true) }
    var sweepProgress by remember(round) { mutableStateOf<Float?>(null) }
    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    LaunchedEffect(discovery) {
        delay(SEARCH_MS)
        searching = false
    }

    fun pick(type: OutputType, host: String?, port: Int) {
        applyController(context, prefs, current, type, host, port)
        current = type
        currentHost = prefs.getString(R.string.pref_key_host)?.trim().orEmpty()
        onBackClick()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.devices_title))
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
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val focusCurrent = remember { FocusRequester() }
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                // С пульта фокус сразу на текущем типе, а не на стрелке «Назад»
                if (inputModeManager.inputMode == InputMode.Keyboard) {
                    runCatching { focusCurrent.requestFocus() }
                }
            }

            GroupTitle(stringResource(R.string.devices_found))
            if (remote != null) {
                Hint(stringResource(R.string.devices_remote_note))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (device in found) {
                    val typeTitle = stringResource(device.type.titleRes())
                    val address = if (device.port > 0) "${device.host}:${device.port}" else device.host
                    DeviceRow(
                        icon = device.type.group.icon,
                        title = device.name,
                        subtitle = "$typeTitle · $address",
                        current = device.type == current && device.host == currentHost,
                        onClick = { pick(device.type, device.host, device.port) }
                    )
                }
            }
            when {
                searching -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.devices_searching), style = MaterialTheme.typography.bodyMedium)
                }

                found.isEmpty() && sweepProgress == null -> Hint(stringResource(R.string.devices_nothing_found))
            }

            val progress = sweepProgress
            if (progress != null && progress < 1f) {
                Column(modifier = Modifier.widthIn(max = 560.dp).padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.devices_sweep_progress, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }
            if (!searching) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    OutlinedButton(onClick = { round++ }) {
                        Text(stringResource(R.string.devices_search_again))
                    }
                    if (progress == null) {
                        OutlinedButton(onClick = {
                            sweepProgress = 0f
                            discovery.sweep { sweepProgress = it }
                        }) {
                            Text(stringResource(R.string.devices_sweep))
                        }
                    }
                }
                if (progress == null) Hint(stringResource(R.string.devices_sweep_summary))
            }

            GroupTitle(stringResource(R.string.devices_manual))
            for (group in OutputType.Group.entries) {
                Text(
                    text = stringResource(group.titleRes()),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (type in OutputType.entries.filter { it.group == group }) {
                        DeviceRow(
                            icon = group.icon,
                            title = stringResource(type.titleRes()),
                            subtitle = stringResource(type.summaryRes()),
                            current = type == current,
                            onClick = { pick(type, null, 0) },
                            modifier = if (type == current) Modifier.focusRequester(focusCurrent) else Modifier
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Пишет выбранный контроллер в настройки. [host] null - выбран тип вручную: берётся адрес,
 * с которым этот тип работал раньше, иначе прежний, если устройство того же рода. Адрес
 * ленты у лампы или моста был бы чужим, такой очищается.
 */
private fun applyController(
    context: Context,
    prefs: Preferences,
    old: OutputType,
    type: OutputType,
    host: String?,
    port: Int,
) {
    val oldHost = prefs.getString(R.string.pref_key_host)?.trim().orEmpty()
    val recent = parseRecentHosts(prefs.getString(R.string.pref_key_recent_hosts).orEmpty())
    if (oldHost.isNotEmpty()) recent[old.id] = oldHost
    prefs.putString(R.string.pref_key_recent_hosts, recent.entries.joinToString("|") { "${it.key}=${it.value}" })
    val newHost = host ?: recent[type.id] ?: if (type.needsHost && type.group != old.group) "" else oldHost
    prefs.putString(R.string.pref_key_connection_type, type.id)
    if (newHost != oldHost) {
        prefs.putString(R.string.pref_key_host, newHost)
    }
    val newPort = when {
        port > 0 -> port
        type != old || host != null -> if (type == OutputType.WLED) {
            if (prefs.getString(R.string.pref_key_wled_protocol) == "ddp") 4048 else type.defaultPort
        } else {
            type.defaultPort
        }

        else -> 0
    }
    if (newPort > 0) prefs.putString(R.string.pref_key_port, newPort.toString())

    AnalyticsHelper.logProtocolChanged(context, old.id, type.id)
    AnalyticsHelper.updateProtocolProperty(context, type.id)
    AnalyticsHelper.logSettingChanged(context, "controller_picked", if (host != null) "found:${type.id}" else type.id)
}

private fun parseRecentHosts(raw: String): MutableMap<String, String> =
    raw.split('|')
        .mapNotNull { entry -> entry.split('=', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() } }
        .associateTo(LinkedHashMap()) { it[0] to it[1] }

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp)
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        current -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(if (focused || current) 2.dp else 1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (current) {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.devices_current),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
