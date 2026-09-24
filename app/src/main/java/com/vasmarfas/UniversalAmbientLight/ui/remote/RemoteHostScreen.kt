package com.vasmarfas.UniversalAmbientLight.ui.remote

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.common.remote.PairingCode
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteControlService
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteHostConfig
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.home.generateQRCode
import com.vasmarfas.UniversalAmbientLight.ui.settings.CheckBoxPreference
import com.vasmarfas.UniversalAmbientLight.ui.settings.ClickablePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Экран на ТВ: QR-код для телефона, адрес и код для ручного ввода, подключённые телефоны.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteHostScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val config = remember { RemoteHostConfig(context) }
    val prefs = remember { Preferences(context) }

    var enabled by remember { mutableStateOf(RemoteControlService.isEnabled(context)) }
    var code by remember { mutableStateOf(config.code) }
    var running by remember { mutableStateOf(RemoteControlService.sRunning) }
    var port by remember { mutableIntStateOf(RemoteControlService.sPort) }
    var clients by remember { mutableStateOf(RemoteControlService.sClients) }
    var confirmReset by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener = RemoteControlService.Listener {
            running = RemoteControlService.sRunning
            port = RemoteControlService.sPort
            clients = RemoteControlService.sClients
        }
        RemoteControlService.addListener(listener)
        onDispose { RemoteControlService.removeListener(listener) }
    }

    // Зашли на экран впервые — значит, хотят подключить телефон: включаем доступ сами,
    // без лишнего переключателя. Выключить его можно здесь же.
    LaunchedEffect(Unit) {
        if (!prefs.contains(R.string.pref_key_remote_access)) {
            RemoteControlService.setEnabled(context, true)
            enabled = true
        } else if (enabled) {
            RemoteControlService.startIfEnabled(context)
        }
    }

    val addresses = remember(running) { RemoteHostConfig.localAddresses() }
    val payloadUri = remember(code, port, addresses) { config.payload(port).copy(hosts = addresses).toUri() }
    val qr by produceState<ImageBitmap?>(initialValue = null, payloadUri) {
        value = withContext(Dispatchers.Default) { generateQRCode(payloadUri, 512) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_host_title)) },
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
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val qrPanel: @Composable () -> Unit = {
            QrPanel(
                qr = qr?.takeIf { enabled && running && addresses.isNotEmpty() },
                size = if (landscape) 300 else 240
            )
        }
        val info: @Composable () -> Unit = {
            Column(modifier = Modifier.widthIn(max = 520.dp)) {
                Text(
                    text = stringResource(R.string.remote_host_instructions),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                when {
                    !enabled -> Text(
                        text = stringResource(R.string.remote_host_disabled),
                        color = MaterialTheme.colorScheme.error
                    )

                    addresses.isEmpty() -> Text(
                        text = stringResource(R.string.remote_host_no_network),
                        color = MaterialTheme.colorScheme.error
                    )

                    else -> ManualDetails(config.displayName, addresses, port, code)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (clients.isEmpty()) {
                        stringResource(R.string.remote_host_no_clients)
                    } else {
                        stringResource(R.string.remote_host_clients, clients.joinToString())
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (clients.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                // key: доступ включается и автоматически при первом входе на экран
                key(enabled) {
                    CheckBoxPreference(
                        prefs = prefs,
                        keyRes = R.string.pref_key_remote_access,
                        title = stringResource(R.string.remote_host_enable),
                        summary = stringResource(R.string.remote_host_enable_summary),
                        onValueChange = {
                            enabled = it
                            RemoteControlService.setEnabled(context, it)
                        }
                    )
                }
                ClickablePreference(
                    title = stringResource(R.string.remote_host_reset_code),
                    summary = stringResource(R.string.remote_host_reset_code_summary),
                    enabled = enabled,
                    onClick = { confirmReset = true }
                )
            }
        }

        if (landscape) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            ) {
                qrPanel()
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { info() }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                qrPanel()
                Spacer(modifier = Modifier.height(24.dp))
                info()
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.remote_host_reset_code)) },
            text = { Text(stringResource(R.string.remote_host_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    code = config.resetCode()
                    RemoteControlService.onCodeChanged(context)
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun QrPanel(qr: ImageBitmap?, size: Int) {
    // Белая подложка с полями: в тёмной теме камера телефона иначе не находит края кода
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.remote_host_qr_description),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ManualDetails(name: String, addresses: List<String>, port: Int, code: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailRow(stringResource(R.string.remote_host_name), name)
        DetailRow(
            stringResource(R.string.remote_host_address),
            addresses.joinToString { "$it:$port" }
        )
        // Код отдельной строкой и крупно: его переписывают с экрана ТВ с дивана, а на
        // узком экране рядом с подписью он не помещается
        Text(
            text = stringResource(R.string.remote_host_code),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = PairingCode.format(code),
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
