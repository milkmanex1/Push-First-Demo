# Push-Up Detection Implementation

## Overview

On-device AI push-up detection has been implemented using MediaPipe Pose and CameraX. The system detects push-ups in real-time using joint angle calculations (not ML classification).

## Implementation Details

### 1. Dependencies Added

- **MediaPipe Tasks Vision**: `com.google.mediapipe:tasks-vision:0.10.8`
- **CameraX**: Already present (for camera frame capture)

### 2. Files Created/Modified

#### New Files:

- `app/src/main/java/com/pushfirst/demo/PoseAnalyzer.kt`
  - Handles MediaPipe Pose inference
  - Calculates joint angles (shoulder → elbow → wrist)
  - Implements push-up state machine (UP/DOWN based on elbow angles)
  - Counts reps on DOWN → UP transition

#### Modified Files:

- `app/build.gradle.kts` - Added MediaPipe dependency
- `app/src/main/java/com/pushfirst/demo/PushupCounterActivity.kt`
  - Integrated ImageAnalysis use case for frame processing
  - Connected PoseAnalyzer to camera frames
  - Updated UI to show AI-detected counts instead of button clicks

### 3. Key Features

#### PoseAnalyzer Class:

- **Joint Angle Calculation**: Calculates elbow angles from MediaPipe landmarks
- **State Machine**:
  - `UP`: Elbow angle ≥ 160° (arms extended)
  - `DOWN`: Elbow angle ≤ 90° (arms bent)
  - Rep counted on transition: DOWN → UP
- **Landmark Detection**: Uses MediaPipe Pose landmarks:
  - Shoulders (11, 12)
  - Elbows (13, 14)
  - Wrists (15, 16)
  - Hips (23, 24)
- **Visibility Check**: Ensures all required landmarks are visible before counting

#### Integration:

- Uses CameraX `ImageAnalysis` to process frames
- Converts `ImageProxy` (YUV_420_888) to Bitmap for MediaPipe
- Handles front camera mirroring and rotation
- Properly closes resources to prevent memory leaks
- Callbacks run on main thread for UI updates

## Setup Required

### 1. Download MediaPipe Model File

**CRITICAL**: You must download the MediaPipe Pose model file before running the app.

1. Download `pose_landmarker_lite.task` from:

   ```
   https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
   ```

2. Place the file in:

   ```
   app/src/main/assets/pose_landmarker_lite.task
   ```

3. The file should be approximately 2-3 MB in size.

**Note**: If the model file is missing, the app will fail to initialize PoseAnalyzer. Check Logcat for errors like "Failed to initialize PoseLandmarker".

### 2. Build and Run

1. Sync Gradle files to download MediaPipe dependency
2. Ensure the model file is in `app/src/main/assets/`
3. Build and run the app
4. Grant camera permission when prompted
5. Position yourself in front of the front camera
6. Perform push-ups - the AI will count automatically

## How It Works

1. **Frame Capture**: CameraX captures frames from front camera
2. **Image Analysis**: Each frame is processed by ImageAnalysis use case
3. **Pose Detection**: MediaPipe Pose detects body landmarks
4. **Angle Calculation**: Elbow angles are calculated from landmark positions
5. **State Detection**: Current state (UP/DOWN) is determined from angles
6. **Rep Counting**: Rep is counted when transitioning from DOWN → UP
7. **UI Update**: Count and status are displayed in real-time

## Configuration

### Angle Thresholds (in `PoseAnalyzer.kt`):

- `UP_THRESHOLD = 160.0` degrees - Arms extended
- `DOWN_THRESHOLD = 90.0` degrees - Arms bent

### Visibility Threshold:

- `MIN_VISIBILITY = 0.5` - Minimum landmark visibility (0.0 to 1.0)

### Confidence Thresholds:

- `MinPoseDetectionConfidence = 0.5f`
- `MinPosePresenceConfidence = 0.5f`
- `MinTrackingConfidence = 0.5f`

These can be adjusted in `PoseAnalyzer.initializePoseLandmarker()` if needed.

## Performance Considerations

- **Frame Rate**: ImageAnalysis processes frames as fast as MediaPipe can handle
- **Backpressure Strategy**: `STRATEGY_KEEP_ONLY_LATEST` ensures only latest frame is processed
- **Memory Management**: ImageProxy and MPImage are properly closed after use
- **Threading**: Pose detection runs on background thread, UI updates on main thread

## Troubleshooting

### App crashes on startup:

- Check if model file exists in `app/src/main/assets/`
- Check Logcat for MediaPipe initialization errors

### No pose detection:

- Ensure good lighting
- Position yourself fully in camera view
- Check that all body parts (shoulders, elbows, wrists, hips) are visible

### Incorrect rep counting:

- Adjust angle thresholds if needed
- Ensure smooth push-up motion (full extension and full bend)
- Check that pose is valid (all landmarks visible)

### Performance issues:

- Reduce ImageAnalysis resolution if needed
- Consider using `pose_landmarker_lite.task` (lighter model) vs full model

## Testing

1. **Basic Test**: Perform 1-2 push-ups and verify count increments
2. **Full Test**: Complete 20 push-ups to trigger unlock screen
3. **Edge Cases**:
   - Partial push-ups (should not count)
   - Moving out of frame (should pause detection)
   - Multiple people in frame (uses first detected pose)

## Notes

- Detection is based on **joint angles**, not ML classification
- Uses **on-device inference only** - no cloud/backend required
- **Front camera only** as specified
- Changes are **minimal and localized** - no refactoring of unrelated code
