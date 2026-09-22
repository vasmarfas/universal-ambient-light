package com.vasmarfas.UniversalAmbientLight.ui.home

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.camera.CameraPreviewBackground
import com.vasmarfas.UniversalAmbientLight.ui.settings.ClickablePreference
import java.text.DateFormat
import java.util.Date
import kotlin.math.sqrt

/**
 * Рамка фокуса для d-pad на ТВ. Отдельно от border с условной шириной: 0.dp — это
 * Dp.Hairline, и тонкое кольцо primary рисовалось даже без фокуса.
 */
@Composable
private fun Modifier.focusBorder(focused: Boolean): Modifier =
    if (focused) {
        border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        this
    }

/** Контур OutlinedButton, подсвечивающийся при фокусе с пульта. */
@Composable
private fun focusableOutline(focused: Boolean): BorderStroke =
    BorderStroke(
        width = if (focused) 2.dp else 1.dp,
        color = if (focused) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }
    )

/**
 * Содержимое карточки состояния под кнопками. Ошибка остаётся на экране до следующего
 * запуска: toast на ТВ исчезает раньше, чем его успевают прочитать с дивана.
 */
data class HomeStatus(
    val running: Boolean,
    val pending: Boolean = false,
    val error: String? = null,
    val target: String? = null,
    val source: String? = null,
    /** Когда подсветка выключится по таймеру сна, мс по часам; 0 - таймера нет. */
    val sleepAt: Long = 0L,
)

