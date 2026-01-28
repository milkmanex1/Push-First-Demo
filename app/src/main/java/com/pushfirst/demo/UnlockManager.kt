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
    private const val UNLOCK_DURATION_MS = 30_000L // 30 seconds

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
}
