package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AdbKeyHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import dadb.Dadb
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Low-latency capture using `screenrecord --output-format=h264` streamed via ADB.
 *
 * Architecture — 3 threads with a bounded queue to prevent data loss:
 *
 *   Thread 1 (readThread)   : ADB stream → mDataQueue
 *   Thread 2 (codecInThread): mDataQueue → MediaCodec input buffers (blocking, no drops)
 *   Thread 3 (codecOutThread): MediaCodec decoded YUV → direct RGB conversion → Hyperion
 *
 * Key optimisation: zero per-frame Bitmap allocations.
 * YUV planes are read directly into a reused byte buffer → no GC pressure.
 *
 * Auto-restart when screenrecord exits (it has a 180-second default time limit on older Android).
 */
class ScreenrecordEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
    private val mAdbPort: Int = 5555,
    private val onFatalError: ((String) -> Unit)? = null
) {
    @Volatile private var mRunning = false
    @Volatile private var mCapturing = false

    // Reused across frames — no allocations in the hot path
    @Volatile private var mRgbBuffer: ByteArray? = null

    // Bounded queue: read thread produces, codec-input thread consumes.
    // 128 × 16KB chunks ≈ 2MB max backlog before applying back-pressure.
    private val mDataQueue = ArrayBlockingQueue<ByteArray>(128)

    private var mSupervisorThread: Thread? = null

    // Capture at 480p — enough detail for ambient lighting, far less codec load
    val mCapW: Int
    val mCapH: Int

    init {
        val w = 480
        var h = (w * mScreenHeight.toFloat() / mScreenWidth.toFloat()).toInt()
        if (h % 2 != 0) h++
        mCapW = w
        mCapH = h
        startCapture()
    }

    fun isCapturing(): Boolean = mCapturing
    fun sendStatus() = mListener.sendStatus(mCapturing)

    fun clearLights() {
        Thread { repeat(CLEAR_FRAMES) { Thread.sleep(CLEAR_DELAY_MS); mListener.clear() } }.start()
    }

    fun stopRecording() = stopInternal(disconnect = true)
    fun stopRecordingKeepConnection() = stopInternal(disconnect = false)

    fun resumeRecording() {
        if (!mRunning) startCapture()
    }

    @Suppress("UNUSED_PARAMETER")
    fun setOrientation(o: Int) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCapture() {
        mRunning = true
        mCapturing = true
        mDataQueue.clear()
        mSupervisorThread = Thread({
            var sessionErrors = 0
            while (mRunning) {
                val ok = runSession()
                if (!mRunning) break
                if (ok) {
                    // Normal exit (stream EOF / 3-min limit) — restart immediately
                    sessionErrors = 0
                    Log.i(TAG, "screenrecord ended normally, restarting…")
                    Thread.sleep(300)
                } else {
                    sessionErrors++
                    if (sessionErrors >= MAX_SESSION_ERRORS) {
                        Log.e(TAG, "Too many consecutive errors, giving up")
                        break
                    }
                    Thread.sleep(2000)
                }
                mDataQueue.clear()
            }
        }, "screenrecord-supervisor").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Runs one screenrecord session.
     * @return true if the session ended cleanly (stream EOF), false on error.
     */
    private fun runSession(): Boolean {
        var dadb: Dadb? = null
        var adbStream: dadb.AdbStream? = null
        var decoder: MediaCodec? = null
        var codecInThread: Thread? = null
        var codecOutThread: Thread? = null
        var cleanExit = false
        val watchdogFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val codecFailureFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val hasDecodedFrame = java.util.concurrent.atomic.AtomicBoolean(false)
        val framesDecoded = java.util.concurrent.atomic.AtomicInteger(0)
        var bytesReceived = 0L

        try {
            Log.i(TAG, "ADB connecting port=$mAdbPort…")
            val kp = AdbKeyHelper.getKeyPair(mContext)
            dadb = Dadb.create("127.0.0.1", mAdbPort, kp)
            Log.i(TAG, "ADB connected")

            // Balanced bitrate: fewer compression artifacts than 500k, still lightweight enough.
            val cmd = "shell:screenrecord --output-format=h264 --size ${mCapW}x${mCapH}" +
                    " --bit-rate 1200000 -"
            Log.i(TAG, "Launching: $cmd")
            adbStream = dadb.open(cmd)

            // Configure H.264 decoder
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mCapW, mCapH)
            fmt.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.configure(fmt, null, null, 0)
            decoder.start()
            mCapturing = true
            Log.i(TAG, "MediaCodec started (${mCapW}×${mCapH})")

            val lastDataActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val lastDecodeActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val finalDecoder = decoder

            // ── Thread 2: codec input ──────────────────────────────────────
            codecInThread = Thread({
                try {
                    while (mRunning) {
                        val chunk = mDataQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        var offset = 0
                        while (offset < chunk.size && mRunning) {
                            val idx = finalDecoder.dequeueInputBuffer(5_000L)
                            if (idx >= 0) {
                                val buf = finalDecoder.getInputBuffer(idx)!!
                                buf.clear()
                                val len = minOf(chunk.size - offset, buf.remaining())
                                buf.put(chunk, offset, len)
                                finalDecoder.queueInputBuffer(idx, 0, len, 0, 0)
                                offset += len
                            }
                        }
                    }
                } catch (_: IllegalStateException) {
                    if (mRunning) {
                        codecFailureFlag.set(true)
                        Log.w(TAG, "Codec-in failed (IllegalState), restarting session")
                        try { adbStream.close() } catch (_: Exception) {}
                    }
                } catch (_: InterruptedException) {}
            }, "screenrecord-codec-in").also { it.isDaemon = true; it.start() }

            // ── Thread 3: codec output ────────────────────────────────────
            codecOutThread = Thread({
                val info = MediaCodec.BufferInfo()
                try {
                    while (mRunning) {
                        val idx = finalDecoder.dequeueOutputBuffer(info, 10_000L)
                        when {
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                                Log.i(TAG, "Output format changed: ${finalDecoder.outputFormat}")
                            idx >= 0 -> {
                                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isConfig && info.size > 0) {
                                    val img = finalDecoder.getOutputImage(idx)
                                    if (img != null) {
                                        try {
                                            processImageDirect(img)
                                            val decoded = framesDecoded.incrementAndGet()
                                            hasDecodedFrame.set(true)
                                            lastDecodeActivity.set(System.currentTimeMillis())
                                            if (decoded == 1) Log.i(TAG, "✓ First frame decoded (${img.width}×${img.height})")
                                            if (decoded % 100 == 0) Log.d(TAG, "Frames: $decoded, bytes in: $bytesReceived, queue: ${mDataQueue.size}")
                                        } finally {
                                            img.close()
                                        }
                                    }
                                }
                                finalDecoder.releaseOutputBuffer(idx, false)
                            }
                        }
                    }
                } catch (_: IllegalStateException) {
                    if (mRunning) {
                        codecFailureFlag.set(true)
                        Log.w(TAG, "Codec-out failed (IllegalState), restarting session")
                        try { adbStream.close() } catch (_: Exception) {}
                    }
                } catch (_: InterruptedException) {}
            }, "screenrecord-codec-out").also { it.isDaemon = true; it.start() }

            // ── Thread 1 (this): read ADB stream into queue ───────────────
            val inputStream = adbStream.source.inputStream()
            val chunk = ByteArray(16384)

            // Watchdog policy:
            // - startup: if first frame never appears, restart
            // - runtime: restart only on prolonged lack of input bytes
            // Do NOT restart only on decode-gap; this causes false positives on some devices/scenes.
            val watchdog = Thread({
                while (mRunning && !cleanExit) {
                    Thread.sleep(2000)
                    val now = System.currentTimeMillis()
                    val startupStall = !hasDecodedFrame.get() && (now - lastDataActivity.get() > 20000)
                    val hardInputStall = hasDecodedFrame.get() && (now - lastDataActivity.get() > 45000)
                    if (startupStall || hardInputStall) {
                        if (startupStall) {
                            Log.w(TAG, "Watchdog: startup stall (no first frame for 20s), restarting session…")
                        } else {
                            Log.w(TAG, "Watchdog: no input data for 45s, restarting session…")
                        }
                        watchdogFlag.set(true)
                        try { adbStream.close() } catch (_: Exception) {}
                        break
                    }
                }
            }, "screenrecord-watchdog").also { it.isDaemon = true; it.start() }

            // Log first few bytes to verify this is actually H264 (starts with 0x00 0x00 0x00 0x01)
            val firstRead = inputStream.read(chunk)
            if (firstRead > 0) {
                val now = System.currentTimeMillis()
                lastDataActivity.set(now)
                lastDecodeActivity.set(now)
                val hex = chunk.take(minOf(firstRead, 8)).joinToString(" ") { "%02X".format(it) }
                Log.i(TAG, "First $firstRead bytes: $hex  (H264 Annex B starts with 00 00 00 01)")
                bytesReceived += firstRead
                mDataQueue.put(chunk.copyOf(firstRead))
            } else {
                Log.e(TAG, "screenrecord returned no data (firstRead=$firstRead). Command not supported?")
            }

            while (mRunning) {
                if (codecFailureFlag.get()) {
                    Log.w(TAG, "Codec thread failure detected, ending current session")
                    break
                }
                // Backpressure: wait until queue has room rather than flushing.
                // Flushing breaks H264 stream alignment → artifacts.
                // Blocking here causes ADB to back-pressure screenrecord naturally.
                while (mRunning && mDataQueue.size >= 96) {
                    Thread.sleep(8)
                }
                val n = inputStream.read(chunk)
                if (n < 0) {
                    Log.i(TAG, "ADB stream EOF after $bytesReceived bytes, ${framesDecoded.get()} frames decoded")
                    break
                }
                if (n == 0) continue
                lastDataActivity.set(System.currentTimeMillis())
                bytesReceived += n
                mDataQueue.put(chunk.copyOf(n))
            }
            cleanExit = true
        } catch (e: Exception) {
            if (mRunning && !watchdogFlag.get() && !codecFailureFlag.get()) {
                Log.e(TAG, "Session error: ${e.message}")
                onFatalError?.invoke(
                    "ADB Stream error: ${e.message}\n\n" +
                            "Make sure Wireless Debugging is enabled and the key is authorized (test with \"Test ADB Connection\")."
                )
                mRunning = false
            } else if (codecFailureFlag.get()) {
                Log.i(TAG, "Session interrupted by codec failure, supervisor will restart")
                cleanExit = true
            } else if (watchdogFlag.get()) {
                Log.i(TAG, "Session interrupted by watchdog, supervisor will restart")
                cleanExit = true
            }
        } finally {
            val wasRunning = mRunning
            mDataQueue.clear()
            // Interrupt codec threads first
            codecInThread?.interrupt()
            codecOutThread?.interrupt()
            // Wait for codec threads to notice the interruption / mRunning=false
            try { Thread.sleep(150) } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { adbStream?.close() } catch (_: Exception) {}
            try { dadb?.close() } catch (_: Exception) {}
            if (!wasRunning) mCapturing = false
        }
        return cleanExit
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YUV → RGB  (zero Bitmap allocations)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts a YUV_420_888 Image to RGB and sends to Hyperion.
     * Directly accesses YUV planes — no intermediate Bitmap created.
     */
    private fun processImageDirect(image: Image) {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "Invalid image dimensions: ${w}x${h}")
            return
        }
        if (image.planes.size < 3) {
            Log.w(TAG, "Unexpected plane count: ${image.planes.size}, format=${image.format}")
            return
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        // Plane layout parameters
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride    // always 1
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride   // 1 for I420, 2 for NV12

        // Subsample: captureQuality is the target width in pixels
        val targetW = mOptions.captureQuality.coerceIn(64, w)
        val step = max(1, w / targetW)
        val sw = (w / step).coerceAtLeast(1)
        val sh = (h / step).coerceAtLeast(1)

        if (mOptions.useAverageColor) {
            sendAvgDirect(yBuf, uBuf, vBuf, step, sw, sh, yRowStride, yPixStride, uvRowStride, uvPixStride)
        } else {
            sendRgbDirect(yBuf, uBuf, vBuf, step, sw, sh, yRowStride, yPixStride, uvRowStride, uvPixStride)
        }
    }

    private fun sendRgbDirect(
        yBuf: java.nio.ByteBuffer, uBuf: java.nio.ByteBuffer, vBuf: java.nio.ByteBuffer,
        step: Int, sw: Int, sh: Int,
        yRowStride: Int, yPixStride: Int, uvRowStride: Int, uvPixStride: Int
    ) {
        val rgbSize = sw * sh * 3
        // Use local reference — safe even if stopInternal nulls mRgbBuffer concurrently
        val rgb: ByteArray
        val existing = mRgbBuffer
        rgb = if (existing != null && existing.size >= rgbSize) existing else ByteArray(rgbSize).also { mRgbBuffer = it }
        var dst = 0

        for (sy in 0 until sh) {
            val srcY = sy * step
            val uvRow = srcY / 2
            for (sx in 0 until sw) {
                val srcX = sx * step
                val y = yBuf.get(srcY * yRowStride + srcX * yPixStride).toInt() and 0xFF
                val uvOff = uvRow * uvRowStride + (srcX / 2) * uvPixStride
                val u = uBuf.get(uvOff).toInt() and 0xFF
                val v = vBuf.get(uvOff).toInt() and 0xFF
                val c = y - 16; val d = u - 128; val e = v - 128
                rgb[dst++] = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255).toByte()
                rgb[dst++] = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255).toByte()
                rgb[dst++] = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255).toByte()
            }
        }

        ColorProcessor.processRgbData(rgb, mOptions)
        mListener.sendFrame(rgb, sw, sh)
    }

    private fun sendAvgDirect(
        yBuf: java.nio.ByteBuffer, uBuf: java.nio.ByteBuffer, vBuf: java.nio.ByteBuffer,
        step: Int, sw: Int, sh: Int,
        yRowStride: Int, yPixStride: Int, uvRowStride: Int, uvPixStride: Int
    ) {
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var cnt = 0
        for (sy in 0 until sh) {
            val srcY = sy * step
            val uvRow = srcY / 2
            for (sx in 0 until sw) {
                val srcX = sx * step
                val y = yBuf.get(srcY * yRowStride + srcX * yPixStride).toInt() and 0xFF
                val uvOff = uvRow * uvRowStride + (srcX / 2) * uvPixStride
                val u = uBuf.get(uvOff).toInt() and 0xFF
                val v = vBuf.get(uvOff).toInt() and 0xFF
                val c = y - 16; val d = u - 128; val e = v - 128
                rSum += ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                gSum += ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                bSum += ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                cnt++
            }
        }
        if (cnt > 0) {
            val (ro, go, bo) = ColorProcessor.processColor(
                (rSum / cnt).toInt(), (gSum / cnt).toInt(), (bSum / cnt).toInt(),
                mOptions.brightness, mOptions.contrast,
                mOptions.blackLevel, mOptions.whiteLevel, mOptions.saturation
            )
            mListener.sendFrame(byteArrayOf(ro.toByte(), go.toByte(), bo.toByte()), 1, 1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stop
    // ─────────────────────────────────────────────────────────────────────────

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false
        mCapturing = false
        mDataQueue.clear()
        mSupervisorThread?.interrupt()
        mRgbBuffer = null
        if (disconnect) {
            Thread {
                repeat(CLEAR_FRAMES) { Thread.sleep(CLEAR_DELAY_MS); mListener.clear() }
                mListener.disconnect()
            }.start()
        } else {
            clearLights()
        }
    }

    companion object {
        private const val TAG = "ScreenrecordEncoder"
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5
        private const val MAX_SESSION_ERRORS = 3
    }
}
