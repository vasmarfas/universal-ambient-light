package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AdbKeyHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import dadb.Dadb
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class AdbEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
    private val mAdbPort: Int = 5555
) {
    @Volatile private var mRunning = false
    @Volatile private var mCapturing = false
    private var mDadb: Dadb? = null

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null
    private val mFrameIntervalMs: Long = max(100L, 1000L / mOptions.frameRate)

    private var mRgbBuffer: ByteArray? = null
    private var mPixelBuffer: IntArray? = null

    // Start with raw RGBA (no PNG overhead). Fall back to PNG if parsing fails.
    private var mUseRawMode = true
    private var mFailCount = 0

    private val mCaptureRunnable = object : Runnable {
        override fun run() {
            if (!mRunning) return
            val start = System.currentTimeMillis()
            captureFrame()
            val elapsed = System.currentTimeMillis() - start
            if (mRunning) {
                val delay = max(50L, mFrameIntervalMs - elapsed)
                mHandler?.postDelayed(this, delay)
            }
        }
    }

    init {
        startCapture()
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
            if (mHandler == null) {
                mThread = HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND)
                mThread!!.start()
                mHandler = Handler(mThread!!.looper)
            }
            mRunning = true
            mCapturing = true
            mHandler!!.post { connectAdb() }
            mHandler!!.post(mCaptureRunnable)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setOrientation(orientation: Int) {
        // No-op
    }

    private fun startCapture() {
        mThread = HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND)
        mThread!!.start()
        mHandler = Handler(mThread!!.looper)
        mRunning = true
        mCapturing = true
        mHandler!!.post { connectAdb() }
        mHandler!!.post(mCaptureRunnable)
    }

    private fun connectAdb() {
        try {
            if (mDadb == null) {
                Log.i(TAG, "Connecting to ADB local port $mAdbPort...")
                val keyPair = AdbKeyHelper.getKeyPair(mContext)
                // Dadb.create blocks until connected or throws
                mDadb = Dadb.create("127.0.0.1", mAdbPort, keyPair)
                Log.i(TAG, "ADB connected to localhost:$mAdbPort")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB connection failed: ${e.message}. Ensure Wireless Debugging is ON.")
            mDadb = null
        }
    }

    private fun captureFrame() {
        if (mDadb == null) {
            connectAdb()
            if (mDadb == null) return
        }

        try {
            // Raw mode skips PNG encode on device + PNG decode here — much faster
            val cmd = if (mUseRawMode) "shell:screencap" else "shell:screencap -p"
            val stream = mDadb!!.open(cmd)
            val data = stream.source.readByteArray()
            stream.close()

            if (data.isEmpty()) {
                Log.w(TAG, "ADB screencap returned empty data")
                return
            }

            val bitmap = if (mUseRawMode) {
                parseRawScreencap(data)
            } else {
                val opts = BitmapFactory.Options().apply { inSampleSize = computeSampleSize() }
                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            }

            if (bitmap != null) {
                processBitmap(bitmap)
                bitmap.recycle()
                mFailCount = 0
            } else {
                mFailCount++
                if (mUseRawMode && mFailCount > 3) {
                    Log.w(TAG, "Raw RGBA mode failed $mFailCount times, switching to PNG mode")
                    mUseRawMode = false
                    mFailCount = 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ADB capture error: ${e.message}")
            try { mDadb?.close() } catch (_: Exception) {}
            mDadb = null
        }
    }

    /**
     * Parses raw screencap output (no -p flag).
     * Format: [width:4][height:4][format:4][RGBA pixels...]
     * Subsamples directly from the byte buffer to avoid allocating a full-resolution Bitmap.
     */
    private fun parseRawScreencap(data: ByteArray): Bitmap? {
        if (data.size < 12) return null
        return try {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val w = bb.int
            val h = bb.int
            val format = bb.int
            val expectedSize = w * h * 4

            if (w !in 100..7680 || h !in 100..4320 || data.size < 12 + expectedSize) {
                Log.w(TAG, "Raw screencap invalid header: ${w}x${h} fmt=$format dataLen=${data.size}")
                return null
            }

            val sampleSize = computeSampleSize()
            val targetW = (w / sampleSize).coerceAtLeast(1)
            val targetH = (h / sampleSize).coerceAtLeast(1)

            // Subsample directly from raw bytes — no intermediate full-res Bitmap
            val pixels = IntArray(targetW * targetH)
            var dst = 0
            for (y in 0 until targetH) {
                val srcY = y * sampleSize
                val rowBase = 12 + srcY * w * 4
                for (x in 0 until targetW) {
                    val srcOffset = rowBase + x * sampleSize * 4
                    val r = data[srcOffset].toInt() and 0xFF
                    val g = data[srcOffset + 1].toInt() and 0xFF
                    val b = data[srcOffset + 2].toInt() and 0xFF
                    val a = data[srcOffset + 3].toInt() and 0xFF
                    pixels[dst++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "parseRawScreencap failed: ${e.message}")
            null
        }
    }

    private fun computeSampleSize(): Int {
        val targetWidth = mOptions.captureQuality.coerceIn(64, 512)
        var sampleSize = 1
        var width = mScreenWidth
        while (width / 2 >= targetWidth) {
            sampleSize = sampleSize shl 1
            width = width shr 1
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun processBitmap(bitmap: Bitmap) {
        if (mOptions.useAverageColor) {
            sendAvgColor(bitmap)
        } else {
            sendPixelData(bitmap)
        }
    }

    private fun sendPixelData(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixelCount = w * h

        if (mPixelBuffer == null || mPixelBuffer!!.size < pixelCount) {
            mPixelBuffer = IntArray(pixelCount)
        }
        bitmap.getPixels(mPixelBuffer!!, 0, w, 0, 0, w, h)

        val rgbSize = pixelCount * 3
        if (mRgbBuffer == null || mRgbBuffer!!.size < rgbSize) {
            mRgbBuffer = ByteArray(rgbSize)
        }

        var dst = 0
        for (i in 0 until pixelCount) {
            val pixel = mPixelBuffer!![i]
            mRgbBuffer!![dst++] = ((pixel shr 16) and 0xFF).toByte()
            mRgbBuffer!![dst++] = ((pixel shr 8) and 0xFF).toByte()
            mRgbBuffer!![dst++] = (pixel and 0xFF).toByte()
        }

        ColorProcessor.processRgbData(mRgbBuffer!!, mOptions)
        mListener.sendFrame(mRgbBuffer!!, w, h)
    }

    private fun sendAvgColor(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                count++
                x += 4
            }
            y += 4
        }
        if (count > 0) {
            val (rOut, gOut, bOut) = ColorProcessor.processColor(
                (r / count).toInt(), (g / count).toInt(), (b / count).toInt(),
                mOptions.brightness, mOptions.contrast,
                mOptions.blackLevel, mOptions.whiteLevel, mOptions.saturation
            )
            mListener.sendFrame(byteArrayOf(rOut.toByte(), gOut.toByte(), bOut.toByte()), 1, 1)
        }
    }

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false
        mCapturing = false
        mHandler?.removeCallbacksAndMessages(null)
        mThread?.quitSafely()
        mThread = null
        mHandler = null
        mRgbBuffer = null
        mPixelBuffer = null
        try {
            mDadb?.close()
        } catch (e: Exception) {}
        mDadb = null
        
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

    companion object {
        private const val TAG = "AdbEncoder"
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5
    }
}
