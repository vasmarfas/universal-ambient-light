package com.vasmarfas.UniversalAmbientLight.ui.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QrScanner"

/**
 * Превью камеры с распознаванием QR-кода. [onResult] приходит на главном потоке, и
 * [accept] решает, подходит ли текст: чужие QR-коды в кадре не должны сбивать сканирование.
 */
@Composable
fun QrScanner(
    accept: (String) -> Boolean,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentAccept by rememberUpdatedState(accept)
    val currentOnResult by rememberUpdatedState(onResult)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        val reader = QRCodeReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var disposed = false

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                val previewUseCase = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysisUseCase = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysisUseCase.setAnalyzer(executor) { image ->
                    image.use {
                        if (delivered.get()) return@use
                        val text = decode(reader, it, hints) ?: return@use
                        if (currentAccept(text) && delivered.compareAndSet(false, true)) {
                            main.post { currentOnResult(text) }
                        }
                    }
                }
                // Только свои use case, без unbindAll: камеру может держать и сервис захвата
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    analysisUseCase
                )
                provider = cameraProvider
                preview = previewUseCase
                analysis = analysisUseCase
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

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Ищет QR в яркостной плоскости кадра; null, если в кадре его нет. */
private fun decode(reader: QRCodeReader, image: ImageProxy, hints: Map<DecodeHintType, *>): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val rowStride = plane.rowStride
    val buffer = plane.buffer
    // Последняя строка плоскости бывает короче rowStride — добиваем массив до полного прямоугольника
    val data = ByteArray(rowStride * image.height)
    buffer.rewind()
    buffer.get(data, 0, minOf(buffer.remaining(), data.size))
    return try {
        val source = PlanarYUVLuminanceSource(
            data, rowStride, image.height, 0, 0, image.width, image.height, false
        )
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Unexpected frame layout: ${e.message}")
        null
    } finally {
        reader.reset()
    }
}
