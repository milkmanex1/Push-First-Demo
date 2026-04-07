package com.pushfirst.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.PowerManager

class ReblockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Guard against doze/throttling: acquire a short wake lock just for this decision path.
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PushFirst:ReblockWakelock")
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(3000) // 3 seconds max
        } catch (e: Exception) {
            Log.e("ReblockAlarmReceiver", "Failed to acquire wakelock: ${e.message}", e)
        }
        val prefs = context.getSharedPreferences("pushfirst_detection_prefs", Context.MODE_PRIVATE)
        val domain = prefs.getString("last_block_domain", null)
        val ts = prefs.getLong("last_block_ts", 0L)
        val lastBrowser = prefs.getString("last_browser_package", null)
        val lastActive = prefs.getString("last_active_package", null)
        val lastEventTs = prefs.getLong("last_browser_event_ts", 0L)
        val now = System.currentTimeMillis()
        val freshDomainOk = ts != 0L && (now - ts) <= 2 * 60 * 1000L // 2 minutes freshness
        val matches = !lastBrowser.isNullOrBlank() && lastBrowser.equals(lastActive, ignoreCase = true)
        Log.d("ReblockAlarmReceiver", "onReceive freshDomainOk=$freshDomainOk matches=$matches domain=$domain lastBrowser=$lastBrowser lastActive=$lastActive")
        // Delegate to the accessibility service when available for authoritative check.
        val delegated = try {
            BrowserDetectionService.performExpiryCheckFromAlarmIfAvailable()
        } catch (_: Throwable) { false }
        if (!delegated) {
            // Fallback: very conservative start only if gates pass.
            if (domain != null && freshDomainOk && matches) {
                try {
                    val svc = Intent(context, BlockingOverlayService::class.java)
                        .putExtra("blocked_domain", domain)
                    lastBrowser?.let { svc.putExtra("browser_package", it) }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(svc)
                    } else {
                        @Suppress("DEPRECATION")
                        context.startService(svc)
                    }
                } catch (e: Exception) {
                    Log.e("ReblockAlarmReceiver", "Fallback start failed: ${e.message}", e)
                }
            } else {
                Log.d("ReblockAlarmReceiver", "Fallback skip — gates not met")
            }
        }
        try {
            wakeLock?.release()
        } catch (_: Exception) { }
    }
}
