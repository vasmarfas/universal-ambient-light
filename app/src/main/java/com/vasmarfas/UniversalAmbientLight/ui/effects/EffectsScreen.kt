package com.vasmarfas.UniversalAmbientLight.ui.effects

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.effect.Effect
import com.vasmarfas.UniversalAmbientLight.common.effect.EffectConfig
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.ui.components.ValueSlider
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberSettingsPreferences
import com.vasmarfas.UniversalAmbientLight.ui.settings.CheckBoxPreference

private val STATIC_EFFECTS = listOf(Effect.SOLID, Effect.GRADIENT, Effect.WHITE)
private val DYNAMIC_EFFECTS = listOf(
    Effect.RAINBOW, Effect.COLOR_CYCLE, Effect.BREATHING, Effect.CANDLE, Effect.FIRE,
    Effect.AURORA, Effect.OCEAN, Effect.PLASMA, Effect.COMET,
)
private val SETUP_EFFECTS = listOf(Effect.LAYOUT_TEST)

/**
 * Эффекты без захвата экрана. Выбор карточки сразу включает эффект: переключает источник
 * подсветки и, если она была выключена, запускает её. На телефоне-пульте экран правит
 * зеркало настроек ТВ, и эффект включается на телевизоре.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EffectsScreen(
    running: Boolean,
    onBackClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val remote = LocalRemote.current
    val prefs = rememberSettingsPreferences()
    var config by remember(prefs) { mutableStateOf(EffectConfig.from(prefs)) }
    var source by remember(prefs) {
        mutableStateOf(prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen")
    }
    val supported = remote == null || remote.caps?.features?.contains(RemoteProtocol.FEATURE_EFFECTS) == true

    fun select(effect: Effect) {
        prefs.putString(R.string.pref_key_effect, effect.id)
        config = config.copy(effect = effect)
        AnalyticsHelper.logEffectChanged(context, effect.id)
        if (source != "effect") {
            prefs.putString(R.string.pref_key_capture_source, "effect")
            source = "effect"
        }
        if (!running) onStart()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.effects_title))
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (!supported) {
                Text(
                    text = stringResource(R.string.remote_error_update_tv),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                return@Column
            }

            StateCard(
                running = running,
                source = source,
                effect = config.effect,
                onStop = onStop,
                onBackToScreen = {
                    prefs.putString(R.string.pref_key_capture_source, "screen")
                    source = "screen"
                }
            )

            // С пульта фокус сразу на текущем эффекте, иначе он встаёт на кнопку «Назад»
            val selectedFocus = remember { FocusRequester() }
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                if (inputModeManager.inputMode == InputMode.Keyboard) {
                    runCatching { selectedFocus.requestFocus() }
                }
            }

            for ((titleRes, effects) in listOf(
                R.string.effects_group_static to STATIC_EFFECTS,
                R.string.effects_group_dynamic to DYNAMIC_EFFECTS,
                R.string.effects_group_setup to SETUP_EFFECTS,
            )) {
                GroupTitle(stringResource(titleRes))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (effect in effects) {
                        val selected = effect == config.effect
                        EffectCard(
                            effect = effect,
                            config = config,
                            selected = selected,
                            onClick = { select(effect) },
                            modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier
                        )
                    }
                }
            }

            GroupTitle(stringResource(R.string.effects_parameters, stringResource(config.effect.titleRes())))
            if (config.effect == Effect.LAYOUT_TEST) {
                Text(
                    text = stringResource(R.string.effect_layout_test_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            if (config.effect.usesColor) {
                ColorPicker(stringResource(R.string.effects_color), config.color) { rgb ->
                    prefs.putString(R.string.pref_key_effect_color, EffectConfig.formatColor(rgb))
                    config = config.copy(color = rgb)
                }
            }
            if (config.effect.usesColor2) {
                ColorPicker(stringResource(R.string.effects_color2), config.color2) { rgb ->
                    prefs.putString(R.string.pref_key_effect_color2, EffectConfig.formatColor(rgb))
                    config = config.copy(color2 = rgb)
                }
            }
            if (config.effect.usesTemperature) {
                ValueSlider(
                    title = stringResource(R.string.effects_temperature),
                    value = config.temperature,
                    onValueChange = {
                        prefs.putInt(R.string.pref_key_effect_temperature, it)
                        config = config.copy(temperature = it)
                    },
                    range = 1500..10000,
                    step = 100,
                    valueText = { resources.getString(R.string.unit_kelvin, it) }
                )
                Text(
                    text = stringResource(R.string.effect_white_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            if (config.effect.animated) {
                ValueSlider(
                    title = stringResource(R.string.effects_speed),
                    value = config.speed,
                    onValueChange = {
                        prefs.putInt(R.string.pref_key_effect_speed, it)
                        config = config.copy(speed = it)
                    },
                    range = 0..100
                )
            }
            ValueSlider(
                title = stringResource(R.string.effects_brightness),
                value = config.brightness,
                onValueChange = {
                    prefs.putInt(R.string.pref_key_effect_brightness, it)
                    config = config.copy(brightness = it)
                },
                range = 1..100,
                valueText = { "$it%" }
            )
            CheckBoxPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_effect_standby,
                title = stringResource(R.string.effects_standby),
                summary = stringResource(R.string.effects_standby_summary)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StateCard(
    running: Boolean,
    source: String,
    effect: Effect,
    onStop: () -> Unit,
    onBackToScreen: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = when {
                    !running -> stringResource(R.string.effects_now_off)
                    source == "effect" -> stringResource(R.string.effects_now_effect, stringResource(effect.titleRes()))
                    else -> stringResource(R.string.effects_now_screen)
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.effects_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (running && source == "effect") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    FilledTonalButton(onClick = onStop) {
                        Text(stringResource(R.string.effects_turn_off))
                    }
                    OutlinedButton(onClick = onBackToScreen) {
                        Text(stringResource(R.string.effects_back_to_screen))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp)
    )
}

@Composable
private fun EffectCard(
    effect: Effect,
    config: EffectConfig,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = modifier
            .width(152.dp)
            .clip(shape)
            .border(if (focused || selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
    ) {
        EffectPreview(
            config = config.copy(effect = effect),
            animate = selected || focused,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .background(Color(0xFF0B0B0F))
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 2.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = stringResource(effect.titleRes()),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
