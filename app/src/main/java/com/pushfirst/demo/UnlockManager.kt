package com.pushfirst.demo

import android.content.Context
import android.content.SharedPreferences

/**
 * Utility class to manage unlock timestamps for "happy time"
 * 
 * Stores unlock timestamp in SharedPreferences and checks if unlock is still valid
 */
object UnlockManager {
    private const val PREFS_NAME = "pushfirst_unlock_prefs"
    private const val KEY_UNLOCK_TIMESTAMP = "unlock_timestamp"
    private val UNLOCK_DURATION_MS = AppConfig.UNLOCK_DURATION_MS
    private const val KEY_BLOCKING_BYPASS_UNTIL = "blocking_bypass_until"

    /**
     * Store the current timestamp as unlock time
     */
    fun setUnlocked(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_UNLOCK_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * Check if unlock is still valid (within 30 seconds)
     */
    fun isUnlocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val unlockTimestamp = prefs.getLong(KEY_UNLOCK_TIMESTAMP, 0L)
        
        if (unlockTimestamp == 0L) {
            return false // Never unlocked
        }
        
        val elapsed = System.currentTimeMillis() - unlockTimestamp
        return elapsed < UNLOCK_DURATION_MS
    }

    /**
     * Get remaining unlock time in seconds (0 if expired)
     */
    fun getRemainingSeconds(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val unlockTimestamp = prefs.getLong(KEY_UNLOCK_TIMESTAMP, 0L)
        
        if (unlockTimestamp == 0L) {
            return 0
        }
        
        val elapsed = System.currentTimeMillis() - unlockTimestamp
        val remaining = UNLOCK_DURATION_MS - elapsed
        
        return if (remaining > 0) {
            (remaining / 1000).toInt()
        } else {
            0
        }
    }

    /**
     * Clear/revoke unlock access (set timestamp to 0)
     */
    fun clearUnlock(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_UNLOCK_TIMESTAMP, 0L)
            .apply()
    }

    /**
     * Set a temporary bypass period for blocking (e.g., during countdown overlay)
     * @param durationMs Duration in milliseconds to bypass blocking
     */
    fun setBlockingBypass(context: Context, durationMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bypassUntil = System.currentTimeMillis() + durationMs
        prefs.edit()
            .putLong(KEY_BLOCKING_BYPASS_UNTIL, bypassUntil)
            .apply()
    }

    /**
     * Check if blocking is currently bypassed (e.g., during countdown period)
     */
    fun isBlockingBypassed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bypassUntil = prefs.getLong(KEY_BLOCKING_BYPASS_UNTIL, 0L)
        if (bypassUntil == 0L) {
            return false
        }
        val now = System.currentTimeMillis()
        return now < bypassUntil
    }
}
