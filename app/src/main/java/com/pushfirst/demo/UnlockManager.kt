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
    private const val KEY_UNLOCK_DURATION_MS = "unlock_duration_ms"
    private const val KEY_INSTALL_TIME = "install_time"
    private const val KEY_BLOCKING_BYPASS_UNTIL = "blocking_bypass_until"

    private fun getInstallTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    /**
     * Store the current timestamp as unlock time
     */
    fun setUnlocked(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_UNLOCK_TIMESTAMP, System.currentTimeMillis())
            .putLong(KEY_UNLOCK_DURATION_MS, AppConfig.UNLOCK_DURATION_MS)
            .putLong(KEY_INSTALL_TIME, getInstallTime(context))
            .apply()
    }

    /**
     * Check if unlock is still valid (within 30 seconds)
     */
    fun isUnlocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val unlockTimestamp = prefs.getLong(KEY_UNLOCK_TIMESTAMP, 0L)

        if (unlockTimestamp == 0L) return false

        val savedInstallTime = prefs.getLong(KEY_INSTALL_TIME, 0L)
        if (savedInstallTime != getInstallTime(context)) return false

        val grantedDuration = prefs.getLong(KEY_UNLOCK_DURATION_MS, AppConfig.UNLOCK_DURATION_MS)
        val elapsed = System.currentTimeMillis() - unlockTimestamp
        return elapsed < grantedDuration
    }

    /**
     * Get remaining unlock time in seconds (0 if expired)
     */
    fun getRemainingSeconds(context: Context): Int {
        if (!isUnlocked(context)) return 0

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val unlockTimestamp = prefs.getLong(KEY_UNLOCK_TIMESTAMP, 0L)
        val grantedDuration = prefs.getLong(KEY_UNLOCK_DURATION_MS, AppConfig.UNLOCK_DURATION_MS)
        val elapsed = System.currentTimeMillis() - unlockTimestamp
        val remaining = grantedDuration - elapsed

        return if (remaining > 0) (remaining / 1000).toInt() else 0
    }

    /**
     * Clear/revoke unlock access (set timestamp to 0)
     */
    fun clearUnlock(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_UNLOCK_TIMESTAMP, 0L)
            .remove(KEY_UNLOCK_DURATION_MS)
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

    /**
     * Get remaining bypass duration in milliseconds (0 if no active bypass).
     */
    fun getBlockingBypassRemainingMs(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bypassUntil = prefs.getLong(KEY_BLOCKING_BYPASS_UNTIL, 0L)
        if (bypassUntil == 0L) return 0L
        val remaining = bypassUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }
}
