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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import android.media.SoundPool
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.pushfirst.demo.R
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
    var browserPackage: String? = null // Made accessible for Control Regained flow
    private var poseAnalyzer: PoseAnalyzer? = null

    // State holders for Compose (member vars so we can reset in onNewIntent when reused)
    private lateinit var repCountState: MutableState<Int>
    private lateinit var currentStateState: MutableState<PushupState>
    private lateinit var isValidPoseState: MutableState<Boolean>
    private lateinit var currentLandmarksState: MutableState<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?>

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
        // Dismiss any existing blocking overlay when this activity is freshly created
       

        // Make status bar transparent and extend content behind it
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insetsController = window.insetsController
                insetsController?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                // For older versions, use deprecated method
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        } catch (e: Exception) {
            android.util.Log.e("PushupCounterActivity", "Error setting status bar: ${e.message}", e)
            // Continue execution even if status bar setup fails
        }
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Get browser package name from intent
        browserPackage = intent.getStringExtra("browser_package")

        // State holders for Compose (will be updated by callbacks)
        repCountState = mutableStateOf(0)
        currentStateState = mutableStateOf(PushupState.UNKNOWN)
        isValidPoseState = mutableStateOf(false)
        currentLandmarksState = mutableStateOf<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?>(null)
        
        // CRITICAL: Create PoseAnalyzer BEFORE setContent to avoid initialization in Compose
        // MediaPipe MUST NOT be initialized in constructor, init block, or Compose composition
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
                                            UnlockCountdownService.start(context)
                                // Show unlock screen by setting repCount to 20
                                repCountState.value = 20
                            }
                        },
                        onCancelUnlock = {
                            // Stop detection and camera, then show Control Regained screen
                            val activity = context as? PushupCounterActivity
                            activity?.let {
                                it.stopDetectionAndShowControlRegained()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        browserPackage = intent.getStringExtra("browser_package")
        // Activity was brought to front via CLEAR_TOP (e.g. user clicked Start Pushups again).
        // Reset state so we show camera and count from 0, not the previous completion screen.
        repCountState.value = 0
        currentStateState.value = PushupState.UNKNOWN
        isValidPoseState.value = false
        currentLandmarksState.value = null
        poseAnalyzer?.reset()

        // Restart camera if previewView is ready and permission is granted
        if (
            previewView != null &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            cameraProvider?.unbindAll()
            startCamera()
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
     * Stop push-up detection and camera, then show Control Regained screen
     */
    fun stopDetectionAndShowControlRegained() {
        // Stop camera and detection
        cameraProvider?.unbindAll()
        poseAnalyzer?.reset()
        
        // Start Control Regained activity
        val intent = Intent(this, ControlRegainedActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        // Pass browser package name
        browserPackage?.let {
            intent.putExtra("browser_package", it)
        }
        startActivity(intent)
        
        // Finish this activity
        finish()
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
    onCancelUnlock: () -> Unit = {},
    onPreviewViewCreated: (PreviewView) -> Unit = {}
) {
    val context = LocalContext.current

    val orbScale = remember { Animatable(1f) }
    var lastRepCount by remember { mutableIntStateOf(0) }

    val soundPool = remember { SoundPool.Builder().setMaxStreams(3).build() }
    var soundId by remember { mutableIntStateOf(0) }
    var soundLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        soundId = soundPool.load(context, R.raw.rep_count_flashpoint, 1)
        soundPool.setOnLoadCompleteListener { _, _, status -> if (status == 0) soundLoaded = true }
    }

    LaunchedEffect(repCount) {
        if (repCount > lastRepCount) {
            lastRepCount = repCount
            if (soundLoaded && soundId != 0) soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
            orbScale.animateTo(1.28f, animationSpec = tween(80, easing = FastOutSlowInEasing))
            orbScale.animateTo(1.0f, animationSpec = tween(220, easing = FastOutSlowInEasing))
        }
    }

    DisposableEffect(Unit) {
        onDispose { soundPool.release() }
    }

    // Show unlock screen if completed
    if (repCount >= 20) {
            // Store unlock timestamp when first reaching 20
        LaunchedEffect(Unit) {
            UnlockManager.setUnlocked(context)
            UnlockCountdownService.start(context)
        }
        UnlockScreen(
            onDone = {
                val activity = context as? android.app.Activity
                if (activity is PushupCounterActivity) {
                    activity.returnToBrowser()
                    activity.finish()
                } else {
                    activity?.finish()
                }
            },
            onUrgeGone = {
                // Revoke unlock and show Control Regained screen
                UnlockManager.clearUnlock(context)
                val activity = context as? android.app.Activity
                if (activity is PushupCounterActivity) {
                    val intent = Intent(context, ControlRegainedActivity::class.java)
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    // Pass browser package name
                    activity.browserPackage?.let {
                        intent.putExtra("browser_package", it)
                    }
                    context.startActivity(intent)
                    activity.finish()
                }
            }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Camera preview (reduced to give more space to control panel)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(3.5f) // Reduced from 4f to give more space to control panel
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

            // Glowing animated orb counter
            val orbBaseColor = when {
                repCount >= 15 -> Color(0xFFB347FF)
                repCount >= 10 -> Color(0xFFFF8C00)
                repCount >= 5  -> Color(0xFF39FF14)
                else           -> Color(0xFF00D4FF)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 34.dp)
                    .size(224.dp)
                    .graphicsLayer(scaleX = orbScale.value, scaleY = orbScale.value),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(orbBaseColor.copy(alpha = 0.25f), Color.Transparent),
                            center = center, radius = radius
                        ),
                        radius = radius, center = center
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(orbBaseColor.copy(alpha = 0.55f), orbBaseColor.copy(alpha = 0.30f), Color.Transparent),
                            center = center, radius = radius * 0.75f
                        ),
                        radius = radius * 0.75f, center = center
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFFFFFF).copy(alpha = 0.95f), orbBaseColor.copy(alpha = 0.85f), orbBaseColor.copy(alpha = 0.60f)),
                            center = center, radius = radius * 0.45f
                        ),
                        radius = radius * 0.45f, center = center
                    )
                }
                Text(
                    text = "$repCount",
                    fontSize = 58.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    style = LocalTextStyle.current.copy(
                        shadow = Shadow(
                            color = Color(0xFF003344),
                            offset = Offset(0f, 1f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }

        // Control panel (increased vertical space to show all buttons)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.5f) // Increased weight to provide more vertical space
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()) // Make scrollable if content overflows
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp) // Slightly reduced spacing to fit content
        ) {
            // Status text moved from top overlay
            Text(
                text = when {
                    !isValidPose -> "Position yourself in front of camera"
                    currentState == PushupState.WRONG_POSITION -> "Get into push-up position 💪 Lay flat, face the camera"
                    currentState == PushupState.UP -> "UP ✓"
                    currentState == PushupState.DOWN -> "DOWN ✓"
                    else -> "Detecting..."
                },
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            
            Text(
                text = when {
                    !isValidPose -> "Make sure your whole body is visible"
                    currentState == PushupState.WRONG_POSITION -> "Lay flat facing the camera to begin"
                    isValidPose -> "💪 AI is detecting your push-ups"
                    else -> "Position yourself in front of camera"
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
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

            if (AppConfig.SHOW_SKIP_BUTTON) {
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
            
            // Cancel unlock button
            val cancelInteractionSource = remember { MutableInteractionSource() }
            val isCancelPressed by cancelInteractionSource.collectIsPressedAsState()
            OutlinedButton(
                onClick = onCancelUnlock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .graphicsLayer(alpha = if (isCancelPressed) 0.6f else 1.0f), // Press opacity change
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent, // Transparent background
                    contentColor = Color(0xFF999999) // Muted grey text
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = Color(0xFF666666) // Light grey border
                ),
                interactionSource = cancelInteractionSource
            ) {
                Text(
                    text = "Cancel unlock",
                    fontSize = 16.sp,
                    color = Color(0xFF999999) // Muted grey
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
    onDone: () -> Unit,
    onUrgeGone: () -> Unit = {}
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
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Full screen background image
        val bitmap = remember {
            try {
                // Choose background based on Is_Stoic flag
                val imagePath = if (AppConfig.Is_Stoic) {
                    // Dynamically list all images from "You Earned It Stoic" folder
                    val stoicFolderPath = "You Earned It Stoic"
                    val imageExtensions = setOf(".jpeg", ".jpg", ".png", ".webp")

                    val availableStoicImages = try {
                        context.assets.list(stoicFolderPath)?.filter { fileName ->
                            val lowerName = fileName.lowercase()
                            imageExtensions.any { lowerName.endsWith(it) }
                        }?.map { "$stoicFolderPath/$it" } ?: emptyList()
                    } catch (e: Exception) {
                        android.util.Log.e("UnlockScreen", "Error listing You Earned It Stoic folder: ${e.message}", e)
                        emptyList()
                    }

                    if (availableStoicImages.isNotEmpty()) {
                        availableStoicImages.random()
                    } else {
                        android.util.Log.w("UnlockScreen", "No images found in You Earned It Stoic folder, falling back to default You Earned it images")
                        null
                    }
                } else {
                    null
                }

                val finalImagePath = imagePath ?: run {
                    // Fallback: default "You Earned it" images (original behavior)
                    val defaultFolderPath = "You Earned it"
                    val imageExtensions = setOf(".jpeg", ".jpg", ".png", ".webp")

                    val availableImages = try {
                        context.assets.list(defaultFolderPath)?.filter { fileName ->
                            val lowerName = fileName.lowercase()
                            imageExtensions.any { lowerName.endsWith(it) }
                        }?.map { "$defaultFolderPath/$it" } ?: emptyList()
                    } catch (e: Exception) {
                        android.util.Log.e("UnlockScreen", "Error listing You Earned it folder: ${e.message}", e)
                        emptyList()
                    }

                    if (availableImages.isNotEmpty()) {
                        availableImages.random()
                    } else {
                        // As a last resort, fall back to the original hardcoded list (in case listing fails)
                        val images = listOf(
                            "You Earned it/1.jpeg",
                            "You Earned it/2.jpeg",
                            "You Earned it/3.jpeg",
                            "You Earned it/4.jpeg"
                        )
                        images.random()
                    }
                }

                val inputStream = context.assets.open(finalImagePath)
                BitmapFactory.decodeStream(inputStream)?.asImageBitmap()
            } catch (e: Exception) {
                android.util.Log.e("UnlockScreen", "Error loading image: ${e.message}", e)
                null
            }
        }
        bitmap?.let {
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
                .navigationBarsPadding() // Add padding for Android navigation bar
                .padding(start = 32.dp, top = 0.dp, end = 32.dp, bottom = 32.dp), // Then regular padding
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "45 Minutes Unlocked.",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Your Call.",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                lineHeight = 52.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // I don't need it button - gradient purple to blue
            val urgeGoneInteractionSource = remember { MutableInteractionSource() }
            val isUrgeGonePressed by urgeGoneInteractionSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFF4B0082), Color(0xFF4169E1))
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .graphicsLayer(alpha = if (isUrgeGonePressed) 0.6f else 1.0f)
                    .clickable(interactionSource = urgeGoneInteractionSource, indication = rememberRipple()) { onUrgeGone() }
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "I don't want it", fontSize = 18.sp, color = Color.White)
            }

            // Return button - ghost outlined
            val returnInteractionSource = remember { MutableInteractionSource() }
            val isReturnPressed by returnInteractionSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF999999), RoundedCornerShape(20.dp))
                    .graphicsLayer(alpha = if (isReturnPressed) 0.6f else 1.0f)
                    .clickable(interactionSource = returnInteractionSource, indication = rememberRipple()) { onDone() }
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Continue", fontSize = 18.sp, color = Color(0xFF999999))
            }
        }
    }
}
