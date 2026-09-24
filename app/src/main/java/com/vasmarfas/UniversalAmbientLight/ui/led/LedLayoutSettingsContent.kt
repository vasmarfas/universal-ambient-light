package com.vasmarfas.UniversalAmbientLight.ui.led

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.components.NumberStepper

/**
 * Панель параметров раскладки: количество светодиодов по сторонам, стартовый угол,
 * направление обхода и отступы захвата.
 */
@Composable
internal fun LedLayoutSettingsContent(
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
    onDirectionChange: (String) -> Unit,
) {
    // Количество светодиодов по сторонам (порядок: левая, верх, правая, низ)
    NumberStepper(
        label = stringResource(R.string.led_layout_left_count_label),
        value = leftLedText.toIntOrNull() ?: 0,
        onValueChange = { onLeftLedTextChange(it.toString()) },
        range = 0..MAX_LEDS_PER_SIDE
    )

    Spacer(modifier = Modifier.height(12.dp))

    NumberStepper(
        label = stringResource(R.string.led_layout_top_count_label),
        value = topLedText.toIntOrNull() ?: 0,
        onValueChange = { onTopLedTextChange(it.toString()) },
        range = 0..MAX_LEDS_PER_SIDE
    )

    Spacer(modifier = Modifier.height(12.dp))

    NumberStepper(
        label = stringResource(R.string.led_layout_right_count_label),
        value = rightLedText.toIntOrNull() ?: 0,
        onValueChange = { onRightLedTextChange(it.toString()) },
        range = 0..MAX_LEDS_PER_SIDE
    )

    Spacer(modifier = Modifier.height(12.dp))

    NumberStepper(
        label = stringResource(R.string.led_layout_bottom_count_label),
        value = bottomLedText.toIntOrNull() ?: 0,
        onValueChange = { onBottomLedTextChange(it.toString()) },
        range = 0..MAX_LEDS_PER_SIDE
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Какие стороны заведены
    Text(
        text = stringResource(R.string.led_layout_active_sides),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Порядок: левая, верх, правая, низ
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

    // Разрыв снизу
    NumberStepper(
        label = stringResource(R.string.led_layout_bottom_gap_label),
        value = bottomGapText.toIntOrNull() ?: 0,
        onValueChange = { onBottomGapTextChange(it.toString()) },
        range = 0..MAX_LEDS_PER_SIDE
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Отступы захвата — свой на каждую сторону (порядок: левая, верх, правая, низ)
    Text(
        text = stringResource(R.string.led_layout_capture_margin_label),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    NumberStepper(
        label = stringResource(R.string.led_layout_capture_margin_left_label),
        value = captureMarginLeftText.toIntOrNull() ?: 0,
        onValueChange = { onCaptureMarginLeftTextChange(it.toString()) },
        range = 0..40,
        supportingText = stringResource(R.string.led_layout_capture_margin_left_help)
    )

    Spacer(modifier = Modifier.height(12.dp))

    NumberStepper(
        label = stringResource(R.string.led_layout_capture_margin_top_label),
        value = captureMarginTopText.toIntOrNull() ?: 0,
        onValueChange = { onCaptureMarginTopTextChange(it.toString()) },
        range = 0..40,
        supportingText = stringResource(R.string.led_layout_capture_margin_top_help)
    )

    Spacer(modifier = Modifier.height(12.dp))

    NumberStepper(
        label = stringResource(R.string.led_layout_capture_margin_right_label),
        value = captureMarginRightText.toIntOrNull() ?: 0,
        onValueChange = { onCaptureMarginRightTextChange(it.toString()) },
        range = 0..40,
        supportingText = stringResource(R.string.led_layout_capture_margin_right_help)
    )

    Spacer(modifier = Modifier.height(12.dp))

    NumberStepper(
        label = stringResource(R.string.led_layout_capture_margin_bottom_label),
        value = captureMarginBottomText.toIntOrNull() ?: 0,
        onValueChange = { onCaptureMarginBottomTextChange(it.toString()) },
        range = 0..40,
        supportingText = stringResource(R.string.led_layout_capture_margin_bottom_help)
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Сдвиг светодиодов по периметру
    NumberStepper(
        label = stringResource(R.string.led_layout_offset_label),
        value = ledOffsetText.toIntOrNull() ?: 0,
        onValueChange = { onLedOffsetTextChange(it.toString()) },
        range = -MAX_LEDS_PER_SIDE..MAX_LEDS_PER_SIDE,
        supportingText = stringResource(R.string.led_layout_offset_help)
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Глубина сканирования
    NumberStepper(
        label = stringResource(R.string.led_layout_scan_depth_label),
        value = scanDepthText.toIntOrNull() ?: 0,
        onValueChange = { onScanDepthTextChange(it.toString()) },
        range = 1..50,
        supportingText = stringResource(R.string.led_layout_scan_depth_help)
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Выбор стартового угла
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

    // Выбор направления обхода
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

    // Легенда
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
    onModeSelected: (String) -> Unit,
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
