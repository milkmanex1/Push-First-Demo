# MediaPipe Pose Model

This folder should contain the MediaPipe Pose Landmarker model file.

## Download Instructions

**IMPORTANT:** Using FULL model to avoid native crash with MediaPipe Tasks library.

1. Download `pose_landmarker_full.task` from:
   https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task

2. Create a `models` subdirectory in this folder if it doesn't exist

3. Place the file at: `app/src/main/assets/models/pose_landmarker_full.task`

4. The file should be named exactly: `pose_landmarker_full.task`

**Note:** MediaPipe requires the path to include a directory structure (must contain a slash), so the model must be in a subdirectory, not directly in assets.

**Why FULL model?** The lite model causes native crashes with the current MediaPipe Tasks version. The full model is more compatible and stable.
