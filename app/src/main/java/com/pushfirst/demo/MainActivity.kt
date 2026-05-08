@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.pushfirst.demo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pushfirst.demo.ui.theme.PushFirstTheme

class MainActivity : ComponentActivity() {

    private lateinit var billingManager: BillingManager

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Permission result handled via refreshPermissions on resume
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        // Show the main screen immediately — subscription check runs in background
        setContent {
            PushFirstTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }

        // Background subscription enforcement
        billingManager = BillingManager(
            context = this,
            onPurchaseSuccess = {},
            onPurchaseCancelled = {},
            onPurchaseError = {}
        )

        billingManager.connect(onReady = {
            if (AppConfig.SKIP_ONBOARDING_FOR_DEV) return@connect
            lifecycleScope.launch {
                val isSubscribed = billingManager.checkSubscriptionStatus()
                Log.d("BILLING_DEBUG", "MainActivity subscription check — isSubscribed=$isSubscribed")

                if (!isSubscribed) {
                    withContext(Dispatchers.Main) {
                        Log.d("BILLING_DEBUG", "Not subscribed — redirecting to paywall")
                        startActivity(
                            Intent(this@MainActivity, OnboardingActivity::class.java)
                                .putExtra("force_paywall", true)
                        )
                        finish()
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billingManager.isInitialized) billingManager.destroy()
    }

    @Composable
    fun MainScreen() {
        var accessibilityEnabled by remember { mutableStateOf(false) }
        var overlayGranted by remember { mutableStateOf(false) }
        var notificationGranted by remember { mutableStateOf(false) }
        var showAccessibilityDialog by remember { mutableStateOf(false) }
        var showWelcomeScreen by remember { mutableStateOf(true) }
        val lifecycleOwner = LocalLifecycleOwner.current

        val refreshPermissions: () -> Unit = {
            accessibilityEnabled = BrowserDetectionService.isServiceEnabled(this@MainActivity)
            overlayGranted = Settings.canDrawOverlays(this@MainActivity)
            notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        LaunchedEffect(Unit) {
            refreshPermissions()
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshPermissions()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Background image — welcome screen only
            if (showWelcomeScreen) {
                val context = LocalContext.current
                val bgBitmap = remember {
                    context.assets.open("Rest Stoic/Rest 2.jpeg")
                        .use { BitmapFactory.decodeStream(it) }
                }
                Image(
                    bitmap = bgBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Dark scrim so text stays readable
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000))
                )
            }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            if (showWelcomeScreen) {
                // Screen 1 - Welcome Screen
                Spacer(modifier = Modifier.height(100.dp))

                Text(
                    text = "PUSH FIRST",
                    fontSize = 54.sp,
                    fontFamily = CinzelFont,
                    letterSpacing = 6.sp,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "Block adult sites. Earn access back with push-ups.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 0.dp)
                )

                Spacer(modifier = Modifier.height(60.dp))

                Text(
                    text = "HOW IT WORKS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                Text(
                    text = "1. Enable three permissions below",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                )

                Text(
                    text = "2. Browse normally — we watch in the background",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                )

                Text(
                    text = "3. Get blocked → do 20 push-ups → unlock 45 mins",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 42.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                GradientButton(
                    text = "Get Started",
                    onClick = { showWelcomeScreen = false },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(50.dp))
            } else {
                // Screen 2 - Setup Screen
                Spacer(modifier = Modifier.height(50.dp))

                Text(
                    text = "Setup (2 steps)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                StatusCard(
                    title = "Accessibility Service",
                    enabled = accessibilityEnabled,
                    description = if (accessibilityEnabled) "Enabled ✓" else "Tap to enable →",
                    onClick = {
                        if (accessibilityEnabled) {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        } else {
                            showAccessibilityDialog = true
                        }
                    }
                )

                if (!accessibilityEnabled) {
                    Text(
                        text = "Tap above, then look for 'Installed apps' or 'Downloaded apps' → select PushFirst",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }

                if (showAccessibilityDialog) {
                    AccessibilityPermissionDialog(
                        onDismiss = { showAccessibilityDialog = false },
                        onContinue = {
                            showAccessibilityDialog = false
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                StatusCard(
                    title = "Display Over Other Apps",
                    enabled = overlayGranted,
                    description = if (overlayGranted) "Enabled ✓" else "Tap to enable →",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                StatusCard(
                    title = "Notifications",
                    enabled = notificationGranted,
                    description = "Shows unlock countdown timer",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            startActivity(intent)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (accessibilityEnabled && overlayGranted && notificationGranted) {
                    Text(
                        text = "✅ All set! You're protected.",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = { showWelcomeScreen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF555555)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFAAAAAA)
                    )
                ) {
                    Text("Back")
                }

                Spacer(modifier = Modifier.height(12.dp))

                GradientButton(
                    text = "Done",
                    onClick = { this@MainActivity.finish() },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(50.dp))
            }
        }
        } // end outer Box
    }

    @Composable
    fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 17.sp) {
        val interactionSource = remember { MutableInteractionSource() }
        val gradient = Brush.horizontalGradient(
            colors = listOf(Color(0xFF4B0082), Color(0xFF4169E1))
        )
        Box(
            modifier = modifier
                .height(56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(gradient)
                .clickable(
                    interactionSource = interactionSource,
                    indication = rememberRipple(color = Color.White)
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }

    @Composable
    fun StatusCard(
        title: String,
        enabled: Boolean,
        description: String,
        onClick: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            colors = CardDefaults.cardColors(
                containerColor = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (enabled) "✅" else "❌",
                    fontSize = 24.sp
                )
            }
        }
    }

    @Composable
    fun AccessibilityPermissionDialog(
        onDismiss: () -> Unit,
        onContinue: () -> Unit
    ) {
        // Use a standard full-screen dialog but handle insets manually
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true  // KEY FIX: let system handle insets
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()  // KEY FIX: respects both status bar and nav bar
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween  // KEY FIX: pushes buttons to bottom of safe area
                ) {
                    // Top content
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Accessibility Permission Required",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )

                        Text(
                            text = "PushFirst uses Android's Accessibility Service to monitor your browser's address bar in real time.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "This permission is used to:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "• Detect when you visit a website on your block list",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "• Trigger a blocking screen to prevent access",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "What we DO NOT do:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "• We do not collect, store, or transmit your browsing data",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "• We do not read page content, only the URL",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "• We do not share any data with third parties",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "All processing happens entirely on your device.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "You can disable this permission at any time in:\nSettings → Accessibility → PushFirst",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Bottom buttons — always above nav bar due to systemBarsPadding()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val view = LocalView.current
                        
                        OutlinedButton(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onDismiss()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF555555)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFAAAAAA)
                            )
                        ) {
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        GradientButton(
                            text = "I Understand, Continue",
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onContinue()
                            },
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}