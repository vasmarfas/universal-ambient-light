package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AdbKeyHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AdbPortResolver
import com.vasmarfas.UniversalAmbientLight.common.util.AppAdbConnectionManager
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import dadb.Dadb
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Захват с низкой задержкой через `screenrecord --output-format=h264`, поток идёт по ADB.
 *
 * Устройство — три потока и очередь ограниченного размера, чтобы не терять данные:
 *
 *   Поток 1 (readThread)    : поток ADB → mDataQueue
 *   Поток 2 (codecInThread) : mDataQueue → входные буферы MediaCodec (с блокировкой, без потерь)
 *   Поток 3 (codecOutThread): декодированный YUV → прямая конвертация в RGB → Hyperion
 *
 * Главная оптимизация — ни одной аллокации Bitmap на кадр: плоскости YUV читаются прямо
 * в переиспользуемый буфер, поэтому сборщик мусора не нагружается.
 *
 * Сессия перезапускается сама, когда screenrecord завершается (на старых Android у него
 * стоит ограничение в 180 секунд).
 */
class ScreenrecordEncoder(
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

    // Переиспользуется между кадрами — в горячем пути аллокаций нет
    @Volatile
    private var mRgbBuffer: ByteArray? = null
    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()

    // Очередь ограниченного размера: поток чтения пишет, поток входа кодека читает.
    // 128 кусков по 16 КБ — около 2 МБ отставания, дальше включается обратное давление.
    private val mDataQueue = ArrayBlockingQueue<ByteArray>(128)

    private var mSupervisorThread: Thread? = null

    // Текущий ADB-поток сессии: interrupt() чтение из сокета не разблокирует, поэтому
    // stopInternal закрывает поток напрямую, и супервизор выходит из read().
    @Volatile
    private var mCurrentShell: AdbShell? = null

    // Снимаем в 480p: для подсветки детализации достаточно, а нагрузка на кодек куда меньше
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
    // Жизненный цикл
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCapture() {
        mRunning = true
        mCapturing = true
        mDataQueue.clear()
        mSupervisorThread = Thread({
            var sessionErrors = 0
            try {
                while (mRunning) {
                    val ok = runSession()
                    if (!mRunning) break
                    if (ok) {
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
            } catch (_: InterruptedException) {
                Log.i(TAG, "Supervisor interrupted, stopping")
            } finally {
                // Как бы супервизор ни закончился, статус обязан стать честным: иначе после
                // «giving up» isCapturing() вечно отвечал бы true, а resumeRecording() —
                // no-op из-за mRunning.
                mRunning = false
                mCapturing = false
            }
        }, "screenrecord-supervisor").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Проводит одну сессию screenrecord.
     * @return true, если сессия закончилась штатно (EOF потока), false при ошибке.
     */
    private fun runSession(): Boolean {
        var shell: AdbShell? = null
        var decoder: MediaCodec? = null
        var codecInThread: Thread? = null
        var codecOutThread: Thread? = null
        var cleanExit = false
        val watchdogFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val codecFailureFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val hasDecodedFrame = java.util.concurrent.atomic.AtomicBoolean(false)
        val framesDecoded = java.util.concurrent.atomic.AtomicInteger(0)
        val bytesReceived = java.util.concurrent.atomic.AtomicLong(0L)

        try {
            // Компромиссный битрейт: артефактов меньше, чем на 500k, а нагрузка всё ещё небольшая.
            val cmd = "shell:screenrecord --output-format=h264 --size ${mCapW}x${mCapH}" +
                    " --bit-rate 1200000 -"
            Log.i(TAG, "ADB connecting (api=${Build.VERSION.SDK_INT}, port=$mAdbPort): $cmd")
            val openedShell = openAdbShell(cmd)
            shell = openedShell
            mCurrentShell = openedShell
            Log.i(TAG, "ADB stream open")

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

            val lastDataActivity =
                java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val lastDecodeActivity =
                java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val finalDecoder = decoder

            // ── Поток 2: вход кодека ───────────────────────────────────────
            codecInThread = Thread({
                try {
                    while (mRunning) {
                        val chunk = mDataQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        var offset = 0
                        while (offset < chunk.size && mRunning) {
                            val idx = finalDecoder.dequeueInputBuffer(5_000L)
                            if (idx >= 0) {
                                // Буфер по валидному индексу пропадает только если декодер уже
                                // разваливается — тогда бросаем кадр и ждём следующий.
                                val buf = finalDecoder.getInputBuffer(idx) ?: break
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
                        try {
                            openedShell.close()
                        } catch (_: Exception) {
                            // Причина сбоя уже залогирована выше; сессия всё равно
                            // пересоздаётся, поэтому закрытие здесь best-effort.
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }, "screenrecord-codec-in").also { it.isDaemon = true; it.start() }

            // ── Поток 3: выход кодека ──────────────────────────────────────
            codecOutThread = Thread({
                val info = MediaCodec.BufferInfo()
                try {
                    while (mRunning) {
                        val idx = finalDecoder.dequeueOutputBuffer(info, 10_000L)
                        when {
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                                Log.i(TAG, "Output format changed: ${finalDecoder.outputFormat}")

                            idx >= 0 -> {
                                val isConfig =
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isConfig && info.size > 0) {
                                    val img = finalDecoder.getOutputImage(idx)
                                    if (img != null) {
                                        try {
                                            processImageDirect(img)
                                            val decoded = framesDecoded.incrementAndGet()
                                            hasDecodedFrame.set(true)
                                            lastDecodeActivity.set(System.currentTimeMillis())
                                            if (decoded == 1) Log.i(
                                                TAG,
                                                "✓ First frame decoded (${img.width}×${img.height})"
                                            )
                                            if (decoded % 100 == 0) Log.d(
                                                TAG,
                                                "Frames: $decoded, bytes in: ${bytesReceived.get()}, queue: ${mDataQueue.size}"
                                            )
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
                        try {
                            openedShell.close()
                        } catch (_: Exception) {
                            // Причина сбоя уже залогирована выше; сессия всё равно
                            // пересоздаётся, поэтому закрытие здесь best-effort.
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }, "screenrecord-codec-out").also { it.isDaemon = true; it.start() }

            // ── Поток 1 (текущий): поток ADB → очередь ────────────────────
            val inputStream = openedShell.input
            val chunk = ByteArray(16384)

            // Правила сторожа:
            // - на старте: перезапуск, если первый кадр так и не появился
            // - в работе: перезапуск только при долгом отсутствии входных байт
            // Перезапускать по одной лишь паузе в декодировании нельзя: на части устройств и сцен это ложные срабатывания.
            Thread({
                while (mRunning && !cleanExit) {
                    Thread.sleep(2000)
                    val now = System.currentTimeMillis()
                    val startupStall =
                        !hasDecodedFrame.get() && (now - lastDataActivity.get() > 20000)
                    val hardInputStall =
                        hasDecodedFrame.get() && (now - lastDataActivity.get() > 45000)
                    if (startupStall || hardInputStall) {
                        if (startupStall) {
                            Log.w(
                                TAG,
                                "Watchdog: startup stall (no first frame for 20s), restarting session…"
                            )
                        } else {
                            Log.w(TAG, "Watchdog: no input data for 45s, restarting session…")
                        }
                        watchdogFlag.set(true)
                        try {
                            openedShell.close()
                        } catch (_: Exception) {
                            // Причина сбоя уже залогирована выше; сессия всё равно
                            // пересоздаётся, поэтому закрытие здесь best-effort.
                        }
                        break
                    }
                }
            }, "screenrecord-watchdog").also { it.isDaemon = true; it.start() }

            // Логируем первые байты, чтобы убедиться, что это действительно H264 (начинается с 0x00 0x00 0x00 0x01)
            val firstRead = inputStream.read(chunk)
            if (firstRead > 0) {
                val now = System.currentTimeMillis()
                lastDataActivity.set(now)
                lastDecodeActivity.set(now)
                val hex = chunk.take(minOf(firstRead, 8)).joinToString(" ") { "%02X".format(it) }
                Log.i(TAG, "First $firstRead bytes: $hex  (H264 Annex B starts with 00 00 00 01)")
                bytesReceived.addAndGet(firstRead.toLong())
                mDataQueue.put(chunk.copyOf(firstRead))
            } else {
                Log.e(
                    TAG,
                    "screenrecord returned no data (firstRead=$firstRead). Command not supported?"
                )
            }

            while (mRunning) {
                if (codecFailureFlag.get()) {
                    Log.w(TAG, "Codec thread failure detected, ending current session")
                    break
                }
                // Обратное давление: ждём места в очереди, а не сбрасываем её.
                // Сброс ломает выравнивание потока H264 и даёт артефакты, а блокировка здесь
                // естественным образом придерживает и сам screenrecord через ADB.
                while (mRunning && mDataQueue.size >= 96) {
                    Thread.sleep(8)
                }
                val n = inputStream.read(chunk)
                if (n < 0) {
                    Log.i(
                        TAG,
                        "ADB stream EOF after ${bytesReceived.get()} bytes, ${framesDecoded.get()} frames decoded"
                    )
                    break
                }
                if (n == 0) continue
                lastDataActivity.set(System.currentTimeMillis())
                bytesReceived.addAndGet(n.toLong())
                mDataQueue.put(chunk.copyOf(n))
            }
            cleanExit = true
        } catch (e: AdbPairingRequiredException) {
            Log.w(TAG, "ADB pairing required")
            if (mRunning) {
                onFatalError?.invoke(mContext.getString(R.string.error_adb_pairing_required))
                mRunning = false
            }
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
            mCurrentShell = null
            mDataQueue.clear()
            // Потоки кодека живут по while(mRunning) и при перезапуске сессии сами не
            // выйдут — закрываем ADB-поток и ждём их, иначе старый поток входа заберёт
            // из общей очереди первые куски новой сессии, а release() ниже освободил бы
            // кодек прямо у них под руками.
            // Всё это — разбор уже останавливаемой сессии: любой ресурс мог быть закрыт
            // раньше нас (потоками кодека или упавшим ADB), и это не ошибка.
            try {
                shell?.close()
            } catch (_: Exception) {
            }
            // Поток входа выходит по interrupt (poll очереди прерываем), и только после
            // join он гарантированно не утащит куски следующей сессии. Поток выхода
            // из dequeueOutputBuffer прерыванием не выбить — его выбивает stop() кодека
            // через IllegalStateException, поэтому его join идёт после stop().
            codecInThread?.interrupt()
            codecOutThread?.interrupt()
            try {
                codecInThread?.join(300)
            } catch (_: InterruptedException) {
            }
            // release() отдельно от stop(), как в ScrcpyEncoder: иначе декодер уходит
            // в финализатор, который на части ТВ виснет и роняет процесс.
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                codecOutThread?.join(300)
            } catch (_: InterruptedException) {
            }
            if (!wasRunning) mCapturing = false
        }
        return cleanExit
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Транспорт ADB: libadb (TLS и сопряжение) на Android 11+, dadb (RSA) на более старых
    // ─────────────────────────────────────────────────────────────────────────

    private interface AdbShell {
        val input: java.io.InputStream
        fun close()
    }

    private fun openAdbShell(cmd: String): AdbShell {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var mgr: AppAdbConnectionManager? = null
            try {
                val m = AppAdbConnectionManager.getInstance(mContext)
                mgr = m
                if (!m.isConnected) {
                    // autoConnect находит TLS-порт через mDNS — вручную порт указывать не нужно.
                    // Бросает AdbPairingRequiredException, если ключ ещё не сопряжён.
                    val auto = try {
                        m.autoConnect(mContext, 8000)
                    } catch (e: AdbPairingRequiredException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "autoConnect failed: ${e.message}")
                        false
                    }
                    // Если поиск не удался, берём порт, введённый вручную.
                    if (!auto && mAdbPort > 0) m.connect("127.0.0.1", mAdbPort)
                }
                val stream = m.openStream(cmd)
                return object : AdbShell {
                    override val input: java.io.InputStream = stream.openInputStream()
                    override fun close() {
                        // Закрытие идемпотентно: поток мог быть уже оборван обрывом ADB.
                        try {
                            stream.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: AdbPairingRequiredException) {
                throw e
            } catch (e: Throwable) {
                // Ловим в том числе NoClassDefFoundError: пусть обработчик сессии разберётся штатно.
                try {
                    mgr?.disconnect()
                } catch (_: Throwable) {
                    // Соединение и так не поднялось — гасим всё, что успело создаться.
                }
                throw java.io.IOException("ADB connect failed: ${e.message}", e)
            }
        } else {
            val kp = AdbKeyHelper.getKeyPair(mContext)
            // Эта ветка работает только на Android 10 и ниже, где настроенный RSA-порт ещё жив.
            val port = AdbPortResolver.resolveForDadb(mContext, mAdbPort)
            val d = Dadb.create("127.0.0.1", port, kp)
            val stream = d.open(cmd)
            return object : AdbShell {
                override val input: java.io.InputStream = stream.source.inputStream()
                override fun close() {
                    // Закрытие идемпотентно: поток и соединение могли отвалиться раньше.
                    try {
                        stream.close()
                    } catch (_: Exception) {
                    }
                    try {
                        d.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YUV → RGB (без единой аллокации Bitmap)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Переводит Image в формате YUV_420_888 в RGB и отправляет в Hyperion.
     * Читает плоскости YUV напрямую, без промежуточного Bitmap.
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

        // Параметры раскладки плоскостей
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride    // always 1
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride   // 1 for I420, 2 for NV12

        // Прореживание: captureQuality задаёт целевую ширину в пикселях
        val targetW = mOptions.captureQuality.coerceIn(64, w)
        val step = max(1, w / targetW)
        val sw = (w / step).coerceAtLeast(1)
        val sh = (h / step).coerceAtLeast(1)

        if (mOptions.useAverageColor) {
            sendAvgDirect(
                yBuf,
                uBuf,
                vBuf,
                step,
                sw,
                sh,
                yRowStride,
                yPixStride,
                uvRowStride,
                uvPixStride
            )
        } else {
            sendRgbDirect(
                yBuf,
                uBuf,
                vBuf,
                step,
                sw,
                sh,
                yRowStride,
                yPixStride,
                uvRowStride,
                uvPixStride
            )
        }
    }

    private fun sendRgbDirect(
        yBuf: java.nio.ByteBuffer, uBuf: java.nio.ByteBuffer, vBuf: java.nio.ByteBuffer,
        step: Int, sw: Int, sh: Int,
        yRowStride: Int, yPixStride: Int, uvRowStride: Int, uvPixStride: Int,
    ) {
        val rgbSize = sw * sh * 3
        // Работаем через локальную ссылку — безопасно, даже если stopInternal обнулит mRgbBuffer параллельно.
        // Размер сверяем точно: Hyperion сериализует весь массив, и буфер длиннее кадра
        // ушёл бы на сервер с хвостом от прежнего разрешения.
        val rgb: ByteArray
        val existing = mRgbBuffer
        rgb =
            if (existing != null && existing.size == rgbSize) existing else ByteArray(rgbSize).also {
                mRgbBuffer = it
            }
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
        yBuf: java.nio.ByteBuffer, uBuf: java.nio.ByteBuffer, vBuf: java.nio.ByteBuffer,
        step: Int, sw: Int, sh: Int,
        yRowStride: Int, yPixStride: Int, uvRowStride: Int, uvPixStride: Int,
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
                val y = yBuf.get(srcY * yRowStride + srcX * yPixStride).toInt() and 0xFF
                val uvOff = uvRow * uvRowStride + (srcX / 2) * uvPixStride
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
    // Остановка
    // ─────────────────────────────────────────────────────────────────────────

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false
        mCapturing = false
        mDataQueue.clear()
        // Супервизор может сидеть в блокирующем чтении ADB-сокета, которое interrupt не
        // разбудит, — закрываем поток, и read() возвращается с ошибкой.
        try {
            mCurrentShell?.close()
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
        private const val TAG = "ScreenrecordEncoder"
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5
        private const val MAX_SESSION_ERRORS = 3
    }
}
