package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.components.dpadAdjust
import com.vasmarfas.UniversalAmbientLight.ui.components.focusHighlight
import com.vasmarfas.UniversalAmbientLight.ui.components.NumberInputDialog
import com.vasmarfas.UniversalAmbientLight.ui.components.snapToStep
import kotlinx.coroutines.launch

/**
 * Переиспользуемые элементы экрана настроек: группа и четыре типа пунктов.
 */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun CheckBoxPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    summary: String? = null,
    onValueChange: ((Boolean) -> Unit)? = null,
) {
    var checked by remember { mutableStateOf(prefs.getBoolean(keyRes)) }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusHighlight(interactionSource)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                // Справа Switch — TalkBack не должен объявлять его «флажком»
                role = Role.Switch,
                onValueChange = {
                    checked = it
                    prefs.putBoolean(keyRes, it)
                    onValueChange?.invoke(it)
                }
            )
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun EditTextPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    summaryProvider: (String) -> String = { it },
    keyboardType: KeyboardType = KeyboardType.Text,
    externalValue: String? = null,
    onValueChange: ((String) -> Unit)? = null,
    recomposeKey: Any? = null,
) {
    // Для числовых настроек значения по умолчанию лежат в <integer pref_default_*>, и
    // getString() их не видит. Откатываемся на getInt(), иначе при первом запуске поле
    // показалось бы пустым вместо значения из ресурсов.
    fun readInitial(): String {
        val stored = prefs.getString(keyRes)
        if (!stored.isNullOrEmpty()) return stored
        return if (keyboardType == KeyboardType.Number) prefs.getInt(keyRes).toString() else ""
    }

    var value by remember(keyRes, recomposeKey) { mutableStateOf(readInitial()) }

    LaunchedEffect(externalValue, recomposeKey) {
        externalValue?.let { value = it }
        recomposeKey?.let { value = readInitial() }
    }

    // Сбрасываем состояние диалога при смене recomposeKey — например, при уходе с экрана.
    // rememberSaveable — чтобы открытый диалог переживал поворот экрана.
    var showDialog by rememberSaveable(recomposeKey) { mutableStateOf(false) }

    // Закрываем диалог, когда компонент уходит из композиции
    DisposableEffect(Unit) {
        onDispose {
            showDialog = false
        }
    }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusHighlight(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { showDialog = true }
            )
            .padding(horizontal = 8.dp, vertical = 16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summaryProvider(value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDialog) {
        // Весь текст выделен: новое значение набирается поверх старого, без стирания по
        // символу — на экранной клавиатуре ТВ это десяток нажатий пульта.
        // rememberSaveable — введённый текст не теряется при повороте экрана.
        var tempValue by rememberSaveable(showDialog, stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(value, TextRange(0, value.length)))
        }
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

        fun applyValue() {
            // Обрезаем пробелы: случайный пробел с экранной клавиатуры прошёл бы проверку
            // «не пусто» и всплыл бы позже недоступным хостом.
            value = tempValue.text.trim()
            prefs.putString(keyRes, value)
            onValueChange?.invoke(value)
            keyboardController?.hide()
            showDialog = false
        }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { applyValue() }
                    ),
                    singleLine = true,
                    modifier = Modifier.focusRequester(focusRequester)
                )
            },
            confirmButton = {
                TextButton(onClick = { applyValue() }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    keyboardController?.hide()
                    showDialog = false
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun ListPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    entriesRes: Int,
    entryValuesRes: Int,
    onValueChange: ((String) -> Unit)? = null,
    recomposeKey: Any? = null,
    disabledIndices: Set<Int> = emptySet(),
) {
    val entries = stringArrayResource(entriesRes)
    val entryValues = stringArrayResource(entryValuesRes)

    var value by remember(keyRes, recomposeKey) {
        mutableStateOf(
            prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: ""
        )
    }

    LaunchedEffect(recomposeKey) {
        recomposeKey?.let { value = prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: "" }
    }
    // Сбрасываем состояние диалога при смене recomposeKey — например, при уходе с экрана.
    // rememberSaveable — чтобы открытый диалог переживал поворот экрана.
    var showDialog by rememberSaveable(recomposeKey) { mutableStateOf(false) }

    // Закрываем диалог, когда компонент уходит из композиции
    DisposableEffect(Unit) {
        onDispose {
            showDialog = false
        }
    }
    val interactionSource = remember { MutableInteractionSource() }

    val summary = entries.getOrNull(entryValues.indexOf(value)) ?: value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusHighlight(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { showDialog = true }
            )
            .padding(horizontal = 8.dp, vertical = 16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDialog) {
        // С пульта фокус сразу на текущем значении: иначе он встаёт на первый пункт, и
        // «открыл и закрыл OK» молча меняет настройку.
        val selectedFocus = remember { FocusRequester() }
        val selectedIndex = entryValues.indexOf(value)
        LaunchedEffect(Unit) {
            if (selectedIndex >= 0 && selectedIndex !in disabledIndices) {
                runCatching { selectedFocus.requestFocus() }
            }
        }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEachIndexed { index, entry ->
                        val isDisabled = index in disabledIndices
                        val interactionSource = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (index == selectedIndex) {
                                        Modifier.focusRequester(selectedFocus)
                                    } else {
                                        Modifier
                                    }
                                )
                                .focusHighlight(interactionSource)
                                .then(
                                    if (isDisabled) Modifier
                                    else Modifier.clickable(
                                        interactionSource = interactionSource,
                                        indication = LocalIndication.current,
                                        onClick = {
                                            val newValue = entryValues[index]
                                            value = newValue
                                            prefs.putString(keyRes, newValue)
                                            onValueChange?.invoke(newValue)
                                            showDialog = false
                                        }
                                    )
                                )
                                // Не ниже 48dp: строки по 12dp не дотягивали до цели касания
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .alpha(if (isDisabled) 0.38f else 1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value == entryValues[index],
                                onClick = null,
                                enabled = !isDisabled
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = entry)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun ClickablePreference(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    // Та же геометрия, что у остальных пунктов списка: обёртка в Surface с другим
    // вертикальным отступом заставляла ряды «плясать» по высоте
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusHighlight(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 16.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * Число с ползунком. Значение пишется в настройки на каждом шаге: цвет и яркость сервис
 * подхватывает прямо в идущем захвате, и результат сразу виден на ленте.
 *
 * Сам ползунок фокус не берёт — он съедал бы стрелки вверх и вниз, и пульт застревал бы
 * на нём. С пульта значение меняют стрелки влево и вправо на всей строке, OK открывает
 * точный ввод; на телефоне строку можно тянуть пальцем или нажать для ввода числа.
 */
@Composable
fun SliderPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    range: IntRange,
    step: Int = 1,
    summaryProvider: (Int) -> String = { it.toString() },
    onValueChange: ((Int) -> Unit)? = null,
    recomposeKey: Any? = null,
) {
    var value by remember(keyRes, recomposeKey) { mutableIntStateOf(prefs.getInt(keyRes)) }
    var showDialog by rememberSaveable(recomposeKey) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    fun update(newValue: Int) {
        val clamped = newValue.coerceIn(range)
        if (clamped == value) return
        value = clamped
        prefs.putInt(keyRes, clamped)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusHighlight(interactionSource)
            .dpadAdjust(
                onStep = { direction, multiplier -> update(value + direction * step * multiplier) },
                // Аналитика — по отпусканию кнопки, а не на каждый шаг автоповтора
                onRelease = { onValueChange?.invoke(value) }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { showDialog = true }
            )
            .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summaryProvider(value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Slider(
            value = value.coerceIn(range).toFloat(),
            onValueChange = { update(snapToStep(it, range, step)) },
            onValueChangeFinished = { onValueChange?.invoke(value) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.focusProperties { canFocus = false }
        )
    }

    if (showDialog) {
        NumberInputDialog(
            title = title,
            initial = value,
            range = range,
            onConfirm = {
                update(it)
                onValueChange?.invoke(value)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}
