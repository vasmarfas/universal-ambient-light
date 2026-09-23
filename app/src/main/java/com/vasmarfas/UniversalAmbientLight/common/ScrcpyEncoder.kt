package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AdbKeyHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AdbPortResolver
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
 * Захват экрана через встроенный сервер scrcpy (Apache 2.0).
 *
 * Scrcpy обращается к SurfaceControl напрямую (из привилегированного контекста
 * app_process) и обходит ограничения MediaProjection, из-за которых на части устройств
 * не работает screenrecord.
 *
 * Различия протокола по версиям:
 *   v1.x  – позиционные аргументы; сырой H264 после 68-байтового заголовка с
 *           информацией об устройстве
 *   v2.x  – аргументы вида key=value; кадровый режим: каждый кадр это
 *           [8 байт PTS][4 байта длина][данные]
 *   v3.x  – как v2.x, но дополнительно требует `scid=XXXXXXXX`; имя сокета —
 *           "scrcpy_XXXXXXXX"
 *   v4.x  – как v3.x, но изменился формат передачи:
 *             • заголовок видео (codec meta) сократился с 12 до 4 байт (остался
 *               только codecId, ширина и высота убраны). `send_codec_meta`
 *               переименован в `send_stream_meta` (оба по умолчанию true — мы их
 *               не передаём).
 *             • в поток кадров добавлен 12-байтовый пакет «session meta»: он идёт
 *               перед пакетом конфигурации и повторяется при каждом изменении
 *               размера — [flags(4)][width(4)][height(4)] с текущим размером видео.
 *             • сдвинулись биты флагов кадра: bit63=session, bit62=config,
 *               bit61=ключевой кадр (было bit63=config, bit62=ключевой кадр).
 *
 * Пакеты кадров (кадровый режим, без raw_stream):
 *   стоит флаг config  →  конфигурация кодека (SPS/PPS), отдаём с BUFFER_FLAG_CODEC_CONFIG
 *   стоит флаг session →  (только v4) метаданные, полезной нагрузки за ними нет
 *   иначе              →  обычный кадр
 */
class ScrcpyEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
    private val mAdbPort: Int = 5555,
    private val onFatalError: ((String) -> Unit)? = null,
) : CaptureBackend {
    @Volatile
    private var mRunning = false
    @Volatile
    private var mCapturing = false
    @Volatile
    private var mRgbBuffer: ByteArray? = null

    /** Одна запись — один целый кадр H264 вместе с метаданными. */
    private data class Frame(val data: ByteArray, val pts: Long, val codecFlags: Int)

    private val mFrameQueue = ArrayBlockingQueue<Frame>(64)
    private var mSupervisorThread: Thread? = null

    // ADB-соединение текущей сессии: stopInternal закрывает его, чтобы выбить супервизор
    // из блокирующего чтения, которое interrupt не прерывает.
    @Volatile
    private var mCurrentDadb: Dadb? = null
    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()

    val mCapW: Int
    val mCapH: Int

    init {
        val w = 480
        // Часть прошивок на старте отдаёт нулевые метрики — деление уводило бы высоту в
        // Int.MAX_VALUE; пока метрик нет, считаем экран 16:9
        var h = if (mScreenWidth > 0 && mScreenHeight > 0) {
            (w * mScreenHeight.toFloat() / mScreenWidth.toFloat()).toInt()
        } else {
            270
        }
        if (h % 2 != 0) h++
        mCapW = w
        mCapH = h
        startCapture()
    }

    override fun isCapturing(): Boolean = mCapturing
    override fun sendStatus() = mListener.sendStatus(mCapturing)
    override fun clearLights() {
        Thread { repeat(CLEAR_FRAMES) { Thread.sleep(CLEAR_DELAY_MS); mListener.clear() } }.start()
    }

    override fun stopRecording() = stopInternal(disconnect = true)
    fun stopRecordingKeepConnection() = stopInternal(disconnect = false)
    override fun resumeRecording() {
        if (!mRunning) startCapture()
    }

    @Suppress("UNUSED_PARAMETER")
    override fun setOrientation(orientation: Int) {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Определение версии — ищем versionName в бинарном AndroidManifest.xml
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Достаёт версию сервера scrcpy: сначала пробует запись "Scrcpy-Version:" в
     * META-INF/MANIFEST.MF (так это работало раньше), затем ищет semver-строку в
     * кодировке UTF-16LE в бинарном AndroidManifest.xml.
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
     * Ищет в байтах бинарного XML (AXML) строки UTF-16LE, похожие на semver-версию
     * (например, "3.3.4"). Атрибут versionName лежит в пуле строк AXML обычной
     * строкой и в типичном APK сервера scrcpy оказывается единственным значением
     * такого формата.
     */
    private fun scanBinaryManifestForVersion(data: ByteArray): String? {
        val semver = Regex("^\\d+\\.\\d+(\\.\\d+)*$")
        var i = 0
        while (i < data.size - 4) {
            // UTF-16LE: каждый символ это [младший байт, 0x00] — ищем цифру ASCII с нулём
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
    // Жизненный цикл
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCapture() {
        mRunning = true
        mCapturing = true
        mFrameQueue.clear()
        mSupervisorThread = Thread({
            var errors = 0
            try {
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
            } catch (_: InterruptedException) {
                Log.i(TAG, "Supervisor interrupted, stopping")
            } finally {
                // После «giving up» статус обязан стать честным, иначе resumeRecording()
                // навсегда останется no-op из-за mRunning=true.
                mRunning = false
                mCapturing = false
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
        val sessionActive = AtomicBoolean(true)
        val watchdogTriggered = AtomicBoolean(false)

        try {
            // ── Определяем версию сервера ─────────────────────────────────
            val version = detectVersion()
            val major = version?.split(".")?.firstOrNull()?.toIntOrNull() ?: 1
            Log.i(TAG, "scrcpy-server version: ${version ?: "unknown (v1.x assumed)"}")

            // ── Подключаемся по ADB ───────────────────────────────────────
            val kp = AdbKeyHelper.getKeyPair(mContext)
            // dadb не умеет работать с TLS-портом беспроводной отладки Android 11+, да и
            // порт этот всё равно меняется. Подбираем обычный порт, пригодный для dadb
            // (при необходимости переводим adbd в tcpip:5555 через TLS-соединение).
            val adbPort = AdbPortResolver.resolveForDadb(mContext, mAdbPort)
            Log.i(TAG, "ADB connecting on port $adbPort (configured $mAdbPort)…")
            dadb = Dadb.create("127.0.0.1", adbPort, kp)
            mCurrentDadb = dadb
            Log.i(TAG, "ADB connected")

            // ── Заливаем сервер заново, чтобы не нарваться на битую копию ─
            Log.i(TAG, "Pushing scrcpy-server to device…")
            val tmp = File(mContext.cacheDir, ASSET_NAME)
            try {
                mContext.assets.open(ASSET_NAME)
                    .use { src -> tmp.outputStream().use { src.copyTo(it) } }
                dadb.push(tmp, REMOTE_PATH)
            } finally {
                tmp.delete()
            }
            Log.i(TAG, "Server pushed to $REMOTE_PATH")

            // ── Собираем команду запуска ──────────────────────────────────
            // v3.x требует scid — от него зависит имя абстрактного сокета.
            val scid: String?
            val socketName: String
            val useFramedMode: Boolean
            val startCmd: String

            when {
                major >= 3 -> {
                    scid = "%08x".format(Random.nextInt() and 0x7FFFFFFF)
                    socketName = "scrcpy_$scid"
                    useFramedMode = true
                    // tunnel_forward=true → сервер поднимает LocalServerSocket, мы к нему подключаемся
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
                    // Позиционные аргументы v1.x: max_size bit_rate max_fps tunnel_forward crop send_frame_meta control
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

            // Фоном перечитываем stdout/stderr сервера — нужно для диагностики
            val shellOut = shellStream
            Thread({
                try {
                    val reader = shellOut.source.inputStream().bufferedReader()
                    while (mRunning) {
                        val line = reader.readLine() ?: break
                        Log.i(TAG, "[server] $line")
                    }
                } catch (_: Exception) {
                    // Это только пересылка логов scrcpy-сервера в logcat: обрыв чтения
                    // означает конец сессии и на захват кадров никак не влияет.
                }
            }, "scrcpy-shell-reader").also { it.isDaemon = true; it.start() }

            // ── Подключаемся к абстрактному сокету через туннель ADB ──────
            // При tunnel_forward=true сервер поднимает LocalServerSocket, а мы идём к нему
            // через демон ADB (сервис localabstract:).

            // Даём серверу время поднять JVM и занять сокет
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

            // Сторож ловит только зависание на старте, когда кадров нет вообще.
            // Рабочую сессию из-за пауз в данных перезапускать нельзя: на статичной
            // картинке scrcpy законно может не присылать ничего.
            Thread({
                while (mRunning && !cleanExit) {
                    Thread.sleep(2000)
                    val noDataTooLong = System.currentTimeMillis() - lastActivity.get() > 15000
                    if (framesDecoded.get() == 0 && noDataTooLong) {
                        watchdogTriggered.set(true)
                        Log.w(
                            TAG,
                            "Watchdog: scrcpy startup timeout (no first frame in 15s), restarting…"
                        )
                        try {
                            finalVideoStream.close()
                        } catch (_: Exception) {
                            // Причина рестарта уже залогирована; поток закрываем best-effort.
                        }
                        break
                    }
                }
            }, "scrcpy-watchdog").also { it.isDaemon = true; it.start() }

            // ── Читаем метаданные фиксированной длины ────────────────────
            // При send_dummy_byte=false и умолчаниях (send_device_meta / send_stream_meta = true):
            // - 64 байта имени устройства
            // - заголовок потока (codec meta):
            //     v2.x/v3.x -> 12 байт: codecId + ширина + высота
            //     v4.x      ->  4 байта: только codecId (размер приходит позже
            //                   пакетом «session meta» внутри потока кадров)
            val deviceMeta = ByteArray(64)
            readFully(socketInput, deviceMeta)
            val deviceName = String(deviceMeta, Charsets.UTF_8).trimEnd('\u0000')
            lastActivity.set(System.currentTimeMillis())

            val codecMeta = ByteArray(if (major >= 4) 4 else 12)
            readFully(socketInput, codecMeta)
            lastActivity.set(System.currentTimeMillis())
            val codecId = ByteBuffer.wrap(codecMeta, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
            if (major >= 4) {
                Log.i(
                    TAG,
                    "Device: '$deviceName', codecId=$codecId (v4 stream header; size via session meta)"
                )
            } else {
                val streamW = ByteBuffer.wrap(codecMeta, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt()
                val streamH = ByteBuffer.wrap(codecMeta, 8, 4).order(ByteOrder.BIG_ENDIAN).getInt()
                Log.i(TAG, "Device: '$deviceName', codecId=$codecId, stream=${streamW}x${streamH}")
            }

            // ── Настраиваем MediaCodec ───────────────────────────────────
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

            // ── Поток 2: вход кодека ─────────────────────────────────────
            codecInThread = Thread({
                try {
                    while (mRunning && sessionActive.get()) {
                        val frame = mFrameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        var offset = 0
                        while (offset < frame.data.size && mRunning && sessionActive.get()) {
                            val idx = finalDecoder.dequeueInputBuffer(5_000L)
                            if (idx >= 0) {
                                // Буфер по валидному индексу пропадает только если декодер уже
                                // разваливается — тогда бросаем кадр и ждём следующий.
                                val buf = finalDecoder.getInputBuffer(idx) ?: break
                                buf.clear()
                                val len = minOf(frame.data.size - offset, buf.remaining())
                                buf.put(frame.data, offset, len)
                                // Корректные PTS и флаги — декодер ведёт себя стабильнее
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

            // ── Поток 3: выход кодека ────────────────────────────────────
            codecOutThread = Thread({
                val info = MediaCodec.BufferInfo()
                while (mRunning && sessionActive.get()) {
                    try {
                        val idx = finalDecoder.dequeueOutputBuffer(info, 10_000L)
                        when {
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                                Log.i(TAG, "Output format: ${finalDecoder.outputFormat}")

                            idx >= 0 -> {
                                val isConfig =
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isConfig && info.size > 0) {
                                    val img = finalDecoder.getOutputImage(idx)
                                    if (img != null) {
                                        try {
                                            if (!sessionActive.get()) break
                                            processImageDirect(img)
                                            val decoded = framesDecoded.incrementAndGet()
                                            if (decoded == 1) Log.i(
                                                TAG,
                                                "✓ First frame (${img.width}×${img.height})"
                                            )
                                            if (decoded % 100 == 0) Log.d(
                                                TAG,
                                                "Frames: $decoded bytes: $bytesReceived queue: ${mFrameQueue.size}"
                                            )
                                        } finally {
                                            img.close()
                                        }
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
                        Log.w(
                            TAG,
                            "Codec-out error f$framesDecoded: ${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }, "scrcpy-codec-out").also { it.isDaemon = true; it.start() }

            // ── Поток 1 (текущий): сокет → очередь ───────────────────────
            if (useFramedMode) {
                // Кадровый режим: [8 байт PTS+флаги big-endian][4 байта длина big-endian][данные]
                // Биты флагов в поле PTS зависят от версии протокола:
                //   v2.x/v3.x -> bit63 = config, bit62 = ключевой кадр
                //   v4.x      -> bit63 = session meta, bit62 = config, bit61 = ключевой кадр
                // Пакет «session meta» из v4 не несёт данных: в 8-байтовом поле лежит
                // [flags(4)][width(4)], а в 4-байтовом поле длины — высота.
                val isV4 = major >= 4
                val configMask = if (isV4) (1L shl 62) else Long.MIN_VALUE
                val ptsMask = if (isV4) 0x1FFFFFFFFFFFFFFFL else 0x3FFFFFFFFFFFFFFFL
                val metaBuf = ByteArray(12)
                Log.i(TAG, "Reading in framed mode (v$major.x)")
                while (mRunning) {
                    readFully(socketInput, metaBuf)
                    val ptsAndFlags =
                        ByteBuffer.wrap(metaBuf, 0, 8).order(ByteOrder.BIG_ENDIAN).getLong()
                    val frameLen =
                        ByteBuffer.wrap(metaBuf, 8, 4).order(ByteOrder.BIG_ENDIAN).getInt()

                    // Пакет session meta из v4 (bit63): данных за ним нет, только размер.
                    if (isV4 && (ptsAndFlags and Long.MIN_VALUE) != 0L) {
                        val sessW = (ptsAndFlags and 0xFFFFFFFFL).toInt()
                        val sessH = frameLen
                        Log.i(TAG, "Session meta: ${sessW}x$sessH")
                        lastActivity.set(System.currentTimeMillis())
                        bytesReceived += 12
                        continue
                    }

                    if (frameLen <= 0 || frameLen > MAX_FRAME_BYTES) {
                        Log.e(
                            TAG,
                            "Invalid frame length: $frameLen (ptsAndFlags=$ptsAndFlags) — stream likely corrupt"
                        )
                        break
                    }

                    val frameData = ByteArray(frameLen)
                    readFully(socketInput, frameData)
                    lastActivity.set(System.currentTimeMillis())
                    bytesReceived += 12 + frameLen

                    val isConfig = (ptsAndFlags and configMask) != 0L
                    val pts = ptsAndFlags and ptsMask
                    val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    // Обратное давление: ждём место в очереди, чтобы не терять кадры
                    while (mRunning && mFrameQueue.size >= 56) Thread.sleep(4)
                    mFrameQueue.offer(Frame(frameData, pts, flags), 200, TimeUnit.MILLISECONDS)
                }
            } else {
                // Режим сырого H264 (v1.x без send_frame_meta — запасной путь)
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
            Log.i(
                TAG,
                "Socket EOF after $bytesReceived bytes, ${framesDecoded.get()} frames decoded"
            )
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
            // Сначала останавливаем рабочие потоки и только потом кодек — иначе гонка и падение.
            sessionActive.set(false)
            mFrameQueue.clear()
            codecInThread?.interrupt()
            codecOutThread?.interrupt()
            joinQuietly(codecInThread, 300)
            joinQuietly(codecOutThread, 300)
            // Разбор уже останавливаемой сессии: декодер и потоки ADB могли отвалиться
            // раньше нас, поэтому каждый шаг закрываем best-effort и идём дальше.
            // release() не зависит от stop(): тот бросает на сломанном или не запущенном
            // кодеке, а неосвобождённый декодер освобождает FinalizerDaemon, и на Xiaomi
            // MiTV (Android 10) native_finalize висит дольше 10 с и роняет процесс.
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                videoStream?.close()
            } catch (_: Exception) {
            }
            try {
                shellStream?.close()
            } catch (_: Exception) {
            }
            mCurrentDadb = null
            try {
                dadb?.close()
            } catch (_: Exception) {
            }
            if (!mRunning) mCapturing = false
        }
        return cleanExit
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YUV → RGB (без единой аллокации Bitmap)
    // ─────────────────────────────────────────────────────────────────────────

    private fun processImageDirect(image: Image) {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || image.planes.size < 3) return
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val yRS = yP.rowStride
        val yPS = yP.pixelStride
        val uvRS = uP.rowStride
        val uvPS = uP.pixelStride
        val targetW = mOptions.captureQuality.coerceIn(64, w)
        val step = max(1, w / targetW)
        val sw = (w / step).coerceAtLeast(1)
        val sh = (h / step).coerceAtLeast(1)
        if (mOptions.useAverageColor) {
            sendAvgDirect(yBuf, uBuf, vBuf, step, sw, sh, yRS, yPS, uvRS, uvPS)
        } else {
            sendRgbDirect(yBuf, uBuf, vBuf, step, sw, sh, yRS, yPS, uvRS, uvPS)
        }
    }

    private fun sendRgbDirect(
        yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
        step: Int, sw: Int, sh: Int, yRS: Int, yPS: Int, uvRS: Int, uvPS: Int,
    ) {
        // Размер сверяем точно: Hyperion сериализует весь массив, и буфер длиннее кадра
        // ушёл бы на сервер с хвостом от прежнего разрешения.
        val rgbSize = sw * sh * 3
        val existing = mRgbBuffer
        val rgb =
            if (existing != null && existing.size == rgbSize) existing else ByteArray(rgbSize).also {
                mRgbBuffer = it
            }
        var dst = 0
        for (sy in 0 until sh) {
            val srcY = sy * step
            val uvRow = srcY / 2
            for (sx in 0 until sw) {
                val srcX = sx * step
                val y = yBuf.get(srcY * yRS + srcX * yPS).toInt() and 0xFF
                val uvOff = uvRow * uvRS + (srcX / 2) * uvPS
                val u = uBuf.get(uvOff).toInt() and 0xFF
                val v = vBuf.get(uvOff).toInt() and 0xFF
                val c = y - 16
                val d = u - 128
                val e = v - 128
                rgb[dst++] = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255).toByte()
                rgb[dst++] = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255).toByte()
                rgb[dst++] = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255).toByte()
            }
        }
        ColorProcessor.processRgbData(rgb, mOptions)
        val cropped = mBorderCropper.applyForEncoder(rgb, sw, sh, mOptions)
        mListener.sendFrame(cropped.rgb, cropped.width, cropped.height)
    }

    private fun sendAvgDirect(
        yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
        step: Int, sw: Int, sh: Int, yRS: Int, yPS: Int, uvRS: Int, uvPS: Int,
    ) {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var cnt = 0
        for (sy in 0 until sh) {
            val srcY = sy * step
            val uvRow = srcY / 2
            for (sx in 0 until sw) {
                val srcX = sx * step
                val y = yBuf.get(srcY * yRS + srcX * yPS).toInt() and 0xFF
                val uvOff = uvRow * uvRS + (srcX / 2) * uvPS
                val u = uBuf.get(uvOff).toInt() and 0xFF
                val v = vBuf.get(uvOff).toInt() and 0xFF
                val c = y - 16
                val d = u - 128
                val e = v - 128
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
                mOptions.blackLevel, mOptions.whiteLevel, mOptions.saturation,
                mOptions.brightnessR, mOptions.brightnessG, mOptions.brightnessB,
                mOptions.gammaR, mOptions.gammaG, mOptions.gammaB
            )
            mListener.sendFrame(byteArrayOf(ro.toByte(), go.toByte(), bo.toByte()), 1, 1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false; mCapturing = false
        mFrameQueue.clear()
        // Супервизор может сидеть в блокирующем чтении видеопотока (на статичной картинке
        // scrcpy законно молчит), interrupt его не разбудит — закрываем ADB-соединение,
        // и readFully возвращается с ошибкой.
        try {
            mCurrentDadb?.close()
        } catch (_: Exception) {
        }
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
                // Ждём поток строго ограниченное время; прерывание ожидания не мешает
                // остановке — дальше ресурсы всё равно закрываются.
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
