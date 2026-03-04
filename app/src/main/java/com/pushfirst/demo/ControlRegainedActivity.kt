package com.pushfirst.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.core.view.WindowCompat
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        
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
    val context = LocalContext.current
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Full screen background image
        val restBitmap = remember {
            try {
                val inputStream = context.assets.open("Rest.jpeg")
                BitmapFactory.decodeStream(inputStream)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        restBitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Dark gradient overlay from transparent to near-black (moved lower)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xCC000000),
                            Color(0xF2000000)
                        ),
                        startY = 600f // Moved lower to show more background image
                    )
                )
        )

        // Content anchored to bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, top = 0.dp, end = 32.dp, bottom = 80.dp), // Bottom padding to position content
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Not today.",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "The urge lost. You didn't.",
                fontSize = 18.sp,
                color = Color(0xFFAAAAAA),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Done button - ghost outlined
            val doneInteractionSource = remember { MutableInteractionSource() }
            val isDonePressed by doneInteractionSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF999999), RoundedCornerShape(20.dp))
                    .graphicsLayer(alpha = if (isDonePressed) 0.6f else 1.0f)
                    .clickable(interactionSource = doneInteractionSource, indication = rememberRipple()) { onBriefDisplayComplete() }
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Done", fontSize = 18.sp, color = Color(0xFF999999))
            }
        }
    }
}
