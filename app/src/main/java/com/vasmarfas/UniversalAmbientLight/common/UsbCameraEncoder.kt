package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.serenegiant.usb.USBMonitor
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import kotlin.math.max
import kotlin.math.min

/**
 * Captures frames from a USB UVC camera via the AUSBC userspace driver (no Android
 * External Camera HAL required), applies perspective correction, and forwards RGB data
 * to the Hyperion/LED controller — a drop-in replacement for CameraEncoder in USB mode.
 *
 * Directly opens the USB device via USBMonitor.openDevice() (no register() call),
 * which avoids the Android 14+ SecurityException:
 *   "One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified"
 * USB permission must be pre-granted by UsbCameraUtil before calling start().
 */
class UsbCameraEncoder(
    private val context: Context,
    private val listener: HyperionThread.HyperionThreadListener,
    private val options: AppOptions,
    corners: FloatArray,
    private val targetVendorId: Int,
    private val targetProductId: Int,
    /** Clockwise rotation applied to each frame before perspective correction: 0, 90, 180, or 270. */
    private val rotation: Int = 0
) {
    private var usbMonitor: USBMonitor? = null
    private var currentCamera: MultiCameraClient.Camera? = null

    @Volatile private var mRunning = false
    @Volatile private var mCapturing = false

    private val mCorners = corners.copyOf()
    private val frameIntervalMs = 1000L / options.frameRate.coerceAtLeast(1)
    @Volatile private var lastFrameTime = 0L

    private val outputWidth: Int
    private val outputHeight: Int

    // Dummy SurfaceTexture used as a headless render target (AUSBC requires a non-null surface)
    private var dummySurfaceTexture: SurfaceTexture? = null

    // Reusable buffers (only accessed from the AUSBC callback thread)
    private var rawFrameBitmap: Bitmap? = null
    private var srcBitmap: Bitmap? = null
    private var correctedBitmap: Bitmap? = null
    private var rgbBuffer: ByteArray? = null
    private var pixelInts: IntArray? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        val q = if (options.captureQuality > 0) options.captureQuality else 128
        outputWidth = max(32, min(q, 512))
        outputHeight = max(32, (outputWidth * 9f / 16f).toInt())
        if (DEBUG) Log.d(TAG, "UsbCameraEncoder init: output=${outputWidth}x${outputHeight}, fps=${options.frameRate}")
    }

    // ======================== Public API ========================

    fun start() {
        if (mRunning) return
        mRunning = true

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.find {
            it.vendorId == targetVendorId && it.productId == targetProductId
        }

        if (device == null) {
            Log.w(TAG, "USB device not found (VID=${"%04X".format(targetVendorId)} PID=${"%04X".format(targetProductId)})")
            mRunning = false
            clearAndDisconnect()
            return
        }

        // Create USBMonitor with a no-op listener. We intentionally skip register() to avoid
        // the Android 14+ SecurityException ("RECEIVER_NOT_EXPORTED required").
        // Since USB permission was already granted by UsbCameraUtil, openDevice() works directly.
        val monitor = USBMonitor(context, NOOP_DEVICE_LISTENER)
        usbMonitor = monitor

        try {
            if (DEBUG) Log.d(TAG, "Opening USB camera via openDevice()")
            val ctrlBlock = monitor.openDevice(device)

            val cam = MultiCameraClient.Camera(context, device)
            cam.setUsbControlBlock(ctrlBlock)
            cam.setCameraStateCallBack(object : ICameraStateCallBack {
                override fun onCameraState(
                    self: MultiCameraClient.Camera,
                    code: ICameraStateCallBack.State,
                    msg: String?
                ) {
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> {
                            if (DEBUG) Log.d(TAG, "USB camera opened")
                            mCapturing = true
                            listener.sendStatus(true)
                        }
                        ICameraStateCallBack.State.CLOSED -> {
                            if (DEBUG) Log.d(TAG, "USB camera closed")
                            mCapturing = false
                        }
                        ICameraStateCallBack.State.ERROR -> {
                            Log.e(TAG, "USB camera error: $msg")
                            mCapturing = false
                        }
                    }
                }
            })

            cam.addPreviewDataCallBack(previewCallback)
            currentCamera = cam

            val request = CameraRequest.Builder()
                .setPreviewWidth(PREVIEW_WIDTH)
                .setPreviewHeight(PREVIEW_HEIGHT)
                .create()

            // AUSBC requires a non-null surface; create a headless SurfaceTexture so the
            // camera has a render target while all actual frame data arrives via previewCallback.
            val st = SurfaceTexture(0).also { it.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT) }
            dummySurfaceTexture = st
            cam.openCamera(st, request)
        } catch (e: SecurityException) {
            Log.e(TAG, "USB camera permission denied", e)
            mRunning = false
            clearAndDisconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open USB camera", e)
            mRunning = false
            clearAndDisconnect()
        }
    }

    fun stopRecording() {
        if (DEBUG) Log.i(TAG, "stopRecording")
        mRunning = false
        mCapturing = false
        currentCamera?.closeCamera()
        currentCamera = null
        usbMonitor?.destroy()
        usbMonitor = null
        dummySurfaceTexture?.release()
        dummySurfaceTexture = null
        clearAndDisconnect()
    }

    fun resumeRecording() {
        if (!mCapturing) start()
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

    @Suppress("UNUSED_PARAMETER")
    fun setOrientation(orientation: Int) {
        // USB cameras don't have a rotation concept like built-in cameras
    }

    // ======================== Preview callback ========================

    private val previewCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
            if (!mRunning || data == null) return
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < frameIntervalMs) return
            lastFrameTime = now

            val previewSize = currentCamera?.getPreviewSize() ?: return
            val width = previewSize.width
            val height = previewSize.height
            if (width <= 0 || height <= 0) return

            try {
                processNv21Frame(data, width, height)
            } catch (e: Exception) {
                if (DEBUG) Log.w(TAG, "Frame processing error", e)
            }
        }
    }

    // ======================== Frame processing ========================

    private fun processNv21Frame(nv21: ByteArray, width: Int, height: Int) {
        val totalPixels = width * height
        if (pixelInts == null || pixelInts!!.size < totalPixels) {
            pixelInts = IntArray(totalPixels)
        }
        nv21ToArgb(nv21, width, height, pixelInts!!)

        if (rotation != 0) {
            // Reuse a raw-frame bitmap to avoid per-frame allocation
            if (rawFrameBitmap == null || rawFrameBitmap!!.width != width || rawFrameBitmap!!.height != height) {
                rawFrameBitmap?.recycle()
                rawFrameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            rawFrameBitmap!!.setPixels(pixelInts!!, 0, width, 0, 0, width, height)

            val rotMatrix = Matrix()
            rotMatrix.setRotate(rotation.toFloat(), width / 2f, height / 2f)
            val rotated = Bitmap.createBitmap(rawFrameBitmap!!, 0, 0, width, height, rotMatrix, true)

            val rw = rotated.width
            val rh = rotated.height
            val rotPixels = IntArray(rw * rh)
            rotated.getPixels(rotPixels, 0, rw, 0, 0, rw, rh)
            rotated.recycle()

            processBitmapPixels(rotPixels, rw, rh)
        } else {
            processBitmapPixels(pixelInts!!, width, height)
        }
    }

    private fun processBitmapPixels(pixels: IntArray, width: Int, height: Int) {
        // 1. Build source Bitmap from pixel array
        if (srcBitmap == null || srcBitmap!!.width != width || srcBitmap!!.height != height) {
            srcBitmap?.recycle()
            srcBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        srcBitmap!!.setPixels(pixels, 0, width, 0, 0, width, height)

        // 2. Perspective correction: map user-defined quad → output rectangle
        val displayPts = floatArrayOf(
            mCorners[0] * width,  mCorners[1] * height,
            mCorners[2] * width,  mCorners[3] * height,
            mCorners[4] * width,  mCorners[5] * height,
            mCorners[6] * width,  mCorners[7] * height
        )
        val dstPts = floatArrayOf(
            0f,                    0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f,                    outputHeight.toFloat()
        )
        val perspectiveMatrix = Matrix()
        perspectiveMatrix.setPolyToPoly(displayPts, 0, dstPts, 0, 4)

        if (correctedBitmap == null ||
            correctedBitmap!!.width != outputWidth ||
            correctedBitmap!!.height != outputHeight
        ) {
            correctedBitmap?.recycle()
            correctedBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(correctedBitmap!!)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(srcBitmap!!, perspectiveMatrix, paint)

        // 3. Extract RGB bytes
        val outPixels = IntArray(outputWidth * outputHeight)
        correctedBitmap!!.getPixels(outPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        val rgbSize = outputWidth * outputHeight * 3
        if (rgbBuffer == null || rgbBuffer!!.size < rgbSize) {
            rgbBuffer = ByteArray(rgbSize)
        }
        var idx = 0
        for (pixel in outPixels) {
            rgbBuffer!![idx++] = ((pixel shr 16) and 0xFF).toByte()
            rgbBuffer!![idx++] = ((pixel shr 8)  and 0xFF).toByte()
            rgbBuffer!![idx++] = (pixel           and 0xFF).toByte()
        }

        // 4. Color processing + send
        ColorProcessor.processRgbData(rgbBuffer!!, options)
        listener.sendFrame(rgbBuffer!!, outputWidth, outputHeight)
    }

    // ======================== NV21 → ARGB conversion ========================

    private fun nv21ToArgb(nv21: ByteArray, width: Int, height: Int, out: IntArray) {
        val frameSize = width * height
        for (j in 0 until height) {
            val uvRow = frameSize + (j / 2) * width
            for (i in 0 until width) {
                val yIndex = j * width + i
                if (yIndex >= nv21.size) return
                val y = ((nv21[yIndex].toInt() and 0xFF) - 16).coerceAtLeast(0)

                val uvIndex = uvRow + (i / 2) * 2
                val v = if (uvIndex < nv21.size) (nv21[uvIndex].toInt() and 0xFF) - 128 else 0
                val u = if (uvIndex + 1 < nv21.size) (nv21[uvIndex + 1].toInt() and 0xFF) - 128 else 0

                val r = clamp((298 * y + 409 * v + 128) shr 8)
                val g = clamp((298 * y - 100 * u - 208 * v + 128) shr 8)
                val b = clamp((298 * y + 516 * u + 128) shr 8)
                out[j * width + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
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
        private const val TAG = "UsbCameraEncoder"
        private const val DEBUG = false
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val CLEAR_FRAMES = 5
        private const val CLEAR_DELAY_MS = 100L

        /** Facing constant used in preferences to indicate AUSBC userspace UVC mode. */
        const val CAMERA_FACING_USB_UVC = 99

        private fun clamp(v: Int) = v.coerceIn(0, 255)

        private fun sleep(ms: Long) {
            try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }

        private val NOOP_DEVICE_LISTENER = object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {}
            override fun onDetach(device: UsbDevice?) {}
            override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {}
            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
            override fun onCancel(device: UsbDevice?) {}
        }
    }
}
