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
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.random.Random

/**
 * Screen capture via the bundled scrcpy server (Apache 2.0).
 *
 * Scrcpy uses SurfaceControl directly (via high-privilege app_process context),
 * bypassing the MediaProjection restrictions that break screenrecord on some devices.
 *
 * Protocol differences by version:
 *   v1.x  – positional args; raw H264 after 68-byte device-info header
 *   v2.x  – key=value args; framed mode: each frame is [8-byte PTS][4-byte len][data]
 *   v3.x  – like v2.x but also requires `scid=XXXXXXXX`; socket name = "scrcpy_XXXXXXXX"
 *
 * Frame packets (v2.x/v3.x without raw_stream):
 *   PTS == -1  →  codec config (SPS/PPS); pass with BUFFER_FLAG_CODEC_CONFIG
 *   PTS >= 0   →  normal frame
 */
class ScrcpyEncoder(
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
    @Volatile private var mRgbBuffer: ByteArray? = null

    /** Each entry = one complete H264 frame + metadata. */
    private data class Frame(val data: ByteArray, val pts: Long, val codecFlags: Int)

    private val mFrameQueue = ArrayBlockingQueue<Frame>(64)
    private var mSupervisorThread: Thread? = null

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
    fun resumeRecording() { if (!mRunning) startCapture() }
    @Suppress("UNUSED_PARAMETER") fun setOrientation(o: Int) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Version detection — scan binary AndroidManifest.xml for versionName
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the scrcpy server version string by:
     * 1. Checking META-INF/MANIFEST.MF for "Scrcpy-Version:" entry (old approach).
     * 2. Scanning binary AndroidManifest.xml for UTF-16LE encoded semver strings.
     */
    private fun detectVersion(): String? {
        try {
            ZipInputStream(mContext.assets.open(ASSET_NAME)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "META-INF/MANIFEST.MF" -> {
                            val lines = zis.bufferedReader().readLines()
                            val v = lines.find { it.startsWith("Scrcpy-Version:") }
                                ?.substringAfter(":")?.trim()
                            if (v != null) {
                                Log.i(TAG, "Version from MANIFEST.MF: $v")
                                return v
                            }
                        }
                        "AndroidManifest.xml" -> {
                            val data = zis.readBytes()
                            val v = scanBinaryManifestForVersion(data)
                            if (v != null) {
                                Log.i(TAG, "Version from binary manifest: $v")
                                return v
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version detection failed: ${e.message}")
        }
        return null
    }

    /**
     * Scans an Android binary XML (AXML) byte array for UTF-16LE strings that look
     * like a semantic version (e.g. "3.3.4"). The versionName attribute is stored
     * as a plain string in the AXML string pool and is the only semver-formatted
     * string in a typical scrcpy server APK.
     */
    private fun scanBinaryManifestForVersion(data: ByteArray): String? {
        val semver = Regex("^\\d+\\.\\d+(\\.\\d+)*$")
        var i = 0
        while (i < data.size - 4) {
            // UTF-16LE: each char is [lo, 0x00]; look for ASCII digit + null byte
            if (data[i].toInt() in 0x30..0x39 && data[i + 1] == 0.toByte()) {
                val sb = StringBuilder()
                var j = i
                while (j + 1 < data.size) {
                    val lo = data[j].toInt() and 0xFF
                    val hi = data[j + 1].toInt() and 0xFF
                    if (hi != 0) break
                    val c = lo.toChar()
                    if (!c.isDigit() && c != '.') break
                    sb.append(c)
                    j += 2
                }
                val s = sb.toString()
                if (s.contains('.') && semver.matches(s)) {
                    return s
                }
            }
            i++
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCapture() {
        mRunning = true
        mCapturing = true
        mFrameQueue.clear()
        mSupervisorThread = Thread({
            var errors = 0
            while (mRunning) {
                val ok = runSession()
                if (!mRunning) break
                if (ok) {
                    errors = 0
                    Log.i(TAG, "scrcpy session ended normally, restarting…")
                    Thread.sleep(500)
                } else {
                    if (++errors >= MAX_SESSION_ERRORS) {
                        Log.e(TAG, "Too many consecutive errors, giving up")
                        mCapturing = false
                        break
                    }
                    Thread.sleep(3000)
                }
                mFrameQueue.clear()
            }
        }, "scrcpy-supervisor").also { it.isDaemon = true; it.start() }
    }

    private fun runSession(): Boolean {
        var dadb: Dadb? = null
        var shellStream: dadb.AdbStream? = null
        var videoStream: dadb.AdbStream? = null
        var decoder: MediaCodec? = null
        var codecInThread: Thread? = null
        var codecOutThread: Thread? = null
        var cleanExit = false
        val framesDecoded = AtomicInteger(0)
        var bytesReceived = 0L
        val sessionActive = java.util.concurrent.atomic.AtomicBoolean(true)
        val watchdogTriggered = AtomicBoolean(false)

        try {
            // ── 1. Detect version ─────────────────────────────────────────
            val version = detectVersion()
            val major = version?.split(".")?.firstOrNull()?.toIntOrNull() ?: 1
            Log.i(TAG, "scrcpy-server version: ${version ?: "unknown (v1.x assumed)"}")

            // ── 2. ADB connect ────────────────────────────────────────────
            Log.i(TAG, "ADB connecting on port $mAdbPort…")
            val kp = AdbKeyHelper.getKeyPair(mContext)
            dadb = Dadb.create("127.0.0.1", mAdbPort, kp)
            Log.i(TAG, "ADB connected")

            // ── 3. Push server every time (avoid stale/corrupted remote binary) ─
            Log.i(TAG, "Pushing scrcpy-server to device…")
            val tmp = File(mContext.cacheDir, ASSET_NAME)
            mContext.assets.open(ASSET_NAME).use { src -> tmp.outputStream().use { src.copyTo(it) } }
            dadb.push(tmp, REMOTE_PATH)
            tmp.delete()
            Log.i(TAG, "Server pushed to $REMOTE_PATH")

            // ── 4. Build start command ────────────────────────────────────
            // v3.x needs a scid — it determines the abstract socket name.
            val scid: String?
            val socketName: String
            val useFramedMode: Boolean
            val startCmd: String

            when {
                major >= 3 -> {
                    scid = "%08x".format(Random.nextInt() and 0x7FFFFFFF)
                    socketName = "scrcpy_$scid"
                    useFramedMode = true
                    // tunnel_forward=true → server creates LocalServerSocket, we connect to it
                    startCmd = "shell:CLASSPATH=$REMOTE_PATH app_process / " +
                        "com.genymobile.scrcpy.Server $version " +
                        "scid=$scid log_level=info " +
                        "video=true audio=false control=false " +
                        "tunnel_forward=true video_codec=h264 " +
                        "max_size=$mCapW video_bit_rate=2000000 send_dummy_byte=false"
                }
                major >= 2 -> {
                    scid = null
                    socketName = SOCKET_NAME
                    useFramedMode = true
                    startCmd = "shell:CLASSPATH=$REMOTE_PATH app_process / " +
                        "com.genymobile.scrcpy.Server $version " +
                        "log_level=info video=true audio=false control=false " +
                        "tunnel_forward=true video_codec=h264 " +
                        "max_size=$mCapW video_bit_rate=2000000 send_dummy_byte=false"
                }
                else -> {
                    // v1.x positional: max_size bit_rate max_fps tunnel_forward crop send_frame_meta control
                    scid = null
                    socketName = SOCKET_NAME
                    useFramedMode = true
                    startCmd = "shell:CLASSPATH=$REMOTE_PATH app_process / " +
                        "com.genymobile.scrcpy.Server " +
                        "$mCapW 2000000 30 true - true false"
                }
            }
            Log.i(TAG, "Starting: $startCmd")
            shellStream = dadb.open(startCmd)

            // Read server stdout/stderr in background for diagnostics
            val shellOut = shellStream
            Thread({
                try {
                    val reader = shellOut.source.inputStream().bufferedReader()
                    while (mRunning) {
                        val line = reader.readLine() ?: break
                        Log.i(TAG, "[server] $line")
                    }
                } catch (_: Exception) {}
            }, "scrcpy-shell-reader").also { it.isDaemon = true; it.start() }

            // ── 5. Connect to abstract socket via ADB tunnel ──────────────
            // With tunnel_forward=true, the server creates a LocalServerSocket.
            // We connect to it through the ADB daemon (localabstract: service).
            
            // Give server time to start JVM + bind socket
            for (attempt in 1..30) {
                Thread.sleep(200)
                if (!mRunning) return false
                try {
                    videoStream = dadb.open("localabstract:$socketName")
                    Log.i(TAG, "Connected to '$socketName' via ADB (attempt $attempt)")
                    break
                } catch (e: Exception) {
                    if (attempt % 5 == 0) Log.d(TAG, "Socket attempt $attempt: ${e.message}")
                }
            }
            
            if (videoStream == null) {
                 throw Exception(
                    "Could not connect to socket '$socketName' via ADB after 30 attempts.\n" +
                        "Check [server] log lines above for errors."
                )
            }

            val socketInput = videoStream.source.inputStream()
            val lastActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val finalVideoStream = videoStream
            
            // Watchdog: only detect startup stall (no frames at all).
            // Do NOT restart active sessions on temporary no-data periods:
            // scrcpy may legitimately send nothing on static scenes.
            val watchdog = Thread({
                while (mRunning && !cleanExit) {
                    Thread.sleep(2000)
                    val noDataTooLong = System.currentTimeMillis() - lastActivity.get() > 15000
                    if (framesDecoded.get() == 0 && noDataTooLong) {
                        watchdogTriggered.set(true)
                        Log.w(TAG, "Watchdog: scrcpy startup timeout (no first frame in 15s), restarting…")
                        try { finalVideoStream.close() } catch(_:Exception){}
                        break
                    }
                }
            }, "scrcpy-watchdog").also { it.isDaemon = true; it.start() }

            // ── 6. Read fixed metadata ──────────────────────────────────────
            // With send_dummy_byte=false + defaults:
            // - 64 bytes device name (send_device_meta=true)
            // - 12 bytes codec meta: codecId + width + height (send_codec_meta=true)
            val deviceMeta = ByteArray(64)
            readFully(socketInput, deviceMeta)
            val deviceName = String(deviceMeta, Charsets.UTF_8).trimEnd('\u0000')
            lastActivity.set(System.currentTimeMillis())

            val codecMeta = ByteArray(12)
            readFully(socketInput, codecMeta)
            lastActivity.set(System.currentTimeMillis())
            val codecId = ByteBuffer.wrap(codecMeta, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
            val streamW = ByteBuffer.wrap(codecMeta, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt()
            val streamH = ByteBuffer.wrap(codecMeta, 8, 4).order(ByteOrder.BIG_ENDIAN).getInt()
            Log.i(TAG, "Device: '$deviceName', codecId=$codecId, stream=${streamW}x${streamH}")

            // ── 7. Configure MediaCodec ───────────────────────────────────
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
            val finalDecoder = decoder

            // ── Thread 2: codec input ─────────────────────────────────────
            codecInThread = Thread({
                try {
                    while (mRunning && sessionActive.get()) {
                        val frame = mFrameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        var offset = 0
                        while (offset < frame.data.size && mRunning && sessionActive.get()) {
                            val idx = finalDecoder.dequeueInputBuffer(5_000L)
                            if (idx >= 0) {
                                val buf = finalDecoder.getInputBuffer(idx)!!
                                buf.clear()
                                val len = minOf(frame.data.size - offset, buf.remaining())
                                buf.put(frame.data, offset, len)
                                // Use proper PTS and flags for better decoder behaviour
                                val pts = if (frame.pts < 0) 0L else frame.pts
                                finalDecoder.queueInputBuffer(idx, 0, len, pts, frame.codecFlags)
                                offset += len
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (_: IllegalStateException) {
                }
            }, "scrcpy-codec-in").also { it.isDaemon = true; it.start() }

            // ── Thread 3: codec output ────────────────────────────────────
            codecOutThread = Thread({
                val info = MediaCodec.BufferInfo()
                while (mRunning && sessionActive.get()) {
                    try {
                        val idx = finalDecoder.dequeueOutputBuffer(info, 10_000L)
                        when {
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                                Log.i(TAG, "Output format: ${finalDecoder.outputFormat}")
                            idx >= 0 -> {
                                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isConfig && info.size > 0) {
                                    val img = finalDecoder.getOutputImage(idx)
                                    if (img != null) {
                                        try {
                                            if (!sessionActive.get()) break
                                            processImageDirect(img)
                                            val decoded = framesDecoded.incrementAndGet()
                                            if (decoded == 1) Log.i(TAG, "✓ First frame (${img.width}×${img.height})")
                                            if (decoded % 100 == 0) Log.d(TAG, "Frames: $decoded bytes: $bytesReceived queue: ${mFrameQueue.size}")
                                        } finally { img.close() }
                                    }
                                }
                                finalDecoder.releaseOutputBuffer(idx, false)
                            }
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (_: IllegalStateException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Codec-out error f$framesDecoded: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }, "scrcpy-codec-out").also { it.isDaemon = true; it.start() }

            // ── Thread 1 (this): read socket → queue ──────────────────────
            if (useFramedMode) {
                // Framed mode: [8-byte PTS big-endian][4-byte len big-endian][data]
                // High bits in PTS field are flags:
                // bit63 = config packet, bit62 = key-frame, remaining bits = pts
                val metaBuf = ByteArray(12)
                Log.i(TAG, "Reading in framed mode")
                while (mRunning) {
                    readFully(socketInput, metaBuf)
                    val ptsAndFlags = ByteBuffer.wrap(metaBuf, 0, 8).order(ByteOrder.BIG_ENDIAN).getLong()
                    val frameLen = ByteBuffer.wrap(metaBuf, 8, 4).order(ByteOrder.BIG_ENDIAN).getInt()

                    if (frameLen <= 0 || frameLen > MAX_FRAME_BYTES) {
                        Log.e(TAG, "Invalid frame length: $frameLen (ptsAndFlags=$ptsAndFlags) — stream likely corrupt")
                        break
                    }

                    val frameData = ByteArray(frameLen)
                    readFully(socketInput, frameData)
                    lastActivity.set(System.currentTimeMillis())
                    bytesReceived += 12 + frameLen

                    val isConfig = (ptsAndFlags and Long.MIN_VALUE) != 0L
                    val pts = ptsAndFlags and 0x3FFFFFFFFFFFFFFFL
                    val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    // Backpressure: wait for space so we never lose frames
                    while (mRunning && mFrameQueue.size >= 56) Thread.sleep(4)
                    mFrameQueue.offer(Frame(frameData, pts, flags), 200, TimeUnit.MILLISECONDS)
                }
            } else {
                // Raw H264 mode (v1.x without send_frame_meta — fallback)
                val chunk = ByteArray(16384)
                Log.i(TAG, "Reading in raw mode")
                while (mRunning) {
                    while (mRunning && mFrameQueue.size >= 56) Thread.sleep(4)
                    val n = socketInput.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    lastActivity.set(System.currentTimeMillis())
                    bytesReceived += n
                    mFrameQueue.offer(Frame(chunk.copyOf(n), 0L, 0), 200, TimeUnit.MILLISECONDS)
                }
            }
            Log.i(TAG, "Socket EOF after $bytesReceived bytes, ${framesDecoded.get()} frames decoded")
            cleanExit = true
        } catch (e: Exception) {
            if (mRunning && !watchdogTriggered.get()) {
                Log.e(TAG, "Session error: ${e.message}", e)
                onFatalError?.invoke(
                    "Scrcpy error: ${e.message}\n\n" +
                        "Make sure:\n• Wireless Debugging is ON in Developer Options\n" +
                        "• Tap Allow on the TV when asked\n" +
                        "• Port matches (use Test ADB Connection)"
                )
                mRunning = false
            } else if (watchdogTriggered.get()) {
                Log.i(TAG, "Session interrupted by startup watchdog, supervisor will restart")
                cleanExit = true
            }
        } finally {
            // Stop worker threads first, then stop codec to avoid race/crash on shutdown.
            sessionActive.set(false)
            mFrameQueue.clear()
            codecInThread?.interrupt()
            codecOutThread?.interrupt()
            joinQuietly(codecInThread, 300)
            joinQuietly(codecOutThread, 300)
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { videoStream?.close() } catch (_: Exception) {}
            try { shellStream?.close() } catch (_: Exception) {}
            try { dadb?.close() } catch (_: Exception) {}
            if (!mRunning) mCapturing = false
        }
        return cleanExit
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YUV → RGB (zero Bitmap allocations)
    // ─────────────────────────────────────────────────────────────────────────

    private fun processImageDirect(image: Image) {
        val w = image.width; val h = image.height
        if (w <= 0 || h <= 0 || image.planes.size < 3) return
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        val yRS = yP.rowStride; val yPS = yP.pixelStride
        val uvRS = uP.rowStride; val uvPS = uP.pixelStride
        val targetW = mOptions.captureQuality.coerceIn(64, w)
        val step = max(1, w / targetW)
        val sw = (w / step).coerceAtLeast(1); val sh = (h / step).coerceAtLeast(1)
        if (mOptions.useAverageColor) {
            sendAvgDirect(yBuf, uBuf, vBuf, step, sw, sh, yRS, yPS, uvRS, uvPS)
        } else {
            sendRgbDirect(yBuf, uBuf, vBuf, step, sw, sh, yRS, yPS, uvRS, uvPS)
        }
    }

    private fun sendRgbDirect(
        yBuf: java.nio.ByteBuffer, uBuf: java.nio.ByteBuffer, vBuf: java.nio.ByteBuffer,
        step: Int, sw: Int, sh: Int, yRS: Int, yPS: Int, uvRS: Int, uvPS: Int
    ) {
        val rgbSize = sw * sh * 3
        val existing = mRgbBuffer
        val rgb = if (existing != null && existing.size >= rgbSize) existing else ByteArray(rgbSize).also { mRgbBuffer = it }
        var dst = 0
        for (sy in 0 until sh) {
            val srcY = sy * step; val uvRow = srcY / 2
            for (sx in 0 until sw) {
                val srcX = sx * step
                val y = yBuf.get(srcY * yRS + srcX * yPS).toInt() and 0xFF
                val uvOff = uvRow * uvRS + (srcX / 2) * uvPS
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
        step: Int, sw: Int, sh: Int, yRS: Int, yPS: Int, uvRS: Int, uvPS: Int
    ) {
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var cnt = 0
        for (sy in 0 until sh) {
            val srcY = sy * step; val uvRow = srcY / 2
            for (sx in 0 until sw) {
                val srcX = sx * step
                val y = yBuf.get(srcY * yRS + srcX * yPS).toInt() and 0xFF
                val uvOff = uvRow * uvRS + (srcX / 2) * uvPS
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

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false; mCapturing = false
        mFrameQueue.clear()
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
        private const val TAG = "ScrcpyEncoder"
        private const val ASSET_NAME = "scrcpy-server"
        private const val REMOTE_PATH = "/data/local/tmp/scrcpy-server"
        private const val SOCKET_NAME = "scrcpy"
        private const val PREFS_NAME = "scrcpy_prefs"
        private const val PREF_PUSHED_VERSION = "pushed_version"
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5
        private const val MAX_SESSION_ERRORS = 3
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024  // 4 MB sanity limit

        private fun joinQuietly(thread: Thread?, timeoutMs: Long) {
            if (thread == null) return
            try {
                thread.join(timeoutMs)
            } catch (_: Exception) {
            }
        }

        fun readFully(stream: InputStream, buf: ByteArray, startOffset: Int = 0) {
            var off = startOffset
            while (off < buf.size) {
                val n = stream.read(buf, off, buf.size - off)
                if (n < 0) throw Exception("Stream ended prematurely (read $off/${buf.size} bytes)")
                off += n
            }
        }
    }
}
