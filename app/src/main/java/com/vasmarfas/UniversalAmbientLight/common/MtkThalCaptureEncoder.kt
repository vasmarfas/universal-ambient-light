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
 * Screen capture encoder using MediaTek HIDL capture via libthal_capture.so.
 *
 * Uses do_capture_window() which talks to vendor.mediatek.hardware.capture@1.0
 * HIDL service and captures frames from the display pipeline (video + OSD)
 * using the hardware DIP engine — minimal CPU overhead (~3.5% at 1080p/60fps).
 *
 * Requires:
 *   - Root access
 *   - /dev/dma_heap/mtk_dip_capture_uncached (writable)
 *   - /vendor/lib/libthal_capture.so
 *
 * The binary (mtk_thal_capture_server) runs as root via su, loads the vendor
 * library, and streams raw RGB frames to stdout.
 * Protocol: per frame = 4 bytes LE width + 4 bytes LE height + (w*h*3) RGB bytes.
 */
class MtkThalCaptureEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
    private val onFatalError: ((String) -> Unit)? = null,
) {
    @Volatile
    private var mRunning = false
    @Volatile
    private var mCapturing = false

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null
    private var mProcess: java.lang.Process? = null

    private var mCaptureWidth = 0
    private var mCaptureHeight = 0

    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()

    init {
        calculateCaptureDimensions()
        // Defer slow work (APK extraction, su exec) to the worker so the constructor
        // — called from ScreenGrabberService on the main thread — returns immediately.
        mThread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        mHandler = Handler(mThread!!.looper)
        mRunning = true
        mCapturing = true
        mHandler!!.post { startCaptureOnWorker() }
    }

    private fun calculateCaptureDimensions() {
        // Hardware DIP captures full screen at 1920x1080, the server downscales.
        // 240p is enough for LED color sampling and keeps pipe bandwidth reasonable.
        val aspectRatio = mScreenWidth.toFloat() / mScreenHeight.toFloat()
        mCaptureWidth = 426
        mCaptureHeight = (mCaptureWidth / aspectRatio).roundToInt()
        // Ensure even
        mCaptureWidth = (mCaptureWidth + 1) and 0x7FFFFFFE.toInt()
        mCaptureHeight = (mCaptureHeight + 1) and 0x7FFFFFFE.toInt()

        Log.i(TAG, "Capture dimensions: ${mCaptureWidth}x${mCaptureHeight}")
    }

    private fun extractBinary(): File? {
        val destFile = File(mContext.filesDir, BINARY_NAME)

        // Re-extract if APK is newer than cached binary (handles app updates)
        val apkLastModified = File(mContext.applicationInfo.sourceDir).lastModified()
        if (destFile.exists() && destFile.canExecute() && destFile.lastModified() >= apkLastModified) {
            return destFile
        }

        // Try nativeLibraryDir on disk
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

        // Extract from APK zip
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
            // stopRecording fired before the process launched.
            try {
                mProcess?.destroy()
            } catch (_: Exception) {
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
         * Never blocks the caller. First call probes `su` on a daemon thread and
         * returns false; subsequent calls return the cached result.
         */
        fun isAvailable(): Boolean {
            sCachedAvailable?.let { return it }
            // First caller starts the background probe; everyone else just sees false until it lands.
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

        try {
            // Read status header: magic (4B LE) + flags (4B LE)
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
                // Old binary without status header — first 8 bytes are the first frame header
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
            if (mRunning) {
                Log.e(TAG, "Read error", e)
                try {
                    val stderr = process.errorStream.bufferedReader().readText()
                    if (stderr.isNotEmpty()) Log.e(TAG, "Process stderr: $stderr")
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            if (mRunning) Log.e(TAG, "Unexpected error", e)
        }

        mCapturing = false
    }

    fun isCapturing(): Boolean = mCapturing

    fun sendStatus() {
        mListener.sendStatus(mCapturing)
    }

    fun clearLights() {
        Thread {
            repeat(CLEAR_FRAMES) {
                Thread.sleep(CLEAR_DELAY_MS)
                mListener.clear()
            }
        }.start()
    }

    fun stopRecording() {
        stopInternal(disconnect = true)
    }

    fun resumeRecording() {
        if (mRunning) return
        if (mHandler == null) {
            mThread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            mHandler = Handler(mThread!!.looper)
        }
        mRunning = true
        mCapturing = true
        mHandler!!.post { restartCaptureOnWorker() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setOrientation(orientation: Int) {
        // Hardware capture handles orientation at the pipeline level
    }

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false
        mCapturing = false

        mProcess?.let { proc ->
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
