package com.pushfirst.demo

import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(overlayIntent)
                } else {
                    startService(overlayIntent)
                }
                
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
    
    // Randomly select image each time the screen is shown
    val restBitmap = remember {
        try {
            val imagePath = if (AppConfig.Is_Stoic) {
                // Dynamically list all images from Rest Stoic folder
                val stoicFolderPath = "Rest Stoic"
                val imageExtensions = setOf(".jpeg", ".jpg", ".png", ".webp")
                
                val availableImages = try {
                    context.assets.list(stoicFolderPath)?.filter { fileName ->
                        val lowerName = fileName.lowercase()
                        imageExtensions.any { lowerName.endsWith(it) }
                    }?.map { "$stoicFolderPath/$it" } ?: emptyList()
                } catch (e: Exception) {
                    android.util.Log.e("ControlRegainedScreen", "Error listing Rest Stoic folder: ${e.message}", e)
                    emptyList()
                }
                
                if (availableImages.isNotEmpty()) {
                    availableImages.random()
                } else {
                    android.util.Log.w("ControlRegainedScreen", "No images found in Rest Stoic folder, falling back to Rest.jpeg")
                    "Rest.jpeg"
                }
            } else {
                "Rest.jpeg"
            }
            
            val inputStream = context.assets.open(imagePath)
            BitmapFactory.decodeStream(inputStream)?.asImageBitmap()
        } catch (e: Exception) {
            android.util.Log.e("ControlRegainedScreen", "Error loading image: ${e.message}", e)
            // Fallback: try to load default Rest.jpeg
            try {
                val fallbackStream = context.assets.open("Rest.jpeg")
                BitmapFactory.decodeStream(fallbackStream)?.asImageBitmap()
            } catch (fallbackException: Exception) {
                android.util.Log.e("ControlRegainedScreen", "Error loading fallback image: ${fallbackException.message}", fallbackException)
                null
            }
        }
    }
    
    var phase by remember { mutableStateOf(0) }
    val titleAlpha by animateFloatAsState(
        targetValue = if (phase >= 1) 1f else 0f,
        animationSpec = tween(1200),
        label = "titleAlpha"
    )
    val subtitleAlpha by animateFloatAsState(
        targetValue = if (phase >= 2) 1f else 0f,
        animationSpec = tween(1200),
        label = "subtitleAlpha"
    )
    val buttonAlpha by animateFloatAsState(
        targetValue = if (phase >= 3) 1f else 0f,
        animationSpec = tween(1200),
        label = "buttonAlpha"
    )

    LaunchedEffect(Unit) {
        delay(300)
        phase = 1
        delay(900)
        phase = 2
        delay(900)
        phase = 3
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val isStoic = AppConfig.Is_Stoic
        
        // Calculate 30% of screen height in Dp
        val bottomPadding = if (isStoic) {
            screenHeight * 0.3f
        } else {
            80.dp
        }
        
        // Full screen background image
        restBitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Subtle grey overlay for Stoic mode — improves text/button readability
        if (isStoic) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
            )
        }

        // Dark gradient overlay - only show when Is_Stoic is false
        if (!isStoic) {
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
        }

        // Content - positioned differently based on Is_Stoic flag
        if (isStoic) {
            // When Is_Stoic is true: text and button moved down by 15% of screen height
            
            // Text content - positioned in center area, moved down by 15%
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = screenHeight * 0.15f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Not today.",
                    fontSize = 47.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer(alpha = titleAlpha)
                )
                Text(
                    text = "The urge lost. You didn't.",
                    fontSize = 23.sp,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer(alpha = subtitleAlpha)
                )
            }

            // Done button - positioned at bottom 10% of screen (moved up ~5% from previous)
            val doneInteractionSource = remember { MutableInteractionSource() }
            val isDonePressed by doneInteractionSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = 32.dp,
                        end = 32.dp,
                        bottom = screenHeight * 0.10f
                    )
            ) {
                Box(
                    modifier = Modifier
                        .graphicsLayer(alpha = buttonAlpha * (if (isDonePressed) 0.6f else 1.0f))
                        .fillMaxWidth()
                        .background(Color(0x66000000), RoundedCornerShape(20.dp)) // subtle grey/black translucent background
                        .border(1.dp, Color(0xFF999999), RoundedCornerShape(20.dp))
                        .clickable(interactionSource = doneInteractionSource, indication = rememberRipple(), enabled = phase >= 3) { onBriefDisplayComplete() }
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "Done", fontSize = 18.sp, color = Color(0xFF999999))
                }
            }
        } else {
            // When Is_Stoic is false: original layout with text and button together
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = 32.dp,
                        top = 0.dp,
                        end = 32.dp,
                        bottom = bottomPadding
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Not today.",
                    fontSize = 47.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer(alpha = titleAlpha)
                )
                Text(
                    text = "The urge lost. You didn't.",
                    fontSize = 23.sp,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer(alpha = subtitleAlpha)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Done button - ghost outlined
                val doneInteractionSource = remember { MutableInteractionSource() }
                val isDonePressed by doneInteractionSource.collectIsPressedAsState()
                Box(
                    modifier = Modifier
                        .graphicsLayer(alpha = buttonAlpha * (if (isDonePressed) 0.6f else 1.0f))
                        .fillMaxWidth()
                        .background(Color(0x66000000), RoundedCornerShape(20.dp)) // subtle grey/black translucent background
                        .border(1.dp, Color(0xFF999999), RoundedCornerShape(20.dp))
                        .clickable(interactionSource = doneInteractionSource, indication = rememberRipple(), enabled = phase >= 3) { onBriefDisplayComplete() }
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "Done", fontSize = 18.sp, color = Color(0xFF999999))
                }
            }
        }
    }
}
