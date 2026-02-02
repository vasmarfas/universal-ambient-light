package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Utility class for managing Google Play review dialog display
 */
object ReviewHelper {
    private const val TAG = "ReviewHelper"
    
    private const val PREF_KEY_LAST_REVIEW_REQUEST = "last_review_request_time"
    private const val PREF_KEY_LIGHTING_START_COUNT = "lighting_start_count"
    private const val PREF_KEY_REVIEW_DISMISSED = "review_dismissed"
    private const val PREF_KEY_REVIEW_COMPLETED = "review_completed"
    
    private const val MIN_DAYS_BETWEEN_REQUESTS = 3L
    private const val MIN_LIGHTING_STARTS = 5
    
    /**
     * Increments lighting start counter and checks if review dialog should be shown
     */
    fun onLightingStarted(activity: Activity) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
        
        val currentCount = prefs.getInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        val newCount = currentCount + 1
        prefs.edit { putInt(PREF_KEY_LIGHTING_START_COUNT, newCount) }
        
        if (shouldShowReviewDialog(activity)) {
            requestReview(activity)
        }
    }
    
    /**
     * Checks if review dialog should be shown
     */
    private fun shouldShowReviewDialog(context: Context): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        
        val reviewCompleted = prefs.getBoolean(PREF_KEY_REVIEW_COMPLETED, false)
        if (reviewCompleted) {
            return false
        }
        
        // Check last request time (don't use dismissed flag, as Google Play may not show dialog
        // due to quotas, and we don't know if it was actually shown)
        val lastRequestTime = prefs.getLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
        val now = System.currentTimeMillis()
        val daysSinceLastRequest = if (lastRequestTime > 0) {
            (now - lastRequestTime) / (1000 * 60 * 60 * 24)
        } else {
            -1L
        }
        
        val lightingStarts = prefs.getInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        if (lightingStarts < MIN_LIGHTING_STARTS) {
            return false
        }
        
        // Don't use dismissed flag for blocking, as dialog might not have appeared due to Google Play quotas
        return lastRequestTime == 0L || daysSinceLastRequest >= MIN_DAYS_BETWEEN_REQUESTS
    }
    
    /**
     * Checks if app is installed from Google Play
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
     * Requests review dialog display
     * 
     * Important: Google Play has quotas and may not show dialog even if we call launchReviewFlow.
     * Therefore we save request time only after successful flow completion.
     */
    private fun requestReview(activity: Activity) {
        val reviewManager: ReviewManager = ReviewManagerFactory.create(activity)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
        
        val request = reviewManager.requestReviewFlow()
        request.addOnCompleteListener { requestTask ->
            if (requestTask.isSuccessful) {
                val reviewInfo: ReviewInfo = requestTask.result
                
                val flow = reviewManager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener {
                    // Save last request time only after flow completion. Important: Google Play may not show
                    // dialog due to quotas, but we still shouldn't request too frequently.
                    // Don't set dismissed=true, as we don't know if dialog was actually shown.
                    // Instead, rely only on last request time.
                    prefs.edit { 
                        putLong(PREF_KEY_LAST_REVIEW_REQUEST, System.currentTimeMillis())
                    }
                }
            } else {
                val exception = requestTask.exception
                Log.e(TAG, "Failed to request review flow: ${exception?.message}", exception)
            }
        }
    }
    
    /**
     * Resets dismissal flag (for testing)
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
     * Resets all review data including start counter (for testing)
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
     * Force shows review dialog (for testing)
     * WARNING: Use only for testing!
     */
    fun forceShowReview(activity: Activity) {
        Log.d(TAG, "Force showing review dialog (for testing)")
        requestReview(activity)
    }
    
    /**
     * Gets current state for debugging
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
