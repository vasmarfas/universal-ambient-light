package com.vasmarfas.UniversalAmbientLight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R

val FocusShape = RoundedCornerShape(12.dp)

/**
 * Заметная рамка элемента в фокусе. Штатная индикация Material 3 в фокусе — едва видимая
 * заливка, и с пульта непонятно, где сейчас курсор.
 */
@Composable
fun Modifier.focusHighlight(
    interactionSource: InteractionSource,
    shape: Shape = FocusShape,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    if (!focused) return this
    return this
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape)
        .border(2.dp, MaterialTheme.colorScheme.primary, shape)
}

/**
 * Стрелки пульта влево и вправо меняют число, вверх и вниз остаются навигацией. Удержание
 * кнопки ускоряет шаг: иначе от 0 до 1000 пришлось бы жать сотню раз.
 *
 * Ставится левее clickable/focusable в цепочке: событие клавиши получают модификаторы-предки
 * цели фокуса, а не стоящие после неё.
 */
fun Modifier.dpadAdjust(
    onStep: (direction: Int, multiplier: Int) -> Unit,
    onRelease: () -> Unit = {},
): Modifier = onKeyEvent { event ->
    val direction = when (event.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        else -> return@onKeyEvent false
    }
    when (event.type) {
        KeyEventType.KeyDown -> {
            val repeat = event.nativeKeyEvent.repeatCount
            val multiplier = when {
                repeat > 30 -> 10
                repeat > 10 -> 5
                else -> 1
            }
            onStep(direction, multiplier)
            true
        }

        KeyEventType.KeyUp -> {
            onRelease()
            true
        }

        else -> false
    }
}

/** Ближайшее к [raw] значение на сетке шага внутри диапазона. */
fun snapToStep(raw: Float, range: IntRange, step: Int): Int {
    val steps = Math.round((raw - range.first) / step)
    return (range.first + steps * step).coerceIn(range)
}

/**
 * Точный ввод числа. Весь текст выделен сразу: новое значение набирается поверх старого,
 * без стирания по символу с экранной клавиатуры ТВ.
 */
@Composable
fun NumberInputDialog(
    title: String,
    initial: Int,
    range: IntRange,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    allowNegative: Boolean = range.first < 0,
) {
    var field by remember {
        val text = initial.toString()
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    val parsed = field.text.toIntOrNull()
    val valid = parsed != null && parsed in range
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun confirm() {
        val value = parsed ?: return
        onConfirm(value.coerceIn(range))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { new ->
                    val filtered = new.text.filterIndexed { index, c ->
                        c.isDigit() || (allowNegative && c == '-' && index == 0)
                    }
                    field = new.copy(text = filtered.take(7))
                },
                singleLine = true,
                isError = !valid,
                supportingText = {
                    Text(stringResource(R.string.number_input_range, range.first, range.last))
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { if (valid) confirm() }),
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = parsed != null) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Число с кнопками «−» и «+». На ТВ вся строка — одна точка фокуса: стрелки меняют
 * значение, OK открывает точный ввод. Кнопки фокус не берут, чтобы пульт не спотыкался
 * о каждую; на телефоне они работают касанием.
 */
@Composable
fun NumberStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    supportingText: String? = null,
    valueText: (Int) -> String = { it.toString() },
) {
    val interactionSource = remember { MutableInteractionSource() }
    var showDialog by rememberSaveable { mutableStateOf(false) }

    fun change(newValue: Int) {
        val clamped = newValue.coerceIn(range)
        if (clamped != value) onValueChange(clamped)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .focusHighlight(interactionSource)
            .dpadAdjust(onStep = { direction, multiplier -> change(value + direction * step * multiplier) })
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { showDialog = true }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(
            onClick = { change(value - step) },
            enabled = value > range.first,
            modifier = Modifier.focusProperties { canFocus = false }
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.action_decrease))
        }
        Text(
            text = valueText(value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp)
        )
        IconButton(
            onClick = { change(value + step) },
            enabled = value < range.last,
            modifier = Modifier.focusProperties { canFocus = false }
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_increase))
        }
    }

    if (showDialog) {
        NumberInputDialog(
            title = label,
            initial = value,
            range = range,
            onConfirm = {
                change(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}
