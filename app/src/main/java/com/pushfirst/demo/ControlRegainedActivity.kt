package com.pushfirst.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushfirst.demo.ui.theme.PushFirstTheme
import kotlinx.coroutines.delay

/**
 * CONTROL REGAINED ACTIVITY
 * 
 * Shows a brief message (3 seconds) when user opts out of unlocking.
 * Then returns to browser and starts a 10-second countdown overlay.
 */
class ControlRegainedActivity : ComponentActivity() {
    private var browserPackage: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get browser package name from intent
        browserPackage = intent.getStringExtra("browser_package")
        
        setContent {
            PushFirstTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ControlRegainedScreen(
                        onBriefDisplayComplete = {
                            // After 3 seconds, return to browser and start countdown overlay
                            returnToBrowserAndStartCountdown()
                        }
                    )
                }
            }
        }
    }
    
    /**
     * Return to browser and start the 10-second countdown overlay
     */
    private fun returnToBrowserAndStartCountdown() {
        browserPackage?.let { packageName ->
            try {
                // Set blocking bypass for 10 seconds (duration of countdown overlay)
                UnlockManager.setBlockingBypass(this, 10_000L)
                
                // Return to browser
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    startActivity(intent)
                }
                
                // Start countdown overlay service
                val overlayIntent = Intent(this, BlockingOverlayService::class.java)
                overlayIntent.putExtra("countdown_mode", true)
                overlayIntent.putExtra("browser_package", packageName)
                startService(overlayIntent)
                
                // Move our task to back
                moveTaskToBack(true)
            } catch (e: Exception) {
                android.util.Log.e("ControlRegainedActivity", "Error returning to browser: ${e.message}", e)
                moveTaskToBack(true)
            }
        } ?: run {
            // No browser package - just finish
            moveTaskToBack(true)
        }
        
        finish()
    }
}

/**
 * CONTROL REGAINED SCREEN
 * 
 * Shows "Control regained." message for 3 seconds.
 * Then returns to browser where countdown overlay will be shown.
 */
@Composable
fun ControlRegainedScreen(
    onBriefDisplayComplete: () -> Unit
) {
    // Show for 2 seconds, then trigger completion
    LaunchedEffect(Unit) {
        delay(2000) // 2 seconds
        onBriefDisplayComplete()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Large centered title
        Text(
            text = "Control regained.",
            fontSize = 36.sp,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = MaterialTheme.typography.headlineLarge.fontWeight,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // Smaller grey subtitle
        Text(
            text = "Close your tabs. Blocker resumes in 10 seconds.",
            fontSize = 16.sp,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
