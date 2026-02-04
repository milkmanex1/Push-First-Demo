package com.pushfirst.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * POSE OVERLAY
 * 
 * Draws skeleton visualization with blue lines connecting joints
 * and small circles at each landmark point
 */
@Composable
fun PoseOverlay(
    landmarks: List<NormalizedLandmark>?,
    modifier: Modifier = Modifier
) {
    // Diagnostic logging
    android.util.Log.d("POSE_DEBUG", "PoseOverlay: landmarks = ${landmarks?.size ?: 0}")
    
    if (landmarks == null || landmarks.isEmpty()) {
        android.util.Log.d("POSE_DEBUG", "PoseOverlay: No landmarks to draw")
        return
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Blue color for lines and circles (matching the image reference)
        val lineColor = Color(0xFF00D4FF) // Bright cyan-blue
        val pointColor = Color(0xFFB3EFFF) // Light blue for circles

        // Draw connections between landmarks
        // MediaPipe Pose has 33 landmarks (0-32)
        // Key connections for push-up detection:
        val connections = listOf(
            // Arms (most important for push-ups)
            Pair(11, 13), // Left shoulder to left elbow
            Pair(13, 15), // Left elbow to left wrist
            Pair(12, 14), // Right shoulder to right elbow
            Pair(14, 16), // Right elbow to right wrist
            // Torso
            Pair(11, 12), // Left shoulder to right shoulder
            Pair(11, 23), // Left shoulder to left hip
            Pair(12, 24), // Right shoulder to right hip
            Pair(23, 24), // Left hip to right hip
            // Legs (for full body visualization)
            Pair(23, 25), // Left hip to left knee
            Pair(24, 26), // Right hip to right knee
            Pair(25, 27), // Left knee to left ankle
            Pair(26, 28), // Right knee to right ankle
            // Face (optional, for complete skeleton)
            Pair(0, 2), Pair(2, 5), Pair(5, 8), Pair(8, 10),
            Pair(0, 1), Pair(1, 3), Pair(3, 7), Pair(7, 9)
        )

        // Draw lines connecting landmarks
        connections.forEach { (startIdx, endIdx) ->
            if (startIdx < landmarks.size && endIdx < landmarks.size) {
                val start = landmarks[startIdx]
                val end = landmarks[endIdx]
                
                // Check if both landmarks have good visibility
                val startVisible = start.visibility().orElse(0f) >= 0.3f
                val endVisible = end.visibility().orElse(0f) >= 0.3f
                
                if (startVisible && endVisible) {
                    // Flip X coordinates: MediaPipe detects on horizontally flipped bitmap,
                    // but camera preview is also mirrored, so we need to flip X to match preview
                    // Flip Y coordinates: MediaPipe detects on rotated bitmap,
                    // but overlay is drawn on camera preview which has different orientation
                    val startX = width - (start.x() * width) // Flip X horizontally
                    val startY = height - (start.y() * height) // Flip Y vertically
                    val endX = width - (end.x() * width) // Flip X horizontally
                    val endY = height - (end.y() * height) // Flip Y vertically
                    
                    drawLine(
                        color = lineColor,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 4f
                    )
                }
            }
        }

        // Draw circles at each landmark point
        landmarks.forEachIndexed { index, landmark ->
            val visibility = landmark.visibility().orElse(0f)
            
            if (visibility >= 0.3f) {
                // Flip X coordinates: MediaPipe detects on horizontally flipped bitmap,
                // but camera preview is also mirrored, so we need to flip X to match preview
                // Flip Y coordinates: MediaPipe detects on rotated bitmap,
                // but overlay is drawn on camera preview which has different orientation
                val x = width - (landmark.x() * width) // Flip X horizontally
                val y = height - (landmark.y() * height) // Flip Y vertically
                
                // Draw circle at landmark point
                drawCircle(
                    color = pointColor,
                    radius = 8f,
                    center = Offset(x, y)
                )
                
                // Draw a smaller inner circle for better visibility
                drawCircle(
                    color = lineColor,
                    radius = 4f,
                    center = Offset(x, y)
                )
            }
        }
    }
}
