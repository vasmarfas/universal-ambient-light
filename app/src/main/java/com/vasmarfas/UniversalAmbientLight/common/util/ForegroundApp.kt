package com.vasmarfas.UniversalAmbientLight.common.util

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * Какое приложение сейчас на экране. Нужно задержке по приложениям: подсветка работает
 * в фоне, и спросить у системы «кто сверху» можно только через статистику использования.
 *
 * Без доступа к статистике (выдаётся в настройках ТВ или через ADB) система молча отдаёт
 * пустые события - тогда [current] возвращает null, и действует общая задержка.
 */
class ForegroundApp(context: Context) {

    private val mUsage = context.getSystemService(UsageStatsManager::class.java)
    private var mPackage: String? = null
    private var mCheckedUntil = 0L

    /** Последнее выведенное на экран приложение. Блокирует на единицы миллисекунд. */
    @SuppressLint("InlinedApi")
    fun current(): String? {
        val usage = mUsage ?: return null
        val now = System.currentTimeMillis()
        // Первый запрос смотрит далеко назад: фильм могли включить задолго до подсветки
        val from = if (mCheckedUntil == 0L) now - FIRST_LOOKBACK_MS else mCheckedUntil
        try {
            val events = usage.queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // ACTIVITY_RESUMED появилась в Android 10, на 8–9 то же значение называлось
                // MOVE_TO_FOREGROUND; константа подставляется при компиляции
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    mPackage = event.packageName
                }
            }
        } catch (e: RuntimeException) {
            // Часть прошивок бросает SecurityException вместо пустой выборки без доступа
            Log.w(TAG, "Usage events unavailable: ${e.message}")
            return null
        }
        mCheckedUntil = now
        return mPackage
    }

    companion object {
        private const val TAG = "ForegroundApp"
        private const val FIRST_LOOKBACK_MS = 6 * 60 * 60 * 1000L

        /**
         * Есть ли доступ к статистике. Без него система отдаёт пустой список, а своя
         * статистика у работающего приложения есть всегда - пустота значит отказ.
         */
        fun hasAccess(context: Context): Boolean {
            val usage = context.getSystemService(UsageStatsManager::class.java) ?: return false
            val now = System.currentTimeMillis()
            return try {
                usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DAY_MS, now).isNotEmpty()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Usage stats unavailable: ${e.message}")
                false
            }
        }

        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
