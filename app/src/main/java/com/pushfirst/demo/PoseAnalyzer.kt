package com.pushfirst.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import android.content.Context
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.*
import java.io.ByteArrayOutputStream

/**
 * POSE ANALYZER
 * 
 * Analyzes camera frames using MediaPipe Pose to detect push-ups.
 * Uses joint angle calculations (not ML classification) to count reps.
 * 
 * State Machine:
 * - UP: elbow angle > ~160° (arms extended)
 * - DOWN: elbow angle < ~90° (arms bent)
 * - Rep counted on transition: DOWN → UP
 */
class PoseAnalyzer(
    private val context: Context,
    private val onRepCountChanged: (Int) -> Unit = {},
    private val onStateChanged: (PushupState) -> Unit = {},
    private val onValidPoseChanged: (Boolean) -> Unit = {}
) {
    private var poseLandmarker: PoseLandmarker? = null
    private var currentState: PushupState = PushupState.UNKNOWN
    private var repCount: Int = 0
    private var isValidPose: Boolean = false
    private var currentLandmarks: List<NormalizedLandmark>? = null
    
    // Handler for main thread callbacks
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Angle thresholds for state detection
    private val UP_THRESHOLD = 160.0 // degrees - arms extended
    private val DOWN_THRESHOLD = 90.0 // degrees - arms bent
    
    // Minimum visibility threshold for landmarks
    private val MIN_VISIBILITY = 0.5f
    
    // Angle smoothing
    private val angleHistory = ArrayDeque<Double>(5)
    private val ANGLE_HISTORY_SIZE = 5
    
    // CRITICAL: MediaPipe Tasks MUST NOT be initialized in constructors or init blocks
    // Initialization must happen from Activity.onCreate() on the main thread
    // This prevents native crashes during Compose composition
    
    /**
     * Initialize MediaPipe Pose Landmarker
     * MUST be called from Activity.onCreate() on the main thread
     * MUST NOT be called from constructor, init block, or Compose composition
     */
    fun initialize() {
        android.util.Log.d("POSE_DEBUG", "initialize: Starting initialization")
        
        // First, verify model file exists
        val modelPath = "models/pose_landmarker_full.task"
        try {
            val assetManager = context.assets
            val modelFile = assetManager.open(modelPath)
            modelFile.close()
            android.util.Log.d("POSE_DEBUG", "initialize: Model file found at: $modelPath")
        } catch (e: java.io.FileNotFoundException) {
            android.util.Log.e("POSE_DEBUG", "initialize: ❌ MODEL FILE NOT FOUND: $modelPath")
            android.util.Log.e("POSE_DEBUG", "initialize: Expected location: app/src/main/assets/$modelPath")
            android.util.Log.e("POSE_DEBUG", "initialize: Download from: https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task")
            android.util.Log.e("POSE_DEBUG", "initialize: Create 'models' subdirectory in assets/ and place the file there")
            return
        } catch (e: Exception) {
            android.util.Log.e("POSE_DEBUG", "initialize: Error checking model file: ${e.message}", e)
            return
        }
        
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelPath)
                .build()
            
            android.util.Log.d("POSE_DEBUG", "initialize: Model path configured: $modelPath")
            
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, image ->
                    processPoseResult(result, image)
                }
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            
            android.util.Log.d("POSE_DEBUG", "initialize: Creating PoseLandmarker instance")
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            
            if (poseLandmarker != null) {
                android.util.Log.d("POSE_DEBUG", "initialize: ✅ Successfully initialized")
            } else {
                android.util.Log.e("POSE_DEBUG", "initialize: ❌ createFromOptions returned null")
            }
        } catch (e: java.io.FileNotFoundException) {
            android.util.Log.e("POSE_DEBUG", "initialize: ❌ FileNotFoundException: Model file not found")
            android.util.Log.e("POSE_DEBUG", "initialize: Expected: app/src/main/assets/$modelPath")
            android.util.Log.e("POSE_DEBUG", "initialize: Download URL: https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task")
        } catch (e: Exception) {
            android.util.Log.e("POSE_DEBUG", "initialize: ❌ Failed: ${e.message}", e)
            android.util.Log.e("POSE_DEBUG", "initialize: Exception type: ${e.javaClass.simpleName}")
            android.util.Log.e("POSE_DEBUG", "initialize: Stack trace: ${android.util.Log.getStackTraceString(e)}")
        }
    }
    
    /**
     * Process a camera frame from CameraX ImageAnalysis
     * Converts ImageProxy to MediaPipe image format and runs inference
     */
    fun analyzeFrame(imageProxy: ImageProxy) {
        val poseLandmarker = this.poseLandmarker ?: run {
            android.util.Log.w("POSE_DEBUG", "analyzeFrame: PoseLandmarker is null, skipping frame")
            imageProxy.close()
            return
        }
        
        try {
            android.util.Log.v("POSE_DEBUG", "analyzeFrame: Processing frame ${imageProxy.width}x${imageProxy.height}")
            
            // Convert ImageProxy to Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)
            
            // Convert Bitmap to MPImage
            val mpImage = BitmapImageBuilder(bitmap).build()
            
            // Get current timestamp for MediaPipe (required for LIVE_STREAM mode)
            val frameTime = SystemClock.uptimeMillis()
            
            // Run pose detection (result will be handled in callback)
            poseLandmarker.detectAsync(mpImage, frameTime)
            
        } catch (e: Exception) {
            android.util.Log.e("POSE_DEBUG", "analyzeFrame: Error analyzing frame: ${e.message}", e)
        } finally {
            // Always close the ImageProxy to prevent memory leaks
            imageProxy.close()
        }
    }
    
    /**
     * Convert ImageProxy to Bitmap
     * Handles YUV_420_888 format from CameraX
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
            100,
            out
        )
        val imageBytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalStateException("Failed to decode bitmap")
        
        // Rotate and mirror bitmap for front camera
        val matrix = Matrix().apply {
            // Mirror horizontally for front camera
            postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            // Apply rotation if needed
            if (imageProxy.imageInfo.rotationDegrees != 0) {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
        }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        
        return bitmap
    }
    
    /**
     * Process MediaPipe pose detection result
     * Extracts landmarks, calculates angles, and updates state machine
     */
    private fun processPoseResult(result: PoseLandmarkerResult, image: MPImage) {
        android.util.Log.d("POSE_DEBUG", "processPoseResult: landmarks count = ${result.landmarks().size}")
        
        if (result.landmarks().isEmpty()) {
            android.util.Log.d("POSE_DEBUG", "processPoseResult: No landmarks detected")
            updateValidPose(false)
            currentLandmarks = null
            return
        }
        
        // Use first detected pose
        val landmarks = result.landmarks()[0]
        android.util.Log.d("POSE_DEBUG", "processPoseResult: Found ${landmarks.size} landmarks in pose")
        
        // ALWAYS store landmarks for visualization, even if not all are visible
        // This allows skeleton to be drawn with partial detections
        currentLandmarks = landmarks
        android.util.Log.d("POSE_DEBUG", "processPoseResult: Stored landmarks for visualization")
        
        // Extract required landmarks for push-up detection
        // MediaPipe Pose landmark indices:
        // 11 = left shoulder, 12 = right shoulder
        // 13 = left elbow, 14 = right elbow
        // 15 = left wrist, 16 = right wrist
        // 23 = left hip, 24 = right hip
        
        if (landmarks.size < 33) {
            android.util.Log.w("POSE_DEBUG", "processPoseResult: Not enough landmarks (${landmarks.size} < 33)")
            updateValidPose(false)
            return
        }
        
        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]
        val leftElbow = landmarks[13]
        val rightElbow = landmarks[14]
        val leftWrist = landmarks[15]
        val rightWrist = landmarks[16]
        val leftHip = landmarks[23]
        val rightHip = landmarks[24]
        
        // Check if all required landmarks are visible for push-up detection
        val requiredLandmarks = listOf(
            leftShoulder, rightShoulder,
            leftElbow, rightElbow,
            leftWrist, rightWrist,
            leftHip, rightHip
        )
        
        // visibility() returns Optional<Float>, so we need to extract the value
        val allVisible = requiredLandmarks.all { landmark ->
            landmark.visibility().orElse(0f) >= MIN_VISIBILITY
        }
        
        if (!allVisible) {
            android.util.Log.d("POSE_DEBUG", "processPoseResult: Not all required landmarks visible (threshold: $MIN_VISIBILITY)")
            updateValidPose(false)
            // Keep landmarks for visualization even if not valid for push-up detection
            return
        }
        
        android.util.Log.d("POSE_DEBUG", "processPoseResult: All required landmarks visible - valid pose")
        updateValidPose(true)
        
        // Check body orientation - must be in push-up position (front-facing camera)
        if (!isInPushupPosition(leftShoulder, rightShoulder, leftHip, rightHip, leftElbow, rightElbow)) {
            android.util.Log.d("POSE_DEBUG", "Not in push-up position, skipping")
            if (currentState != PushupState.WRONG_POSITION) {
                currentState = PushupState.WRONG_POSITION
                mainHandler.post { onStateChanged(currentState) }
            }
            angleHistory.clear()
            return
        }
        
        // Calculate elbow angles for both arms
        val leftElbowAngle = calculateAngle(
            leftShoulder.x(), leftShoulder.y(),
            leftElbow.x(), leftElbow.y(),
            leftWrist.x(), leftWrist.y()
        )
        
        val rightElbowAngle = calculateAngle(
            rightShoulder.x(), rightShoulder.y(),
            rightElbow.x(), rightElbow.y(),
            rightWrist.x(), rightWrist.y()
        )
        
        // Use average of both arms for more stable detection
        val avgElbowAngle = (leftElbowAngle + rightElbowAngle) / 2.0
        
        // Update state machine based on elbow angle
        updateState(avgElbowAngle)
        
        // Clean up MPImage
        image.close()
    }
    
    /**
     * Check if body is in push-up position (front-facing camera)
     * Checks that shoulders and hips are close in Y (body is flat), elbows are at shoulder level,
     * and shoulders are level (not tilted)
     */
    private fun isInPushupPosition(
        leftShoulder: NormalizedLandmark,
        rightShoulder: NormalizedLandmark,
        leftHip: NormalizedLandmark,
        rightHip: NormalizedLandmark,
        leftElbow: NormalizedLandmark,
        rightElbow: NormalizedLandmark
    ): Boolean {
        // In normalized coords: Y=0 is top of frame, Y=1 is bottom
        // When standing: shoulders near top, hips near middle/bottom, large Y gap
        // When in push-up position facing camera: shoulders and hips are close in Y,
        // elbows are roughly at or below shoulder level

        val shoulderMidY = (leftShoulder.y() + rightShoulder.y()) / 2f
        val hipMidY = (leftHip.y() + rightHip.y()) / 2f
        val elbowMidY = (leftElbow.y() + rightElbow.y()) / 2f

        // Check 1: Shoulders and hips should be close in Y (body is flat/horizontal toward camera)
        // When standing, hipMidY is much larger than shoulderMidY (hips are lower in frame)
        // When in push-up position, they should be within ~0.3 of each other
        val shoulderHipYDiff = abs(hipMidY - shoulderMidY)
        val isBodyFlat = shoulderHipYDiff < 0.35f

        // Check 2: Elbows should be at roughly the same Y level as shoulders or slightly below
        // When standing with arms down, elbows are far below shoulders
        val elbowShoulderYDiff = elbowMidY - shoulderMidY
        val elbowsInRange = elbowShoulderYDiff < 0.3f

        // Check 3: Left and right shoulders should be roughly level (not tilted >30% of frame height)
        val shoulderLevelDiff = abs(leftShoulder.y() - rightShoulder.y())
        val shouldersLevel = shoulderLevelDiff < 0.25f

        android.util.Log.d("POSE_DEBUG", "PushupPosition check - shoulderHipYDiff: $shoulderHipYDiff, elbowShoulderYDiff: $elbowShoulderYDiff, shoulderLevelDiff: $shoulderLevelDiff")
        android.util.Log.d("POSE_DEBUG", "isBodyFlat: $isBodyFlat, elbowsInRange: $elbowsInRange, shouldersLevel: $shouldersLevel")

        return isBodyFlat && elbowsInRange && shouldersLevel
    }
    
    /**
     * Calculate angle at point B (middle point) given three points A, B, C
     * Returns angle in degrees
     */
    private fun calculateAngle(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Double {
        // Vector BA (from B to A)
        val baX = ax - bx
        val baY = ay - by
        
        // Vector BC (from B to C)
        val bcX = cx - bx
        val bcY = cy - by
        
        // Calculate angle using dot product and magnitudes
        val dotProduct = baX * bcX + baY * bcY
        val magnitudeBA = sqrt((baX * baX + baY * baY).toDouble())
        val magnitudeBC = sqrt((bcX * bcX + bcY * bcY).toDouble())
        
        if (magnitudeBA == 0.0 || magnitudeBC == 0.0) {
            return 180.0 // Default to straight angle if vectors are invalid
        }
        
        val cosAngle = dotProduct / (magnitudeBA * magnitudeBC)
        // Clamp to [-1, 1] to avoid NaN from acos
        val clampedCos = cosAngle.coerceIn(-1.0, 1.0)
        val angleRadians = acos(clampedCos)
        val angleDegrees = Math.toDegrees(angleRadians)
        
        return angleDegrees
    }
    
    /**
     * Update push-up state based on elbow angle
     * Counts rep on transition DOWN → UP
     */
    private fun updateState(elbowAngle: Double) {
        // Smooth angle over last 5 frames
        angleHistory.addLast(elbowAngle)
        if (angleHistory.size > ANGLE_HISTORY_SIZE) angleHistory.removeFirst()
        val smoothedAngle = angleHistory.average()
        
        val newState = when {
            smoothedAngle >= UP_THRESHOLD -> PushupState.UP
            smoothedAngle <= DOWN_THRESHOLD -> PushupState.DOWN
            else -> {
                // If in between thresholds, maintain current state
                // But if state is UNKNOWN, initialize to UP (starting position)
                if (currentState == PushupState.UNKNOWN) {
                    PushupState.UP
                } else {
                    currentState
                }
            }
        }
        
        // Count rep on transition DOWN → UP
        if (currentState == PushupState.DOWN && newState == PushupState.UP) {
            repCount++
            // Post callback on main thread
            mainHandler.post { onRepCountChanged(repCount) }
        }
        
        // Update state if changed
        if (newState != currentState) {
            currentState = newState
            // Post callback on main thread
            mainHandler.post { onStateChanged(currentState) }
        }
    }
    
    /**
     * Update valid pose flag
     */
    private fun updateValidPose(valid: Boolean) {
        if (isValidPose != valid) {
            isValidPose = valid
            // Post callback on main thread
            mainHandler.post { onValidPoseChanged(isValidPose) }
        }
    }
    
    /**
     * Get current rep count
     */
    fun getRepCount(): Int = repCount
    
    /**
     * Get current push-up state
     */
    fun getCurrentState(): PushupState = currentState
    
    /**
     * Check if pose is valid (all landmarks visible)
     */
    fun getIsValidPose(): Boolean = isValidPose
    
    /**
     * Get current landmarks for visualization
     */
    fun getCurrentLandmarks(): List<NormalizedLandmark>? = currentLandmarks
    
    /**
     * Check if PoseLandmarker is initialized and ready
     */
    fun isPoseLandmarkerInitialized(): Boolean = poseLandmarker != null
    
    /**
     * Reset rep count and state
     */
    fun reset() {
        repCount = 0
        currentState = PushupState.UNKNOWN
        isValidPose = false
        angleHistory.clear()
        onRepCountChanged(repCount)
        onStateChanged(currentState)
        onValidPoseChanged(isValidPose)
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }
}

/**
 * Push-up state enum
 */
enum class PushupState {
    UNKNOWN,  // Initial state or pose not detected
    UP,       // Arms extended (elbow angle > 160°)
    DOWN,     // Arms bent (elbow angle < 90°)
    WRONG_POSITION  // Body detected but not in push-up position
}
