package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Захватывает кадры с камеры, применяет перспективную коррекцию (по 4 углам)
 * и отправляет RGB данные на контроллер подсветки — аналог ScreenEncoder для режима камеры.
 *
 * Предназначен для работы внутри foreground-сервиса без Activity.
 */
class CameraEncoder(
    private val context: Context,
    private val listener: HyperionThread.HyperionThreadListener,
    private val options: AppOptions,
    corners: FloatArray // 8 floats: tl_x, tl_y, tr_x, tr_y, br_x, br_y, bl_x, bl_y (normalized 0..1)
) : LifecycleOwner {

    // --- Lifecycle для CameraX ---
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var mRunning = false
    @Volatile
    private var mCapturing = false

    // Track our own use case so we can unbind it without affecting other use cases (e.g. Activity Preview)
    private var imageAnalysisUseCase: ImageAnalysis? = null

    // Corners (copy to prevent external mutation)
    private val mCorners = corners.copyOf()

    // Timing
    private val frameIntervalMs = 1000L / options.frameRate

    // Output dimensions
    private val outputWidth: Int
    private val outputHeight: Int

    // Reusable buffers
    private var srcBitmap: Bitmap? = null
    private var correctedBitmap: Bitmap? = null
    private var rgbBuffer: ByteArray? = null
    private var rgbaBytes: ByteArray? = null
    private var pixelInts: IntArray? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        val q = if (options.captureQuality > 0) options.captureQuality else 128
        outputWidth = max(32, min(q, 512))
        outputHeight = max(32, (outputWidth * 9f / 16f).toInt())

        if (DEBUG) Log.d(TAG, "CameraEncoder init: output=${outputWidth}x${outputHeight}, fps=${options.frameRate}")
    }

    // ======================== Public API ========================

    fun start() {
        mainHandler.post {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    bindCamera()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get CameraProvider", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    fun stopRecording() {
        if (DEBUG) Log.i(TAG, "stopRecording")
        mRunning = false
        mCapturing = false

        mainHandler.post {
            try {
                // Only unbind our own ImageAnalysis, not everything (Preview from Activity may still be bound)
                imageAnalysisUseCase?.let { cameraProvider?.unbind(it) }
                imageAnalysisUseCase = null
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed", e)
            }
            cameraProvider = null

            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            } catch (e: Exception) {
                Log.w(TAG, "Lifecycle transition failed", e)
            }
        }

        cameraExecutor.shutdownNow()
        clearAndDisconnect()
    }

    fun stopRecordingNoDisconnect() {
        if (DEBUG) Log.i(TAG, "stopRecordingNoDisconnect")
        mRunning = false
        mCapturing = false

        mainHandler.post {
            try {
                imageAnalysisUseCase?.let { cameraProvider?.unbind(it) }
                imageAnalysisUseCase = null
            } catch (_: Exception) {}
            cameraProvider = null
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            } catch (_: Exception) {}
        }

        clearLights()
    }

    fun resumeRecording() {
        if (DEBUG) Log.i(TAG, "resumeRecording")
        if (!mCapturing) {
            start()
        }
    }

    fun isCapturing(): Boolean = mCapturing

    fun sendStatus() {
        listener.sendStatus(mCapturing)
    }

    fun clearLights() {
        Thread {
            repeat(CLEAR_FRAMES) {
                sleep(CLEAR_DELAY_MS)
                listener.clear()
            }
        }.start()
    }

    fun setOrientation(orientation: Int) {
        // Camera rotation handled automatically via rotationDegrees in processFrame
    }

    // ======================== Camera binding ========================

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        // Unbind only our previous ImageAnalysis (if any), not everything.
        // This preserves the Activity's Preview use case.
        imageAnalysisUseCase?.let {
            try { provider.unbind(it) } catch (_: Exception) {}
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(CAMERA_WIDTH, CAMERA_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysisUseCase = imageAnalysis

        var lastFrameTime = 0L
        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!mRunning) {
                imageProxy.close()
                return@setAnalyzer
            }
            val now = System.currentTimeMillis()
            if (now - lastFrameTime >= frameIntervalMs) {
                lastFrameTime = now
                try {
                    processFrame(imageProxy)
                } catch (e: Exception) {
                    if (DEBUG) Log.w(TAG, "processFrame error", e)
                }
            }
            imageProxy.close()
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            mRunning = true
            mCapturing = true
            listener.sendStatus(true)
            Log.i(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    // ======================== Frame processing ========================

    private fun processFrame(imageProxy: ImageProxy) {
        val width = imageProxy.width
        val height = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees

        // 1. Read RGBA bytes from camera
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride

        val totalBytes = rowStride * height
        if (rgbaBytes == null || rgbaBytes!!.size < totalBytes) {
            rgbaBytes = ByteArray(totalBytes)
        }
        buffer.rewind()
        buffer.get(rgbaBytes!!, 0, min(totalBytes, buffer.remaining()))

        // 2. Create source Bitmap (RGBA → ARGB conversion)
        if (srcBitmap == null || srcBitmap!!.width != width || srcBitmap!!.height != height) {
            srcBitmap?.recycle()
            srcBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        val totalPixels = width * height
        if (pixelInts == null || pixelInts!!.size < totalPixels) {
            pixelInts = IntArray(totalPixels)
        }

        // CameraX RGBA_8888: bytes are R, G, B, A
        // Android ARGB_8888 int: 0xAARRGGBB
        for (y in 0 until height) {
            val rowOff = y * rowStride
            for (x in 0 until width) {
                val i = rowOff + x * 4
                val r = rgbaBytes!![i].toInt() and 0xFF
                val g = rgbaBytes!![i + 1].toInt() and 0xFF
                val b = rgbaBytes!![i + 2].toInt() and 0xFF
                pixelInts!![y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        srcBitmap!!.setPixels(pixelInts!!, 0, width, 0, 0, width, height)

        // 3. Compute display dimensions after rotation
        val displayWidth: Int
        val displayHeight: Int
        if (rotation == 90 || rotation == 270) {
            displayWidth = height
            displayHeight = width
        } else {
            displayWidth = width
            displayHeight = height
        }

        // 4. Convert normalized corners (in display space) to raw image space
        val displayPts = floatArrayOf(
            mCorners[0] * displayWidth, mCorners[1] * displayHeight,  // top-left
            mCorners[2] * displayWidth, mCorners[3] * displayHeight,  // top-right
            mCorners[4] * displayWidth, mCorners[5] * displayHeight,  // bottom-right
            mCorners[6] * displayWidth, mCorners[7] * displayHeight   // bottom-left
        )

        // Build forward rotation matrix (raw → display) and invert to get display → raw
        if (rotation != 0) {
            val rawToDisplay = Matrix()
            rawToDisplay.postRotate(rotation.toFloat())
            when (rotation) {
                90 -> rawToDisplay.postTranslate(height.toFloat(), 0f)
                180 -> rawToDisplay.postTranslate(width.toFloat(), height.toFloat())
                270 -> rawToDisplay.postTranslate(0f, width.toFloat())
            }
            val displayToRaw = Matrix()
            rawToDisplay.invert(displayToRaw)
            displayToRaw.mapPoints(displayPts)
        }

        // 5. Perspective correction: srcPts → output rectangle
        val dstPts = floatArrayOf(
            0f, 0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f, outputHeight.toFloat()
        )

        val perspectiveMatrix = Matrix()
        perspectiveMatrix.setPolyToPoly(displayPts, 0, dstPts, 0, 4)

        // 6. Draw source bitmap with perspective correction into output
        if (correctedBitmap == null || correctedBitmap!!.width != outputWidth || correctedBitmap!!.height != outputHeight) {
            correctedBitmap?.recycle()
            correctedBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(correctedBitmap!!)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(srcBitmap!!, perspectiveMatrix, paint)

        // 7. Extract RGB from corrected bitmap
        val outPixels = IntArray(outputWidth * outputHeight)
        correctedBitmap!!.getPixels(outPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        val rgbSize = outputWidth * outputHeight * 3
        if (rgbBuffer == null || rgbBuffer!!.size < rgbSize) {
            rgbBuffer = ByteArray(rgbSize)
        }

        var idx = 0
        for (pixel in outPixels) {
            rgbBuffer!![idx++] = ((pixel shr 16) and 0xFF).toByte() // R
            rgbBuffer!![idx++] = ((pixel shr 8) and 0xFF).toByte()  // G
            rgbBuffer!![idx++] = (pixel and 0xFF).toByte()           // B
        }

        // 8. Apply color processing
        ColorProcessor.processRgbData(rgbBuffer!!, options)

        // 9. Send frame
        listener.sendFrame(rgbBuffer!!, outputWidth, outputHeight)
    }

    // ======================== Helpers ========================

    private fun clearAndDisconnect() {
        Thread {
            repeat(CLEAR_FRAMES) {
                sleep(CLEAR_DELAY_MS)
                listener.clear()
            }
            listener.disconnect()
        }.start()
    }

    companion object {
        private const val TAG = "CameraEncoder"
        private const val DEBUG = false
        private const val CAMERA_WIDTH = 640
        private const val CAMERA_HEIGHT = 480
        private const val CLEAR_FRAMES = 5
        private const val CLEAR_DELAY_MS = 100L

        /** Parse corners string "x1,y1,x2,y2,x3,y3,x4,y4" → FloatArray(8) */
        fun parseCornersString(str: String?): FloatArray {
            if (str != null) {
                val parts = str.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (parts.size == 8) return parts.toFloatArray()
            }
            // Default: 10% inset from edges
            return floatArrayOf(
                0.1f, 0.1f,   // top-left
                0.9f, 0.1f,   // top-right
                0.9f, 0.9f,   // bottom-right
                0.1f, 0.9f    // bottom-left
            )
        }

        fun cornersToString(corners: FloatArray): String {
            return corners.joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }
        }

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
