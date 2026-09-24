package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.MtkThalCaptureEncoder
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Группа «Захват»: источник и метод захвата, качество, частота кадров и цветокоррекция.
 */
@Composable
internal fun ColumnScope.CaptureSection(prefs: Preferences, state: SettingsScreenState, onLedLayoutClick: () -> Unit, onCameraSetupClick: () -> Unit) {
    val context = LocalContext.current
    val remote = LocalRemote.current
    val scope = rememberCoroutineScope()
    SettingsGroup(title = stringResource(R.string.pref_group_capturing)) {
        // Источник захвата: экран или камера
        key(state.captureSource) {
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_capture_source,
                title = stringResource(R.string.pref_title_capture_source),
                entriesRes = R.array.pref_list_capture_source,
                entryValuesRes = R.array.pref_list_capture_source_values,
                recomposeKey = state.captureSource,
                onValueChange = { newSource ->
                    state.captureSource = newSource
                    AnalyticsHelper.logSettingChanged(context, "capture_source", newSource)
                }
            )
        }

        // Метод захвата (MediaProjection, Screencap, Accessibility)
        if (state.captureSource == "screen") {
            // Доступные методы решает устройство, которое снимает экран: в режиме пульта —
            // телевизор (флейвор, MTK, служба доступности), а не телефон
            val remoteCaps = remote?.caps
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_capture_method,
                title = stringResource(R.string.pref_title_capture_method),
                entriesRes = R.array.pref_list_capture_method,
                entryValuesRes = R.array.pref_list_capture_method_values,
                recomposeKey = state.captureMethod to state.captureMethodRevision,
                disabledIndices = remember(remoteCaps) {
                    val entryValues =
                        context.resources.getStringArray(R.array.pref_list_capture_method_values)
                    entryValues.indices.filter { index ->
                        val value = entryValues[index]
                        if (remoteCaps != null) {
                            value !in remoteCaps.methods
                        } else {
                            value == "mtk_thal_capture" && !MtkThalCaptureEncoder.isAvailable()
                        }
                    }.toSet()
                },
                onValueChange = { newMethod ->
                    if (newMethod == "accessibility" && remote != null) {
                        if (remoteCaps?.accessibilityOn != true) {
                            // Службу доступности включают пультом на самом ТВ, с телефона её
                            // не включить — выбор откатываем
                            prefs.putString(R.string.pref_key_capture_method, state.captureMethod)
                            state.captureMethodRevision++
                            Toast.makeText(
                                context,
                                R.string.remote_accessibility_on_tv,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            state.captureMethod = newMethod
                        }
                    } else if (newMethod == "accessibility") {
                        // Смотрим, включена ли служба
                        if (AccessibilityCaptureService.getInstance() == null) {
                            // ListPreference уже сохранил выбор в настройки — откатываем его
                            // сразу: поворот или смерть процесса с открытым предупреждением
                            // не должны оставить accessibility включённым без согласия.
                            // Подтверждение в диалоге запишет значение заново.
                            state.previousCaptureMethod = state.captureMethod
                            prefs.putString(
                                R.string.pref_key_capture_method,
                                state.captureMethod
                            )
                            state.showAccessibilityDisclosure = true
                        } else {
                            state.captureMethod = newMethod
                            AnalyticsHelper.logSettingChanged(
                                context,
                                "capture_method",
                                newMethod
                            )
                        }
                    } else {
                        state.captureMethod = newMethod
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "capture_method",
                            newMethod
                        )
                    }
                }
            )

            if (state.captureMethod == "adb_local" || state.captureMethod == "adb_stream" || state.captureMethod == "scrcpy") {
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_adb_port,
                    title = stringResource(R.string.pref_title_adb_port),
                    summaryProvider = { it },
                    keyboardType = KeyboardType.Number
                )
                ClickablePreference(
                    title = stringResource(R.string.pref_btn_adb_pair),
                    summary = stringResource(R.string.pref_summary_adb_pair),
                    onClick = { state.showAdbPairingDialog = true }
                )
            }
        }

        // Настройка углов камеры (только когда выбран источник «камера»)
        if (state.captureSource == "camera" && remote == null) {
            ClickablePreference(
                title = stringResource(R.string.pref_title_camera_setup),
                summary = stringResource(R.string.pref_summary_camera_setup),
                onClick = { onCameraSetupClick() }
            )
        } else if (state.captureSource == "camera") {
            // Углы тянут по живому превью камеры ТВ — с телефона его не видно. Автопоиск
            // экрана в кадре работает и удалённо: камеру держит сервис на ТВ
            val detectStarted = stringResource(R.string.remote_detect_frame_started)
            ClickablePreference(
                title = stringResource(R.string.camera_auto_frame_button),
                summary = stringResource(R.string.remote_detect_frame_summary),
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { RemoteSession.call(RemoteProtocol.OP_DETECT_FRAME) }
                        }
                        Toast.makeText(
                            context,
                            result.exceptionOrNull()?.message ?: detectStarted,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        ClickablePreference(
            title = stringResource(R.string.pref_title_led_layout),
            summary = stringResource(R.string.pref_summary_led_layout),
            onClick = {
                AnalyticsHelper.logLedLayoutOpened(context)
                onLedLayoutClick()
            }
        )
        ListPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_framerate,
            title = stringResource(R.string.pref_title_framerate),
            entriesRes = R.array.pref_list_framerate,
            entryValuesRes = R.array.pref_list_framerate_values,
            onValueChange = { newFramerate ->
                val framerateInt = newFramerate.toIntOrNull() ?: 10
                AnalyticsHelper.logFramerateChanged(context, framerateInt)
                AnalyticsHelper.logSettingChanged(context, "framerate", newFramerate)
            }
        )
        ListPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_capture_quality,
            title = stringResource(R.string.pref_title_capture_quality),
            entriesRes = R.array.pref_list_capture_quality,
            entryValuesRes = R.array.pref_list_capture_quality_values,
            onValueChange = { newQuality ->
                val qualityInt = newQuality.toIntOrNull() ?: 128
                AnalyticsHelper.logCaptureQualityChanged(context, qualityInt)
                AnalyticsHelper.logSettingChanged(context, "capture_quality", newQuality)
            }
        )
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_use_avg_color,
            title = stringResource(R.string.pref_title_use_avg_color),
            onValueChange = { enabled ->
                AnalyticsHelper.logUseAvgColorChanged(context, enabled)
                AnalyticsHelper.logSettingChanged(
                    context,
                    "use_avg_color",
                    enabled.toString()
                )
            }
        )

        // Настройки цветокоррекции
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_color_processing_enabled,
            title = stringResource(R.string.pref_title_color_processing_enabled),
            onValueChange = { enabled ->
                state.colorProcessingEnabled = enabled
                AnalyticsHelper.logSettingChanged(
                    context,
                    "color_processing_enabled",
                    enabled.toString()
                )
            }
        )

        if (state.colorProcessingEnabled) {
            // Увеличивается при каждой правке цвета и обновляет живое превью ниже.
            var colorPrefsVersion by remember { mutableIntStateOf(0) }

            listOf(
                Triple(R.string.pref_key_color_brightness, R.string.pref_title_color_brightness, 0..200),
                Triple(R.string.pref_key_color_contrast, R.string.pref_title_color_contrast, 0..200),
                Triple(R.string.pref_key_color_black_level, R.string.pref_title_color_black_level, 0..100),
                Triple(R.string.pref_key_color_white_level, R.string.pref_title_color_white_level, 0..100),
                Triple(R.string.pref_key_color_saturation, R.string.pref_title_color_saturation, 0..200)
            ).forEach { (keyRes, titleRes, range) ->
                val keyName = stringResource(keyRes)
                SliderPreference(
                    prefs = prefs,
                    keyRes = keyRes,
                    title = stringResource(titleRes),
                    range = range,
                    summaryProvider = { "${it}%" },
                    onValueChange = { newValue ->
                        colorPrefsVersion++
                        AnalyticsHelper.logSettingChanged(
                            context,
                            keyName.removePrefix("pref_key_"),
                            newValue.toString()
                        )
                    }
                )
            }

            // Поканальная коррекция (issue #21).
            Text(
                text = stringResource(R.string.pref_group_color_per_channel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )
            listOf(
                Triple(R.string.pref_key_color_brightness_r, R.string.pref_title_color_brightness_r, 0..200),
                Triple(R.string.pref_key_color_brightness_g, R.string.pref_title_color_brightness_g, 0..200),
                Triple(R.string.pref_key_color_brightness_b, R.string.pref_title_color_brightness_b, 0..200),
                Triple(R.string.pref_key_color_gamma_r, R.string.pref_title_color_gamma_r, 10..500),
                Triple(R.string.pref_key_color_gamma_g, R.string.pref_title_color_gamma_g, 10..500),
                Triple(R.string.pref_key_color_gamma_b, R.string.pref_title_color_gamma_b, 10..500)
            ).forEach { (keyRes, titleRes, range) ->
                val keyName = stringResource(keyRes)
                SliderPreference(
                    prefs = prefs,
                    keyRes = keyRes,
                    title = stringResource(titleRes),
                    range = range,
                    summaryProvider = { "${it}%" },
                    onValueChange = { newValue ->
                        colorPrefsVersion++
                        AnalyticsHelper.logSettingChanged(context, keyName, newValue.toString())
                    }
                )
            }

            ColorProcessingPreview(prefs = prefs, version = colorPrefsVersion)
        }
    }
}

