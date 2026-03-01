package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Shell-based screen capture encoder using the `screencap` system command.
 *
 * This is a fallback for devices where MediaProjection is blocked at the firmware level
 * (e.g. Yandex TV with YaOS). The `screencap` binary is available on all Android devices
 * and does not require MediaProjection permission.
 *
 * Limitations compared to ScreenEncoder:
 * - Lower frame rate (~5–7 fps typical)
 * - Higher CPU overhead per frame (PNG decode via BitmapFactory)
 * - No VirtualDisplay, so capture quality is bounded by inSampleSize steps
 */
class ScreencapEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
    private val mUseRoot: Boolean = false,
    private val onFatalError: ((String) -> Unit)? = null
) {
    @Volatile private var mRunning = false
    @Volatile private var mCapturing = false

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null

    // Screencap is slow; don't try faster than 10 fps regardless of user setting
    private val mFrameIntervalMs: Long = max(100L, 1000L / mOptions.frameRate)

    private var mRgbBuffer: ByteArray? = null
    private var mPixelBuffer: IntArray? = null
    
    private var mUseRawScreencap = false
    private var mUseFileMode = false
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

    fun stopRecordingKeepConnection() {
        stopInternal(disconnect = false)
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
        // screencap captures whatever is currently on screen including rotation — no-op
    }

    private fun startCapture() {
        mThread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND)
        mThread!!.start()
        mHandler = Handler(mThread!!.looper)
        mRunning = true
        mCapturing = true
        mHandler!!.post(mCaptureRunnable)
    }

    private fun captureFrame() {
        var process: java.lang.Process? = null
        try {
            var bitmap: Bitmap? = null

            if (mUseFileMode) {
                // File-based capture (fallback for SELinux blocked stdout)
                val cacheDir = mContext.externalCacheDir ?: mContext.cacheDir
                val file = File(cacheDir, "cap_${System.currentTimeMillis()}.png")
                
                // "screencap -p /path/to/file"
                val cmd = if (mUseRoot) {
                    arrayOf("su", "-c", "screencap -p ${file.absolutePath}")
                } else {
                    arrayOf("screencap", "-p", file.absolutePath)
                }
                
                process = Runtime.getRuntime().exec(cmd)
                process.waitFor()
                
                if (file.exists() && file.length() > 0) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = computeSampleSize() }
                    bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
                    file.delete()
                    mFailCount = 0
                } else {
                    val err = process.errorStream.bufferedReader().use { it.readText() }
                    Log.w(TAG, "File capture failed (root=$mUseRoot). Stderr: $err")
                    mFailCount++
                }
            } else {
                // Stdout capture
                val baseCmd = if (mUseRoot) "su -c screencap" else "screencap"
                val cmd = if (mUseRawScreencap) baseCmd else "$baseCmd -p"
                
                process = Runtime.getRuntime().exec(cmd)
                
                val inputStream = process.inputStream
                val buffer = ByteArrayOutputStream()
                val temp = ByteArray(8192)
                var read: Int
                while (inputStream.read(temp).also { read = it } != -1) {
                    buffer.write(temp, 0, read)
                }
                val data = buffer.toByteArray()
                
                val err = process.errorStream.bufferedReader().use { it.readText() }
                process.waitFor()

                if (data.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = computeSampleSize() }
                    bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    
                    if (bitmap == null) {
                        // Check for RAW data fallback
                        if (data.size > 12) {
                            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                            val w = bb.int
                            val h = bb.int
                            val f = bb.int
                            val pixelSize = 4
                            val expectedDataSize = w * h * pixelSize
                            
                            if (w in 100..4096 && h in 100..4096 && (data.size - 12) >= expectedDataSize) {
                                if (mUseRawScreencap) Log.d(TAG, "Raw frame: ${w}x${h}, format=$f")
                                else Log.w(TAG, "Detected raw frame despite -p flag: ${w}x${h}, format=$f")
                                
                                try {
                                    val rawBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    val pixelBuf = ByteBuffer.wrap(data, 12, expectedDataSize)
                                    rawBitmap.copyPixelsFromBuffer(pixelBuf)
                                    
                                    if (mOptions.captureQuality < h) {
                                        val scale = mOptions.captureQuality.toFloat() / h.toFloat()
                                        val newW = (w * scale).toInt()
                                        val newH = (h * scale).toInt()
                                        val scaled = Bitmap.createScaledBitmap(rawBitmap, newW, newH, true)
                                        rawBitmap.recycle()
                                        bitmap = scaled
                                    } else {
                                        bitmap = rawBitmap
                                    }
                                    mFailCount = 0
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse raw bitmap: ${e.message}")
                                }
                            }
                        }
                        
                        if (bitmap == null) {
                            mFailCount++
                            // Log unexpected format
                             val headerHex = data.take(16).joinToString(" ") { "%02X".format(it) }
                             Log.e(TAG, "decodeByteArray failed (root=$mUseRoot). Header: $headerHex")
                        }
                    } else {
                        mFailCount = 0
                    }
                } else {
                    Log.w(TAG, "screencap stdout empty (root=$mUseRoot). Stderr: $err")
                    mFailCount++
                }
            }

            if (bitmap != null) {
                processBitmap(bitmap)
                bitmap.recycle()
            } else {
                // Adaptive fallback strategy
                if (mFailCount > 3) {
                    if (!mUseRawScreencap && !mUseFileMode) {
                        mUseRawScreencap = true
                        Log.w(TAG, "Switching to RAW stdout mode")
                        mFailCount = 0
                    } else if (mUseRawScreencap && !mUseFileMode) {
                        mUseFileMode = true
                        Log.w(TAG, "Switching to FILE mode")
                        mFailCount = 0
                    } else if (mUseFileMode && mFailCount > 8) {
                        // All 3 modes exhausted — screencap is completely blocked on this device
                        Log.e(TAG, "All screencap modes failed (root=$mUseRoot). Device likely blocks screencap via SELinux.")
                        mRunning = false
                        mCapturing = false
                        val msg = if (mUseRoot)
                            "Screencap (Root) is blocked by the device. Try ADB Localhost method."
                        else
                            "Screencap (Shell) is blocked by the device. Try ADB Localhost or Accessibility method."
                        onFatalError?.invoke(msg)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "screencap error: ${e.message}")
            mFailCount++
        } finally {
            process?.destroy()
        }
    }

    /**
     * Computes the largest power-of-2 sample size such that the decoded width
     * is still at least [AppOptions.captureQuality] pixels wide.
     */
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
        private const val TAG = "ScreencapEncoder"
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5

        /**
         * Quick probe: check whether the screencap binary is accessible from this process.
         * Run in a background thread before presenting the option to the user.
         */
        fun isAvailable(): Boolean {
            return try {
                val process: java.lang.Process = Runtime.getRuntime().exec("screencap -p")
                val firstByte = process.inputStream.read()
                process.destroy()
                firstByte != -1
            } catch (e: Exception) {
                false
            }
        }
    }
}
