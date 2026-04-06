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
    
    // Performance profiling
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
            
            // Profiling loop time
            val elapsedNs = end - start
            val elapsedMs = elapsedNs / 1_000_000L
            mTotalLoopTime += elapsedNs
            mProfileFrames++
            
            val now = System.currentTimeMillis()
            if (now - mLastLogTime >= 2000) { // Log every 2 seconds
                if (mProfileFrames > 0) {
                    val fps = mProfileFrames * 1000f / (now - mLastLogTime)
                    // Averages in ms
                    val avgLoop = (mTotalLoopTime / mProfileFrames) / 1_000_000f
                    val avgCapture = (mTotalCaptureTime / mProfileFrames) / 1_000_000f
                    val avgProcess = (mTotalProcessTime / mProfileFrames) / 1_000_000f
                    val avgSend = (mTotalSendTime / mProfileFrames) / 1_000_000f
                    
                    Log.i(TAG, String.format("PERF: FPS=%.1f | Loop=%.1fms (Cap=%.1f, Proc=%.1f, Send=%.1f)", 
                        fps, avgLoop, avgCapture, avgProcess, avgSend))
                }
                
                mLastLogTime = now
                mProfileFrames = 0
                mTotalLoopTime = 0
                mTotalCaptureTime = 0
                mTotalProcessTime = 0
                mTotalSendTime = 0
            }

            // Capture to local var to avoid race condition: stopInternal() may null
            // mCaptureHandler on another thread between the null-check and the !! unwrap.
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
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()

        Log.d(
            TAG,
            "initCaptureDimensions: quality=$quality, screenWidth=$screenWidth, screenHeight=$screenHeight"
        )

        // Calculate aspect ratio from real screen dimensions
        val ratio = screenWidth.toFloat() / screenHeight
        Log.d(TAG, "initCaptureDimensions: ratio=$ratio")

        val (w, h) = if (quality <= 512) {
            // Legacy behaviour: treat quality as max capture width in pixels
            val targetWidth = min(screenWidth, quality)
            val targetHeight = (targetWidth / ratio).toInt()
            Log.d(
                TAG,
                "initCaptureDimensions (legacy): requestedWidth=$quality, targetWidth=$targetWidth, targetHeight=$targetHeight"
            )
            targetWidth to targetHeight
        } else {
            // New behaviour for "p" presets (720p/1080p/1440p/2160p):
            // treat quality as target VERTICAL resolution, keep aspect ratio from real screen.
            val targetHeight = min(screenHeight, quality)
            val targetWidth = (targetHeight * ratio).toInt()
            Log.d(
                TAG,
                "initCaptureDimensions (p-preset): requestedHeight=$quality, targetWidth=$targetWidth, targetHeight=$targetHeight"
            )
            targetWidth to targetHeight
        }

        // Ensure even dimensions and sane minimum size
        mCaptureWidth = max(32, w and 1.inv())
        mCaptureHeight = max(32, h and 1.inv())

        Log.d(
            TAG,
            "initCaptureDimensions: FINAL mCaptureWidth=$mCaptureWidth, mCaptureHeight=$mCaptureHeight"
        )
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
        val handler = mCaptureHandler
        if (handler == null) {
            Log.e(TAG, "Cannot start capture: mCaptureHandler is null")
            return
        }
        mRunning = true
        setCapturing(true)
        mFrameCount = 0
        handler.post(mCaptureRunnable)
    }

    private fun captureFrame() {
        var img: Image? = null
        try {
            val startCap = System.nanoTime()
            img = mImageReader!!.acquireLatestImage()
            mTotalCaptureTime += (System.nanoTime() - startCap)
            
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
        val startProc = System.nanoTime()
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
        
        mTotalProcessTime += (System.nanoTime() - startProc)
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
        
        // Применяем обработку цветов
        ColorProcessor.processRgbData(rgb, mOptions)
        
        // Log occasionally for debugging
        if (DEBUG && System.currentTimeMillis() % 5000 < 100) {
            Log.d(TAG, "sendPixelData: effWidth=$effWidth, effHeight=$effHeight, rgb.size=${rgb.size}, expected=${effWidth * effHeight * 3}")
        }
        
        val startSend = System.nanoTime()
        mListener.sendFrame(rgb, effWidth, effHeight)
        mTotalSendTime += (System.nanoTime() - startSend)
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
                mOptions.saturation
            )
            
            mAvgColorResult[0] = rOut.toByte()
            mAvgColorResult[1] = gOut.toByte()
            mAvgColorResult[2] = bOut.toByte()
            
            val startSend = System.nanoTime()
            mListener.sendFrame(mAvgColorResult, 1, 1)
            mTotalSendTime += (System.nanoTime() - startSend)
        }
    }

    private fun stopInternal(disconnect: Boolean) {
        if (DEBUG) Log.i(TAG, "Stopping (disconnect=$disconnect)")
        mRunning = false
        setCapturing(false)

        mCaptureHandler?.removeCallbacksAndMessages(null)

        // Release VirtualDisplay first to stop new frames being written to the surface
        if (mVirtualDisplay != null) {
            mVirtualDisplay!!.release()
            mVirtualDisplay = null
        }

        // Close ImageReader BEFORE quitting the handler looper.
        // ImageReader.close() touches native Binder-backed buffers; closing it after the
        // looper is gone can cause a deadlock in FinalizerDaemon (BinderInternal$GcWatcher
        // timed out) because the native finalizer cannot acquire the required Binder lock.
        if (mImageReader != null) {
            mImageReader!!.close()
            mImageReader = null
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

        // quitSafely + join to ensure the thread is fully stopped before resources are GC'd
        stopHandlerThread()

        // Keep connection alive on system stop (sleep) to prevent WLED from reverting to default effect
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

    override fun setOrientation(orientation: Int) {
        if (mVirtualDisplay == null || orientation == mCurrentOrientation) return

        mCurrentOrientation = orientation
        mRunning = false
        setCapturing(false)

        val tmp = mCaptureWidth
        mCaptureWidth = mCaptureHeight
        mCaptureHeight = tmp

        mCaptureHandler?.removeCallbacksAndMessages(null)

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
