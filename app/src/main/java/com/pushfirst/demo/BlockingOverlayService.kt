package com.pushfirst.demo

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat

/**
 * BLOCKING OVERLAY SERVICE
 * 
 * This service creates a full-screen overlay that blocks user interaction with the browser
 * when an adult website is detected.
 * 
 * HOW IT WORKS:
 * 1. Creates a system overlay window (TYPE_APPLICATION_OVERLAY)
 * 2. Shows blocking popup with Gen Z humor
 * 3. Prevents user from interacting with underlying browser
 * 4. Provides CTA button to start push-ups
 * 
 * PERMISSIONS REQUIRED:
 * - SYSTEM_ALERT_WINDOW permission (Display over other apps)
 * - User must grant this in: Settings > Apps > Push First > Display over other apps
 * 
 * NOTE: This is a foreground service (required for Android 8.0+)
 */
class BlockingOverlayService : Service() {

    private var overlayView: ViewGroup? = null
    private var windowManager: WindowManager? = null
    private var isOverlayShowing = false
    private var browserPackage: String? = null

    companion object {
        private const val TAG = "BlockingOverlayService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "blocking_overlay_channel"
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d(TAG, "BlockingOverlayService onCreate")
        createNotificationChannel()
        
        // Start foreground service with proper type for Android 14+
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34+) requires explicit foreground service type
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val blockedDomain = intent?.getStringExtra("blocked_domain") ?: "that site"
        browserPackage = intent?.getStringExtra("browser_package")
        android.util.Log.d(TAG, "onStartCommand called with domain: $blockedDomain, browser: $browserPackage")
        showBlockingOverlay(blockedDomain)
        return START_STICKY // Keep service running even if killed
    }

    /**
     * Create and show the blocking overlay window
     */
    private fun showBlockingOverlay(blockedDomain: String) {
        if (isOverlayShowing) {
            android.util.Log.d(TAG, "Overlay already showing, skipping")
            return
        }

        android.util.Log.d(TAG, "Showing blocking overlay for domain: $blockedDomain")
        
        // Check overlay permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                android.util.Log.e(TAG, "❌ Overlay permission not granted!")
                android.util.Log.e(TAG, "Please enable 'Display over other apps' permission in settings")
                stopSelf()
                return
            }
        }
        
        // Remove existing overlay if any
        removeOverlay()

        try {
            // Create overlay window parameters
            // Use TYPE_APPLICATION_OVERLAY for Android 8.0+, TYPE_SYSTEM_ALERT for older versions
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                // FLAG_NOT_FOCUSABLE: Don't steal keyboard focus, but can still receive touches
                // Without FLAG_NOT_TOUCH_MODAL: All touches are intercepted by our overlay
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            
            // Set gravity to fill screen
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            // Set format to ensure proper rendering
            params.format = PixelFormat.TRANSLUCENT

            // Create overlay using standard Android Views (ComposeView doesn't work in Services)
            // ComposeView requires a LifecycleOwner which Services don't have
            android.util.Log.d(TAG, "Creating standard Android View overlay")
            overlayView = createStandardOverlay(blockedDomain)
            
            if (overlayView == null) {
                android.util.Log.e(TAG, "Failed to create overlay view")
                stopSelf()
                return
            }
            // Ensure the view group intercepts all touches
            overlayView?.isClickable = true
            overlayView?.isFocusable = true
            
            // Make sure window manager is available
            val wm = windowManager
            if (wm != null) {
                wm.addView(overlayView, params)
                isOverlayShowing = true
                android.util.Log.d(TAG, "✅ Overlay successfully added to window manager")
            } else {
                android.util.Log.e(TAG, "❌ WindowManager is null!")
                stopSelf()
            }
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "SecurityException: Overlay permission might not be granted", e)
            android.util.Log.e(TAG, "Error: ${e.message}", e)
            isOverlayShowing = false
            stopSelf()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error showing overlay: ${e.message}", e)
            android.util.Log.e(TAG, "Exception type: ${e.javaClass.simpleName}", e)
            e.printStackTrace()
            isOverlayShowing = false
            stopSelf()
        }
    }

    /**
     * Create overlay using standard Android Views
     * This is the primary method since ComposeView doesn't work in Services (no LifecycleOwner)
     */
    private fun createStandardOverlay(blockedDomain: String): ViewGroup {
        android.util.Log.d(TAG, "Creating standard Android View overlay")
        
        // Random Gen Z phrases
        val phrases = listOf(
            "AYO chill 💀",
            "Earn it. 20 pushups.",
            "No reps = no happy time",
            "Bro really? 💀",
            "20 pushups or we're done here",
            "Not today, chief 😤"
        )
        val randomPhrase = phrases.random()
        
        val layout = FrameLayout(applicationContext).apply {
            setBackgroundColor(ContextCompat.getColor(applicationContext, android.R.color.black))
            setOnClickListener {
                // Block touches to background
            }
        }
        
        val container = LinearLayout(applicationContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(64, 64, 64, 64)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        val emojiText = TextView(applicationContext).apply {
            text = "🚫"
            textSize = 80f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(applicationContext, android.R.color.white))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }
        
        val messageText = TextView(applicationContext).apply {
            text = randomPhrase
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(applicationContext, android.R.color.white))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        
        val domainText = TextView(applicationContext).apply {
            text = "You tried to visit: $blockedDomain"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF999999.toInt()) // Gray color
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 48)
            }
        }
        
        val button = Button(applicationContext).apply {
            text = "Start Pushups 💪"
            textSize = 20f
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 32)
            }
            setOnClickListener {
                android.util.Log.d(TAG, "Start pushups button clicked")
                removeOverlay()
                startPushupActivity()
                stopSelf()
            }
        }
        
        val disclaimerText = TextView(applicationContext).apply {
            text = "Complete 20 pushups to unlock"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF999999.toInt()) // Gray color
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        container.addView(emojiText)
        container.addView(messageText)
        container.addView(domainText)
        container.addView(button)
        container.addView(disclaimerText)
        layout.addView(container)
        
        return layout
    }

    /**
     * Start the push-up counter activity
     */
    private fun startPushupActivity() {
        val intent = Intent(this, PushupCounterActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Pass the browser package name so we can return to it after push-ups
        browserPackage?.let {
            intent.putExtra("browser_package", it)
        }
        startActivity(intent)
    }

    /**
     * Remove the overlay window
     */
    private fun removeOverlay() {
        if (!isOverlayShowing) return
        
        try {
            overlayView?.let { view ->
                android.util.Log.d(TAG, "Removing overlay")
                windowManager?.removeView(view)
                overlayView = null
                isOverlayShowing = false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error removing overlay: ${e.message}", e)
            overlayView = null
            isOverlayShowing = false
        }
    }

    /**
     * Create notification channel (required for Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Blocking Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows blocking overlay when adult sites are detected"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create foreground service notification
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Push First - Blocking Active")
            .setContentText("Monitoring browser navigation")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        android.util.Log.d(TAG, "BlockingOverlayService onDestroy")
        super.onDestroy()
        removeOverlay()
    }
}
