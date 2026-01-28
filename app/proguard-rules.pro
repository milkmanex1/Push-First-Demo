# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep CameraX classes
-keep class androidx.camera.** { *; }

# Keep Accessibility Service
-keep class com.pushfirst.demo.BrowserDetectionService { *; }
-keep class com.pushfirst.demo.BlockingOverlayService { *; }