/** Вход в удалённое управление: на ТВ — показать QR, на телефоне — выбрать телевизор. */
data class RemoteEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun MainScreen(
    isRunning: Boolean,
    onToggleClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEffectsClick: () -> Unit,
    captureSource: String = "screen",
    status: HomeStatus = HomeStatus(running = isRunning),
    // Превью камеры и радуга захвата рисуются на экране этого устройства, при управлении
    // телевизором с телефона они ничего не показывают
    localPreview: Boolean = true,
    remoteEntries: List<RemoteEntry> = emptyList(),
    topContent: @Composable () -> Unit = {},
    onHelpClick: () -> Unit = {},
    onSupportClick: () -> Unit = {},
    onReportIssueClick: () -> Unit = {},
    onLeaveReviewClick: () -> Unit = {},
    // null - таймер сна недоступен (старый ТВ); 0 минут отменяет таймер
    onSleepTimer: ((minutes: Int) -> Unit)? = null,
    // Чего не хватает контроллеру; null - всё настроено и кнопка настройки не нужна
    setupHint: String? = null,
    onSetupClick: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // В режиме камеры фоном идёт превью камеры с углами
        if (captureSource == "camera" && localPreview) {
            CameraPreviewBackground(isCapturing = isRunning)
        }

        // Пока идёт захват экрана, фон переливается радугой: она попадает на ленту и сразу
        // показывает, что картинка доходит до контроллера
        if (isRunning && captureSource == "screen" && localPreview) {
            val infiniteTransition = rememberInfiniteTransition(label = "effects")
            val angle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )

            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val diagonal =
                            sqrt(size.width * size.width + size.height * size.height)

                        rotate(angle) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color.Red,
                                        Color.Magenta,
                                        Color.Blue,
                                        Color.Cyan,
                                        Color.Green,
                                        Color.Yellow,
                                        Color.Red
                                    )
                                ),
                                radius = diagonal / 2
                            )
                        }
                    }
            )
        }

        // Центральный блок с рядом кнопок управления. Прокрутка — на телефоне в ландшафте
        // колонка выше экрана, и без неё нижние кнопки было не достать.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var effectsFocused by remember { mutableStateOf(false) }
            var powerFocused by remember { mutableStateOf(false) }
            var settingsFocused by remember { mutableStateOf(false) }

            // С пульта фокус сразу на кнопке включения: без него первое нажатие OK после
            // запуска ничего не делает, пока не нажата стрелка. На телефоне фокус не
            // трогаем — иначе вокруг кнопки висело бы кольцо фокуса.
            val powerFocus = remember { FocusRequester() }
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                if (inputModeManager.inputMode == InputMode.Keyboard) {
                    runCatching { powerFocus.requestFocus() }
                }
            }

            topContent()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Кнопка эффектов (слева)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shape = CircleShape
                        )
                        .focusBorder(effectsFocused)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onEffectsClick,
                        modifier = Modifier
                            .size(72.dp)
                            .onFocusChanged { effectsFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = stringResource(R.string.home_effects),
                            modifier = Modifier.size(40.dp),
                            tint = if (isRunning && captureSource == "effect") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            }
                        )
                    }
                }

                // Кнопка включения (в центре, самая крупная)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(120.dp)
                        .background(
                            brush = if (isRunning) Brush.sweepGradient(
                                listOf(
                                    Color.Red,
                                    Color.Magenta,
                                    Color.Blue,
                                    Color.Cyan,
                                    Color.Green,
                                    Color.Yellow,
                                    Color.Red
                                )
                            ) else Brush.linearGradient(listOf(Color.Gray, Color.Gray)),
                            shape = CircleShape
                        )
                        .focusBorder(powerFocused)
                        .padding(4.dp) // Border width
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onToggleClick,
                        modifier = Modifier
                            .size(112.dp)
                            .focusRequester(powerFocus)
                            .onFocusChanged { powerFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = stringResource(R.string.home_toggle_power),
                            modifier = Modifier
                                .size(64.dp)
                                .alpha(if (isRunning) 1f else 0.25f),
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Кнопка настроек (справа, меньше кнопки включения)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shape = CircleShape
                        )
                        .focusBorder(settingsFocused)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(72.dp)
                            .onFocusChanged { settingsFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                            modifier = Modifier.size(40.dp),
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            StatusCard(
                status = status,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(horizontal = 16.dp)
            )

            if (setupHint != null && !isRunning) {
                var setupFocused by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = setupHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onSetupClick,
                    border = focusableOutline(setupFocused),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .onFocusChanged { setupFocused = it.isFocused }
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.home_setup_button))
                }
            }

            if (onSleepTimer != null && isRunning) {
                var showSleepDialog by remember { mutableStateOf(false) }
                var sleepFocused by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showSleepDialog = true },
                    border = focusableOutline(sleepFocused),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .onFocusChanged { sleepFocused = it.isFocused }
                ) {
                    Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sleep_timer))
                }
                if (showSleepDialog) {
                    SleepTimerDialog(
                        active = status.sleepAt > 0,
                        onPick = { minutes ->
                            showSleepDialog = false
                            onSleepTimer(minutes)
                        },
                        onDismiss = { showSleepDialog = false }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Столбец кнопок помощи и поддержки. Ширина ограничена: на ТВ кнопки
            // растягивались во весь экран. Рамка при фокусе — состояние d-pad на
            // OutlinedButton иначе почти неразличимо.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(horizontal = 16.dp)
            ) {
                for (remoteEntry in remoteEntries) {
                    var remoteFocused by remember { mutableStateOf(false) }
                    FilledTonalButton(
                        onClick = remoteEntry.onClick,
                        border = if (remoteFocused) focusableOutline(true) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { remoteFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = remoteEntry.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(remoteEntry.label)
                    }
                }

                var helpFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onHelpClick,
                    border = focusableOutline(helpFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { helpFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = stringResource(R.string.help),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.help))
                }

                var supportFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onSupportClick,
                    border = focusableOutline(supportFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { supportFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = stringResource(R.string.support_project),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.support_project))
                }

                var reportIssueFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onReportIssueClick,
                    border = focusableOutline(reportIssueFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { reportIssueFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = stringResource(R.string.report_issue),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.report_issue))
                }

                var leaveReviewFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onLeaveReviewClick,
                    border = focusableOutline(leaveReviewFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { leaveReviewFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.leave_review),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.leave_review))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: HomeStatus, modifier: Modifier = Modifier) {
    val error = status.error?.takeIf { !status.running }
    val failed = error != null
    val dotColor = when {
        failed -> MaterialTheme.colorScheme.error
        status.running -> Color(0xFF4CAF50)
        status.pending -> Color(0xFFFFB300)
        else -> MaterialTheme.colorScheme.outline
    }
    val title = when {
        failed -> stringResource(R.string.home_status_error)
        status.running -> stringResource(R.string.status_grabber_running)
        status.pending -> stringResource(R.string.home_status_starting)
        else -> stringResource(R.string.home_status_stopped)
    }
    // Полупрозрачная подложка: за карточкой может крутиться анимация эффектов
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            status.target?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            status.source?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (status.running && status.sleepAt > 0) {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.sleepAt))
                Text(
                    text = stringResource(R.string.sleep_timer_until, time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(active: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column {
                for (minutes in SLEEP_OPTIONS) {
                    ClickablePreference(
                        title = stringResource(R.string.sleep_timer_minutes, minutes),
                        onClick = { onPick(minutes) }
                    )
                }
                if (active) {
                    ClickablePreference(
                        title = stringResource(R.string.sleep_timer_cancel),
                        onClick = { onPick(0) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

private val SLEEP_OPTIONS = listOf(15, 30, 45, 60, 90, 120)
