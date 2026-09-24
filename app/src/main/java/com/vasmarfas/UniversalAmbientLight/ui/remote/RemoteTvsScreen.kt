package com.vasmarfas.UniversalAmbientLight.ui.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vasmarfas.UniversalAmbientLight.common.remote.PairedTv
import com.vasmarfas.UniversalAmbientLight.common.remote.PairedTvStore
import com.vasmarfas.UniversalAmbientLight.common.remote.PairingCode
import com.vasmarfas.UniversalAmbientLight.common.remote.PairingPayload
import com.vasmarfas.UniversalAmbientLight.common.remote.PairingRejectedException
import com.vasmarfas.UniversalAmbientLight.common.remote.ProtocolMismatchException
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteDiscovery
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.remote.WrongTvException
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.components.focusHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что подставить в ручной ввод: найденный в сети ТВ знает адрес, но не код. */
private data class ManualPrefill(val host: String = "", val port: Int = RemoteProtocol.DEFAULT_PORT)

/**
 * Телефон как пульт: выбрать сопряжённый телевизор или сопрячь новый по QR-коду.
 * [pendingPayload] — QR, открытый системной камерой по ссылке; сопрягается сразу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteTvsScreen(
    onBackClick: () -> Unit,
    onControlTv: () -> Unit,
    pendingPayload: PairingPayload? = null,
    onPayloadConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PairedTvStore(context) }
    val remote = rememberRemoteSnapshot()

    var storeVersion by remember { mutableIntStateOf(0) }
    val paired = remember(storeVersion, remote.tv) { store.all() }
    var discovered by remember { mutableStateOf<List<RemoteDiscovery.Found>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var discoveryRound by remember { mutableIntStateOf(0) }

    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf<ManualPrefill?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<PairedTv?>(null) }

    fun pair(hosts: List<String>, port: Int, code: String, tvId: String?) {
        pairing = true
        pairError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { RemoteSession.verify(context, hosts, port, code, tvId) }
            }
            pairing = false
            result.onSuccess { tv ->
                RemoteSession.activate(tv)
                storeVersion++
                onControlTv()
            }.onFailure { pairError = describePairingError(context, it) }
        }
    }

    LaunchedEffect(pendingPayload) {
        val payload = pendingPayload ?: return@LaunchedEffect
        onPayloadConsumed()
        pair(payload.hosts, payload.port, payload.code, payload.tvId)
    }

    LaunchedEffect(discoveryRound) {
        discovering = true
        discovered = withContext(Dispatchers.IO) { RemoteDiscovery.discover(context, 3000) }
        discovering = false
    }

    val cameraRequired = stringResource(R.string.camera_permission_required)
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanning = true else pairError = cameraRequired
    }

    fun startScan() {
        pairError = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            scanning = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    if (scanning) {
        ScanOverlay(
            onCancel = { scanning = false },
            onPayload = { payload ->
                scanning = false
                pair(payload.hosts, payload.port, payload.code, payload.tvId)
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_tvs_title)) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.remote_tvs_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { startScan() },
                enabled = !pairing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.remote_tvs_scan))
            }
            OutlinedButton(
                onClick = { manual = ManualPrefill() },
                enabled = !pairing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.remote_tvs_manual))
            }

            if (pairing) {
                Text(stringResource(R.string.remote_tvs_connecting))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            pairError?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            if (paired.isNotEmpty()) {
                SectionTitle(stringResource(R.string.remote_tvs_paired))
                for (tv in paired) {
                    val online = discovered.any { it.id == tv.id }
                    TvRow(
                        title = tv.name,
                        subtitle = when {
                            remote.tv?.id == tv.id -> stringResource(R.string.remote_tvs_active)
                            online -> stringResource(R.string.remote_tvs_online)
                            else -> tv.hosts.firstOrNull().orEmpty()
                        },
                        highlighted = remote.tv?.id == tv.id,
                        onClick = {
                            RemoteSession.activate(tv)
                            onControlTv()
                        },
                        onDelete = { toDelete = tv }
                    )
                }
            }

            val unpaired = discovered.filter { found -> paired.none { it.id == found.id } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    stringResource(R.string.remote_tvs_found),
                    modifier = Modifier.weight(1f)
                )
                if (discovering) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { discoveryRound++ }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.remote_tvs_refresh)
                        )
                    }
                }
            }
            if (unpaired.isEmpty() && !discovering) {
                Text(
                    text = stringResource(R.string.remote_tvs_none_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for (found in unpaired) {
                TvRow(
                    title = found.name,
                    subtitle = "${found.host}:${found.port}",
                    highlighted = false,
                    onClick = { manual = ManualPrefill(found.host, found.port) },
                    onDelete = null
                )
            }
        }
    }

    manual?.let { prefill ->
        ManualPairDialog(
            prefill = prefill,
            onDismiss = { manual = null },
            onConfirm = { host, port, code ->
                manual = null
                pair(listOf(host), port, code, null)
            }
        )
    }

    toDelete?.let { tv ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tv.name) },
            text = { Text(stringResource(R.string.remote_tvs_forget_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    if (remote.tv?.id == tv.id) RemoteSession.deactivate()
                    store.remove(tv.id)
                    storeVersion++
                    toDelete = null
                }) { Text(stringResource(R.string.remote_tvs_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun ScanOverlay(onCancel: () -> Unit, onPayload: (PairingPayload) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        QrScanner(
            accept = { PairingPayload.parse(it) != null },
            onResult = { text -> PairingPayload.parse(text)?.let(onPayload) },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.remote_scan_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel), color = Color.White)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 12.dp)
    )
}

@Composable
private fun TvRow(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape
            )
            .focusHighlight(interactionSource, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(Icons.Default.Tv, contentDescription = null)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remote_tvs_forget))
            }
        }
    }
}

@Composable
private fun ManualPairDialog(
    prefill: ManualPrefill,
    onDismiss: () -> Unit,
    onConfirm: (host: String, port: Int, code: String) -> Unit,
) {
    var host by remember { mutableStateOf(prefill.host) }
    var port by remember { mutableStateOf(prefill.port.toString()) }
    var code by remember { mutableStateOf("") }
    val normalized = PairingCode.normalize(code)
    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && portValue != null && normalized != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_tvs_manual)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.remote_manual_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text(stringResource(R.string.remote_host_address)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.pref_title_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(24) },
                    label = { Text(stringResource(R.string.remote_host_code)) },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                    isError = code.isNotEmpty() && normalized == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (portValue != null && normalized != null) onConfirm(host, portValue, normalized)
                }
            ) { Text(stringResource(R.string.remote_tvs_connect)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

private fun describePairingError(context: Context, error: Throwable): String = when (error) {
    is PairingRejectedException -> context.getString(R.string.remote_error_code_rejected)
    is ProtocolMismatchException -> context.getString(R.string.remote_error_version)
    is WrongTvException -> context.getString(R.string.remote_error_wrong_tv)
    else -> context.getString(R.string.remote_error_not_found)
}
