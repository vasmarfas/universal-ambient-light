package com.vasmarfas.UniversalAmbientLight.ui.calibration

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.util.CameraGeometry
import com.vasmarfas.UniversalAmbientLight.common.util.DelayProfiles
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberSettingsPreferences
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val TAG = "CalibrationScreen"

/**
 * Автоподбор задержки камерой телефона. Работает только в режиме пульта: задержку меряют
 * на том фильме, что идёт на ТВ, а телефон нужен как камера со стороны.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    running: Boolean,
    onBackClick: () -> Unit,
    onStartLighting: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val remote = LocalRemote.current
    val prefs = rememberSettingsPreferences()
    var state by remember { mutableStateOf<CalibrationSession.State?>(null) }
    var session by remember { mutableStateOf<CalibrationSession?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var analysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var rotation by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf<String?>(null) }
    val source = prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"
    val supported = remote?.caps?.features?.contains(RemoteProtocol.FEATURE_CALIBRATION) == true

    fun begin() {
        saved = null
        session?.stop()
        session = CalibrationSession(
            onState = { state = it },
            onLockExposure = { x, y -> lockExposure(camera, analysis, x, y) }
        ).also { it.start() }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin()
    }

    fun requestStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // Замер идёт около минуты - экран телефона не должен погаснуть на середине
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            session?.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.calibration_title))
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            val blocker = when {
                remote == null -> stringResource(R.string.calibration_needs_remote)
                !supported -> stringResource(R.string.remote_error_update_tv)
                !running || source != "screen" -> stringResource(R.string.calibration_needs_screen)
                else -> null
            }
            if (blocker != null) {
                Text(blocker, style = MaterialTheme.typography.bodyLarge)
                if (remote != null && supported && !running) {
                    Button(onClick = onStartLighting) { Text(stringResource(R.string.calibration_turn_on)) }
                }
                return@Column
            }

            val current = state
            if (current == null) {
                Intro(onStart = { requestStart() })
                return@Column
            }

            if (current !is CalibrationSession.State.Done && current !is CalibrationSession.State.Failed) {
                val corners = (current as? CalibrationSession.State.Measuring)?.corners
                CameraBox(
                    session = session,
                    corners = corners,
                    rotation = rotation,
                    onRotation = { rotation = it },
                    onBound = { boundCamera, boundAnalysis ->
                        camera = boundCamera
                        analysis = boundAnalysis
                    }
                )
                Progress(current)
                OutlinedButton(onClick = {
                    session?.stop()
                    session = null
                    state = null
                }) { Text(stringResource(R.string.action_cancel)) }
            }

            when (current) {
                is CalibrationSession.State.Done -> ResultCard(
                    done = current,
                    saved = saved,
                    onSave = { pkg, delay ->
                        if (pkg != null) {
                            DelayProfiles.put(prefs, pkg, delay)
                        } else {
                            prefs.putInt(R.string.pref_key_output_delay, delay)
                        }
                        saved = resources.getString(R.string.calibration_saved, delay)
                    },
                    onRepeat = { begin() }
                )

                is CalibrationSession.State.Failed -> {
                    Text(
                        text = failureText(current),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = { begin() }) { Text(stringResource(R.string.calibration_repeat)) }
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun Intro(onStart: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.calibration_intro), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.calibration_steps), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onStart) { Text(stringResource(R.string.calibration_start)) }
        }
    }
}

@Composable
private fun Progress(state: CalibrationSession.State) {
    when (state) {
        CalibrationSession.State.Starting, CalibrationSession.State.Searching -> {
            Text(stringResource(R.string.calibration_searching), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is CalibrationSession.State.Measuring -> {
            Text(
                text = stringResource(R.string.calibration_measuring, state.events, state.seconds),
                style = MaterialTheme.typography.bodyLarge
            )
            LinearProgressIndicator(
                progress = { (state.events / 10f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            if (state.glowVisible == false) {
                Text(
                    text = stringResource(R.string.calibration_glow_invisible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        else -> {}
    }
}

@Composable
private fun ResultCard(
    done: CalibrationSession.State.Done,
    saved: String?,
    onSave: (pkg: String?, delay: Int) -> Unit,
    onRepeat: () -> Unit,
) {
    val lag = done.result.lagMs
    // Опережение ленты закрывается задержкой; отставание задержкой не лечится
    val recommended = if (lag < -MIN_SIGNIFICANT_MS) roundTo5(-lag) else 0
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Text(
                text = when {
                    lag < -MIN_SIGNIFICANT_MS -> stringResource(R.string.calibration_result_ahead, -lag)
                    lag > MIN_SIGNIFICANT_MS -> stringResource(R.string.calibration_result_behind, lag)
                    else -> stringResource(R.string.calibration_result_in_sync)
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(
                    R.string.calibration_result_details,
                    done.result.events,
                    (done.result.spreadMs / 2).coerceAtMost(999),
                    (done.result.correlation * 100).roundToInt()
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!done.reliable) {
                Text(
                    text = stringResource(R.string.calibration_unreliable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (lag > MIN_SIGNIFICANT_MS) {
                Text(
                    text = stringResource(R.string.calibration_behind_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = stringResource(R.string.calibration_current, done.info.delayMs, recommended),
                style = MaterialTheme.typography.bodyMedium
            )
            val app = done.info.app
            if (app != null) {
                Button(onClick = { onSave(app, recommended) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.calibration_save_app, done.info.label ?: app))
                }
            }
            OutlinedButton(onClick = { onSave(null, recommended) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.calibration_save_all))
            }
            if (app == null && !done.info.usageAccess) {
                Text(
                    text = stringResource(R.string.calibration_no_usage_access),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            saved?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = onRepeat) { Text(stringResource(R.string.calibration_repeat)) }
        }
    }
}

@Composable
private fun failureText(failed: CalibrationSession.State.Failed): String = when (failed.reason) {
    CalibrationSession.Reason.TV_REFUSED -> failed.detail ?: stringResource(R.string.remote_error_offline)
    CalibrationSession.Reason.NO_SCREEN -> stringResource(R.string.calibration_error_no_screen)
    CalibrationSession.Reason.TOO_DARK -> stringResource(R.string.calibration_error_too_dark)
    CalibrationSession.Reason.TOO_CLOSE -> stringResource(R.string.calibration_error_too_close)
    CalibrationSession.Reason.NOT_ENOUGH_CHANGES -> stringResource(R.string.calibration_error_static)
    CalibrationSession.Reason.CONNECTION -> stringResource(R.string.remote_error_offline)
}

/**
 * Превью камеры с разметкой: найденный экран и кольцо, где меряется свечение. Кадры анализа
 * и превью одной пропорции 4:3 - иначе разметка по кадрам анализа съезжала бы с картинки.
 */
