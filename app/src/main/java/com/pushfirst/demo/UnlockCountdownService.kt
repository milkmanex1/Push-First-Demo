package com.pushfirst.demo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.pushfirst.demo.R
import kotlinx.coroutines.*

/**
 * UNLOCK COUNTDOWN SERVICE
 * 
 * Foreground service that displays a persistent countdown notification
 * showing remaining unlock time after user completes push-ups.
 * 
 * Features:
 * - Shows countdown in format: 🔓 9:42 remaining
 * - Updates every second in real time
 * - Non-dismissible notification (ongoing = true) while timer is active
 * - When timer hits 0, shows "🔒 Access expired" and stops service
 * - Calls UnlockManager to confirm expiry when timer expires
 */
class UnlockCountdownService : Service() {

    private var countdownJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    
    companion object {
        private const val TAG = "UnlockCountdownService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "unlock_countdown_channel"
        
        /**
         * Start the countdown service
         */
        fun start(context: Context) {
            val intent = Intent(context, UnlockCountdownService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the countdown service
         */
        fun stop(context: Context) {
            val intent = Intent(context, UnlockCountdownService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d(TAG, "UnlockCountdownService onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d(TAG, "UnlockCountdownService onStartCommand")
        
        // Get current remaining seconds before creating notification
        val remainingSeconds = UnlockManager.getRemainingSeconds(this)
        android.util.Log.d(TAG, "Starting service with remaining seconds: $remainingSeconds")
        
        // Start foreground service with actual remaining time
        val notification = createNotification(remainingSeconds)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34+) requires explicit foreground service type
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            android.util.Log.d(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error starting foreground service: ${e.message}", e)
        }
        
        // Start countdown
        startCountdown()
        
        return START_STICKY // Keep service running even if killed
    }

    /**
     * Start the countdown timer that updates notification every second
     */
    private fun startCountdown() {
        // Cancel any existing countdown
        countdownJob?.cancel()
        
        countdownJob = serviceScope.launch {
            // Update immediately first, then continue with 1-second intervals
            var remainingSeconds = UnlockManager.getRemainingSeconds(this@UnlockCountdownService)
            
            while (true) {
                if (remainingSeconds <= 0) {
                    // Timer expired - update notification and stop service
                    updateNotificationExpired()
                    delay(1000) // Show expired message for 1 second
                    UnlockManager.clearUnlock(this@UnlockCountdownService)
                    android.util.Log.d(TAG, "Countdown expired, unlock cleared")
                    stopSelf()
                    break
                } else {
                    // Update notification with remaining time
                    updateNotification(remainingSeconds)
                    delay(1000) // Update every second
                    remainingSeconds = UnlockManager.getRemainingSeconds(this@UnlockCountdownService)
                }
            }
        }
    }

    /**
     * Update notification with remaining time
     */
    private fun updateNotification(remainingSeconds: Int) {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        val timeText = String.format("%d:%02d", minutes, seconds)
        
        val notification = createNotification(remainingSeconds)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Update notification to show expired message
     */
    private fun updateNotificationExpired() {
        // Use app icon instead of system icon
        val iconRes = try {
            R.mipmap.ic_launcher
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info // Fallback to system icon
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔒 Access expired")
            .setContentText("Your unlock time has expired")
            .setSmallIcon(iconRes)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false) // Allow dismissal after expiry
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Create notification with countdown
     */
    private fun createNotification(remainingSeconds: Int): Notification {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        val timeText = String.format("%d:%02d", minutes, seconds)
        
        // Use app icon instead of system icon for better visibility
        val iconRes = try {
            R.mipmap.ic_launcher
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info // Fallback to system icon
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔓 $timeText remaining")
            .setContentText("Unlock time countdown")
            .setSmallIcon(iconRes)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Changed to DEFAULT for visibility
            .setOngoing(true) // Non-dismissible while timer is active
            .setAutoCancel(false) // Don't auto-cancel
            .setOnlyAlertOnce(true) // Only alert once, then silent updates
            .build()
    }

    /**
     * Create notification channel (required for Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use IMPORTANCE_DEFAULT to ensure notification is visible, but disable sound/vibration
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Unlock Countdown",
                NotificationManager.IMPORTANCE_DEFAULT // Visible but we'll disable sound
            ).apply {
                description = "Shows remaining unlock time countdown"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null) // No sound
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            android.util.Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        android.util.Log.d(TAG, "UnlockCountdownService onDestroy")
        countdownJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

