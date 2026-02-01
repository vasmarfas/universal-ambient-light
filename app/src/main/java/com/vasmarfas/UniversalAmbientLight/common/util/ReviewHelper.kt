package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Утилитный класс для управления показом диалога оценки приложения в Google Play
 */
object ReviewHelper {
    private const val TAG = "ReviewHelper"
    
    // Ключи для SharedPreferences
    private const val PREF_KEY_LAST_REVIEW_REQUEST = "last_review_request_time"
    private const val PREF_KEY_LIGHTING_START_COUNT = "lighting_start_count"
    private const val PREF_KEY_REVIEW_DISMISSED = "review_dismissed"
    private const val PREF_KEY_REVIEW_COMPLETED = "review_completed"
    
    // Настройки для показа диалога
    private const val MIN_DAYS_BETWEEN_REQUESTS = 3L // Минимум 3 дня между запросами
    private const val MIN_LIGHTING_STARTS = 5 // Минимум 5 запусков подсветки
    
    /**
     * Увеличивает счетчик запусков подсветки и проверяет, нужно ли показать диалог оценки
     */
    fun onLightingStarted(activity: Activity) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
        
        // Увеличиваем счетчик запусков
        val currentCount = prefs.getInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        val newCount = currentCount + 1
        prefs.edit { putInt(PREF_KEY_LIGHTING_START_COUNT, newCount) }
        
        // Проверяем, нужно ли показать диалог
        if (shouldShowReviewDialog(activity)) {
            requestReview(activity)
        }
    }
    
    /**
     * Проверяет, нужно ли показать диалог оценки
     */
    private fun shouldShowReviewDialog(context: Context): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        
        // Если пользователь уже оставил отзыв, больше не показываем
        val reviewCompleted = prefs.getBoolean(PREF_KEY_REVIEW_COMPLETED, false)
        if (reviewCompleted) {
            return false
        }
        
        // Проверяем время последнего запроса (не используем флаг dismissed, так как
        // Google Play может не показать диалог из-за квот, и мы не знаем, был ли он реально показан)
        val lastRequestTime = prefs.getLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
        val now = System.currentTimeMillis()
        val daysSinceLastRequest = if (lastRequestTime > 0) {
            (now - lastRequestTime) / (1000 * 60 * 60 * 24)
        } else {
            -1L
        }
        
        // Проверяем количество запусков подсветки
        val lightingStarts = prefs.getInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        if (lightingStarts < MIN_LIGHTING_STARTS) {
            return false
        }
        
        // Если прошло достаточно времени с последнего запроса или это первый запрос
        // Не используем флаг dismissed для блокировки, так как диалог мог не показаться из-за квот Google Play
        return lastRequestTime == 0L || daysSinceLastRequest >= MIN_DAYS_BETWEEN_REQUESTS
    }
    
    /**
     * Проверяет, установлено ли приложение через Google Play
     */
    private fun isInstalledFromPlayStore(context: Context): Boolean {
        return try {
            val installer = context.packageManager.getInstallerPackageName(context.packageName)
            installer == "com.android.vending" || installer == "com.google.android.feedback"
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Запрашивает показ диалога оценки
     * 
     * Важно: Google Play имеет свои квоты и может не показать диалог,
     * даже если мы вызываем launchReviewFlow. Поэтому мы сохраняем время
     * запроса только после успешного завершения flow.
     */
    private fun requestReview(activity: Activity) {
        val reviewManager: ReviewManager = ReviewManagerFactory.create(activity)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
        
        val request = reviewManager.requestReviewFlow()
        request.addOnCompleteListener { requestTask ->
            if (requestTask.isSuccessful) {
                val reviewInfo: ReviewInfo = requestTask.result
                
                // Показываем диалог
                val flow = reviewManager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener {
                    // Сохраняем время последнего запроса только после завершения flow
                    // Это важно, так как Google Play может не показать диалог из-за квот,
                    // но мы все равно не должны запрашивать слишком часто
                    prefs.edit { 
                        putLong(PREF_KEY_LAST_REVIEW_REQUEST, System.currentTimeMillis())
                        // НЕ устанавливаем dismissed=true, так как Google Play может не показать диалог
                        // из-за квот, и мы не знаем, был ли он реально показан пользователю.
                        // Вместо этого полагаемся только на время последнего запроса.
                    }
                }
            } else {
                // Если запрос не удался, не сохраняем время, чтобы можно было повторить позже
                val exception = requestTask.exception
                Log.e(TAG, "Failed to request review flow: ${exception?.message}", exception)
            }
        }
    }
    
    /**
     * Сбрасывает флаг отклонения (можно использовать для тестирования)
     */
    fun resetReviewState(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            putBoolean(PREF_KEY_REVIEW_DISMISSED, false)
            putBoolean(PREF_KEY_REVIEW_COMPLETED, false)
            putLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
        }
        Log.d(TAG, "Review state reset")
    }
    
    /**
     * Сбрасывает все данные о ревью, включая счетчик запусков (для тестирования)
     */
    fun resetAllReviewData(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            putBoolean(PREF_KEY_REVIEW_DISMISSED, false)
            putBoolean(PREF_KEY_REVIEW_COMPLETED, false)
            putLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
            putInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        }
        Log.d(TAG, "All review data reset")
    }
    
    /**
     * Принудительно показывает диалог оценки (для тестирования)
     * ВНИМАНИЕ: Используйте только для тестирования!
     */
    fun forceShowReview(activity: Activity) {
        Log.d(TAG, "Force showing review dialog (for testing)")
        requestReview(activity)
    }
    
    /**
     * Получает текущее состояние для отладки
     */
    fun getReviewState(context: Context): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val lightingStarts = prefs.getInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        val dismissed = prefs.getBoolean(PREF_KEY_REVIEW_DISMISSED, false)
        val completed = prefs.getBoolean(PREF_KEY_REVIEW_COMPLETED, false)
        val lastRequestTime = prefs.getLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
        val daysSinceLastRequest = if (lastRequestTime > 0) {
            (System.currentTimeMillis() - lastRequestTime) / (1000 * 60 * 60 * 24)
        } else {
            -1L
        }
        
        return "Lighting starts: $lightingStarts/$MIN_LIGHTING_STARTS, " +
                "Dismissed: $dismissed, " +
                "Completed: $completed, " +
                "Days since last request: $daysSinceLastRequest/$MIN_DAYS_BETWEEN_REQUESTS"
    }
}
