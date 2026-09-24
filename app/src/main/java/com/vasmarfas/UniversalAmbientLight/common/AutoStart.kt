package com.vasmarfas.UniversalAmbientLight.common

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.DeviceProfile
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/**
 * Автозапуск подсветки: после загрузки, обновления приложения и пробуждения телевизора.
 *
 * Поднимаем подсветку, только если автозапуск включён и она работала, когда ТВ выключили:
 * остановленная пользователем подсветка сама не включается.
 *
 * Пробуждение ловит сторож — неповторяющийся будильник без пробуждения процессора. Пока ТВ
 * спит, такой будильник не срабатывает, а после включения просроченный приходит сразу. Так
 * подсветка возвращается и там, где прошивка на время сна выгружает все сторонние
 * приложения: SCREEN_ON слушать уже некому, а будильник живёт в системе.
 */
object AutoStart {
    private const val TAG = "AutoStart"

    const val ACTION_WATCHDOG = "com.vasmarfas.UniversalAmbientLight.action.WATCHDOG"

    private const val WATCHDOG_INTERVAL_MS = 3 * 60 * 1000L

    fun shouldResume(context: Context): Boolean {
        val prefs = Preferences(context)
        return prefs.getBoolean(R.string.pref_key_boot) &&
                prefs.getBoolean(R.string.pref_key_lighting_was_active)
    }

    /** Загрузка, быстрый старт ТВ или обновление приложения. */
    fun onBoot(context: Context, reason: String) {
        if (!shouldResume(context)) return
        Log.i(TAG, "Resuming lighting after $reason")
        resume(context)
        scheduleWatchdog(context)
    }

    /** Срабатывание сторожа: подсветка должна работать, а сервиса нет — поднимаем. */
    fun onWatchdog(context: Context) {
        if (!shouldResume(context)) {
            cancelWatchdog(context)
            return
        }
        scheduleWatchdog(context)
        if (ScreenGrabberService.sInstanceRunning) return
        // Экран погашен — ТВ в дежурном режиме, будить ленту незачем
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == false) return
        Log.i(TAG, "Watchdog: capture service is gone, resuming")
        resume(context)
    }

    fun resume(context: Context): CaptureLauncher.Result? {
        if (!shouldResume(context) || ScreenGrabberService.sInstanceRunning) return null
        val result = CaptureLauncher.start(context)
        if (result.outcome == CaptureLauncher.Outcome.FAILED) {
            Log.w(TAG, "Auto start failed: ${result.message}")
        }
        return result
    }

    /**
     * Сторож нужен только телевизору: на телефоне подсветку из фона не вернуть без диалога
     * записи экрана, а всплывающий сам по себе диалог хуже остановившейся подсветки.
     */
    fun scheduleWatchdog(context: Context) {
        if (!DeviceProfile.isTv(context) || !shouldResume(context)) return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            alarms.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                watchdogIntent(context)
            )
        } catch (e: Exception) {
            // Часть прошивок режет будильники фоновым приложениям — остаются загрузка и SCREEN_ON
            Log.w(TAG, "Cannot schedule watchdog: ${e.message}")
        }
    }

    fun cancelWatchdog(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarms.cancel(watchdogIntent(context))
    }

    private fun watchdogIntent(context: Context): PendingIntent {
        val intent = Intent(context, BootReceiver::class.java).setAction(ACTION_WATCHDOG)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
