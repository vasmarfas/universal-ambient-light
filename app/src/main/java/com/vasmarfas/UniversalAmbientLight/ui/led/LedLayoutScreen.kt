package com.vasmarfas.UniversalAmbientLight.ui.led

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import android.content.Context
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import kotlin.math.min

private const val MAX_LEDS_VISUALIZATION = 5000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedLayoutScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val prefs = remember { Preferences(context) }
    
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
    var bottomGapText by remember { mutableStateOf(prefs.getInt(R.string.pref_key_bottom_gap, 0).toString()) }
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
    var ledOffsetText by remember { mutableStateOf(prefs.getInt(R.string.pref_key_led_offset, 0).toString()) }
    var scanDepthText by remember { mutableStateOf(prefs.getInt(R.string.pref_key_scan_depth, 1).toString()) }
    
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
        mutableStateOf(prefs.getString(R.string.pref_key_led_start_corner, "bottom_left") ?: "bottom_left") 
    }
    var direction by remember { 
        mutableStateOf(prefs.getString(R.string.pref_key_led_direction, "clockwise") ?: "clockwise") 
    }
    
    var sideTop by remember { mutableStateOf(prefs.getString(R.string.pref_key_led_side_top, "enabled") ?: "enabled") }
    var sideRight by remember { mutableStateOf(prefs.getString(R.string.pref_key_led_side_right, "enabled") ?: "enabled") }
    var sideBottom by remember { mutableStateOf(prefs.getString(R.string.pref_key_led_side_bottom, "enabled") ?: "enabled") }
    var sideLeft by remember { mutableStateOf(prefs.getString(R.string.pref_key_led_side_left, "enabled") ?: "enabled") }

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
                title = { Text(stringResource(R.string.pref_title_led_layout)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Default.ArrowBack,
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
                        text = stringResource(R.string.pref_title_led_layout),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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
                                    prefs.putInt(R.string.pref_key_led_count_top, value)
                                }
                            }
                        },
                        rightLedText = rightLedText,
                        onRightLedTextChange = { newText ->
                            rightLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(R.string.pref_key_led_count_right, value)
                                }
                            }
                        },
                        bottomLedText = bottomLedText,
                        onBottomLedTextChange = { newText ->
                            bottomLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(R.string.pref_key_led_count_bottom, value)
                                }
                            }
                        },
                        leftLedText = leftLedText,
                        onLeftLedTextChange = { newText ->
                            leftLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(R.string.pref_key_led_count_left, value)
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
                                val clamped = value.coerceIn(0, 40)
                                prefs.putInt(R.string.pref_key_capture_margin_top, clamped)
                            }
                        },
                        captureMarginRightText = captureMarginRightText,
                        onCaptureMarginRightTextChange = { newText ->
                            captureMarginRightText = newText
                            newText.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(0, 40)
                                prefs.putInt(R.string.pref_key_capture_margin_right, clamped)
                            }
                        },
                        captureMarginBottomText = captureMarginBottomText,
                        onCaptureMarginBottomTextChange = { newText ->
                            captureMarginBottomText = newText
                            newText.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(0, 40)
                                prefs.putInt(R.string.pref_key_capture_margin_bottom, clamped)
                            }
                        },
                        captureMarginLeftText = captureMarginLeftText,
                        onCaptureMarginLeftTextChange = { newText ->
                            captureMarginLeftText = newText
                            newText.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(0, 40)
                                prefs.putInt(R.string.pref_key_capture_margin_left, clamped)
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
                // Left Side: Visualization (Fixed)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.pref_title_led_layout),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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

                    // LED visualization
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

                // Right Side: Settings (Scrollable)
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
                                    prefs.putInt(R.string.pref_key_led_count_top, value)
                                }
                            }
                        },
                        rightLedText = rightLedText,
                        onRightLedTextChange = { newText ->
                            rightLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(R.string.pref_key_led_count_right, value)
                                }
                            }
                        },
                        bottomLedText = bottomLedText,
                        onBottomLedTextChange = { newText ->
                            bottomLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(R.string.pref_key_led_count_bottom, value)
                                }
                            }
                        },
                        leftLedText = leftLedText,
                        onLeftLedTextChange = { newText ->
                            leftLedText = newText
                            newText.toIntOrNull()?.let { value ->
                                if (value >= 0) {
                                    prefs.putInt(R.string.pref_key_led_count_left, value)
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
                                val clamped = value.coerceIn(0, 40)
                                prefs.putInt(R.string.pref_key_capture_margin_top, clamped)
                            }
                        },
                        captureMarginRightText = captureMarginRightText,
                        onCaptureMarginRightTextChange = { newText ->
                            captureMarginRightText = newText
                            newText.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(0, 40)
                                prefs.putInt(R.string.pref_key_capture_margin_right, clamped)
                            }
                        },
                        captureMarginBottomText = captureMarginBottomText,
                        onCaptureMarginBottomTextChange = { newText ->
                            captureMarginBottomText = newText
                            newText.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(0, 40)
                                prefs.putInt(R.string.pref_key_capture_margin_bottom, clamped)
                            }
                        },
                        captureMarginLeftText = captureMarginLeftText,
                        onCaptureMarginLeftTextChange = { newText ->
                            captureMarginLeftText = newText
                            newText.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(0, 40)
                                prefs.putInt(R.string.pref_key_capture_margin_left, clamped)
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

@Composable
private fun LedLayoutSettingsContent(
    topLedText: String,
    onTopLedTextChange: (String) -> Unit,
    rightLedText: String,
    onRightLedTextChange: (String) -> Unit,
    bottomLedText: String,
    onBottomLedTextChange: (String) -> Unit,
    leftLedText: String,
    onLeftLedTextChange: (String) -> Unit,
    bottomGapText: String,
    onBottomGapTextChange: (String) -> Unit,
    captureMarginTopText: String,
    onCaptureMarginTopTextChange: (String) -> Unit,
    captureMarginRightText: String,
    onCaptureMarginRightTextChange: (String) -> Unit,
    captureMarginBottomText: String,
    onCaptureMarginBottomTextChange: (String) -> Unit,
    captureMarginLeftText: String,
    onCaptureMarginLeftTextChange: (String) -> Unit,
    ledOffsetText: String,
    onLedOffsetTextChange: (String) -> Unit,
    scanDepthText: String,
    onScanDepthTextChange: (String) -> Unit,
    sideTop: String,
    onSideTopChange: (String) -> Unit,
    sideRight: String,
    onSideRightChange: (String) -> Unit,
    sideBottom: String,
    onSideBottomChange: (String) -> Unit,
    sideLeft: String,
    onSideLeftChange: (String) -> Unit,
    startCorner: String,
    onStartCornerChange: (String) -> Unit,
    direction: String,
    onDirectionChange: (String) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // LED count inputs per side (order: left, top, right, bottom)
    OutlinedTextField(
        value = leftLedText,
        onValueChange = onLeftLedTextChange,
        label = { Text(stringResource(R.string.led_layout_left_count_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = leftLedText.isNotEmpty() && leftLedText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = topLedText,
        onValueChange = onTopLedTextChange,
        label = { Text(stringResource(R.string.led_layout_top_count_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = topLedText.isNotEmpty() && topLedText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = rightLedText,
        onValueChange = onRightLedTextChange,
        label = { Text(stringResource(R.string.led_layout_right_count_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = rightLedText.isNotEmpty() && rightLedText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = bottomLedText,
        onValueChange = onBottomLedTextChange,
        label = { Text(stringResource(R.string.led_layout_bottom_count_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = bottomLedText.isNotEmpty() && bottomLedText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(24.dp))

    // LED sides configuration
    Text(
        text = stringResource(R.string.led_layout_active_sides),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Order: left, top, right, bottom
    SideSelectorCard(
        title = stringResource(R.string.led_layout_side_left),
        selectedMode = sideLeft,
        onModeSelected = onSideLeftChange
    )

    Spacer(modifier = Modifier.height(8.dp))

    SideSelectorCard(
        title = stringResource(R.string.led_layout_side_top),
        selectedMode = sideTop,
        onModeSelected = onSideTopChange
    )

    Spacer(modifier = Modifier.height(8.dp))

    SideSelectorCard(
        title = stringResource(R.string.led_layout_side_right),
        selectedMode = sideRight,
        onModeSelected = onSideRightChange
    )

    Spacer(modifier = Modifier.height(8.dp))

    SideSelectorCard(
        title = stringResource(R.string.led_layout_side_bottom),
        selectedMode = sideBottom,
        onModeSelected = onSideBottomChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Bottom gap
    OutlinedTextField(
        value = bottomGapText,
        onValueChange = onBottomGapTextChange,
        label = { Text(stringResource(R.string.led_layout_bottom_gap_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = bottomGapText.isNotEmpty() && bottomGapText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Capture margins - separate for each side (order: left, top, right, bottom)
    Text(
        text = stringResource(R.string.led_layout_capture_margin_label),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Capture margin - left
    OutlinedTextField(
        value = captureMarginLeftText,
        onValueChange = onCaptureMarginLeftTextChange,
        label = { Text(stringResource(R.string.led_layout_capture_margin_left_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_capture_margin_left_help),
                fontSize = 12.sp
            )
        },
        isError = captureMarginLeftText.isNotEmpty() && captureMarginLeftText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Capture margin - top
    OutlinedTextField(
        value = captureMarginTopText,
        onValueChange = onCaptureMarginTopTextChange,
        label = { Text(stringResource(R.string.led_layout_capture_margin_top_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_capture_margin_top_help),
                fontSize = 12.sp
            )
        },
        isError = captureMarginTopText.isNotEmpty() && captureMarginTopText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Capture margin - right
    OutlinedTextField(
        value = captureMarginRightText,
        onValueChange = onCaptureMarginRightTextChange,
        label = { Text(stringResource(R.string.led_layout_capture_margin_right_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_capture_margin_right_help),
                fontSize = 12.sp
            )
        },
        isError = captureMarginRightText.isNotEmpty() && captureMarginRightText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Capture margin - bottom
    OutlinedTextField(
        value = captureMarginBottomText,
        onValueChange = onCaptureMarginBottomTextChange,
        label = { Text(stringResource(R.string.led_layout_capture_margin_bottom_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_capture_margin_bottom_help),
                fontSize = 12.sp
            )
        },
        isError = captureMarginBottomText.isNotEmpty() && captureMarginBottomText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(16.dp))

    // LED offset along perimeter
    OutlinedTextField(
        value = ledOffsetText,
        onValueChange = onLedOffsetTextChange,
        label = { Text(stringResource(R.string.led_layout_offset_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_offset_help),
                fontSize = 12.sp
            )
        },
        isError = ledOffsetText.isNotEmpty() && ledOffsetText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Scan Depth
    OutlinedTextField(
        value = scanDepthText,
        onValueChange = onScanDepthTextChange,
        label = { Text(stringResource(R.string.led_layout_scan_depth_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_scan_depth_help),
                fontSize = 12.sp
            )
        },
        isError = scanDepthText.isNotEmpty() && (scanDepthText.toIntOrNull() == null || scanDepthText.toInt() !in 1..50)
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Start corner selection
    Text(
        text = stringResource(R.string.pref_title_led_start_corner),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("bottom_left", "top_left", "top_right", "bottom_right").forEach { corner ->
            FilterChip(
                selected = startCorner == corner,
                onClick = { onStartCornerChange(corner) },
                label = { Text(getCornerName(corner), fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Direction selection
    Text(
        text = stringResource(R.string.pref_title_led_direction),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("clockwise", "counterclockwise").forEach { dir ->
            FilterChip(
                selected = direction == dir,
                onClick = { onDirectionChange(dir) },
                label = { Text(getDirectionName(dir), fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Legend
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.led_layout_legend_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LegendItem(
                color = Color(0xFF4CAF50),
                text = stringResource(R.string.led_layout_legend_first_led)
            )
            LegendItem(
                color = Color(0xFF2196F3),
                text = stringResource(R.string.led_layout_legend_active_leds)
            )
            LegendItem(
                color = Color.Gray.copy(alpha = 0.4f),
                text = stringResource(R.string.led_layout_legend_disabled_leds)
            )
            LegendItem(
                color = Color.Gray,
                text = stringResource(R.string.led_layout_legend_screen)
            )
        }
    }

}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideSelectorCard(
    title: String,
    selectedMode: String,
    onModeSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedMode == "enabled",
                    onClick = { onModeSelected("enabled") },
                    label = { Text(stringResource(R.string.led_side_mode_on), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedMode == "disabled",
                    onClick = { onModeSelected("disabled") },
                    label = { Text(stringResource(R.string.led_side_mode_off), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedMode == "not_installed",
                    onClick = { onModeSelected("not_installed") },
                    label = { Text(stringResource(R.string.led_side_mode_none), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun LedVisualization(
    topLed: Int,
    rightLed: Int,
    bottomLed: Int,
    leftLed: Int,
    startCorner: String,
    direction: String,
    sideTop: String,
    sideRight: String,
    sideBottom: String,
    sideLeft: String,
    bottomGap: Int,
    captureMarginTop: Int,
    captureMarginRight: Int,
    captureMarginBottom: Int,
    captureMarginLeft: Int,
    ledOffset: Int,
    scanDepth: Int,
    modifier: Modifier = Modifier
) {
    val (safeTop, safeRight, safeBottom, safeLeft) = remember(topLed, rightLed, bottomLed, leftLed) {
        val t = topLed.coerceAtLeast(0)
        val r = rightLed.coerceAtLeast(0)
        val b = bottomLed.coerceAtLeast(0)
        val l = leftLed.coerceAtLeast(0)
        val total = t + r + b + l

        if (total > MAX_LEDS_VISUALIZATION) {
            val factor = MAX_LEDS_VISUALIZATION.toFloat() / total
            listOf(
                (t * factor).toInt(),
                (r * factor).toInt(),
                (b * factor).toInt(),
                (l * factor).toInt()
            )
        } else {
            listOf(t, r, b, l)
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                
                // Draw screen rectangle
                val screenPadding = 60f
                drawRect(
                    color = Color.Gray.copy(alpha = 0.3f),
                    topLeft = Offset(screenPadding, screenPadding),
                    size = androidx.compose.ui.geometry.Size(
                        width - screenPadding * 2,
                        height - screenPadding * 2
                    ),
                    style = Stroke(width = 2f)
                )

                // Draw capture area with separate margins for each side (as inner rectangle)
                val marginTop = captureMarginTop.coerceIn(0, 40)
                val marginRight = captureMarginRight.coerceIn(0, 40)
                val marginBottom = captureMarginBottom.coerceIn(0, 40)
                val marginLeft = captureMarginLeft.coerceIn(0, 40)
                if (marginTop > 0 || marginRight > 0 || marginBottom > 0 || marginLeft > 0) {
                    val screenWidth = width - screenPadding * 2
                    val screenHeight = height - screenPadding * 2
                    val innerLeft = screenPadding + screenWidth * marginLeft / 100f
                    val innerTop = screenPadding + screenHeight * marginTop / 100f
                    val innerRight = width - screenPadding - screenWidth * marginRight / 100f
                    val innerBottom = height - screenPadding - screenHeight * marginBottom / 100f

                    drawRect(
                        color = Color.Yellow.copy(alpha = 0.35f),
                        topLeft = Offset(innerLeft, innerTop),
                        size = androidx.compose.ui.geometry.Size(
                            innerRight - innerLeft,
                            innerBottom - innerTop
                        ),
                        style = Stroke(width = 2f)
                    )
                }
                
                // Calculate LED positions
                var ledPositions = calculateLedPositions(
                    safeTop, safeRight, safeBottom, safeLeft,
                    startCorner, direction,
                    sideTop, sideRight, sideBottom, sideLeft, bottomGap,
                    width, height, screenPadding,
                    scanDepth
                )

                // Apply same offset in visualization so порядок номеров совпадает
                if (ledPositions.isNotEmpty()) {
                    val size = ledPositions.size
                    val offset = ((ledOffset % size) + size) % size
                    if (offset != 0) {
                        val rotated = MutableList(size) { ledPositions[0] }
                        for (i in 0 until size) {
                            val targetIndex = (i + offset) % size
                            rotated[targetIndex] = ledPositions[i]
                        }
                        ledPositions = rotated
                    }
                }
                
                // Draw LEDs
                if (ledPositions.isNotEmpty()) {
                    // Немного ограничим количество подписанных LED, чтобы не грузить слабые устройства
                    val maxLabeled = 200
                    val shouldLabelIndices: (Int) -> Boolean = { index ->
                        if (ledPositions.size > maxLabeled) {
                            // При очень большом количестве только первые/последние пару и каждый десятый
                            index < 3 || index >= ledPositions.size - 3 || index % 10 == 0
                        } else {
                            index < 5 || index >= ledPositions.size - 5 || index % 5 == 0
                        }
                    }

                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val enabledPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 20f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val disabledPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 20f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val firstLedPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 28f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                    }

                    ledPositions.forEachIndexed { index, ledData ->
                        val color = when {
                            !ledData.enabled -> Color.Gray.copy(alpha = 0.4f) // Disabled LED
                            index == 0 -> Color(0xFF4CAF50) // Green for first LED
                            else -> Color(0xFF2196F3) // Blue for others
                        }

                        // Draw LED
                        if (ledData.rectSize.width > 0 && ledData.rectSize.height > 0) {
                            val topLeft = Offset(
                                ledData.position.x - ledData.rectSize.width / 2f,
                                ledData.position.y - ledData.rectSize.height / 2f
                            )
                            drawRect(
                                color = color.copy(alpha = 0.7f),
                                topLeft = topLeft,
                                size = ledData.rectSize,
                                style = androidx.compose.ui.graphics.drawscope.Fill
                            )
                            // Draw border for visibility
                            drawRect(
                                color = if (index == 0) Color.White else Color.Black.copy(alpha = 0.5f),
                                topLeft = topLeft,
                                size = ledData.rectSize,
                                style = Stroke(width = if (index == 0) 2f else 1f)
                            )
                        } else {
                            // Fallback
                            drawCircle(
                                color = color,
                                radius = if (index == 0) 18f else 8f,
                                center = ledData.position
                            )
                        }

                        if (shouldLabelIndices(index)) {
                            val paint = when {
                                index == 0 -> firstLedPaint
                                ledData.enabled -> enabledPaint
                                else -> disabledPaint
                            }
                            // Adjust text position slightly if rect
                            val textY = if (ledData.rectSize.height > 0) {
                                ledData.position.y + 7f 
                            } else {
                                ledData.position.y + if (index == 0) 10f else 7f
                            }
                            
                            nativeCanvas.drawText(
                                "${index + 1}",
                                ledData.position.x,
                                textY,
                                paint
                            )
                        }
                    }
                }
            }
        }
    }
}

data class LedData(
    val position: Offset,
    val enabled: Boolean,
    val rectSize: androidx.compose.ui.geometry.Size = androidx.compose.ui.geometry.Size(0f, 0f)
)

fun calculateLedPositions(
    topLed: Int,
    rightLed: Int,
    bottomLed: Int,
    leftLed: Int,
    startCorner: String,
    direction: String,
    sideTop: String,
    sideRight: String,
    sideBottom: String,
    sideLeft: String,
    bottomGap: Int,
    width: Float,
    height: Float,
    padding: Float,
    scanDepth: Int // Percent 1-50
): List<LedData> {
    val positions = mutableListOf<LedData>()
    
    val screenWidth = width - padding * 2
    val screenHeight = height - padding * 2
    val topCount = topLed.coerceAtLeast(0)
    val rightCount = rightLed.coerceAtLeast(0)
    val bottomCount = bottomLed.coerceAtLeast(0)
    val leftCount = leftLed.coerceAtLeast(0)

    // Calculate scan depth in pixels (visual approximation)
    // We use screenWidth/screenHeight which corresponds to the inner yellow box if 0 margins.
    // But scan depth is relative to the capture area.
    // Here we visualize it relative to the "screen" rectangle (gray box).
    // Let's assume the gray box is the full captured image.
    val scanDepthV = (screenHeight * scanDepth / 100f).coerceAtLeast(2f)
    val scanDepthH = (screenWidth * scanDepth / 100f).coerceAtLeast(2f)

    fun step(length: Float, count: Int): Float {
        return if (count <= 1) 0f else length / (count - 1)
    }

    val stepTop = step(screenWidth, topCount)
    val stepRight = step(screenHeight, rightCount)
    val stepBottom = step(screenWidth, bottomCount)
    val stepLeft = step(screenHeight, leftCount)
    
    // Determine edge order
    val edges = getEdgeOrder(startCorner, direction)
    
    // Calculate gap range for bottom edge
    val gapStart = if (bottomGap > 0 && bottomCount > 0) (bottomCount - bottomGap) / 2 else -1
    val gapEnd = if (bottomGap > 0 && bottomCount > 0) gapStart + bottomGap else -1
    
    for (edge in edges) {
        val sideMode = when {
            edge.startsWith("top_") -> sideTop
            edge.startsWith("right_") -> sideRight
            edge.startsWith("bottom_") -> sideBottom
            edge.startsWith("left_") -> sideLeft
            else -> "enabled"
        }
        
        // Skip if not installed
        if (sideMode == "not_installed") continue
        
            when (edge) {
            "top_lr" -> {
                // Top edge (left to right)
                for (i in 0 until topCount) {
                    val x = if (topCount <= 1) {
                        padding + screenWidth / 2f
                    } else {
                        padding + i * stepTop + stepTop / 2f
                    }
                    // For top edge, rect is centered at x, starts at padding (top), height scanDepthV
                    positions.add(LedData(
                        position = Offset(x, padding + scanDepthV / 2f), // Center of rect
                        enabled = sideMode == "enabled",
                        rectSize = androidx.compose.ui.geometry.Size(stepTop, scanDepthV)
                    ))
                }
            }
            "top_rl" -> {
                // Top edge (right to left)
                for (i in 0 until topCount) {
                    val ledIndex = topCount - 1 - i
                    val x = if (topCount <= 1) {
                        padding + screenWidth / 2f
                    } else {
                        padding + ledIndex * stepTop + stepTop / 2f
                    }
                    positions.add(LedData(
                        position = Offset(x, padding + scanDepthV / 2f),
                        enabled = sideMode == "enabled",
                        rectSize = androidx.compose.ui.geometry.Size(stepTop, scanDepthV)
                    ))
                }
            }
            "right_tb" -> {
                // Right edge (top to bottom)
                for (i in 0 until rightCount) {
                    val y = if (rightCount <= 1) {
                        padding + screenHeight / 2f
                    } else {
                        padding + i * stepRight + stepRight / 2f
                    }
                    // Right edge: rect starts at width-padding-scanDepthH
                    positions.add(LedData(
                        position = Offset(padding + screenWidth - scanDepthH / 2f, y),
                        enabled = sideMode == "enabled",
                        rectSize = androidx.compose.ui.geometry.Size(scanDepthH, stepRight)
                    ))
                }
            }
            "right_bt" -> {
                // Right edge (bottom to top)
                for (i in 0 until rightCount) {
                    val ledIndex = rightCount - 1 - i
                    val y = if (rightCount <= 1) {
                        padding + screenHeight / 2f
                    } else {
                        padding + ledIndex * stepRight + stepRight / 2f
                    }
                    positions.add(LedData(
                        position = Offset(padding + screenWidth - scanDepthH / 2f, y),
                        enabled = sideMode == "enabled",
                        rectSize = androidx.compose.ui.geometry.Size(scanDepthH, stepRight)
                    ))
                }
            }
            "bottom_rl" -> {
                // Bottom edge (right to left)
                for (i in 0 until bottomCount) {
                    val ledIndex = bottomCount - 1 - i
                    val isInGap = bottomGap > 0 && bottomCount > 0 && ledIndex >= gapStart && ledIndex < gapEnd
                    val x = if (bottomCount <= 1) {
                        padding + screenWidth / 2f
                    } else {
                        padding + ledIndex * stepBottom + stepBottom / 2f
                    }
                    // Bottom edge: rect starts at height-padding-scanDepthV
                    positions.add(LedData(
                        position = Offset(x, padding + screenHeight - scanDepthV / 2f),
                        enabled = sideMode == "enabled" && !isInGap,
                        rectSize = androidx.compose.ui.geometry.Size(stepBottom, scanDepthV)
                    ))
                }
            }
            "bottom_lr" -> {
                // Bottom edge (left to right)
                for (i in 0 until bottomCount) {
                    val isInGap = bottomGap > 0 && bottomCount > 0 && i >= gapStart && i < gapEnd
                    val x = if (bottomCount <= 1) {
                        padding + screenWidth / 2f
                    } else {
                        padding + i * stepBottom + stepBottom / 2f
                    }
                    positions.add(LedData(
                        position = Offset(x, padding + screenHeight - scanDepthV / 2f),
                        enabled = sideMode == "enabled" && !isInGap,
                        rectSize = androidx.compose.ui.geometry.Size(stepBottom, scanDepthV)
                    ))
                }
            }
            "left_bt" -> {
                // Left edge (bottom to top)
                for (i in 0 until leftCount) {
                    val ledIndex = leftCount - 1 - i
                    val y = if (leftCount <= 1) {
                        padding + screenHeight / 2f
                    } else {
                        padding + ledIndex * stepLeft + stepLeft / 2f
                    }
                    // Left edge: rect starts at padding
                    positions.add(LedData(
                        position = Offset(padding + scanDepthH / 2f, y),
                        enabled = sideMode == "enabled",
                        rectSize = androidx.compose.ui.geometry.Size(scanDepthH, stepLeft)
                    ))
                }
            }
            "left_tb" -> {
                // Left edge (top to bottom)
                for (i in 0 until leftCount) {
                    val y = if (leftCount <= 1) {
                        padding + screenHeight / 2f
                    } else {
                        padding + i * stepLeft + stepLeft / 2f
                    }
                    positions.add(LedData(
                        position = Offset(padding + scanDepthH / 2f, y),
                        enabled = sideMode == "enabled",
                        rectSize = androidx.compose.ui.geometry.Size(scanDepthH, stepLeft)
                    ))
                }
            }
        }
    }
    
    return positions
}

private fun sendClearOnce(context: Context) {
    val intent = android.content.Intent(context, ScreenGrabberService::class.java).apply {
        action = ScreenGrabberService.ACTION_CLEAR
    }
    context.startService(intent)
}

fun getEdgeOrder(startCorner: String, direction: String): List<String> {
    return when (startCorner) {
        "top_left" -> {
            if (direction == "clockwise") {
                listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
            } else {
                listOf("left_tb", "bottom_lr", "right_bt", "top_rl")
            }
        }
        "top_right" -> {
            if (direction == "clockwise") {
                listOf("right_tb", "bottom_rl", "left_bt", "top_lr")
            } else {
                listOf("top_rl", "left_tb", "bottom_lr", "right_bt")
            }
        }
        "bottom_right" -> {
            if (direction == "clockwise") {
                listOf("bottom_rl", "left_bt", "top_lr", "right_tb")
            } else {
                listOf("right_bt", "top_rl", "left_tb", "bottom_lr")
            }
        }
        "bottom_left" -> {
            if (direction == "clockwise") {
                listOf("left_bt", "top_lr", "right_tb", "bottom_rl")
            } else {
                listOf("bottom_lr", "right_bt", "top_rl", "left_tb")
            }
        }
        else -> listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
    }
}

@Composable
fun SettingCard(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun getCornerName(corner: String): String {
    return when (corner) {
        "top_left" -> stringResource(R.string.led_corner_top_left)
        "top_right" -> stringResource(R.string.led_corner_top_right)
        "bottom_right" -> stringResource(R.string.led_corner_bottom_right)
        "bottom_left" -> stringResource(R.string.led_corner_bottom_left)
        else -> corner
    }
}

@Composable
fun getDirectionName(direction: String): String {
    return when (direction) {
        "clockwise" -> stringResource(R.string.led_direction_clockwise)
        "counterclockwise" -> stringResource(R.string.led_direction_counterclockwise)
        else -> direction
    }
}
