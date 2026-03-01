package com.vasmarfas.UniversalAmbientLight.common

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import kotlin.math.max

class AccessibilityEncoder(
    private val mService: AccessibilityCaptureService,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions
) {
    @Volatile private var mRunning = false
    @Volatile private var mCapturing = false

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null

    // Accessibility screenshots are heavy, limit fps
    private val mFrameIntervalMs: Long = max(200L, 1000L / mOptions.frameRate)

    private var mRgbBuffer: ByteArray? = null
    private var mPixelBuffer: IntArray? = null

    private val mCaptureRunnable = object : Runnable {
        override fun run() {
            if (!mRunning) return
            val start = System.currentTimeMillis()
            
            mService.takeScreenshot { bitmap ->
                if (bitmap != null) {
                    processBitmap(bitmap)
                    bitmap.recycle()
                }
                
                val elapsed = System.currentTimeMillis() - start
                if (mRunning) {
                    val delay = max(50L, mFrameIntervalMs - elapsed)
                    mHandler?.postDelayed(this, delay)
                }
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
                mThread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND)
                mThread!!.start()
                mHandler = Handler(mThread!!.looper)
            }
            mRunning = true
            mCapturing = true
            mHandler!!.post(mCaptureRunnable)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setOrientation(orientation: Int) {
        // No-op
    }

    private fun startCapture() {
        mThread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND)
        mThread!!.start()
        mHandler = Handler(mThread!!.looper)
        mRunning = true
        mCapturing = true
        mHandler!!.post(mCaptureRunnable)
    }

    private fun processBitmap(bitmap: Bitmap) {
        if (mOptions.useAverageColor) {
            sendAvgColor(bitmap)
        } else {
            sendPixelData(bitmap)
        }
    }

    private fun sendPixelData(bitmap: Bitmap) {
        // Scale down if needed based on captureQuality
        var bmp = bitmap
        val w = bmp.width
        val h = bmp.height
        
        // Simple downscale logic if bitmap is too huge compared to setting
        // Note: Accessibility screenshots are usually full res.
        val targetDim = mOptions.captureQuality.coerceAtLeast(64)
        if (w > targetDim && h > targetDim) {
             val scale = targetDim.toFloat() / max(w, h).toFloat()
             val newW = (w * scale).toInt()
             val newH = (h * scale).toInt()
             val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
             // We don't recycle input bitmap here because it comes from callback which might reuse/recycle it (but we got a copy so it is fine to recycle our input)
             // Actually input is "copy" from Service, so we own it.
             // Wait, processBitmap receives a bitmap. If we scale it, we should use the scaled one.
             bmp = scaled
        }

        val fw = bmp.width
        val fh = bmp.height
        val pixelCount = fw * fh

        if (mPixelBuffer == null || mPixelBuffer!!.size < pixelCount) {
            mPixelBuffer = IntArray(pixelCount)
        }
        bmp.getPixels(mPixelBuffer!!, 0, fw, 0, 0, fw, fh)

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
        mListener.sendFrame(mRgbBuffer!!, fw, fh)
        
        if (bmp != bitmap) {
            bmp.recycle()
        }
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
        private const val TAG = "AccessibilityEncoder"
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5
    }
}
