package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Захват экрана через MediaTek HIDL средствами libthal_capture.so.
 *
 * Использует do_capture_window(), который обращается к HIDL-сервису
 * vendor.mediatek.hardware.capture@1.0 и снимает кадры прямо с конвейера дисплея
 * (видео и экранное меню) аппаратным движком DIP — процессор при этом почти
 * не нагружается (около 3,5% на 1080p/60 кадров).
 *
 * Требуется:
 *   - root-доступ
 *   - /dev/dma_heap/mtk_dip_capture_uncached (с правом записи)
 *
 * Бинарник (mtk_thal_capture_server) запускается от root через su, подгружает библиотеку
 * вендора и пишет сырые RGB-кадры в stdout.
 * Формат: на кадр приходится 4 байта ширины LE + 4 байта высоты LE + (w*h*3) байт RGB.
 */
class MtkThalCaptureEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
    private val onFatalError: ((String) -> Unit)? = null,
) : CaptureBackend {
    @Volatile
    private var mRunning = false
    @Volatile
    private var mCapturing = false

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null

    @Volatile
    private var mProcess: java.lang.Process? = null

    private var mCaptureWidth = 0
    private var mCaptureHeight = 0

    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()

    init {
        calculateCaptureDimensions()
        // Медленную работу (распаковку APK, запуск su) уводим в рабочий поток, чтобы
        // конструктор, вызываемый из ScreenGrabberService на главном потоке, возвращался сразу.
        val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        mThread = thread
        val handler = Handler(thread.looper)
        mHandler = handler
        mRunning = true
        mCapturing = true
        handler.post { startCaptureOnWorker() }
    }

    private fun calculateCaptureDimensions() {
        // Аппаратный DIP снимает весь экран в 1920x1080, уменьшает уже сервер.
        // Для выборки цветов хватает 240p, и нагрузка на канал остаётся вменяемой.
        // Нулевые метрики (часть прошивок на старте) заменяем на 16:9.
        val aspectRatio = if (mScreenWidth > 0 && mScreenHeight > 0) {
            mScreenWidth.toFloat() / mScreenHeight.toFloat()
        } else {
            16f / 9f
        }
        mCaptureWidth = 426
        mCaptureHeight = (mCaptureWidth / aspectRatio).roundToInt()
        // Приводим к чётному
        mCaptureWidth = (mCaptureWidth + 1) and 0x7FFFFFFE.toInt()
        mCaptureHeight = (mCaptureHeight + 1) and 0x7FFFFFFE.toInt()

        Log.i(TAG, "Capture dimensions: ${mCaptureWidth}x${mCaptureHeight}")
    }

    private fun extractBinary(): File? {
        val destFile = File(mContext.filesDir, BINARY_NAME)

        // Распаковываем заново, если APK новее сохранённого бинарника (то есть после обновления)
        val apkLastModified = File(mContext.applicationInfo.sourceDir).lastModified()
        if (destFile.exists() && destFile.canExecute() && destFile.lastModified() >= apkLastModified) {
            return destFile
        }

        // Сначала пробуем nativeLibraryDir на диске
        val nativeLibDir = mContext.applicationInfo.nativeLibraryDir
        val diskFile = File(nativeLibDir, "lib${BINARY_NAME}.so")
        if (diskFile.exists()) {
            try {
                diskFile.inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.setExecutable(true, false)
                Log.i(TAG, "Extracted binary from disk: ${diskFile.absolutePath}")
                return destFile
            } catch (e: IOException) {
                Log.w(TAG, "Failed to copy from disk, trying APK", e)
            }
        }

        // Достаём из zip-архива APK
        try {
            val apkPath = mContext.applicationInfo.sourceDir
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"
            val entryName = "lib/$abi/lib${BINARY_NAME}.so"
            Log.i(TAG, "Extracting from APK: $apkPath!$entryName")

            java.util.zip.ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(entryName)
                if (entry == null) {
                    Log.e(TAG, "Entry $entryName not found in APK")
                    return null
                }
                zip.getInputStream(entry).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            destFile.setExecutable(true, false)
            Log.i(TAG, "Extracted binary from APK to ${destFile.absolutePath}")
            return destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary from APK", e)
            return null
        }
    }

    private fun startCaptureOnWorker() {
        val binary = extractBinary()
        if (binary == null) {
            mCapturing = false
            mRunning = false
            onFatalError?.invoke("MTK THAL Capture: binary not found")
            return
        }

        try {
            val chmod = Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 666 $DMA_HEAP_PATH"))
            if (!chmod.waitFor(CHMOD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                try {
                    chmod.destroyForcibly()
                } catch (_: Exception) {
                    // Процесс мог завершиться сам между таймаутом и попыткой его убить.
                }
                Log.w(TAG, "chmod dma_heap timed out")
            }
        } catch (e: Exception) {
            Log.w(TAG, "chmod dma_heap failed", e)
        }

        val fps = max(1, mOptions.frameRate)
        val cmd = arrayOf(
            "su", "-c",
            "LD_LIBRARY_PATH=/vendor/lib:/system/lib " +
                    "${binary.absolutePath} $mCaptureWidth $mCaptureHeight $fps"
        )

        try {
            mProcess = Runtime.getRuntime().exec(cmd)
            Log.i(TAG, "Started capture: ${mCaptureWidth}x${mCaptureHeight} @ ${fps}fps")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start capture process", e)
            mCapturing = false
            mRunning = false
            onFatalError?.invoke("MTK THAL Capture: failed to start process")
            return
        }

        if (!mRunning) {
            // stopRecording сработал раньше, чем запустился процесс.
            try {
                mProcess?.destroy()
            } catch (_: Exception) {
                // Гонка со stopRecording: процесс мог быть уже убит там.
            }
            mProcess = null
            return
        }
        readFrameLoop()
    }

    private fun restartCaptureOnWorker() {
        if (mProcess != null) return
        startCaptureOnWorker()
    }

    companion object {
        private const val TAG = "MtkThalCaptureEncoder"
        private const val CLEAR_FRAMES = 5
        private const val CLEAR_DELAY_MS = 100L
        private const val BINARY_NAME = "mtk_thal_capture_server"
        private const val DMA_HEAP_PATH = "/dev/dma_heap/mtk_dip_capture_uncached"
        private const val THAL_LIB_PATH = "/vendor/lib/libthal_capture.so"
        private const val STATUS_MAGIC = 0x4D544B53 // "MTKS"
        private const val AVAILABILITY_CHECK_TIMEOUT_SEC = 3L
        private const val CHMOD_TIMEOUT_SEC = 2L

        @Volatile
        private var sCachedAvailable: Boolean? = null
        private val sCheckInProgress = AtomicBoolean(false)

        /**
         * Никогда не блокирует вызывающий поток. Первый вызов запускает проверку `su` в
         * фоновом потоке и возвращает false; последующие отдают уже готовый результат.
         */
        fun isAvailable(): Boolean {
            sCachedAvailable?.let { return it }
            // Первый вызывающий запускает фоновую проверку, остальные до её конца видят false.
            if (sCheckInProgress.compareAndSet(false, true)) {
                Thread {
                    try {
                        sCachedAvailable = checkAvailableBlocking()
                    } finally {
                        sCheckInProgress.set(false)
                    }
                }.apply {
                    isDaemon = true
                    name = "MtkThalAvailCheck"
                }.start()
            }
            return false
        }

        fun isAvailable(context: Context): Boolean = isAvailable()

        private fun checkAvailableBlocking(): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "test -f $THAL_LIB_PATH && test -e $DMA_HEAP_PATH && echo OK"
                    )
                )
                val completed = process.waitFor(AVAILABILITY_CHECK_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!completed) {
                    try {
                        process.destroy()
                    } catch (_: Exception) {
                        // Проверка доступности не удалась по таймауту; процесс мог
                        // завершиться сам, пока мы его убивали.
                    }
                    Log.w(
                        TAG,
                        "Availability probe timed out after ${AVAILABILITY_CHECK_TIMEOUT_SEC}s"
                    )
                    return false
                }
                val result = process.inputStream.bufferedReader().readText().trim()
                result == "OK"
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun readFrameLoop() {
        val process = mProcess ?: return
        val input = DataInputStream(process.inputStream)
        val headerBuf = ByteArray(8)

        // stderr вычитываем параллельно: разговорчивый бинарник иначе забил бы буфер
        // трубы (64 КБ) и завис на записи — захват встал бы без единой строки в логе
        Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) Log.w(TAG, "[capture] $line")
                }
            } catch (_: Exception) {
                // Труба закрывается вместе с процессом — это штатный конец чтения.
            }
        }, "mtk-thal-stderr").apply { isDaemon = true }.start()

        try {
            // Читаем заголовок состояния: magic (4 байта LE) + флаги (4 байта LE)
            input.readFully(headerBuf)
            val statusBb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val magic = statusBb.getInt()
            val flags = statusBb.getInt()

            if (magic == STATUS_MAGIC) {
                val hdmiPatchAvailable = (flags and 1) != 0
                if (!hdmiPatchAvailable) {
                    Log.w(TAG, "HDMI patch pattern not found on this firmware")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            mContext,
                            "MTK Capture: HDMI input capture unavailable on this firmware",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                // Старый бинарник без заголовка состояния — первые 8 байт это уже заголовок кадра
                val w = magic
                val h = flags
                if (w in 1..1920 && h in 1..1920) {
                    val rgbSize = w * h * 3
                    val rgb = ByteArray(rgbSize)
                    input.readFully(rgb)
                    if (mRunning) {
                        ColorProcessor.processRgbData(rgb, mOptions)
                        val cropped = mBorderCropper.applyForEncoder(rgb, w, h, mOptions)
                        mListener.sendFrame(cropped.rgb, cropped.width, cropped.height)
                    }
                }
            }

            while (mRunning) {
                input.readFully(headerBuf)
                val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                val w = bb.getInt()
                val h = bb.getInt()

                if (w <= 0 || h <= 0 || w > 1920 || h > 1920) {
                    Log.e(TAG, "Invalid frame dimensions: ${w}x${h}")
                    break
                }

                val rgbSize = w * h * 3
                val rgb = ByteArray(rgbSize)
                input.readFully(rgb)

                if (!mRunning) break

                ColorProcessor.processRgbData(rgb, mOptions)
                val cropped = mBorderCropper.applyForEncoder(rgb, w, h, mOptions)
                mListener.sendFrame(cropped.rgb, cropped.width, cropped.height)
            }
        } catch (e: IOException) {
            // Причины сбоя процесс печатает в stderr — их уже вывел поток mtk-thal-stderr
            if (mRunning) Log.e(TAG, "Read error", e)
        } catch (e: Exception) {
            if (mRunning) Log.e(TAG, "Unexpected error", e)
        }

        // Цикл закончился: процесс либо умер сам, либо его убил stopInternal. Останки
        // подчищаем здесь, иначе mProcess != null навсегда блокировал бы
        // restartCaptureOnWorker, а mRunning=true — resumeRecording, и после сбоя
        // бинарника захват не оживал бы до перезапуска сервиса.
        try {
            process.destroy()
        } catch (_: Exception) {
        }
        if (mProcess === process) mProcess = null
        mCapturing = false
        mRunning = false
    }

    override fun isCapturing(): Boolean = mCapturing

    override fun sendStatus() {
        mListener.sendStatus(mCapturing)
    }

    override fun clearLights() {
        Thread {
            repeat(CLEAR_FRAMES) {
                Thread.sleep(CLEAR_DELAY_MS)
                mListener.clear()
            }
        }.start()
    }

    override fun stopRecording() {
        stopInternal(disconnect = true)
    }

    override fun resumeRecording() {
        if (mRunning) return
        var handler = mHandler
        if (handler == null) {
            val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            mThread = thread
            handler = Handler(thread.looper)
            mHandler = handler
        }
        mRunning = true
        mCapturing = true
        handler.post { restartCaptureOnWorker() }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun setOrientation(orientation: Int) {
        // Аппаратный захват сам разбирается с ориентацией на уровне конвейера
    }

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false
        mCapturing = false

        mProcess?.let { proc ->
            // Остановка: root-процесс мог уже умереть сам, закрываем и убиваем best-effort.
            try {
                proc.outputStream.close()
            } catch (_: Exception) {
            }
            try {
                proc.destroy()
            } catch (_: Exception) {
            }
        }
        mProcess = null

        mHandler?.removeCallbacksAndMessages(null)
        mThread?.quitSafely()
        mThread = null
        mHandler = null

        if (disconnect) {
            Thread {
                repeat(CLEAR_FRAMES) {
                    Thread.sleep(CLEAR_DELAY_MS)
                    mListener.clear()
                }
                mListener.disconnect()
            }.start()
        } else {
            clearLights()
        }
    }
}
