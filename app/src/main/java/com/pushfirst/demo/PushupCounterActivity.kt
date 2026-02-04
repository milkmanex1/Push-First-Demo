package com.pushfirst.demo

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
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
    private var poseAnalyzer: PoseAnalyzer? = null

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
        
        // CRITICAL: Create PoseAnalyzer BEFORE setContent to avoid initialization in Compose
        // MediaPipe MUST NOT be initialized in constructor, init block, or Compose composition
        // State holders for Compose (will be updated by callbacks)
        val repCountState = mutableStateOf(0)
        val currentStateState = mutableStateOf(PushupState.UNKNOWN)
        val isValidPoseState = mutableStateOf(false)
        val currentLandmarksState = mutableStateOf<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?>(null)
        
        poseAnalyzer = PoseAnalyzer(
            context = this,
            onRepCountChanged = { count ->
                repCountState.value = count
            },
            onStateChanged = { state ->
                currentStateState.value = state
            },
            onValidPoseChanged = { valid ->
                isValidPoseState.value = valid
            }
        )
        android.util.Log.d("POSE_DEBUG", "onCreate: PoseAnalyzer instance created (NO initialization in constructor)")
        
        // CRITICAL: Initialize MediaPipe PoseLandmarker from Activity.onCreate() on main thread
        // MUST NOT initialize in constructor, init block, or Compose composition (causes native crash)
        android.util.Log.d("POSE_DEBUG", "onCreate: Initializing PoseLandmarker from onCreate() on main thread")
        poseAnalyzer?.initialize()
        android.util.Log.d("POSE_DEBUG", "onCreate: PoseLandmarker initialization called, status: ${poseAnalyzer?.getCurrentLandmarks() != null}")

        setContent {
            // State for pose detection results (synced with PoseAnalyzer callbacks)
            val repCount by repCountState
            val currentState by currentStateState
            val isValidPose by isValidPoseState
            val currentLandmarks by currentLandmarksState
            
            val context = LocalContext.current
            
            // Update landmarks periodically for visualization
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(33) // ~30 FPS
                    poseAnalyzer?.let { analyzer ->
                        currentLandmarksState.value = analyzer.getCurrentLandmarks()
                    }
                }
            }
            
            // Update landmarks periodically for visualization
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(33) // ~30 FPS
                    poseAnalyzer?.let { analyzer ->
                        currentLandmarksState.value = analyzer.getCurrentLandmarks()
                    }
                }
            }
            
            PushFirstTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PushupCounterScreen(
                        repCount = repCount,
                        currentState = currentState,
                        isValidPose = isValidPose,
                        landmarks = currentLandmarks,
                        onCompleteWorkout = {
                            // Manually trigger completion for testing
                            val activity = context as? PushupCounterActivity
                            activity?.let {
                                UnlockManager.setUnlocked(context)
                                // Show unlock screen by setting repCount to 20
                                repCountState.value = 20
                            }
                        },
                        onPreviewViewCreated = { view ->
                            previewView = view
                            // Start camera if permission already granted
                            if (ContextCompat.checkSelfPermission(
                                    this@PushupCounterActivity,
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
     * Start CameraX preview with front camera and ImageAnalysis for pose detection
     * CRITICAL: Only starts camera AFTER PoseLandmarker is initialized
     */
    private fun startCamera() {
        val previewView = this.previewView ?: return
        val poseAnalyzer = this.poseAnalyzer ?: run {
            android.util.Log.e("POSE_DEBUG", "startCamera: PoseAnalyzer is null, cannot start camera")
            return
        }
        
        // CRITICAL CHECK: Verify PoseLandmarker is initialized BEFORE binding analyzer
        // This ensures MediaPipe is ready before CameraX starts sending frames
        if (!poseAnalyzer.isPoseLandmarkerInitialized()) {
            android.util.Log.e("POSE_DEBUG", "startCamera: PoseLandmarker not initialized, aborting camera start")
            android.util.Log.e("POSE_DEBUG", "startCamera: Check logs for initialization errors")
            Toast.makeText(this, "Pose detection not ready. Check logs.", Toast.LENGTH_LONG).show()
            return
        }
        
        android.util.Log.d("POSE_DEBUG", "startCamera: PoseLandmarker verified ready, starting CameraX")
        
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

                // Build ImageAnalysis use case for pose detection
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            poseAnalyzer.analyzeFrame(imageProxy)
                        }
                    }

                // Bind preview and image analysis to lifecycle
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Return to the browser app after completing push-ups
     * Tries to bring browser back to foreground without resetting its state
     */
    fun returnToBrowser() {
        browserPackage?.let { packageName ->
            try {
                // Try to launch browser with flags that bring it to front without resetting
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    // Use flags that bring app to front without clearing its state
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    startActivity(intent)
                }
                
                // Move our task to back to ensure browser comes to front
                moveTaskToBack(true)
            } catch (e: Exception) {
                android.util.Log.e("PushupCounterActivity", "Error returning to browser: ${e.message}", e)
                // Fallback: move to back
                try {
                    moveTaskToBack(true)
                } catch (e2: Exception) {
                    finish()
                }
            }
        } ?: run {
            // No browser package - just move to back
            try {
                moveTaskToBack(true)
            } catch (e: Exception) {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        poseAnalyzer?.close()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}

/**
 * PUSH-UP COUNTER SCREEN UI
 * 
 * Shows front camera preview with AI push-up detection.
 * After 20 detected push-ups, shows unlock message.
 */
@Composable
fun PushupCounterScreen(
    repCount: Int = 0,
    currentState: PushupState = PushupState.UNKNOWN,
    isValidPose: Boolean = false,
    landmarks: List<NormalizedLandmark>? = null,
    onCompleteWorkout: () -> Unit = {},
    onPreviewViewCreated: (PreviewView) -> Unit = {}
) {
    val context = LocalContext.current

    // Show unlock screen if completed
    if (repCount >= 20) {
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
        // Camera preview (80% of screen)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(4f) // 4:1 ratio = 80% of screen
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

            // Pose skeleton overlay
            PoseOverlay(
                landmarks = landmarks,
                modifier = Modifier.fillMaxSize()
            )

            // Overlay with count and status
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
                    text = "$repCount / 20",
                    fontSize = 48.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.displayMedium
                )
                
                // Show pose status
                Text(
                    text = when {
                        !isValidPose -> "Position yourself in front of camera"
                        currentState == PushupState.UP -> "UP ✓"
                        currentState == PushupState.DOWN -> "DOWN ✓"
                        else -> "Detecting..."
                    },
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Control panel (20% of screen)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // 1:4 ratio = 20% of screen
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (isValidPose) {
                    "💪 AI is detecting your push-ups"
                } else {
                    "Position yourself in front of camera"
                },
                fontSize = 16.sp,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            // Progress indicator
            LinearProgressIndicator(
                progress = repCount / 20f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Text(
                text = "${20 - repCount} more to go!",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Temporary Complete button for testing (smaller)
            Button(
                onClick = onCompleteWorkout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    text = "Complete Workout (Test)",
                    fontSize = 14.sp
                )
            }
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