/**
 * Живое превью конвейера цветокоррекции.
 * Перечитывает нужные настройки при каждой смене [version] (её увеличивают соседние поля
 * EditTextPreference) и рисует три чистых образца R/G/B и серый градиент — всё уже после
 * [ColorProcessor.processColor].
 */
@Composable
private fun ColorProcessingPreview(prefs: Preferences, version: Int) {
    val preview = remember(version) {
        val brightness = prefs.getInt(R.string.pref_key_color_brightness, 100)
        val contrast = prefs.getInt(R.string.pref_key_color_contrast, 100)
        val blackLevel = prefs.getInt(R.string.pref_key_color_black_level, 0)
        val whiteLevel = prefs.getInt(R.string.pref_key_color_white_level, 100)
        val saturation = prefs.getInt(R.string.pref_key_color_saturation, 100)
        val bR = prefs.getInt(R.string.pref_key_color_brightness_r, 100)
        val bG = prefs.getInt(R.string.pref_key_color_brightness_g, 100)
        val bB = prefs.getInt(R.string.pref_key_color_brightness_b, 100)
        val gR = prefs.getInt(R.string.pref_key_color_gamma_r, 100)
        val gG = prefs.getInt(R.string.pref_key_color_gamma_g, 100)
        val gB = prefs.getInt(R.string.pref_key_color_gamma_b, 100)

        val process: (Int, Int, Int) -> Color = { r, g, b ->
            val (ro, go, bo) = ColorProcessor.processColor(
                r, g, b,
                brightness, contrast, blackLevel, whiteLevel, saturation,
                bR, bG, bB, gR, gG, gB
            )
            Color(ro, go, bo)
        }
        Triple(
            process(255, 0, 0),
            process(0, 255, 0),
            process(0, 0, 255)
        ) to (0..8).map { step -> process(step * 32, step * 32, step * 32) }
    }
    val swatches = preview.first
    val ramp = preview.second

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.pref_color_preview_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.first))
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.second))
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.third))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(Brush.horizontalGradient(ramp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.pref_color_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
