package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.util.AdbSetup
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings
import com.vasmarfas.UniversalAmbientLight.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Диалог сопряжения и подключения по беспроводному ADB (Android 11+). С телефона в режиме
 * пульта те же кнопки выполняются на телевизоре ([RemoteAdbActions]): код сопряжения с
 * экрана ТВ удобнее набрать на телефоне, чем пультом.
 */
@Composable
fun AdbPairingDialog(
    context: Context,
    actions: AdbActions,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var succeeded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }

    var manualExpanded by remember { mutableStateOf(false) }
    var showAutoPairConsent by remember { mutableStateOf(false) }

    var info by remember { mutableStateOf<AdbDialogStatus?>(null) }
    val onLabel = stringResource(R.string.adb_status_on)
    val offLabel = stringResource(R.string.adb_status_off)

    fun refreshInfo() {
        scope.launch {
            info = withContext(Dispatchers.IO) { runCatching { actions.status() }.getOrNull() }
        }
    }

    LaunchedEffect(Unit) { refreshInfo() }

    // Когда пользователь возвращается, включив службу доступности (сценарий автосопряжения),
    // снова открываем диалог согласия, чтобы сопряжение продолжилось без повторного нажатия.
    // Заодно перечитываем статусы Dev options/ADB: за ними пользователь и уходил в настройки,
    // и замороженные значения показывали бы красное «выключено» после включения.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshInfo()
                // Сначала isAvailable: consume снимает флаг, и в обратном порядке возврат
                // без включённой службы съедал бы его — согласие не открылось бы уже никогда
                if (!actions.isRemote && AccessibilityCaptureService.isAvailable() &&
                    AccessibilityCaptureService.consumeAutoPairPending()
                ) {
                    showAutoPairConsent = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun run(block: () -> AdbSetup.Outcome, after: (AdbSetup.Outcome) -> Unit = {}) {
        testing = true
        status = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { block() }
            testing = false
            succeeded = outcome.success
            status = outcome.message
            refreshInfo()
            after(outcome)
        }
    }

    fun open(block: () -> Boolean, failMessage: Int) {
        scope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching(block).getOrDefault(false) }
            if (!opened) Toast.makeText(context, failMessage, Toast.LENGTH_LONG).show()
        }
    }

    // Автосопряжение через службу доступности. Запускается только после явного согласия.
    fun runAutoPair() {
        val local = actions as? LocalAdbActions
        if (local != null && !local.accessibilityReady()) {
            // Просим службу вернуть нас обратно, как только пользователь её включит,
            // и заново открыть диалог согласия, чтобы сопряжение пошло дальше само.
            AccessibilityCaptureService.requestReturnToAppOnConnect()
            AccessibilityCaptureService.markAutoPairPending()
            succeeded = false
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
            return
        }
        local?.openWirelessDebugging()
        run({ actions.autoPair() }) { outcome ->
            Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
            if (outcome.success && local != null) {
                // Сопряжение шло поверх экрана настроек системы — возвращаем приложение
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                launch?.addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                )
                if (launch != null) try {
                    context.startActivity(launch)
                } catch (_: Exception) {
                    // Экран настроек стороннего приложения может отсутствовать или быть
                    // закрыт прошивкой — тогда просто ничего не открываем.
                }
            }
        }
        // После run(): он сбрасывает статус, а подсказка нужна на всё время ожидания
        succeeded = false
        status = context.getString(R.string.adb_autopair_waiting)
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

    val devEnabled = info?.developerOptions == true
    val adbEnabled = info?.adbEnabled == true
    val sdkInt = info?.sdkInt ?: Build.VERSION.SDK_INT

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
                    if (actions.isRemote) {
                        Text(
                            text = stringResource(R.string.remote_adb_runs_on_tv),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
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
                                open({ actions.openDeveloperOptions() }, R.string.adb_toast_cannot_open_dev)
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.adb_toast_dev_options_help),
                                    Toast.LENGTH_LONG
                                ).show()
                                open({ actions.openAboutDevice() }, R.string.adb_toast_cannot_open_dev)
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
                            open({ actions.openWirelessDebugging() }, R.string.adb_toast_cannot_open_wireless)
                        }
                    ) { Text(stringResource(R.string.adb_btn_open_wireless_debug)) }

                    // Сначала подключение по-старому — Android 10 и ниже либо после `adb tcpip 5555`.
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.adb_legacy_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { run({ actions.legacyConnect() }) }
                    ) { Text(stringResource(R.string.adb_legacy_btn)) }

                    // Сопряжение для Android 11+ показываем ниже. Оно разовое, а кнопка
                    // автоматического режима читает код через службу доступности (после согласия).
                    if (sdkInt >= Build.VERSION_CODES.R) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.adb_pair_code_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // Путь «в одно касание»: сначала диалог согласия, затем приложение само
                        // читает код через доступность, находит порт по mDNS и сопрягается, не уводя
                        // пользователя с экрана. Требует службу доступности — во флейворе Google Play скрыт.
                        if (info?.autoPairAvailable == true) {
                            OutlinedButton(
                                enabled = !testing,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showAutoPairConsent = true }
                            ) { Text(stringResource(R.string.adb_autopair_btn)) }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        // Ручной ввод спрятан под спойлер, чтобы не мешать навигации с пульта.
                        // С телефона ручной ввод — основной путь, поэтому там он раскрыт сразу.
                        TextButton(
                            onClick = { manualExpanded = !manualExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = (if (manualExpanded || actions.isRemote) "▾ " else "▸ ") +
                                        stringResource(R.string.adb_pair_manual_label)
                            )
                        }
                        if (manualExpanded || actions.isRemote) {
                            Text(
                                text = stringResource(
                                    if (actions.isRemote) R.string.remote_adb_pair_instruction
                                    else R.string.adb_pair_code_instruction
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pairPort,
                                onValueChange = {
                                    pairPort = it.filter { c -> c.isDigit() }.take(5)
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            if (actions.isRemote) R.string.remote_adb_pair_port_optional
                                            else R.string.adb_pair_port_hint
                                        )
                                    )
                                },
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
                            // Без порта его находит mDNS на устройстве — вводить приходится только код
                            val portRequired = !actions.isRemote
                            OutlinedButton(
                                enabled = !testing && pairCode.length == 6 &&
                                        (!portRequired || pairPort.isNotEmpty()),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val code = pairCode
                                    val port = pairPort.toIntOrNull()
                                    run({ actions.pair(port, code) })
                                }
                            ) {
                                Text(stringResource(if (testing) R.string.adb_pairing else R.string.adb_pair_btn))
                            }
                        }
                    }

                    // Разовая выдача разрешений через ADB — то, на чём держится автозапуск ТВ
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.adb_grant_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.adb_grant_description),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { run({ actions.grantPermissions() }) }
                    ) { Text(stringResource(R.string.adb_grant_btn)) }
                }

                // Состояние и прогресс всегда на виду, под областью прокрутки.
                val currentStatus = status
                if (currentStatus != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (succeeded) "✓ $currentStatus" else currentStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (succeeded)
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
                onClick = { run({ actions.testConnection() }) }
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
