package com.vasmarfas.UniversalAmbientLight.ui.led

import android.content.Context
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberSettingsPreferences

internal const val MAX_LEDS_VISUALIZATION = 5000

internal const val MAX_LEDS_PER_SIDE = 5000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedLayoutScreen(
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // В режиме пульта раскладка правится у телевизора — через зеркало его настроек
    val prefs = rememberSettingsPreferences()

    val legacyX = prefs.getInt(R.string.pref_key_x_led)
    val legacyY = prefs.getInt(R.string.pref_key_y_led)

    var topLedText by remember {
        mutableStateOf(
            prefs.getInt(R.string.pref_key_led_count_top, legacyX).toString()
        )
    }
    var rightLedText by remember {
        mutableStateOf(
            prefs.getInt(R.string.pref_key_led_count_right, legacyY).toString()
        )
    }
    var bottomLedText by remember {
        mutableStateOf(
            prefs.getInt(R.string.pref_key_led_count_bottom, legacyX).toString()
        )
    }
    var leftLedText by remember {
        mutableStateOf(
            prefs.getInt(R.string.pref_key_led_count_left, legacyY).toString()
        )
    }
    var bottomGapText by remember {
        mutableStateOf(
            prefs.getInt(R.string.pref_key_bottom_gap, 0).toString()
        )
    }
    val legacyMargin = prefs.getInt(R.string.pref_key_capture_margin, -1)
    val marginH = prefs.getInt(R.string.pref_key_capture_margin_horizontal, -1)
    val marginV = prefs.getInt(R.string.pref_key_capture_margin_vertical, -1)

    var captureMarginTopText by remember {
        mutableStateOf(
            when {
                legacyMargin >= 0 -> legacyMargin.toString()
                marginV >= 0 -> marginV.toString()
                else -> prefs.getInt(R.string.pref_key_capture_margin_top, 0).toString()
            }
        )
    }
    var captureMarginRightText by remember {
        mutableStateOf(
            when {
                legacyMargin >= 0 -> legacyMargin.toString()
                marginH >= 0 -> marginH.toString()
                else -> prefs.getInt(R.string.pref_key_capture_margin_right, 0).toString()
            }
        )
    }
    var captureMarginBottomText by remember {
        mutableStateOf(
            when {
                legacyMargin >= 0 -> legacyMargin.toString()
                marginV >= 0 -> marginV.toString()
                else -> prefs.getInt(R.string.pref_key_capture_margin_bottom, 0).toString()
            }
        )
    }
    var captureMarginLeftText by remember {
        mutableStateOf(
            when {
                legacyMargin >= 0 -> legacyMargin.toString()
                marginH >= 0 -> marginH.toString()
                else -> prefs.getInt(R.string.pref_key_capture_margin_left, 0).toString()
            }
        )
    }
    var ledOffsetText by remember {
        mutableStateOf(
            prefs.getInt(R.string.pref_key_led_offset, 0).toString()
        )
    }
    var scanDepthText by remember {
        mutableStateOf(
            prefs.getInt(R.string.pref_key_scan_depth, 1).toString()
        )
    }

    val topLed = topLedText.toIntOrNull() ?: 0
    val rightLed = rightLedText.toIntOrNull() ?: 0
    val bottomLed = bottomLedText.toIntOrNull() ?: 0
    val leftLed = leftLedText.toIntOrNull() ?: 0
    val bottomGap = bottomGapText.toIntOrNull() ?: 0
    val captureMarginTop = captureMarginTopText.toIntOrNull() ?: 0
    val captureMarginRight = captureMarginRightText.toIntOrNull() ?: 0
    val captureMarginBottom = captureMarginBottomText.toIntOrNull() ?: 0
    val captureMarginLeft = captureMarginLeftText.toIntOrNull() ?: 0
    val ledOffset = ledOffsetText.toIntOrNull() ?: 0
    val scanDepth = scanDepthText.toIntOrNull() ?: 1

    var startCorner by remember {
        mutableStateOf(
            prefs.getString(R.string.pref_key_led_start_corner, "bottom_left") ?: "bottom_left"
        )
    }
    var direction by remember {
        mutableStateOf(prefs.getString(R.string.pref_key_led_direction, "clockwise") ?: "clockwise")
    }

    var sideTop by remember {
        mutableStateOf(
            prefs.getString(
                R.string.pref_key_led_side_top,
                "enabled"
            ) ?: "enabled"
        )
    }
    var sideRight by remember {
        mutableStateOf(
            prefs.getString(
                R.string.pref_key_led_side_right,
                "enabled"
            ) ?: "enabled"
        )
    }
    var sideBottom by remember {
        mutableStateOf(
            prefs.getString(
                R.string.pref_key_led_side_bottom,
                "enabled"
            ) ?: "enabled"
        )
    }
    var sideLeft by remember {
        mutableStateOf(
            prefs.getString(
                R.string.pref_key_led_side_left,
                "enabled"
            ) ?: "enabled"
        )
    }

    val isPortrait = configuration.screenHeightDp > configuration.screenWidthDp

    // Логируем изменения LED конфигурации
    androidx.compose.runtime.LaunchedEffect(topLed, rightLed, bottomLed, leftLed) {
        val totalHorizontal = topLed + bottomLed
        val totalVertical = rightLed + leftLed
        if (totalHorizontal > 0 || totalVertical > 0) {
            AnalyticsHelper.logLedConfigChanged(context, totalHorizontal, totalVertical)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.pref_title_led_layout))
                        LocalRemote.current?.tv?.let {
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
    ) { padding ->
        if (isPortrait) {
            // Портретная раскладка: сначала визуализация, под ней настройки (прокручиваемые)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val totalLeds = remember(
                        topLed,
                        rightLed,
                        bottomLed,
                        leftLed,
                        sideTop,
                        sideRight,
                        sideBottom,
                        sideLeft
                    ) {
                        var total = 0
                        if (sideTop != "not_installed") total += topLed.coerceAtLeast(0)
                        if (sideRight != "not_installed") total += rightLed.coerceAtLeast(0)
                        if (sideBottom != "not_installed") total += bottomLed.coerceAtLeast(0)
                        if (sideLeft != "not_installed") total += leftLed.coerceAtLeast(0)
                        total
                    }

                    Text(
                        text = stringResource(R.string.led_layout_total_leds, totalLeds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LedVisualization(
                        topLed = topLed,
                        rightLed = rightLed,
                        bottomLed = bottomLed,
                        leftLed = leftLed,
                        startCorner = startCorner,
                        direction = direction,
                        sideTop = sideTop,
                        sideRight = sideRight,
                        sideBottom = sideBottom,
                        sideLeft = sideLeft,
                        bottomGap = bottomGap,
                        captureMarginTop = captureMarginTop.coerceIn(0, 40),
                        captureMarginRight = captureMarginRight.coerceIn(0, 40),
                        captureMarginBottom = captureMarginBottom.coerceIn(0, 40),
                        captureMarginLeft = captureMarginLeft.coerceIn(0, 40),
                        ledOffset = ledOffset,
                        scanDepth = scanDepth.coerceIn(1, 50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    LedLayoutSettingsContent(
                        topLedText = topLedText,
                        onTopLedTextChange = { newText ->
                            topLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(
                                        R.string.pref_key_led_count_top,
                                        value.coerceAtMost(MAX_LEDS_PER_SIDE)
                                    )
                                }
                            }
                        },
                        rightLedText = rightLedText,
                        onRightLedTextChange = { newText ->
                            rightLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(
                                        R.string.pref_key_led_count_right,
                                        value.coerceAtMost(MAX_LEDS_PER_SIDE)
                                    )
                                }
                            }
                        },
                        bottomLedText = bottomLedText,
                        onBottomLedTextChange = { newText ->
                            bottomLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(
                                        R.string.pref_key_led_count_bottom,
                                        value.coerceAtMost(MAX_LEDS_PER_SIDE)
                                    )
                                }
                            }
                        },
                        leftLedText = leftLedText,
                        onLeftLedTextChange = { newText ->
                            leftLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(
                                        R.string.pref_key_led_count_left,
                                        value.coerceAtMost(MAX_LEDS_PER_SIDE)
                                    )
                                }
                            }
                        },
                        bottomGapText = bottomGapText,
                        onBottomGapTextChange = { newText ->
                            bottomGapText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(R.string.pref_key_bottom_gap, value)
                                    sendClearOnce(context)
                                }
                            }
                        },
                        captureMarginTopText = captureMarginTopText,
                        onCaptureMarginTopTextChange = { newText ->
                            captureMarginTopText = newText
                            newText.toIntOrNull()?.let { value ->
                                saveCaptureMargin(prefs, R.string.pref_key_capture_margin_top, value)
                            }
                        },
                        captureMarginRightText = captureMarginRightText,
                        onCaptureMarginRightTextChange = { newText ->
                            captureMarginRightText = newText
                            newText.toIntOrNull()?.let { value ->
                                saveCaptureMargin(prefs, R.string.pref_key_capture_margin_right, value)
                            }
                        },
                        captureMarginBottomText = captureMarginBottomText,
                        onCaptureMarginBottomTextChange = { newText ->
                            captureMarginBottomText = newText
                            newText.toIntOrNull()?.let { value ->
                                saveCaptureMargin(prefs, R.string.pref_key_capture_margin_bottom, value)
                            }
                        },
                        captureMarginLeftText = captureMarginLeftText,
                        onCaptureMarginLeftTextChange = { newText ->
                            captureMarginLeftText = newText
                            newText.toIntOrNull()?.let { value ->
                                saveCaptureMargin(prefs, R.string.pref_key_capture_margin_left, value)
                            }
                        },
                        ledOffsetText = ledOffsetText,
                        onLedOffsetTextChange = { newText ->
                            ledOffsetText = newText
                            newText.toIntOrNull()?.let { value ->
                                prefs.putInt(R.string.pref_key_led_offset, value)
                            }
                        },
                        scanDepthText = scanDepthText,
                        onScanDepthTextChange = { newText ->
                            scanDepthText = newText
                            newText.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(1, 50)
                                prefs.putInt(R.string.pref_key_scan_depth, clamped)
                            }
                        },
                        sideTop = sideTop,
                        onSideTopChange = { mode ->
                            sideTop = mode
                            prefs.putString(R.string.pref_key_led_side_top, mode)
                            sendClearOnce(context)
                        },
                        sideRight = sideRight,
                        onSideRightChange = { mode ->
                            sideRight = mode
                            prefs.putString(R.string.pref_key_led_side_right, mode)
                            sendClearOnce(context)
                        },
                        sideBottom = sideBottom,
                        onSideBottomChange = { mode ->
                            sideBottom = mode
                            prefs.putString(R.string.pref_key_led_side_bottom, mode)
                            sendClearOnce(context)
                        },
                        sideLeft = sideLeft,
                        onSideLeftChange = { mode ->
                            sideLeft = mode
                            prefs.putString(R.string.pref_key_led_side_left, mode)
                            sendClearOnce(context)
                        },
                        startCorner = startCorner,
                        onStartCornerChange = { newCorner ->
                            startCorner = newCorner
                            prefs.putString(R.string.pref_key_led_start_corner, newCorner)
                        },
                        direction = direction,
                        onDirectionChange = { newDir ->
                            direction = newDir
                            prefs.putString(R.string.pref_key_led_direction, newDir)
                        }
                    )
                }
            }
        } else {
            // Ландшафтная раскладка (как было, адаптировано в отдельный блок)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Слева — визуализация (фиксированная)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val totalLeds = remember(
                        topLed,
                        rightLed,
                        bottomLed,
                        leftLed,
                        sideTop,
                        sideRight,
                        sideBottom,
                        sideLeft
                    ) {
                        var total = 0
                        if (sideTop != "not_installed") total += topLed.coerceAtLeast(0)
                        if (sideRight != "not_installed") total += rightLed.coerceAtLeast(0)
                        if (sideBottom != "not_installed") total += bottomLed.coerceAtLeast(0)
                        if (sideLeft != "not_installed") total += leftLed.coerceAtLeast(0)
                        total
                    }

                    Text(
                        text = stringResource(R.string.led_layout_total_leds, totalLeds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    LedVisualization(
                        topLed = topLed,
                        rightLed = rightLed,
                        bottomLed = bottomLed,
                        leftLed = leftLed,
                        startCorner = startCorner,
                        direction = direction,
                        sideTop = sideTop,
                        sideRight = sideRight,
                        sideBottom = sideBottom,
                        sideLeft = sideLeft,
                        bottomGap = bottomGap,
                        captureMarginTop = captureMarginTop.coerceIn(0, 40),
                        captureMarginRight = captureMarginRight.coerceIn(0, 40),
                        captureMarginBottom = captureMarginBottom.coerceIn(0, 40),
                        captureMarginLeft = captureMarginLeft.coerceIn(0, 40),
                        ledOffset = ledOffset,
                        scanDepth = scanDepth.coerceIn(1, 50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .weight(1f, fill = false)
                    )
                }

                // Справа — настройки (с прокруткой)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    LedLayoutSettingsContent(
                        topLedText = topLedText,
                        onTopLedTextChange = { newText ->
                            topLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(
                                        R.string.pref_key_led_count_top,
                                        value.coerceAtMost(MAX_LEDS_PER_SIDE)
                                    )
                                }
                            }
                        },
                        rightLedText = rightLedText,
                        onRightLedTextChange = { newText ->
                            rightLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(
                                        R.string.pref_key_led_count_right,
                                        value.coerceAtMost(MAX_LEDS_PER_SIDE)
                                    )
                                }
                            }
                        },
                        bottomLedText = bottomLedText,
                        onBottomLedTextChange = { newText ->
                            bottomLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(
                                        R.string.pref_key_led_count_bottom,
                                        value.coerceAtMost(MAX_LEDS_PER_SIDE)
                                    )
                                }
                            }
                        },
                        leftLedText = leftLedText,
                        onLeftLedTextChange = { newText ->
                            leftLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(
                                        R.string.pref_key_led_count_left,
                                        value.coerceAtMost(MAX_LEDS_PER_SIDE)
                                    )
                                }
                            }
                        },
                        bottomGapText = bottomGapText,
                        onBottomGapTextChange = { newText ->
                            bottomGapText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(R.string.pref_key_bottom_gap, value)
                                    sendClearOnce(context)
                                }
                            }
                        },
                        captureMarginTopText = captureMarginTopText,
                        onCaptureMarginTopTextChange = { newText ->
                            captureMarginTopText = newText
                            newText.toIntOrNull()?.let { value ->
                                saveCaptureMargin(prefs, R.string.pref_key_capture_margin_top, value)
                            }
                        },
                        captureMarginRightText = captureMarginRightText,
                        onCaptureMarginRightTextChange = { newText ->
                            captureMarginRightText = newText
                            newText.toIntOrNull()?.let { value ->
                                saveCaptureMargin(prefs, R.string.pref_key_capture_margin_right, value)
                            }
                        },
                        captureMarginBottomText = captureMarginBottomText,
                        onCaptureMarginBottomTextChange = { newText ->
                            captureMarginBottomText = newText
                            newText.toIntOrNull()?.let { value ->
                                saveCaptureMargin(prefs, R.string.pref_key_capture_margin_bottom, value)
                            }
                        },
                        captureMarginLeftText = captureMarginLeftText,
                        onCaptureMarginLeftTextChange = { newText ->
                            captureMarginLeftText = newText
                            newText.toIntOrNull()?.let { value ->
                                saveCaptureMargin(prefs, R.string.pref_key_capture_margin_left, value)
                            }
                        },
                        ledOffsetText = ledOffsetText,
                        onLedOffsetTextChange = { newText ->
                            ledOffsetText = newText
                            newText.toIntOrNull()?.let { value ->
                                prefs.putInt(R.string.pref_key_led_offset, value)
                            }
                        },
                        scanDepthText = scanDepthText,
                        onScanDepthTextChange = { newText ->
                            scanDepthText = newText
                            newText.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(1, 50)
                                prefs.putInt(R.string.pref_key_scan_depth, clamped)
                            }
                        },
                        sideTop = sideTop,
                        onSideTopChange = { mode ->
                            sideTop = mode
                            prefs.putString(R.string.pref_key_led_side_top, mode)
                            sendClearOnce(context)
                        },
                        sideRight = sideRight,
                        onSideRightChange = { mode ->
                            sideRight = mode
                            prefs.putString(R.string.pref_key_led_side_right, mode)
                            sendClearOnce(context)
                        },
                        sideBottom = sideBottom,
                        onSideBottomChange = { mode ->
                            sideBottom = mode
                            prefs.putString(R.string.pref_key_led_side_bottom, mode)
                            sendClearOnce(context)
                        },
                        sideLeft = sideLeft,
                        onSideLeftChange = { mode ->
                            sideLeft = mode
                            prefs.putString(R.string.pref_key_led_side_left, mode)
                            sendClearOnce(context)
                        },
                        startCorner = startCorner,
                        onStartCornerChange = { newCorner ->
                            startCorner = newCorner
                            prefs.putString(R.string.pref_key_led_start_corner, newCorner)
                        },
                        direction = direction,
                        onDirectionChange = { newDir ->
                            direction = newDir
                            prefs.putString(R.string.pref_key_led_direction, newDir)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Пишет отступ стороны и выводит из игры устаревшие общие ключи: они перекрывают
 * пер-сторонние при чтении, и без сброса экран показывал бы одно, а захват брал другое.
 */
private fun saveCaptureMargin(prefs: Preferences, keyRes: Int, value: Int) {
    prefs.putInt(keyRes, value.coerceIn(0, 40))
    prefs.putInt(R.string.pref_key_capture_margin, -1)
    prefs.putInt(R.string.pref_key_capture_margin_horizontal, -1)
    prefs.putInt(R.string.pref_key_capture_margin_vertical, -1)
}

private fun sendClearOnce(context: Context) {
    // Гасить нечего, когда сервис не работает, а startService в этот момент создал бы
    // пустой foreground-сервис с вечным уведомлением
    if (!ScreenGrabberService.sInstanceRunning) return
    val intent = android.content.Intent(context, ScreenGrabberService::class.java).apply {
        action = ScreenGrabberService.ACTION_CLEAR
    }
    context.startService(intent)
}
