# Push First - Android Demo App

A marketing demo Android app built with Kotlin + Jetpack Compose that blocks adult websites and requires push-ups to unlock.

## 🎯 Purpose

**Marketing demo only** - This is not a production-ready app. It demonstrates the concept of browser monitoring and blocking with a fun, Gen Z humor twist.

## 📱 Features

- **Accessibility Service**: Monitors browser navigation to detect adult websites
- **Blocking Overlay**: Full-screen popup that prevents browser interaction
- **Push-up Counter**: Fake counter with front camera preview (no real AI)
- **Gen Z Humor**: Casual, emoji-filled UI with phrases like "AYO chill 💀"

## 🔧 Setup Instructions

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 26+ (Android 8.0+)
- Kotlin 1.9.20+

### Build & Run

1. Open the project in Android Studio
2. Sync Gradle files
3. Connect an Android device or start an emulator
4. Click "Run" to install the app

### Required Permissions

The app requires two critical permissions that users must manually enable:

#### 1. Accessibility Service

**Location**: Settings > Accessibility > Push First

**Why**: To monitor browser windows and detect URL navigation

**How to enable**:

- Open the app
- Tap "Accessibility Service" card
- Toggle "Push First" ON
- Confirm the warning dialog

#### 2. Display Over Other Apps

**Location**: Settings > Apps > Push First > Display over other apps

**Why**: To show the blocking overlay on top of browser windows

**How to enable**:

- Open the app
- Tap "Display Over Other Apps" card
- Toggle "Allow display over other apps" ON

## 🏗️ Architecture

### Key Components

1. **MainActivity.kt**
   - Entry point
   - Permission status checker
   - UI to enable required permissions

2. **BrowserDetectionService.kt**
   - Accessibility Service implementation
   - Monitors browser window changes
   - Extracts URLs and checks against adult domain list
   - Triggers blocking overlay when match found

3. **BlockingOverlayService.kt**
   - Foreground service for overlay window
   - Creates full-screen blocking popup
   - Shows Gen Z humor messages
   - Launches push-up counter on CTA click

4. **PushupCounterActivity.kt**
   - CameraX integration for front camera
   - Fake push-up counter (button-based)
   - Unlock screen after 20 "pushups"

### Adult Domain List

Hardcoded in `BrowserDetectionService.kt`:

- pornhub.com
- xvideos.com
- xnxx.com
- hentai (any domain containing this)
- xhamster.com
- redtube.com
- youporn.com
- tube8.com
- spankwire.com
- keezmovies.com

## ⚠️ Limitations & Notes

### Demo Limitations

1. **URL Detection**:
   - Accessibility API may not expose URLs in all browsers
   - Some browsers don't provide URL information via Accessibility
   - Works best with Chrome, Edge, Firefox

2. **No Real AI**:
   - Push-up counter is fake (button clicks)
   - No actual pose detection or ML
   - Camera is just for visual effect

3. **Browser Compatibility**:
   - Only tested with major browsers
   - May not work with custom browsers or older versions

### Production Considerations

If building for production, consider:

- Backend API for domain list management
- Real ML/AI for push-up detection
- More robust URL extraction methods
- Analytics and user feedback
- Privacy policy and data handling
- Better error handling and edge cases

## 📝 Code Structure

```
app/
├── src/main/
│   ├── java/com/pushfirst/demo/
│   │   ├── MainActivity.kt              # Main entry point
│   │   ├── BrowserDetectionService.kt   # Accessibility service
│   │   ├── BlockingOverlayService.kt    # Overlay service
│   │   ├── PushupCounterActivity.kt     # Push-up counter
│   │   └── ui/theme/                    # Compose theme
│   ├── res/
│   │   ├── xml/
│   │   │   └── accessibility_service_config.xml  # Service config
│   │   └── values/
│   │       ├── strings.xml
│   │       └── themes.xml
│   └── AndroidManifest.xml
└── build.gradle.kts
```

## 🎨 UI Features

- **Material Design 3**: Modern, clean UI
- **Gen Z Humor**: Casual tone with emojis
- **Full-screen Overlays**: Blocks browser interaction
- **Camera Preview**: Front camera for push-up counter
- **Progress Indicators**: Visual feedback for push-up count

## 🚀 Testing

1. Enable both required permissions
2. Open Chrome browser
3. Navigate to any adult domain from the list
4. Blocking popup should appear immediately
5. Tap "Start Pushups" button
6. Complete 20 fake pushups (button clicks)
7. Unlock screen appears

## 📄 License

This is a demo app for marketing purposes. Use at your own discretion.

## 🙏 Credits

Built with:

- Kotlin
- Jetpack Compose
- CameraX
- Material Design 3