@Composable
private fun CameraBox(
    session: CalibrationSession?,
    corners: FloatArray?,
    rotation: Int,
    onRotation: (Int) -> Unit,
    onBound: (Camera, ImageAnalysis) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSession by rememberUpdatedState(session)
    val currentOnRotation by rememberUpdatedState(onRotation)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                // Ровные 30 кадров: в тёмной комнате автоматика иначе опускает частоту
                // до 15, и точность замера падает вдвое
                val previewUseCase = Preview.Builder()
                    .setResolutionSelector(selector)
                    .setTargetFrameRate(Range(30, 30))
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val analysisUseCase = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var grid: IntArray? = null
                var lastRotation = -1
                analysisUseCase.setAnalyzer(executor) { image ->
                    image.use {
                        val target = currentSession ?: return@use
                        val cells = grid ?: IntArray(target.gridCols * target.gridRows).also { grid = it }
                        sampleLuma(it, target.gridCols, target.gridRows, cells)
                        target.onFrame(cells, it.imageInfo.timestamp)
                        val degrees = it.imageInfo.rotationDegrees
                        if (degrees != lastRotation) {
                            lastRotation = degrees
                            main.post { currentOnRotation(degrees) }
                        }
                    }
                }
                val bound = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    analysisUseCase
                )
                provider = cameraProvider
                preview = previewUseCase
                analysis = analysisUseCase
                onBound(bound, analysisUseCase)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            try {
                provider?.unbind(*listOfNotNull(preview, analysis).toTypedArray())
            } catch (_: Exception) {
                // Экран закрывается; провайдер мог отвязать use case раньше нас.
            }
            executor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .aspectRatio(if (rotation == 90 || rotation == 270) 3f / 4f else 4f / 3f)
            .clipToBounds()
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (corners != null) {
            val screenColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                val display = FloatArray(8)
                CameraGeometry.rawToDisplayCorners(corners, display, rotation)
                val cx = (display[0] + display[2] + display[4] + display[6]) / 4
                val cy = (display[1] + display[3] + display[5] + display[7]) / 4
                fun outline(k: Float) = Path().apply {
                    for (i in 0 until 4) {
                        val x = (cx + (display[i * 2] - cx) * k) * size.width
                        val y = (cy + (display[i * 2 + 1] - cy) * k) * size.height
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(outline(1f), screenColor, style = Stroke(width = 3.dp.toPx()))
                drawPath(
                    outline(1.5f),
                    Color.White.copy(alpha = 0.7f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                    )
                )
                drawCircle(screenColor, radius = 4.dp.toPx(), center = Offset(cx * size.width, cy * size.height))
            }
        }
    }
}

/** Сетка яркости по плоскости Y кадра, в ориентации буфера камеры. */
private fun sampleLuma(image: ImageProxy, cols: Int, rows: Int, out: IntArray) {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val limit = buffer.limit()
    var i = 0
    for (row in 0 until rows) {
        val y = (image.height * (row + 0.5f) / rows).toInt().coerceIn(0, image.height - 1)
        for (col in 0 until cols) {
            val x = (image.width * (col + 0.5f) / cols).toInt().coerceIn(0, image.width - 1)
            val index = y * rowStride + x * pixelStride
            out[i++] = if (index < limit) buffer.get(index).toInt() and 0xFF else 0
        }
    }
}

private fun roundTo5(value: Int) = ((value + 2) / 5) * 5

private const val MIN_SIGNIFICANT_MS = 12
