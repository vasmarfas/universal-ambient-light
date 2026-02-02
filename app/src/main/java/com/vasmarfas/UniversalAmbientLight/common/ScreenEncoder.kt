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
    private val mOptions: AppOptions
) : ScreenEncoderBase(listener, projection, screenWidth, screenHeight, density, mOptions) {

    // Capture components
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
    private var mBorderX: Int = 0
    private var mBorderY: Int = 0
    private var mFrameCount: Int = 0

    private val mFrameIntervalMs: Long = (1000L / mFrameRate)

    private val mCaptureRunnable = object : Runnable {
        override fun run() {
            if (!mRunning) return

            val start = System.nanoTime()
            captureFrame()

            if (mRunning && mCaptureHandler != null) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                val delayMs = max(1L, mFrameIntervalMs - elapsedMs)
                mCaptureHandler!!.postDelayed(this, delayMs)
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

        if (DEBUG) Log.d(TAG, "Capture: " + mCaptureWidth + "x" + mCaptureHeight + " @ " + mFrameRate + "fps")

        try {
            init()
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "Init failed", e)
        } catch (e: SecurityException) {
            // MediaProjection token expired or revoked by system
            Log.e(TAG, "Init failed: MediaProjection token invalid", e)
            throw e // Re-throw so restartEncoderFromSavedProjection can handle it
        }
    }

    private fun initCaptureDimensions() {
        // Use user-configured capture quality
        var quality = mOptions.captureQuality
        if (quality <= 0) quality = 128 // fallback

        // Use REAL screen dimensions, not the scaled-down ones from base class
        // The base class uses findDivisor() which is meant for full-frame protocols,
        // but we only need perimeter pixels for WLED/LED strips
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        
        Log.d(TAG, "initCaptureDimensions: quality=$quality, screenWidth=$screenWidth, screenHeight=$screenHeight")

        // Calculate aspect ratio from real screen dimensions
        val ratio = screenWidth.toFloat() / screenHeight
        Log.d(TAG, "initCaptureDimensions: ratio=$ratio")

        // Limit width by quality settings
        val w = min(screenWidth, quality)
        val h = (w / ratio).toInt()
        Log.d(TAG, "initCaptureDimensions: calculated w=$w, h=$h")

        // Ensure even dimensions
        mCaptureWidth = max(32, w and 1.inv())
        mCaptureHeight = max(32, h and 1.inv())
        
        Log.d(TAG, "initCaptureDimensions: FINAL mCaptureWidth=$mCaptureWidth, mCaptureHeight=$mCaptureHeight")
    }

    @Throws(MediaCodec.CodecException::class)
    private fun init() {
        mCaptureThread = HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND)
        mCaptureThread!!.start()
        val looper = mCaptureThread!!.looper
        if (looper == null) {
            Log.e(TAG, "Failed to get looper from capture thread")
            throw IllegalStateException("Capture thread looper is null")
        }
        mCaptureHandler = Handler(looper)

        mImageReader = ImageReader.newInstance(
            mCaptureWidth, mCaptureHeight,
            PixelFormat.RGBA_8888,
            IMAGE_READER_IMAGES
        )

        mMediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Important for Android/Google TV: MediaProjection may call onStop() when screen goes to sleep.
                // Don't disconnect from WLED/Hyperion, otherwise WLED quickly returns to default effect
                // and won't reconnect without manual restart.
                stopInternal(disconnect = false)
            }
        }, mHandler)

        // createVirtualDisplay may throw SecurityException if MediaProjection token expired or was revoked.
        // Exception is re-thrown so restartEncoderFromSavedProjection can handle it and clear saved projection data.
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
            TAG,
            mCaptureWidth, mCaptureHeight, mDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            mImageReader!!.surface,
            mDisplayCallback,
            mHandler
        )

        startCapture()
    }

    private fun startCapture() {
        if (mCaptureHandler == null) {
            Log.e(TAG, "Cannot start capture: mCaptureHandler is null")
            return
        }
        mRunning = true
        setCapturing(true)
        mFrameCount = 0
        mCaptureHandler!!.post(mCaptureRunnable)
    }

    private fun captureFrame() {
        var img: Image? = null
        try {
            img = mImageReader!!.acquireLatestImage()
            if (img != null) {
                processImage(img)
            }
        } catch (e: Exception) {
            if (DEBUG) Log.w(TAG, "Capture error", e)
        } finally {
            img?.close()
        }
    }

    private fun processImage(img: Image) {
        val planes = img.planes
        if (planes.isEmpty()) return

        val plane = planes[0]
        val buffer = plane.buffer
        val width = img.width
        val height = img.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        updateBorderDetection(buffer, width, height, rowStride, pixelStride)

        if (mAvgColor) {
            sendAverageColor(buffer, width, height, rowStride, pixelStride)
        } else {
            sendPixelData(buffer, width, height, rowStride, pixelStride)
        }
    }

    private fun updateBorderDetection(
        buffer: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int
    ) {
        if (!mRemoveBorders && !mAvgColor) return

        if (++mFrameCount >= BORDER_CHECK_FRAMES) {
            mFrameCount = 0
            mBorderProcessor.parseBorder(buffer, width, height, rowStride, pixelStride)
            val border = mBorderProcessor.currentBorder
            if (border != null && border.isKnown) {
                mBorderX = border.horizontalBorderIndex
                mBorderY = border.verticalBorderIndex
            }
        }
    }

    private fun sendPixelData(
        buffer: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int
    ) {
        val bx = mBorderX
        val by = mBorderY
        val effWidth = width - (bx shl 1)
        val effHeight = height - (by shl 1)

        if (effWidth <= 0 || effHeight <= 0) return

        val rgb = extractRgb(buffer, width, height, rowStride, pixelStride, bx, by, effWidth, effHeight)
        
        // Log occasionally for debugging
        if (DEBUG && System.currentTimeMillis() % 5000 < 100) {
            Log.d(TAG, "sendPixelData: effWidth=$effWidth, effHeight=$effHeight, rgb.size=${rgb.size}, expected=${effWidth * effHeight * 3}")
        }
        
        mListener.sendFrame(rgb, effWidth, effHeight)
    }

    private fun extractRgb(
        buffer: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int,
        bx: Int, by: Int, effWidth: Int, effHeight: Int
    ): ByteArray {
        val rgbSize = effWidth * effHeight * BYTES_PER_PIXEL_RGB

        if (mRgbBuffer == null || mRgbBuffer!!.size < rgbSize) {
            mRgbBuffer = ByteArray(rgbSize)
        }

        val endY = height - by
        val endX = width - bx
        var rgbIdx = 0

        if (pixelStride == BYTES_PER_PIXEL_RGBA && rowStride == width * BYTES_PER_PIXEL_RGBA) {
            val rowBytes = effWidth * BYTES_PER_PIXEL_RGBA

            if (mRowBuffer == null || mRowBuffer!!.size < rowBytes) {
                mRowBuffer = ByteArray(rowBytes)
            }

            val savedPos = buffer.position()

            for (y in by until endY) {
                buffer.position(y * rowStride + bx * BYTES_PER_PIXEL_RGBA)
                buffer.get(mRowBuffer!!, 0, rowBytes)

                var i = 0
                val unrollLimit = rowBytes - 15
                while (i < unrollLimit) {
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 1]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 2]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 4]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 5]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 6]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 8]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 9]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 10]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 12]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 13]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 14]
                    i += 16
                }
                while (i < rowBytes) {
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 1]
                    mRgbBuffer!![rgbIdx++] = mRowBuffer!![i + 2]
                    i += BYTES_PER_PIXEL_RGBA
                }
            }

            buffer.position(savedPos)
        } else {
            for (y in by until endY) {
                val rowOff = y * rowStride
                for (x in bx until endX) {
                    val off = rowOff + x * pixelStride
                    mRgbBuffer!![rgbIdx++] = buffer.get(off)
                    mRgbBuffer!![rgbIdx++] = buffer.get(off + 1)
                    mRgbBuffer!![rgbIdx++] = buffer.get(off + 2)
                }
            }
        }

        return mRgbBuffer!!
    }

    private fun sendAverageColor(
        buffer: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int
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
            mAvgColorResult[0] = (r / count).toByte()
            mAvgColorResult[1] = (g / count).toByte()
            mAvgColorResult[2] = (b / count).toByte()
            mListener.sendFrame(mAvgColorResult, 1, 1)
        }
    }

    private fun stopInternal(disconnect: Boolean) {
        if (DEBUG) Log.i(TAG, "Stopping (disconnect=$disconnect)")
        mRunning = false
        setCapturing(false)

        if (mCaptureHandler != null) {
            mCaptureHandler!!.removeCallbacksAndMessages(null)
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay!!.release()
            mVirtualDisplay = null
        }

        if (mCaptureThread != null) {
            mCaptureThread!!.quitSafely()
            mCaptureThread = null
            mCaptureHandler = null
        }

        mRgbBuffer = null
        mRowBuffer = null
        mBorderX = 0
        mBorderY = 0
        mFrameCount = 0

        mHandler.looper.quit()

        // Keep connection alive on system stop (sleep) to prevent WLED from reverting to default effect
        if (disconnect) {
            clearAndDisconnect()
        } else {
            clearLights()
        }

        if (mImageReader != null) {
            mImageReader!!.close()
            mImageReader = null
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

    override fun setOrientation(orientation: Int) {
        if (mVirtualDisplay == null || orientation == mCurrentOrientation) return

        mCurrentOrientation = orientation
        mRunning = false
        setCapturing(false)

        val tmp = mCaptureWidth
        mCaptureWidth = mCaptureHeight
        mCaptureHeight = tmp

        if (mCaptureHandler != null) {
            mCaptureHandler!!.removeCallbacksAndMessages(null)
        }

        mVirtualDisplay!!.resize(mCaptureWidth, mCaptureHeight, mDensity)

        if (mImageReader != null) {
            mImageReader!!.close()
        }

        mImageReader = ImageReader.newInstance(
            mCaptureWidth, mCaptureHeight,
            PixelFormat.RGBA_8888,
            IMAGE_READER_IMAGES
        )

        mVirtualDisplay!!.surface = mImageReader!!.surface

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
    }
}
