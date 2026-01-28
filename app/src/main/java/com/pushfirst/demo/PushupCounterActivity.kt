package com.pushfirst.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pushfirst.demo.ui.theme.PushFirstTheme
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PUSH-UP COUNTER ACTIVITY
 * 
 * This activity:
 * 1. Opens front camera using CameraX
 * 2. Shows a fake push-up counter (button-based, no real AI)
 * 3. After 20 "pushups", shows unlock message
 * 
 * NOTE: This is a DEMO - no real AI/ML detection. User clicks button to increment counter.
 */
class PushupCounterActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var browserPackage: String? = null

    // Request camera permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Get browser package name from intent
        browserPackage = intent.getStringExtra("browser_package")

        setContent {
            PushFirstTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PushupCounterScreen(
                        onPreviewViewCreated = { view ->
                            previewView = view
                            // Start camera if permission already granted
                            if (ContextCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                startCamera()
                            }
                        }
                    )
                }
            }
        }

        // Request camera permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Camera will start when PreviewView is created
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Start CameraX preview with front camera
     */
    private fun startCamera() {
        val previewView = this.previewView ?: return
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Use front camera
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                // Build preview use case and connect to PreviewView
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // Bind preview to lifecycle
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Return to the browser app after completing push-ups
     */
    fun returnToBrowser() {
        browserPackage?.let { packageName ->
            try {
                // Launch the browser app
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                    finish()
                } else {
                    // Fallback: try to launch browser using ACTION_VIEW
                    val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                        setPackage(packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (fallbackIntent.resolveActivity(packageManager) != null) {
                        startActivity(fallbackIntent)
                        finish()
                    } else {
                        // Last resort: just finish
                        finish()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PushupCounterActivity", "Error returning to browser: ${e.message}", e)
                finish()
            }
        } ?: run {
            // No browser package, just finish
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}

/**
 * PUSH-UP COUNTER SCREEN UI
 * 
 * Shows front camera preview and fake push-up counter button.
 * After 20 clicks, shows unlock message.
 */
@Composable
fun PushupCounterScreen(
    onPreviewViewCreated: (PreviewView) -> Unit = {}
) {
    var pushupCount by remember { mutableStateOf(0) }
    val context = LocalContext.current

    // Show unlock screen if completed
    if (pushupCount >= 20) {
        // Store unlock timestamp when first reaching 20
        LaunchedEffect(Unit) {
            UnlockManager.setUnlocked(context)
        }
        UnlockScreen(
            onDone = {
                val activity = context as? android.app.Activity
                if (activity is PushupCounterActivity) {
                    activity.returnToBrowser()
                } else {
                    activity?.finish()
                }
            }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Camera preview (top half)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Camera preview using CameraX
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        onPreviewViewCreated(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay with count
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Push-ups Completed",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "$pushupCount / 20",
                    fontSize = 48.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.displayMedium
                )
            }
        }

        // Control panel (bottom half)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "💪 Tap when you complete a push-up",
                fontSize = 18.sp,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            // Push-up button
            Button(
                onClick = { pushupCount++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = "Push-up Complete! 💪",
                    fontSize = 20.sp,
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // Progress indicator
            LinearProgressIndicator(
                progress = pushupCount / 20f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Text(
                text = "${20 - pushupCount} more to go!",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * UNLOCK SCREEN - Shown after completing 20 pushups
 */
@Composable
fun UnlockScreen(
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var remainingSeconds by remember { mutableStateOf(UnlockManager.getRemainingSeconds(context)) }
    
    // Update countdown every second
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds = UnlockManager.getRemainingSeconds(context)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🎉",
            fontSize = 100.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Text(
            text = "Happy Time Unlocked 😈",
            fontSize = 32.sp,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "You earned it! 💪",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = "${remainingSeconds}s remaining",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text(
                text = "Done",
                fontSize = 20.sp
            )
        }
    }
}
