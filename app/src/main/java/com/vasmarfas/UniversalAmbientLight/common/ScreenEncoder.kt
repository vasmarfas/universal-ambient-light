package com.vasmarfas.UniversalAmbientLight.common

import android.annotation.TargetApi
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class ScreenEncoder(
    listener: HyperionThread.HyperionThreadListener,
    projection: MediaProjection,
    screenWidth: Int,
    screenHeight: Int,
    density: Int,
    private val mOptions: AppOptions,
) : ScreenEncoderBase(listener, projection, screenWidth, screenHeight, density, mOptions) {

    // Компоненты захвата
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mImageReader: ImageReader? = null
    private var mCaptureThread: HandlerThread? = null
    private var mCaptureHandler: Handler? = null

    @Volatile
    private var mRunning: Boolean = false
    private var mCaptureWidth: Int = 0
    private var mCaptureHeight: Int = 0
    private var mRgbBuffer: ByteArray? = null
    private var mRowBuffer: ByteArray? = null
    private val mAvgColorResult = ByteArray(3)
    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()
    private var mBorderX: Int = 0
    private var mBorderY: Int = 0
    private var mFrameCount: Int = 0

    // Замеры производительности
    private var mProfileFrames: Int = 0
    private var mTotalLoopTime: Long = 0
    private var mTotalCaptureTime: Long = 0
    private var mTotalProcessTime: Long = 0
    private var mTotalSendTime: Long = 0
    private var mLastLogTime: Long = 0

    private val mFrameIntervalMs: Long = (1000L / mFrameRate)

    private val mCaptureRunnable = object : Runnable {
        override fun run() {
            if (!mRunning) return

            val start = System.nanoTime()
            captureFrame()
            val end = System.nanoTime()

            // Замер времени цикла
            val elapsedNs = end - start
            val elapsedMs = elapsedNs / 1_000_000L
            mTotalLoopTime += elapsedNs
            mProfileFrames++

            val now = System.currentTimeMillis()
            if (now - mLastLogTime >= 2000) { // Log every 2 seconds
                if (mProfileFrames > 0) {
                    val fps = mProfileFrames * 1000f / (now - mLastLogTime)
                    // Средние значения в миллисекундах
                    val avgLoop = (mTotalLoopTime / mProfileFrames) / 1_000_000f
                    val avgCapture = (mTotalCaptureTime / mProfileFrames) / 1_000_000f
                    val avgProcess = (mTotalProcessTime / mProfileFrames) / 1_000_000f
                    val avgSend = (mTotalSendTime / mProfileFrames) / 1_000_000f

                    Log.i(
                        TAG, String.format(
                            "PERF: FPS=%.1f | Loop=%.1fms (Cap=%.1f, Proc=%.1f, Send=%.1f)",
                            fps, avgLoop, avgCapture, avgProcess, avgSend
                        )
                    )
                }

                mLastLogTime = now
                mProfileFrames = 0
                mTotalLoopTime = 0
                mTotalCaptureTime = 0
                mTotalProcessTime = 0
                mTotalSendTime = 0
            }

            // Берём в локальную переменную, чтобы не поймать гонку: stopInternal() из другого
            // потока может обнулить mCaptureHandler между проверкой и использованием.
            val handler = mCaptureHandler
            if (mRunning && handler != null) {
                val delayMs = max(1L, mFrameIntervalMs - elapsedMs)
                handler.postDelayed(this, delayMs)
            }
        }
    }

    private val mDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            if (DEBUG) Log.d(TAG, "Display paused")
        }

        override fun onResumed() {
            if (DEBUG) Log.d(TAG, "Display resumed")
            if (!mRunning && mCaptureHandler != null) {
                startCapture()
            } else if (mCaptureHandler == null) {
                Log.w(TAG, "Cannot resume capture: mCaptureHandler is null")
            }
        }

        override fun onStopped() {
            if (DEBUG) Log.d(TAG, "Display stopped")
            mRunning = false
            setCapturing(false)
        }
    }

    init {
        initCaptureDimensions()

        if (DEBUG) Log.d(
            TAG,
            "Capture: " + mCaptureWidth + "x" + mCaptureHeight + " @ " + mFrameRate + "fps"
        )

        try {
            init()
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "Init failed", e)
        } catch (e: SecurityException) {
            // Токен MediaProjection истёк или отозван системой
            Log.e(TAG, "Init failed: MediaProjection token invalid", e)
            throw e // Re-throw so restartEncoderFromSavedProjection can handle it
        }
    }

    private fun initCaptureDimensions() {
        // Берём качество захвата из настроек
        var quality = mOptions.captureQuality
        if (quality <= 0) quality = 128 // fallback

        // Работаем с настоящими размерами экрана, а не с уменьшенными из базового класса
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()

        Log.d(
            TAG,
            "initCaptureDimensions: quality=$quality, screenWidth=$screenWidth, screenHeight=$screenHeight"
        )

        // Пропорции считаем по настоящим размерам экрана. Часть прошивок (Mi Box на
        // Android 9) в момент старта отдаёт нулевые метрики: ratio уходил в бесконечность,
        // ширина — в Int.MAX_VALUE, и нативная аллокация ImageReader переполнялась в
        // отрицательный размер (краш «allocation size negative»). Пока метрик нет,
        // считаем экран 16:9.
        val ratio = if (screenWidth > 0 && screenHeight > 0) {
            screenWidth.toFloat() / screenHeight
        } else {
            16f / 9f
        }
        Log.d(TAG, "initCaptureDimensions: ratio=$ratio")

        val (w, h) = if (quality <= 512) {
            // Историческое поведение: качество означает максимальную ширину захвата в пикселях
            val targetWidth = min(screenWidth, quality)
            val targetHeight = (targetWidth / ratio).toInt()
            Log.d(
                TAG,
                "initCaptureDimensions (legacy): requestedWidth=$quality, targetWidth=$targetWidth, targetHeight=$targetHeight"
            )
            targetWidth to targetHeight
        } else {
            // Новое поведение для пресетов с «p» (720p/1080p/1440p/2160p): качество задаёт
            // целевую высоту, а пропорции берутся от настоящего экрана.
            val targetHeight = min(screenHeight, quality)
            val targetWidth = (targetHeight * ratio).toInt()
            Log.d(
                TAG,
                "initCaptureDimensions (p-preset): requestedHeight=$quality, targetWidth=$targetWidth, targetHeight=$targetHeight"
            )
            targetWidth to targetHeight
        }

        // Размеры делаем чётными и держим в разумных пределах: верхняя граница страхует
        // от переполнения размера аллокации ImageReader на кривых метриках экрана
        mCaptureWidth = max(32, min(w, MAX_CAPTURE_SIDE) and 1.inv())
        mCaptureHeight = max(32, min(h, MAX_CAPTURE_SIDE) and 1.inv())

        Log.d(
            TAG,
            "initCaptureDimensions: FINAL mCaptureWidth=$mCaptureWidth, mCaptureHeight=$mCaptureHeight"
        )
    }

    @Throws(MediaCodec.CodecException::class)
    private fun init() {
        val captureThread =
            HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND)
        mCaptureThread = captureThread
        captureThread.start()
        val looper = captureThread.looper
        if (looper == null) {
            Log.e(TAG, "Failed to get looper from capture thread")
            throw IllegalStateException("Capture thread looper is null")
        }
        mCaptureHandler = Handler(looper)

        val imageReader = ImageReader.newInstance(
            mCaptureWidth, mCaptureHeight,
            PixelFormat.RGBA_8888,
            IMAGE_READER_IMAGES
        )
        mImageReader = imageReader

        mMediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Важно для Android и Google TV: MediaProjection может вызвать onStop() при уходе
                // экрана в сон. От WLED/Hyperion при этом не отключаемся, иначе WLED быстро вернётся
                // к своему эффекту и без ручного перезапуска не подключится обратно.
                stopInternal(disconnect = false)
            }
        }, mHandler)

        // createVirtualDisplay может бросить SecurityException, если токен MediaProjection истёк или отозван.
        // Пробрасываем исключение дальше, чтобы restartEncoderFromSavedProjection сбросил сохранённые
        // данные проекции, но сначала убираем уже созданное: объект не будет сконструирован, и кроме
        // как здесь два HandlerThread и ImageReader остановить некому. Этот путь повторяется на каждом
        // ACTION_SCREEN_ON с протухшим токеном — без уборки утечка копилась бы с каждой попыткой.
        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                mCaptureWidth, mCaptureHeight, mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader.surface,
                mDisplayCallback,
                mHandler
            )
        } catch (e: SecurityException) {
            try {
                imageReader.close()
            } catch (_: Exception) {
            }
            mImageReader = null
            captureThread.quitSafely()
            mCaptureThread = null
            mCaptureHandler = null
            stopHandlerThread()
            throw e
        }

        startCapture()
    }

    private fun startCapture() {
        val handler = mCaptureHandler
        if (handler == null) {
            Log.e(TAG, "Cannot start capture: mCaptureHandler is null")
            return
        }
        mRunning = true
        setCapturing(true)
        mFrameCount = 0
        // setOrientation и onResumed VirtualDisplay могут позвать нас наперегонки — без
        // снятия старого поста в очереди оказались бы две самоперепощивающиеся цепочки
        handler.removeCallbacks(mCaptureRunnable)
        handler.post(mCaptureRunnable)
    }

    private fun captureFrame() {
        val reader = mImageReader ?: return
        var img: Image? = null
        try {
            val startCap = System.nanoTime()
            img = reader.acquireLatestImage()
            mTotalCaptureTime += (System.nanoTime() - startCap)

            if (img != null) {
                processImage(img)
            }
        } catch (e: Exception) {
            if (DEBUG) Log.w(TAG, "Capture error", e)
        } finally {
            // Сбой закрытия одного кадра не должен ронять весь поток захвата
            try {
                img?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun processImage(img: Image) {
        val startProc = System.nanoTime()
        val planes = img.planes
        if (planes.isEmpty()) return

        val plane = planes[0]
        val buffer = plane.buffer
        val width = img.width
        val height = img.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        if (mAvgColor) {
            sendAverageColor(buffer, width, height, rowStride, pixelStride)
        } else {
            sendPixelData(buffer, width, height, rowStride, pixelStride)
        }

        mTotalProcessTime += (System.nanoTime() - startProc)
    }

    private fun sendPixelData(
        buffer: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int,
    ) {
        val bx = mBorderX
        val by = mBorderY
        val effWidth = width - (bx shl 1)
        val effHeight = height - (by shl 1)

        if (effWidth <= 0 || effHeight <= 0) return

        val rgb =
            extractRgb(buffer, width, height, rowStride, pixelStride, bx, by, effWidth, effHeight)

        // Применяем обработку цветов
        ColorProcessor.processRgbData(rgb, mOptions)

        if (DEBUG && System.currentTimeMillis() % 5000 < 100) {
            Log.d(
                TAG,
                "sendPixelData: effWidth=$effWidth, effHeight=$effHeight, rgb.size=${rgb.size}, expected=${effWidth * effHeight * 3}"
            )
        }

        val cropped = mBorderCropper.applyForEncoder(rgb, effWidth, effHeight, mOptions)
        val startSend = System.nanoTime()
        mListener.sendFrame(cropped.rgb, cropped.width, cropped.height)
        mTotalSendTime += (System.nanoTime() - startSend)
    }

    private fun extractRgb(
        buffer: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int,
        bx: Int, by: Int, effWidth: Int, effHeight: Int,
    ): ByteArray {
        val rgbSize = effWidth * effHeight * BYTES_PER_PIXEL_RGB

        // Локальные ссылки на переиспользуемые буферы: обращение к полю в этом цикле
        // стоит дороже, чем к локальной переменной, а кадр разбирается целиком.
        var rgbBuffer = mRgbBuffer
        if (rgbBuffer == null || rgbBuffer.size < rgbSize) {
            rgbBuffer = ByteArray(rgbSize)
            mRgbBuffer = rgbBuffer
        }

        val endY = height - by
        val endX = width - bx
        var rgbIdx = 0

        if (pixelStride == BYTES_PER_PIXEL_RGBA && rowStride == width * BYTES_PER_PIXEL_RGBA) {
            val rowBytes = effWidth * BYTES_PER_PIXEL_RGBA

            var rowBuffer = mRowBuffer
            if (rowBuffer == null || rowBuffer.size < rowBytes) {
                rowBuffer = ByteArray(rowBytes)
                mRowBuffer = rowBuffer
            }

            val savedPos = buffer.position()

            for (y in by until endY) {
                buffer.position(y * rowStride + bx * BYTES_PER_PIXEL_RGBA)
                buffer.get(rowBuffer, 0, rowBytes)

                var i = 0
                val unrollLimit = rowBytes - 15
                while (i < unrollLimit) {
                    rgbBuffer[rgbIdx++] = rowBuffer[i]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 1]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 2]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 4]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 5]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 6]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 8]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 9]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 10]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 12]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 13]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 14]
                    i += 16
                }
                while (i < rowBytes) {
                    rgbBuffer[rgbIdx++] = rowBuffer[i]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 1]
                    rgbBuffer[rgbIdx++] = rowBuffer[i + 2]
                    i += BYTES_PER_PIXEL_RGBA
                }
            }

            buffer.position(savedPos)
        } else {
            for (y in by until endY) {
                val rowOff = y * rowStride
                for (x in bx until endX) {
                    val off = rowOff + x * pixelStride
                    rgbBuffer[rgbIdx++] = buffer.get(off)
                    rgbBuffer[rgbIdx++] = buffer.get(off + 1)
                    rgbBuffer[rgbIdx++] = buffer.get(off + 2)
                }
            }
        }

        return rgbBuffer
    }

    private fun sendAverageColor(
        buffer: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int,
    ) {
        val bx = mBorderX
        val by = mBorderY
        val startX = bx
        val startY = by
        val endX = width - bx
        val endY = height - by

        if (endX <= startX || endY <= startY) return

        var r: Long = 0
        var g: Long = 0
        var b: Long = 0
        var count = 0

        var y = startY
        while (y < endY) {
            val rowOff = y * rowStride
            var x = startX
            while (x < endX) {
                val off = rowOff + x * pixelStride
                r += (buffer.get(off).toInt() and 0xFF).toLong()
                g += (buffer.get(off + 1).toInt() and 0xFF).toLong()
                b += (buffer.get(off + 2).toInt() and 0xFF).toLong()
                count++
                x += 4
            }
            y += 4
        }

        if (count > 0) {
            val avgR = (r / count).toInt()
            val avgG = (g / count).toInt()
            val avgB = (b / count).toInt()

            // Применяем обработку цветов
            val (rOut, gOut, bOut) = ColorProcessor.processColor(
                avgR, avgG, avgB,
                mOptions.brightness,
                mOptions.contrast,
                mOptions.blackLevel,
                mOptions.whiteLevel,
                mOptions.saturation,
                mOptions.brightnessR, mOptions.brightnessG, mOptions.brightnessB,
                mOptions.gammaR, mOptions.gammaG, mOptions.gammaB
            )

            mAvgColorResult[0] = rOut.toByte()
            mAvgColorResult[1] = gOut.toByte()
            mAvgColorResult[2] = bOut.toByte()

            val startSend = System.nanoTime()
            mListener.sendFrame(mAvgColorResult, 1, 1)
            mTotalSendTime += (System.nanoTime() - startSend)
        }
    }

    // Кадр читается прямо из буфера ImageReader, а close() снимает буфер с отображения: чтение
    // с другого потока падает SIGSEGV мимо всех catch. На потоке захвата кадр либо уже
    // дочитан, либо ещё не взят.
    private fun closeImageReader() {
        val reader = mImageReader ?: return
        mImageReader = null
        val handler = mCaptureHandler
        if (handler == null || !handler.post { reader.close() }) reader.close()
    }

    private fun stopInternal(disconnect: Boolean) {
        synchronized(this) {
            if (DEBUG) Log.i(TAG, "Stopping (disconnect=$disconnect)")
            mRunning = false
            setCapturing(false)

            mCaptureHandler?.removeCallbacksAndMessages(null)

            // Сначала освобождаем VirtualDisplay, чтобы в поверхность перестали писать новые кадры
            mVirtualDisplay?.release()
            mVirtualDisplay = null

            // ImageReader закрываем ДО остановки looper'а. ImageReader.close() трогает нативные
            // буферы поверх Binder, и закрытие после исчезновения looper'а приводит к взаимоблокировке
            // в FinalizerDaemon (BinderInternal$GcWatcher timed out): нативный финализатор не может
            // взять нужную блокировку Binder.
            closeImageReader()

            mCaptureThread?.quitSafely()
            mCaptureThread = null
            mCaptureHandler = null

            mRgbBuffer = null
            mRowBuffer = null
            mBorderX = 0
            mBorderY = 0
            mFrameCount = 0
        }

        // join — уже без монитора: MediaProjection.onStop приходит на останавливаемом
        // HandlerThread и входит в этот же stopInternal; join под монитором в такой
        // встрече всегда выжидал бы полный таймаут, держа главный поток
        stopHandlerThread()

        // При системной остановке (сон) соединение держим, иначе WLED вернётся к своему эффекту
        if (disconnect) {
            clearAndDisconnect()
        } else {
            clearLights()
        }
    }

    override fun stopRecording() {
        stopInternal(disconnect = true)
    }

    /**
     * Мягкая остановка без разрыва соединения (нужно для сна/пробуждения на TV).
     */
    fun stopRecordingNoDisconnect() {
        stopInternal(disconnect = false)
    }

    override fun resumeRecording() {
        if (DEBUG) Log.i(TAG, "Resuming")
        if (!isCapturing() && mImageReader != null && mCaptureHandler != null) {
            startCapture()
        } else if (mCaptureHandler == null) {
            Log.w(TAG, "Cannot resume recording: mCaptureHandler is null")
        }
    }

    @Synchronized
    override fun setOrientation(orientation: Int) {
        // Берём в локальную переменную, чтобы параллельный stopInternal() не освободил объект между проверкой и использованием.
        val virtualDisplay = mVirtualDisplay ?: return
        if (orientation == mCurrentOrientation) return

        mCurrentOrientation = orientation
        mRunning = false
        setCapturing(false)

        val tmp = mCaptureWidth
        mCaptureWidth = mCaptureHeight
        mCaptureHeight = tmp

        mCaptureHandler?.removeCallbacksAndMessages(null)

        try {
            virtualDisplay.resize(mCaptureWidth, mCaptureHeight, mDensity)
        } catch (e: IllegalStateException) {
            // Уже освобождён — энкодер останавливают, смену ориентации прерываем.
            Log.w(TAG, "setOrientation: VirtualDisplay released mid-flight: ${e.message}")
            return
        }

        closeImageReader()

        val imageReader = ImageReader.newInstance(
            mCaptureWidth, mCaptureHeight,
            PixelFormat.RGBA_8888,
            IMAGE_READER_IMAGES
        )
        mImageReader = imageReader

        try {
            virtualDisplay.surface = imageReader.surface
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setOrientation: surface assignment failed: ${e.message}")
            closeImageReader()
            return
        }

        mRgbBuffer = null
        mRowBuffer = null

        startCapture()
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val IMAGE_READER_IMAGES = 2
        private const val BORDER_CHECK_FRAMES = 60
        private const val BYTES_PER_PIXEL_RGBA = 4
        private const val BYTES_PER_PIXEL_RGB = 3
        private const val MAX_CAPTURE_SIDE = 4096
    }
}
