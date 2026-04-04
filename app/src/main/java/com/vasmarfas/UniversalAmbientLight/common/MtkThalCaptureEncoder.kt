package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import android.os.Looper
import android.widget.Toast
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val onFatalError: ((String) -> Unit)? = null
) {
    @Volatile private var mRunning = false
    @Volatile private var mCapturing = false

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null
    private var mProcess: java.lang.Process? = null

    private var mCaptureWidth = 0
    private var mCaptureHeight = 0

    init {
        calculateCaptureDimensions()
        startCapture()
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

    private fun startCapture() {
        val binary = extractBinary()
        if (binary == null) {
            onFatalError?.invoke("MTK THAL Capture: binary not found")
            return
        }

        // Ensure DMA heap is accessible
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "chmod 666 $DMA_HEAP_PATH")).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "chmod dma_heap failed", e)
        }

        val fps = max(1, mOptions.frameRate)
        val cmd = arrayOf("su", "-c",
            "LD_LIBRARY_PATH=/vendor/lib:/system/lib " +
            "${binary.absolutePath} $mCaptureWidth $mCaptureHeight $fps")

        try {
            mProcess = Runtime.getRuntime().exec(cmd)
            Log.i(TAG, "Started capture: ${mCaptureWidth}x${mCaptureHeight} @ ${fps}fps")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start capture process", e)
            onFatalError?.invoke("MTK THAL Capture: failed to start process")
            return
        }

        mThread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND)
        mThread!!.start()
        mHandler = Handler(mThread!!.looper)
        mRunning = true
        mCapturing = true
        mHandler!!.post { readFrameLoop() }
    }

    companion object {
        private const val TAG = "MtkThalCaptureEncoder"
        private const val CLEAR_FRAMES = 5
        private const val CLEAR_DELAY_MS = 100L
        private const val BINARY_NAME = "mtk_thal_capture_server"
        private const val DMA_HEAP_PATH = "/dev/dma_heap/mtk_dip_capture_uncached"
        private const val THAL_LIB_PATH = "/vendor/lib/libthal_capture.so"
        private const val STATUS_MAGIC = 0x4D544B53 // "MTKS"

        fun isAvailable(): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c",
                    "test -f $THAL_LIB_PATH && test -e $DMA_HEAP_PATH && echo OK"))
                val result = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                result == "OK"
            } catch (e: Exception) {
                false
            }
        }

        fun isAvailable(context: Context): Boolean = isAvailable()
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
                    android.os.Handler(Looper.getMainLooper()).post {
                        Toast.makeText(mContext,
                            "MTK Capture: HDMI input capture unavailable on this firmware",
                            Toast.LENGTH_LONG).show()
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
                        mListener.sendFrame(rgb, w, h)
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
                mListener.sendFrame(rgb, w, h)
            }
        } catch (e: IOException) {
            if (mRunning) {
                Log.e(TAG, "Read error", e)
                try {
                    val stderr = process.errorStream.bufferedReader().readText()
                    if (stderr.isNotEmpty()) Log.e(TAG, "Process stderr: $stderr")
                } catch (_: Exception) {}
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
        if (!mRunning) {
            startCapture()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setOrientation(orientation: Int) {
        // Hardware capture handles orientation at the pipeline level
    }

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false
        mCapturing = false

        mProcess?.let { proc ->
            try { proc.outputStream.close() } catch (_: Exception) {}
            try { proc.destroy() } catch (_: Exception) {}
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
