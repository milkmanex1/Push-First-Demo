# Push First - Quick Setup Guide

## 🚀 Getting Started

### Step 1: Build and Install

1. Open the project in Android Studio
2. Sync Gradle files (File > Sync Project with Gradle Files)
3. Connect an Android device (API 26+) or start an emulator
4. Click Run (▶️) to build and install

### Step 2: Enable Required Permissions

The app requires **TWO critical permissions** that users must manually enable:

---

## 📋 Permission 1: Accessibility Service

**Purpose**: Monitors browser windows to detect when you navigate to adult websites.

**How to Enable**:

1. Open the **Push First** app
2. Tap the **"Accessibility Service"** card (it will show ❌)
3. This opens Android Settings > Accessibility
4. Find **"Push First"** in the list
5. Toggle it **ON**
6. Confirm the warning dialog (Android will warn about security)
7. Return to the app - the card should now show ✅

**Why Required**:

- Android Accessibility API is the only way to monitor browser navigation
- Cannot be granted programmatically (security feature)
- User must explicitly enable it

**What It Does**:

- Listens for browser window changes
- Extracts URLs from browser windows
- Checks against adult domain list
- Triggers blocking overlay when match found

---

## 📋 Permission 2: Display Over Other Apps

**Purpose**: Allows the app to show a full-screen blocking popup over browser windows.

**How to Enable**:

1. Open the **Push First** app
2. Tap the **"Display Over Other Apps"** card (it will show ❌)
3. This opens Android Settings > Apps > Push First > Display over other apps
4. Toggle **"Allow display over other apps"** ON
5. Return to the app - the card should now show ✅

**Why Required**:

- System overlay windows require special permission
- Needed to block browser interaction
- Cannot be granted programmatically (security feature)

**What It Does**:

- Creates full-screen overlay window
- Blocks user interaction with browser
- Shows blocking popup with Gen Z humor

---

## ✅ Verification

Once both permissions are enabled:

- Both cards should show ✅
- Status message: **"✅ All set! The service is running."**
- The app is now active and monitoring browser navigation

---

## 🧪 Testing the App

1. **Enable both permissions** (see above)

2. **Open Chrome browser** (or another supported browser)

3. **Navigate to an adult domain** from the hardcoded list:
   - pornhub.com
   - xvideos.com
   - xnxx.com
   - xhamster.com
   - redtube.com
   - (or any domain containing "hentai")

4. **Blocking popup should appear immediately** with messages like:
   - "AYO chill 💀"
   - "Earn it. 20 pushups."
   - "No reps = no happy time"

5. **Tap "Start Pushups 💪"** button

6. **Complete 20 fake pushups**:
   - Front camera preview appears
   - Tap "Push-up Complete! 💪" button 20 times
   - Progress bar shows completion

7. **Unlock screen appears**: "Happy Time Unlocked 😈"

---

## ⚠️ Important Notes

### Browser Compatibility

- Works best with: Chrome, Edge, Firefox, Samsung Internet
- May not work with all browsers or browser versions
- Some browsers don't expose URLs via Accessibility API

### URL Detection Limitations

- Accessibility API has limitations
- URL extraction may not work 100% of the time
- Some browsers hide URL information for privacy

### Demo Limitations

- **No real AI**: Push-up counter is fake (button clicks)
- **No ML detection**: Camera is just for visual effect
- **Hardcoded domains**: List is in `BrowserDetectionService.kt`

---

## 🔧 Troubleshooting

### Permission Not Staying Enabled

- Make sure you're not using battery optimization that kills the service
- Check: Settings > Apps > Push First > Battery > Unrestricted

### Blocking Popup Not Appearing

1. Verify both permissions are enabled (check app status)
2. Try restarting the browser
3. Check if browser is in the supported list
4. Check Logcat for errors: `adb logcat | grep BrowserDetectionService`

### Camera Not Working

- Grant camera permission when prompted
- Make sure device has front camera
- Check Logcat: `adb logcat | grep PushupCounterActivity`

---

## 📱 Supported Android Versions

- **Minimum**: Android 8.0 (API 26)
- **Target**: Android 14 (API 34)
- **Tested on**: Android 10, 11, 12, 13, 14

---

## 🛠️ Development Notes

### Key Files

- `BrowserDetectionService.kt` - Accessibility service for URL detection
- `BlockingOverlayService.kt` - Overlay service for blocking popup
- `PushupCounterActivity.kt` - Push-up counter with CameraX
- `MainActivity.kt` - Permission management UI

### Adding More Domains

Edit `BrowserDetectionService.kt`, add to `ADULT_DOMAINS` list:

```kotlin
private val ADULT_DOMAINS = listOf(
    "pornhub.com",
    "your-domain.com",  // Add here
    // ...
)
```

### Customizing Messages

Edit `BlockingOverlayService.kt`, modify `phrases` list in `BlockingPopupScreen`:

```kotlin
val phrases = listOf(
    "Your custom message 💀",
    // ...
)
```

---

## 📄 License

Marketing demo app - use at your own discretion.
