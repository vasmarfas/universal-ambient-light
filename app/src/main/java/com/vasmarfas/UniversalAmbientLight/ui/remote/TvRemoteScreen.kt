package com.vasmarfas.UniversalAmbientLight.ui.remote

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.input.TvInput
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteClient
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.ui.settings.AdbPairingDialog
import com.vasmarfas.UniversalAmbientLight.ui.settings.RemoteAdbActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max

/** Приложение ТВ в списке пульта; картинка уже разобрана из base64. */
private class TvApp(val pkg: String, val label: String, val image: ImageBitmap?, val banner: Boolean)

/** Что на ТВ доступно для ввода - ответ на OP_INPUT_PREPARE. */
private class InputStatus(val adb: Boolean, val accessibility: Boolean, val cursor: Boolean, val error: String?)

/**
 * Пульт на телефоне: кнопки ТВ, тачпад вместо мыши, ввод текста и запуск приложений.
 * Всё выполняет телевизор, телефон только шлёт команды.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvRemoteScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val remote = LocalRemote.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<InputStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdb by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<TvApp>?>(null) }
    val connected = remote?.connection == RemoteSession.Connection.CONNECTED
    val supported = remote?.caps?.features?.contains(RemoteProtocol.FEATURE_INPUT) == true

    suspend fun prepare() {
        status = withContext(Dispatchers.IO) {
            runCatching {
                val reply = RemoteSession.call(RemoteProtocol.OP_INPUT_PREPARE, timeoutMs = RemoteClient.LONG_TIMEOUT_MS)
                InputStatus(
                    adb = reply.optBoolean("adb"),
                    accessibility = reply.optBoolean("accessibility"),
                    cursor = reply.optBoolean("cursor"),
                    error = reply.optString("error").takeIf { !reply.isNull("error") && it.isNotEmpty() }
                )
            }.getOrNull()
        }
    }

    LaunchedEffect(connected, supported) {
        if (connected && supported) prepare()
    }

    val showError: (String) -> Unit = { error = it }
    fun key(code: Int, action: String) {
        RemoteSession.input(
            JSONObject().put("t", RemoteProtocol.INPUT_KEY).put("code", code).put("a", action),
            onError = showError
        )
    }

    // Громкость телефона в режиме пульта управляет громкостью ТВ
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.tv_remote_title))
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
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val code = when (event.key) {
                        Key.VolumeUp -> KeyEvent.KEYCODE_VOLUME_UP
                        Key.VolumeDown -> KeyEvent.KEYCODE_VOLUME_DOWN
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) key(code, TvInput.KEY_PRESS)
                    true
                }
        ) {
            if (!supported) {
                Text(
                    text = stringResource(
                        if (connected) R.string.remote_error_update_tv else R.string.remote_error_offline
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(
                    R.string.tv_remote_tab_keys,
                    R.string.tv_remote_tab_mouse,
                    R.string.tv_remote_tab_text,
                    R.string.tv_remote_tab_apps,
                ).forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                }
            }

            val current = status
            if (current != null && !current.adb) {
                Notice(
                    text = stringResource(R.string.tv_remote_adb_missing, current.error ?: "?") +
                            if (current.accessibility) "\n" + stringResource(R.string.tv_remote_adb_missing_accessibility) else "",
                    action = stringResource(R.string.tv_remote_adb_setup),
                    onAction = { showAdb = true }
                )
            }
            error?.let { message ->
                Notice(
                    text = message,
                    action = stringResource(R.string.action_close),
                    onAction = { error = null }
                )
            }

            when (tab) {
                0 -> KeysPanel(onKey = { code, action ->
                    if (action == TvInput.KEY_DOWN) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    key(code, action)
                })

                1 -> TouchPanel(
                    cursorAvailable = current?.cursor != false,
                    onPointer = { action, dx, dy, wait ->
                        RemoteSession.input(
                            JSONObject()
                                .put("t", RemoteProtocol.INPUT_POINTER)
                                .put("a", action)
                                .put("dx", dx.toDouble())
                                .put("dy", dy.toDouble()),
                            wait = wait,
                            onError = showError
                        )
                    },
                    onBack = { key(KeyEvent.KEYCODE_BACK, TvInput.KEY_PRESS) }
                )

                2 -> TextPanel(
                    onText = { text ->
                        RemoteSession.input(
                            JSONObject().put("t", RemoteProtocol.INPUT_TEXT).put("text", text),
                            onError = showError
                        )
                    },
                    onDelete = { count ->
                        RemoteSession.input(
                            JSONObject()
                                .put("t", RemoteProtocol.INPUT_KEY)
                                .put("code", KeyEvent.KEYCODE_DEL)
                                .put("a", TvInput.KEY_PRESS)
                                .put("n", count),
                            onError = showError
                        )
                    },
                    onKey = { key(it, TvInput.KEY_PRESS) }
                )

                else -> AppsPanel(
                    apps = apps,
                    onLoad = {
                        scope.launch {
                            apps = withContext(Dispatchers.IO) { loadApps() }.getOrElse {
                                error = it.message
                                emptyList()
                            }
                        }
                    },
                    onLaunch = { app ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    RemoteSession.call(RemoteProtocol.OP_LAUNCH, JSONObject().put("pkg", app.pkg))
                                }
                            }
                            result.exceptionOrNull()?.let { error = it.message }
                                ?: Toast.makeText(context, app.label, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }

    if (showAdb) {
        val actions = remember { RemoteAdbActions() }
        AdbPairingDialog(
            context = context,
            actions = actions,
            onDismiss = {
                showAdb = false
                scope.launch { prepare() }
            }
        )
    }
}

private fun loadApps(): Result<List<TvApp>> = runCatching {
    val reply = RemoteSession.call(RemoteProtocol.OP_APPS, timeoutMs = RemoteClient.LONG_TIMEOUT_MS)
    val list = reply.optJSONArray("apps")
    (0 until (list?.length() ?: 0)).mapNotNull { index ->
        val item = list?.optJSONObject(index) ?: return@mapNotNull null
        val banner = item.optString("banner")
        val encoded = banner.ifEmpty { item.optString("icon") }
        val bitmap = encoded.takeIf { it.isNotEmpty() }?.let {
            val bytes = Base64.decode(it, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
        TvApp(item.optString("pkg"), item.optString("label"), bitmap, banner.isNotEmpty())
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ColumnScope.KeysPanel(onKey: (code: Int, action: String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
    ) {
        KeyRow {
            RemoteKey(Icons.Default.PowerSettingsNew, R.string.tv_remote_power, KeyEvent.KEYCODE_POWER, onKey)
            RemoteKey(Icons.AutoMirrored.Filled.Input, R.string.tv_remote_input, KeyEvent.KEYCODE_TV_INPUT, onKey)
            RemoteKey(Icons.Default.Settings, R.string.tv_remote_settings, KeyEvent.KEYCODE_SETTINGS, onKey)
        }
        DPad(onKey)
        KeyRow {
            RemoteKey(Icons.AutoMirrored.Filled.ArrowBack, R.string.tv_remote_back, KeyEvent.KEYCODE_BACK, onKey)
            RemoteKey(Icons.Default.Home, R.string.tv_remote_home, KeyEvent.KEYCODE_HOME, onKey)
            RemoteKey(Icons.Default.Menu, R.string.tv_remote_menu, KeyEvent.KEYCODE_MENU, onKey)
        }
        KeyRow {
            RemoteKey(Icons.AutoMirrored.Filled.VolumeDown, R.string.tv_remote_volume_down, KeyEvent.KEYCODE_VOLUME_DOWN, onKey)
            RemoteKey(Icons.AutoMirrored.Filled.VolumeOff, R.string.tv_remote_mute, KeyEvent.KEYCODE_VOLUME_MUTE, onKey)
            RemoteKey(Icons.AutoMirrored.Filled.VolumeUp, R.string.tv_remote_volume_up, KeyEvent.KEYCODE_VOLUME_UP, onKey)
        }
        KeyRow {
            RemoteKey(Icons.Default.SkipPrevious, R.string.tv_remote_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS, onKey, small = true)
            RemoteKey(Icons.Default.FastRewind, R.string.tv_remote_rewind, KeyEvent.KEYCODE_MEDIA_REWIND, onKey, small = true)
            RemoteKey(Icons.Default.PlayArrow, R.string.tv_remote_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, onKey, small = true)
            RemoteKey(Icons.Default.FastForward, R.string.tv_remote_forward, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, onKey, small = true)
            RemoteKey(Icons.Default.SkipNext, R.string.tv_remote_next, KeyEvent.KEYCODE_MEDIA_NEXT, onKey, small = true)
        }
    }
}

@Composable
private fun KeyRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() }
    )
}

/** Крестовина: четыре стрелки по кругу и OK в центре, как на пульте ТВ. */
@Composable
private fun DPad(onKey: (Int, String) -> Unit) {
    Box(
        modifier = Modifier
            .size(236.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        PressArea(Icons.Default.KeyboardArrowUp, R.string.tv_remote_up, KeyEvent.KEYCODE_DPAD_UP, onKey,
            Modifier.align(Alignment.TopCenter).size(84.dp))
        PressArea(Icons.Default.KeyboardArrowDown, R.string.tv_remote_down, KeyEvent.KEYCODE_DPAD_DOWN, onKey,
            Modifier.align(Alignment.BottomCenter).size(84.dp))
        PressArea(Icons.AutoMirrored.Filled.KeyboardArrowLeft, R.string.tv_remote_left, KeyEvent.KEYCODE_DPAD_LEFT, onKey,
            Modifier.align(Alignment.CenterStart).size(84.dp))
        PressArea(Icons.AutoMirrored.Filled.KeyboardArrowRight, R.string.tv_remote_right, KeyEvent.KEYCODE_DPAD_RIGHT, onKey,
            Modifier.align(Alignment.CenterEnd).size(84.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .size(92.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .pressKey(KeyEvent.KEYCODE_DPAD_CENTER, stringResource(R.string.tv_remote_ok), onKey)
        ) {
            Text(
                text = stringResource(R.string.tv_remote_ok),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PressArea(icon: ImageVector, label: Int, code: Int, onKey: (Int, String) -> Unit, modifier: Modifier) {
    val description = stringResource(label)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .pressKey(code, description, onKey)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun RemoteKey(
    icon: ImageVector,
    label: Int,
    code: Int,
    onKey: (Int, String) -> Unit,
    small: Boolean = false,
) {
    val description = stringResource(label)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(if (small) 52.dp else 64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pressKey(code, description, onKey)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (small) 26.dp else 30.dp))
    }
}

/**
 * Кнопка пульта шлёт нажатие и отпускание отдельно: удержание стрелки на ТВ листает, а
 * долгое OK открывает меню, как с настоящим пультом. TalkBack жмёт её обычным кликом.
 */
private fun Modifier.pressKey(code: Int, description: String, onKey: (Int, String) -> Unit): Modifier = this
    .semantics {
        contentDescription = description
        role = Role.Button
        onClick {
            onKey(code, TvInput.KEY_PRESS)
            true
        }
    }
    .pointerInput(code) {
        detectTapGestures(onPress = {
            onKey(code, TvInput.KEY_DOWN)
            tryAwaitRelease()
            onKey(code, TvInput.KEY_UP)
        })
    }

/**
 * Тачпад. Сдвиги копятся и уходят на ТВ раз в кадр: сенсор телефона отдаёт события чаще,
 * чем имеет смысл гонять их по Wi-Fi. Ускорение как у тачпада ноутбука: медленное движение
 * точное, быстрое переносит курсор через весь экран.
 */
@Composable
private fun ColumnScope.TouchPanel(
    cursorAvailable: Boolean,
    onPointer: (action: String, dx: Float, dy: Float, wait: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val pending = remember { floatArrayOf(0f, 0f) }
    DisposableEffect(Unit) {
        onPointer(TvInput.POINTER_SHOW, 0f, 0f, true)
        onDispose { onPointer(TvInput.POINTER_HIDE, 0f, 0f, false) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            if (pending[0] != 0f || pending[1] != 0f) {
                onPointer(TvInput.POINTER_MOVE, pending[0], pending[1], false)
                pending[0] = 0f
                pending[1] = 0f
            }
        }
    }
    if (!cursorAvailable) {
        Text(
            text = stringResource(R.string.tv_remote_no_cursor),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var fingers = 1
                    var longSent = false
                    var lastTime = down.uptimeMillis
                    val scroll = floatArrayOf(0f, 0f)
                    while (true) {
                        val event = if (!moved && !longSent) {
                            val left = longPressMs - (lastTime - down.uptimeMillis)
                            withTimeoutOrNull(left.coerceAtLeast(1)) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            onPointer(TvInput.POINTER_LONG, 0f, 0f, true)
                            longSent = true
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        fingers = max(fingers, pressed.size)
                        val now = pressed[0].uptimeMillis
                        if (pressed.size >= 2) {
                            moved = true
                            val dx = pressed.map { it.positionChange().x }.average().toFloat()
                            val dy = pressed.map { it.positionChange().y }.average().toFloat()
                            scroll[0] += dx
                            scroll[1] += dy
                            val notch = SCROLL_NOTCH_DP * density
                            val notchesX = (scroll[0] / notch).toInt()
                            val notchesY = (scroll[1] / notch).toInt()
                            if (notchesX != 0 || notchesY != 0) {
                                onPointer(TvInput.POINTER_SCROLL, notchesX.toFloat(), notchesY.toFloat(), false)
                                scroll[0] -= notchesX * notch
                                scroll[1] -= notchesY * notch
                            }
                        } else if (fingers == 1) {
                            val change = pressed[0]
                            if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                            if (moved) {
                                val delta = change.positionChange() / density
                                val elapsed = (now - lastTime).coerceAtLeast(1)
                                val gain = pointerGain(hypot(delta.x, delta.y) / elapsed)
                                pending[0] += delta.x * gain
                                pending[1] += delta.y * gain
                            }
                        }
                        lastTime = now
                        event.changes.forEach { it.consume() }
                    }
                    if (!moved && !longSent) {
                        if (fingers >= 2) onBack() else onPointer(TvInput.POINTER_CLICK, 0f, 0f, true)
                    }
                }
            }
    ) {
        Text(
            text = stringResource(R.string.tv_remote_touchpad_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        FilledTonalButton(
            onClick = { onPointer(TvInput.POINTER_CLICK, 0f, 0f, true) },
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.tv_remote_click)) }
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.tv_remote_back))
        }
    }
}

/** Ускорение указателя по скорости пальца в dp/мс. */
private fun pointerGain(speed: Float): Float =
    POINTER_BASE_GAIN * (1f + POINTER_ACCELERATION * (speed - 0.25f).coerceIn(0f, 2f))

/**
 * Поле, которое повторяет набор на ТВ: каждая правка сравнивается с уже отправленным текстом,
 * лишний хвост стирается на ТВ, новый дописывается. Так работают и автозамена, и вставка.
 */
@Composable
private fun ColumnScope.TextPanel(
    onText: (String) -> Unit,
    onDelete: (Int) -> Unit,
    onKey: (Int) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var sent by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.tv_remote_text_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = field,
            onValueChange = { next ->
                val text = next.text
                val common = sent.commonPrefixWith(text).length
                val removed = sent.length - common
                if (removed > 0) onDelete(removed)
                val added = text.substring(common)
                if (added.isNotEmpty()) onText(added)
                sent = text
                field = next
            },
            label = { Text(stringResource(R.string.tv_remote_text_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onKey(KeyEvent.KEYCODE_ENTER) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { onKey(KeyEvent.KEYCODE_ENTER) }) {
                Text(stringResource(R.string.tv_remote_enter))
            }
            OutlinedButton(onClick = { onKey(KeyEvent.KEYCODE_DEL) }) {
                Text(stringResource(R.string.tv_remote_backspace))
            }
            // Очищает только поле телефона: следующая буква на ТВ допишется к тому, что там уже есть
            TextButton(onClick = {
                field = TextFieldValue("")
                sent = ""
            }) {
                Text(stringResource(R.string.tv_remote_clear))
            }
        }
    }
}

@Composable
private fun ColumnScope.AppsPanel(
    apps: List<TvApp>?,
    onLoad: () -> Unit,
    onLaunch: (TvApp) -> Unit,
) {
    LaunchedEffect(Unit) { if (apps == null) onLoad() }
    if (apps == null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.tv_remote_apps_loading))
        }
        return
    }
    if (apps.isEmpty()) {
        Text(
            text = stringResource(R.string.tv_remote_apps_empty),
            modifier = Modifier.padding(32.dp)
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.weight(1f)
    ) {
        items(apps, key = { it.pkg }) { app ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onLaunch(app) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val image = app.image
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            modifier = if (app.banner) Modifier.fillMaxSize() else Modifier.size(48.dp)
                        )
                    }
                }
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private const val POINTER_BASE_GAIN = 1.4f
private const val POINTER_ACCELERATION = 1.3f
private const val SCROLL_NOTCH_DP = 28f
