package com.vasmarfas.UniversalAmbientLight.ui.effects

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.effect.EffectConfig
import com.vasmarfas.UniversalAmbientLight.ui.components.ValueSlider
import kotlin.math.roundToInt

private val SWATCHES = intArrayOf(
    0xFF8C3C, 0xFFD6A0, 0xFFFFFF, 0xFF3B30, 0xFF9500, 0xFFCC00, 0x7ED321,
    0x00C853, 0x00BCD4, 0x2962FF, 0x6200EA, 0xAA00FF, 0xFF2D95,
)

/**
 * Выбор цвета, удобный с пульта: готовые образцы плюс оттенок и насыщенность ползунками.
 * Колесо цветов пультом не покрутить, а двух ползунков хватает на любой цвет ленты -
 * яркость у эффекта своя.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(title: String, color: Int, onColorChange: (Int) -> Unit) {
    val hsv = remember(color) {
        FloatArray(3).also { AndroidColor.colorToHSV(0xFF000000.toInt() or color, it) }
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            for (swatch in SWATCHES) {
                Swatch(
                    rgb = swatch,
                    selected = swatch == color,
                    onClick = { onColorChange(swatch) }
                )
            }
        }
        ValueSlider(
            title = stringResource(R.string.effects_hue),
            value = hsv[0].roundToInt(),
            onValueChange = { onColorChange(hsvToRgb(it.toFloat(), hsv[1], 1f)) },
            range = 0..359,
            valueText = { "$it°" }
        )
        ValueSlider(
            title = stringResource(R.string.effects_saturation),
            value = (hsv[1] * 100).roundToInt(),
            onValueChange = { onColorChange(hsvToRgb(hsv[0], it / 100f, 1f)) },
            range = 0..100,
            valueText = { "$it%" }
        )
    }
}

@Composable
private fun Swatch(rgb: Int, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val ring = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val description = EffectConfig.formatColor(rgb)
    Box(
        modifier = Modifier
            .size(44.dp)
            .border(if (focused || selected) 3.dp else 1.dp, ring, CircleShape)
            .padding(5.dp)
            .background(Color(0xFF000000.toInt() or rgb), CircleShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { contentDescription = description }
    )
}

private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int =
    AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF
