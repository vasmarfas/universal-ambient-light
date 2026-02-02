package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.DeviceDetector
import com.vasmarfas.UniversalAmbientLight.common.network.DeviceScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (DeviceDetector.DeviceInfo) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var foundDevices by remember { mutableStateOf<List<DeviceDetector.DeviceInfo>>(emptyList()) }
    var scanner by remember { mutableStateOf<DeviceScanner?>(null) }
    var currentScanIp by remember { mutableStateOf<String?>(null) }
    var totalIps by remember { mutableStateOf(0) }
    
    fun startScan() {
        isScanning = true
        progress = 0f
        foundDevices = emptyList()
        
        scope.launch {
            withContext(Dispatchers.IO) {
                var scannerRef: DeviceScanner? = null
                val newScanner = DeviceScanner { device ->
                    scope.launch(Dispatchers.Main) {
                        scannerRef?.let {
                            foundDevices = it.getFoundDevices()
                        }
                    }
                }
                scannerRef = newScanner
                scanner = newScanner
                
                val scannerInfo = newScanner.getScanInfo()
                withContext(Dispatchers.Main) {
                    totalIps = scannerInfo.totalIps
                }
                
                while (newScanner.hasNextAttempt()) {
                    val currentIp = newScanner.getCurrentIp()
                    withContext(Dispatchers.Main) {
                        currentScanIp = currentIp
                        progress = newScanner.progress
                    }
                    
                    val device = newScanner.tryNext()
                    
                    withContext(Dispatchers.Main) {
                        progress = newScanner.progress
                        if (device != null) {
                            foundDevices = newScanner.getFoundDevices()
                        }
                    }
                    
                    kotlinx.coroutines.delay(1)
                }
                
                withContext(Dispatchers.Main) {
                    isScanning = false
                    progress = 1f
                    foundDevices = newScanner.getFoundDevices()
                }
            }
        }
    }
    
    LaunchedEffect(Unit) {
        startScan()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.scanner_scan_devices))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (isScanning || foundDevices.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            if (isScanning) {
                                Text(
                                    text = stringResource(R.string.scanner_scanning),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "${(progress * 100).toInt()}% (${(progress * totalIps).toInt()}/$totalIps)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                if (currentScanIp != null) {
                                    Text(
                                        text = "Scanning: $currentScanIp",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (foundDevices.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.scanner_found_devices),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = if (isScanning) 250.dp else 300.dp)
                    ) {
                        items(foundDevices) { device ->
                            DeviceItem(
                                device = device,
                                onClick = {
                                    onDeviceSelected(device)
                                    onDismiss()
                                }
                            )
                        }
                    }
                } else if (!isScanning) {
                    Text(
                        text = stringResource(R.string.scanner_no_devices_found),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (!isScanning) {
                TextButton(onClick = { startScan() }) {
                    Text(stringResource(R.string.scanner_retry_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.scanner_cancel))
            }
        }
    )
}

@Composable
private fun DeviceItem(
    device: DeviceDetector.DeviceInfo,
    onClick: () -> Unit
) {
    val deviceTypeName = when (device.type) {
        DeviceDetector.DeviceType.WLED -> stringResource(R.string.scanner_device_wled)
        DeviceDetector.DeviceType.HYPERION -> stringResource(R.string.scanner_device_hyperion)
        DeviceDetector.DeviceType.UNKNOWN -> stringResource(R.string.scanner_device_unknown)
    }
    
    val deviceName = device.name ?: deviceTypeName
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.scanner_device_info,
                        deviceTypeName,
                        device.host,
                        device.port
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (device.hostname != null) {
                    Text(
                        text = "Hostname: ${device.hostname}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (device.protocol != null) {
                    Text(
                        text = "Protocol: ${device.protocol}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
