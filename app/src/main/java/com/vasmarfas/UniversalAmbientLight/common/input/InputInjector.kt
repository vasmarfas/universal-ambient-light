package com.vasmarfas.UniversalAmbientLight.common.input

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.AdbSetup
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Процесс ввода [InputInjectorCli], поднятый через собственный ADB приложения. Живёт, пока
 * с телефона что-то нажимают, и закрывается после простоя: держать shell-процесс и открытый
 * ADB-поток на приставке без дела незачем.
 */
class InputInjector(private val mContext: Context) {

    private val mLock = Any()
    private var mShell: AdbSetup.Shell? = null
    private var mLastUseMs = 0L
    private val mTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "input-idle") }
    private var mIdleCheck: ScheduledFuture<*>? = null

    val isRunning: Boolean
        get() = synchronized(mLock) { mShell != null }

    /**
     * Отправляет команду, при необходимости подняв процесс. Первый вызов блокирует на время
     * подключения к ADB и старта app_process - на слабой приставке до пары секунд.
     */
    @Throws(IOException::class)
    fun send(command: String) {
        synchronized(mLock) {
            val shell = mShell ?: start()
            try {
                shell.output.write((command + "\n").toByteArray(Charsets.UTF_8))
                shell.output.flush()
            } catch (e: IOException) {
                // Процесс умер (ADB перезапустился, приставка уснула) - следующая команда
                // поднимет его заново
                closeLocked()
                throw e
            }
            mLastUseMs = SystemClock.elapsedRealtime()
        }
    }

    /** Поднимает процесс заранее, чтобы первое нажатие на телефоне не ждало ADB. */
    @Throws(IOException::class)
    fun prepare() {
        synchronized(mLock) {
            if (mShell == null) start()
            mLastUseMs = SystemClock.elapsedRealtime()
        }
    }

    fun close() {
        synchronized(mLock) { closeLocked() }
    }

    fun shutdown() {
        close()
        mTimer.shutdownNow()
    }

    private fun start(): AdbSetup.Shell {
        val apk = mContext.applicationInfo.sourceDir
        val shell = try {
            AdbSetup.openShell(mContext, "CLASSPATH=$apk app_process /system/bin $CLI_CLASS")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // libadb бросает свои исключения, в том числе про несопряжённый ADB, - наружу
            // идёт одно понятное IOException
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
        val reader = shell.input.bufferedReader()
        val ready = readFirstLine(reader)
        if (ready != INJECTOR_READY) {
            shell.close()
            throw IOException(ready ?: "input helper did not start")
        }
        // Вывод процесса читается до конца: иначе при переполнении канала он встал бы на
        // записи очередной ошибки. Конец вывода - процесс умер
        Thread({
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    Log.w(TAG, "helper: $line")
                }
            } catch (_: IOException) {
                // Поток закрыли при остановке - это и есть конец вывода.
            }
            synchronized(mLock) { if (mShell === shell) closeLocked() }
        }, "input-helper-output").apply { isDaemon = true }.start()
        mShell = shell
        scheduleIdleCheck()
        Log.i(TAG, "Input helper started")
        return shell
    }

    /** Первая строка процесса с ограничением по времени: зависший app_process не должен вешать пульт. */
    private fun readFirstLine(reader: BufferedReader): String? {
        val result = arrayOfNulls<String>(1)
        val thread = Thread({
            result[0] = try {
                reader.readLine()
            } catch (e: IOException) {
                e.message
            }
        }, "input-helper-start")
        thread.isDaemon = true
        thread.start()
        thread.join(START_TIMEOUT_MS)
        return result[0]
    }

    private fun scheduleIdleCheck() {
        mIdleCheck?.cancel(false)
        mIdleCheck = mTimer.scheduleWithFixedDelay({
            synchronized(mLock) {
                if (mShell != null && SystemClock.elapsedRealtime() - mLastUseMs > IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "Input helper idle, stopping")
                    closeLocked()
                }
            }
        }, IDLE_TIMEOUT_MS, IDLE_TIMEOUT_MS / 4, TimeUnit.MILLISECONDS)
    }

    private fun closeLocked() {
        val shell = mShell ?: return
        mShell = null
        mIdleCheck?.cancel(false)
        mIdleCheck = null
        try {
            shell.close()
        } catch (e: Exception) {
            // Поток мог закрыться раньше вместе с ADB; закрываем то, что осталось.
            Log.w(TAG, "Closing input helper: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "InputInjector"
        private const val CLI_CLASS = "com.vasmarfas.UniversalAmbientLight.common.input.InputInjectorCli"
        private const val START_TIMEOUT_MS = 10_000L
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
