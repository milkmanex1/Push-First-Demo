@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.pushfirst.demo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pushfirst.demo.ui.theme.PushFirstTheme

/**
 * Main Activity - Entry point of the app
 * 
 * This activity:
 * 1. Checks if Accessibility Service is enabled
 * 2. Checks if Overlay permission is granted
 * 3. Provides UI to enable required permissions
 * 4. Shows app status
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    @Composable
    fun MainScreen() {
        var accessibilityEnabled by remember { mutableStateOf(false) }
        var overlayGranted by remember { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current

        // Function to refresh permission status
        val refreshPermissions: () -> Unit = {
            accessibilityEnabled = BrowserDetectionService.isServiceEnabled(this@MainActivity)
            overlayGranted = Settings.canDrawOverlays(this@MainActivity)
        }

        // Check permissions on composition
        LaunchedEffect(Unit) {
            refreshPermissions()
        }

        // Refresh permissions when activity resumes (user returns from settings)
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Push First 💪",
                fontSize = 32.sp,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "Marketing Demo App",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Status indicators
            StatusCard(
                title = "Accessibility Service",
                enabled = accessibilityEnabled,
                description = "Detects browser navigation",
                onClick = {
                    // Open Accessibility Settings
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            StatusCard(
                title = "Display Over Other Apps",
                enabled = overlayGranted,
                description = "Shows blocking popup",
                onClick = {
                    // Request overlay permission
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (accessibilityEnabled && overlayGranted) {
                Text(
                    text = "✅ All set! The service is running.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // Test button to manually trigger overlay
                Button(
                    onClick = {
                        // Manually trigger blocking overlay for testing
                        val intent = Intent(this@MainActivity, BlockingOverlayService::class.java)
                        intent.putExtra("blocked_domain", "test-site.com")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            @Suppress("DEPRECATION")
                            startService(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🧪 Test Overlay Popup")
                }
            } else {
                Text(
                    text = "⚠️ Enable both permissions to start blocking",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
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
}
