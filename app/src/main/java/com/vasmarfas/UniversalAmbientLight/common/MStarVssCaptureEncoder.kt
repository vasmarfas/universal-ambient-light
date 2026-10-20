package com.vasmarfas.UniversalAmbientLight.common

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Build
import java.util.concurrent.locks.ReentrantLock
import com.vasmarfas.UniversalAmbientLight.R
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Аппаратный захват MStar через VSS/DIP с правами root.
 *
 * Нативный процесс передаёт кадры: ширина и высота (u32, little-endian),
 * затем пиксели RGB24. Вызовы драйвера не выполняются в главном потоке Android.
 * ADB и MediaProjection не используются, кадры не записываются на диск.
 * Временные ошибки захвата повторяются; переполнение буфера завершает процесс.
 */
class MStarVssCaptureEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mOptions: AppOptions,
    private val mOnFatalError: ((String) -> Unit)? = null,
) : CaptureBackend {
    @Volatile private var mRunning = false
    @Volatile private var mCapturing = false
    @Volatile private var mProcess: java.lang.Process? = null
    @Volatile private var mNativePid: AtomicInteger? = null
    @Volatile private var mProcessStop: StopTicket? = null

    // Одна реальная очистка на экземпляр процесса; остальные вызывающие ждут её завершения.
    private class StopTicket {
        val started = AtomicBoolean(false)
        val pidReady = CountDownLatch(1)
        val finished = CountDownLatch(1)
    }
    private var mWorker: HandlerThread? = null
    private var mHandler: Handler? = null
    @Volatile private var mSession = 0
    private val mBorders = BorderProcessor()

    init { startWorker() }

    @Synchronized
    private fun startWorker() {
        if (mRunning && mWorker != null) return
        mRunning = true
        mCapturing = false
        mSession++
        val currentSession = mSession
        val thread = HandlerThread("MStar-VSS", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        mWorker = thread
        mHandler = Handler(thread.looper).also { it.post { runCapture(currentSession) } }
    }

    private fun params(): List<String> {
        // Физическое разрешение панели может отличаться от разрешения интерфейса Android (1920×1080).
        @SuppressLint("PrivateApi") // OEM panel dimensions; guarded reflection with DisplayManager fallback.
        fun panelSize(property: String): Pair<Int, Int>? {
            val value = try {
                val systemProperties = Class.forName("android.os.SystemProperties")
                val get = systemProperties.getMethod("get", String::class.java)
                get.invoke(null, property) as? String
            } catch (_: Exception) {
                // Скрытый SystemProperties API может отсутствовать на OEM-прошивке.
                // В этом случае используем разрешение из DisplayManager.
                null
            } ?: return null
            val parts = value.trim().lowercase().split("x")
            if (parts.size != 2) return null
            val w = parts[0].trim().toIntOrNull() ?: return null
            val h = parts[1].trim().toIntOrNull() ?: return null
            return if (w in 1..16384 && h in 1..16384) w to h else null
        }
        val display = mContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val mode = display.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val (sw, sh) = panelSize("vendor.display-size")
            ?: panelSize("sys.display-size")
            ?: ((mode?.physicalWidth ?: 1920).coerceAtLeast(1) to
                (mode?.physicalHeight ?: 1080).coerceAtLeast(1))
        val width = mOptions.captureQuality.coerceAtLeast(1)
        val ow = width.coerceAtMost(sw)
        val oh = (sh.toLong() * ow / sw).toInt().coerceAtLeast(1)
        return listOf("0", "0", sw.toString(), sh.toString(), ow.toString(), oh.toString(),
            mOptions.frameRate.coerceAtLeast(1).toString())
    }

    private fun binary(): File {
        val destination = File(mContext.filesDir, BINARY)
        // Сверяем извлечённый файл с APK: не перезаписываем бинарник без изменения версии.
        val zipPath = mContext.applicationInfo.sourceDir
        val abi = "armeabi-v7a" // Библиотеки производителя на этой платформе 32-разрядные
        java.util.zip.ZipFile(zipPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/lib$BINARY.so")
                ?: throw IOException("MStar native executable missing in APK")
            fun currentBinaryMatches(): Boolean {
                if (!destination.isFile || destination.length() != entry.size) return false
                val crc = java.util.zip.CRC32()
                destination.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val length = stream.read(buffer)
                        if (length < 0) break
                        crc.update(buffer, 0, length)
                    }
                }
                return crc.value == entry.crc
            }
            if (!currentBinaryMatches()) {
                val temporary = File(mContext.filesDir, "$BINARY.new")
                try {
                    zip.getInputStream(entry).use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!temporary.renameTo(destination))
                        throw IOException("Cannot update MStar native executable")
                } finally {
                    temporary.delete()
                }
            }
        }
        if (!destination.setExecutable(true, false)) throw IOException("Cannot chmod MStar native executable")
        return destination
    }

    // Запрос остановки не блокирует главный поток Android.
    private fun requestStop(proc: java.lang.Process) {
        try {
            proc.outputStream.write(1)
            proc.outputStream.flush()
        } catch (_: IOException) { /* Процесс мог уже завершиться. */ }
    }

    // После запроса остановки проверяем именно нативный PID, даже если su уже вышел.
    // Проверка /proc/<pid>/exe перед каждым сигналом защищает от повторного использования PID.
    // Состояние Z означает, что процесс уже не выполняется и не удерживает VSS.
    private fun ensureNativeStopped(pid: Int) {
        if (pid <= 1) {
            Log.w(TAG, "Native VSS PID unavailable; cannot confirm stop")
            return
        }
        val command = "pid=$pid; " +
            "for attempt in 1 2 3 4 5; do " +
            "[ -r /proc/\$pid/stat ] || exit 0; " +
            "read -r stat < /proc/\$pid/stat || exit 0; " +
            "set -- \$stat; " +
            "case \"\$3\" in Z|X) exit 0;; esac; " +
            "exe=\$(readlink /proc/\$pid/exe 2>/dev/null); " +
            "case \"\$exe\" in '${mContext.filesDir.absolutePath}/$BINARY'|" +
            "'${mContext.filesDir.absolutePath}/$BINARY (deleted)') ;; " +
            "*) echo \"VSS PID identity mismatch\" >&2; exit 3;; esac; " +
            "case \$attempt in 2) kill -TERM \$pid;; 4) kill -KILL \$pid;; esac; " +
            "sleep 0.1; " +
            "done; " +
            "echo \"VSS native process did not exit\" >&2; exit 4"
        try {
            val check = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            if (!check.waitFor(2500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for native VSS PID=$pid")
                check.destroyForcibly()
            } else if (check.exitValue() != 0) {
                Log.w(TAG, "Could not confirm native VSS stop PID=$pid, exit=" + check.exitValue())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "VSS stop interrupted, PID=$pid")
        } catch (e: Exception) {
            // OEM shell и Magisk su могут завершиться разными исключениями,
            // проверка PID не должна останавливать поток очистки.
            Log.w(TAG, "VSS PID verification failed, PID=$pid", e)
        }
    }

    // Не запускаем вторую очистку из finally: она дожидается первой, прежде чем
    // освобождать sCaptureLock и разрешать новую аппаратную сессию.
    private fun stopProcess(proc: java.lang.Process, pidHolder: AtomicInteger?, stop: StopTicket) {
        if (!stop.started.compareAndSet(false, true)) {
            try {
                stop.finished.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "VSS cleanup wait interrupted", e)
            }
            return
        }
        try {
            finishProcess(proc, pidHolder, stop)
        } finally {
            stop.finished.countDown()
        }
    }

    // Полная остановка выполняется единственный раз, вне главного потока Android.
    private fun finishProcess(proc: java.lang.Process, pidHolder: AtomicInteger?, stop: StopTicket) {
        Log.i(TAG, "Stopping MStar VSS, PID=" + (pidHolder?.get() ?: -1))
        requestStop(proc)
        try {
            // stderr читается отдельно: при очень быстрой остановке он может ещё
            // не успеть передать MSTAR_PID. Подождём PID короткое ограниченное время
            // на потоке очистки, НЕ на UI-потоке, прежде чем проверять /proc.
            proc.waitFor(250, TimeUnit.MILLISECONDS)
            if ((pidHolder?.get() ?: -1) <= 1 && proc.isAlive) {
                stop.pidReady.await(750, TimeUnit.MILLISECONDS)
            }
            val pid = pidHolder?.get() ?: -1
            if (pid > 1) {
                ensureNativeStopped(pid)
            } else {
                Log.w(TAG, "VSS PID unavailable; stopping su")
            }
            // После завершения нативного сервера даём оболочке время забрать
            // статус дочернего процесса, прежде чем принудительно завершать su.
            if (proc.isAlive && !proc.waitFor(1000, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Root shell did not finish after native VSS stop")
                proc.destroy()
                if (!proc.waitFor(250, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            proc.destroyForcibly()
        } finally {
            try { proc.outputStream.close() } catch (_: IOException) {
                // Поток уже мог закрыться после завершения дочернего процесса.
            }
        }
    }

    private fun runCapture(expectedSession: Int) {
        // Один аппаратный VSS-поток на экземпляр приложения, включая завершение
        // старой сессии перед стартом следующей.
        sCaptureLock.lock()
        var ownProcess: java.lang.Process? = null
        var ownPid: AtomicInteger? = null
        var ownStop: StopTicket? = null
        try {
            if (!mRunning || mSession != expectedSession) return
            val args = params()
            val expectedWidth = args[4].toInt()
            val expectedHeight = args[5].toInt()
            val bin = binary()
            val command = "LD_LIBRARY_PATH=/vendor/lib:/system/lib '${bin.absolutePath}' ${args.joinToString(" ")}"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            ownProcess = proc
            val pidHolder = AtomicInteger(-1)
            ownPid = pidHolder
            val stop = StopTicket()
            ownStop = stop
            Thread({
                try {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        if (line.startsWith("MSTAR_PID=")) {
                            line.removePrefix("MSTAR_PID=").toIntOrNull()?.let { pid ->
                                if (pid > 1) {
                                    pidHolder.set(pid)
                                    stop.pidReady.countDown()
                                    Log.i(TAG, "MStar VSS native PID=$pid")
                                }
                            }
                        } else if (line.isNotBlank()) Log.w(TAG, "[VSS] $line")
                    }
                } catch (_: IOException) {
                    // Закрытие stderr при остановке сервера является штатным завершением чтения.
                }
            }, "MStar-stderr").apply { isDaemon = true }.start()
            // Регистрация под тем же монитором, что и stopRecording:
            // исключает гонку между проверкой сессии и сохранением процесса.
            val registered = synchronized(this) {
                if (!mRunning || mSession != expectedSession) false
                else {
                    mProcess = proc
                    mNativePid = pidHolder
                    mProcessStop = stop
                    true
                }
            }
            if (!registered) {
                // finally сам выполнит остановку; отдельный вызов дал бы дубль.
                return
            }

            val input = DataInputStream(proc.inputStream)
            val header = ByteArray(8)
            var rgbBuffer: ByteArray? = null
            while (mRunning && mSession == expectedSession) {
                input.readFully(header)
                val width = leInt(header, 0)
                val height = leInt(header, 4)
                // Принимаем только запрошенную геометрию, чтобы повреждённый заголовок
                // не привёл к выделению огромного массива или рассинхронизации потока.
                if (width != expectedWidth || height != expectedHeight ||
                    width.toLong() * height * 3L > Int.MAX_VALUE)
                    throw IOException("Invalid MStar frame header $width x $height (expected $expectedWidth x $expectedHeight)")
                // UAL синхронно копирует кадр в HyperionThread или FrameDelayLine.
                // Поэтому после возврата sendFrame исходный RGB-массив можно
                // безопасно перезаписать следующим кадром без новой аллокации.
                val rgbSize = width * height * 3
                var rgb = rgbBuffer
                if (rgb == null || rgb.size != rgbSize) {
                    rgb = ByteArray(rgbSize)
                    rgbBuffer = rgb
                    Log.i(TAG, "MStar RGB buffer allocated: $width x $height, $rgbSize bytes")
                }
                input.readFully(rgb)
                if (!mRunning || mSession != expectedSession) break
                ColorProcessor.processRgbData(rgb, mOptions)
                val crop = mBorders.applyForEncoder(rgb, width, height, mOptions)
                mListener.sendFrame(crop.rgb, crop.width, crop.height)
                // Уведомляем UAL только после успешно отправленного первого кадра.
                if (!mCapturing) { mCapturing = true; mListener.sendStatus(true) }
            }
            if (mRunning && mSession == expectedSession) throw EOFException("MStar capture process stopped")
        } catch (e: Exception) {
            // Граница аппаратного потока: ошибки Java, root shell и OEM-библиотеки
            // приводят к одной процедуре завершения захвата и освобождения VSS.
            if (mRunning && mSession == expectedSession) {
                Log.e(TAG, "MStar capture stopped", e)
                mOnFatalError?.invoke(
                    mContext.getString(R.string.error_mstar_capture_stopped, e.message ?: "unknown")
                )
            }
        } finally {
            // Дожидаемся единственной очистки, включая асинхронную из stopRecording.
            val ticket = ownStop
            if (ticket != null) ownProcess?.let { stopProcess(it, ownPid, ticket) }
            if (mSession == expectedSession) {
                mCapturing = false
                mRunning = false
                if (mProcess === ownProcess) { mProcess = null; mNativePid = null; mProcessStop = null }
                mListener.sendStatus(false)
            }
            sCaptureLock.unlock()
        }
    }

    private fun leInt(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 255) or
        ((buffer[offset + 1].toInt() and 255) shl 8) or
        ((buffer[offset + 2].toInt() and 255) shl 16) or
        ((buffer[offset + 3].toInt() and 255) shl 24)

    override fun isCapturing(): Boolean = mCapturing
    override fun sendStatus() = mListener.sendStatus(mCapturing)
    override fun clearLights() = mListener.clear()

    @Synchronized
    override fun stopRecording() {
        mSession++
        mRunning = false
        mCapturing = false
        val oldProcess = mProcess
        val oldPid = mNativePid
        val oldStop = mProcessStop
        mProcess = null
        mNativePid = null
        mProcessStop = null
        if (oldProcess != null && oldStop != null) {
            Thread({ stopProcess(oldProcess, oldPid, oldStop) }, "MStar-stop").apply {
                isDaemon = true
            }.start()
        }
        mHandler?.removeCallbacksAndMessages(null)
        mWorker?.quitSafely()
        mWorker = null
        mHandler = null
    }

    override fun resumeRecording() {
        // Возобновление по явной команде или после ожидания; обработка ошибок ограничена нативным кодом.
        startWorker()
    }

    override fun setOrientation(orientation: Int) { /* Аппаратное масштабирование уже ориентировано по экрану. */ }

    companion object {
        private const val TAG = "MStarVssCaptureEncoder"
        private const val BINARY = "mstar_vss_server"
        private val sCaptureLock = ReentrantLock(true)

        @Volatile private var sCachedAvailable: Boolean? = null
        private val sCheckStarted = AtomicBoolean(false)

        fun isAvailable(): Boolean {
            sCachedAvailable?.let { return it }
            if (!Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a")) return false
            if (sCheckStarted.compareAndSet(false, true)) {
                Thread({
                    val available = try {
                        val command = "test -f /vendor/lib/libmtal.so && " +
                            "for symbol in MTAL_Init MTVSS_Init MTVSS_CaptureOne MTVSS_DisconnectEx; do " +
                            "grep -aq \"\$symbol\" /vendor/lib/libmtal.so || exit 1; done"
                        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                        val completed = proc.waitFor(3, TimeUnit.SECONDS)
                        if (!completed) proc.destroyForcibly()
                        completed && proc.exitValue() == 0
                    } catch (e: Exception) {
                        Log.w(TAG, "VSS availability check failed", e)
                        false
                    }
                    sCachedAvailable = available
                }, "MStar-availability").apply { isDaemon = true }.start()
            }
            return false
        }
    }
}
