package com.pushfirst.demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.pushfirst.demo.ui.theme.PushFirstTheme

private const val PREFS_NAME = "pushfirst_prefs"
private const val KEY_ONBOARDING_DONE = "onboarding_done"

class OnboardingActivity : ComponentActivity() {

    private val SKIP_ONBOARDING_FOR_DEV = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // DEV FLAG: bypass onboarding entirely
        if (SKIP_ONBOARDING_FOR_DEV) {
            launchMain()
            return
        }

        // If onboarding already completed, go straight to MainActivity
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDING_DONE, false)) {
            launchMain()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            PushFirstTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingFlow(
                        onFinish = {
                            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                            launchMain()
                        },
                        onSkip = {
                            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                            launchMain()
                        }
                    )
                }
            }
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
private fun OnboardingFlow(
    onFinish: () -> Unit,
    onSkip: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it } + fadeOut())
            } else {
                (slideInHorizontally { -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "onboarding_page"
    ) { currentPage ->
        when (currentPage) {
            0 -> ValuePropScreen(onNext = { page = 1 })
            1 -> HowItWorksScreen(onNext = { page = 2 })
            2 -> PaywallScreen(onFinish = onFinish, onSkip = onSkip)
        }
    }
}

// ─── Screen 1: Value Prop ───────────────────────────────────────────────────

@Composable
private fun ValuePropScreen(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Page dots
        PageIndicator(currentPage = 0, pageCount = 3)

        Spacer(modifier = Modifier.weight(0.5f))

        // Hero image
        val context = LocalContext.current
        val bitmap = remember {
            context.assets.open("Rest Stoic/Solo Stoic Statue (1).jpeg")
                .use { BitmapFactory.decodeStream(it) }
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Stoic statue",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(20.dp))
        )

        Spacer(modifier = Modifier.height(40.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.Red)) { append("Want to watch porn?") }
                    append("\n")
                    withStyle(SpanStyle(color = Color.White)) { append("Do 20 push-ups first.") }
                },
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "AI-verified. No cheating.",
                fontSize = 16.sp,
                color = Color(0xFFAAAAAA),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        GradientButton(
            text = "Next",
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ─── Screen 2: How It Works ─────────────────────────────────────────────────

@Composable
private fun HowItWorksScreen(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        PageIndicator(currentPage = 1, pageCount = 3)

        Spacer(modifier = Modifier.weight(0.3f))

        Text(
            text = "How it works",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            HowItWorksStep(
                emoji = "🚫",
                title = "You visit a blocked site",
                subtitle = "PushFirst detects it instantly in the background."
            )
            HowItWorksStep(
                emoji = "📷",
                title = "App blocks you and opens the camera",
                subtitle = "A full-screen overlay locks the browser."
            )
            HowItWorksStep(
                emoji = "💪",
                title = "Do 20 push-ups to unlock for 30 seconds",
                subtitle = "AI counts your reps. No shortcuts."
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        GradientButton(
            text = "Next",
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun HowItWorksStep(emoji: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = emoji,
            fontSize = 36.sp,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = Color(0xFF888888),
                lineHeight = 18.sp
            )
        }
    }
}

// ─── Screen 3: Paywall ──────────────────────────────────────────────────────

@Composable
private fun PaywallScreen(onFinish: () -> Unit, onSkip: () -> Unit) {
    var selectedPlan by remember { mutableStateOf("yearly") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        PageIndicator(currentPage = 2, pageCount = 3)

        Spacer(modifier = Modifier.weight(0.2f))

        Text(
            text = "Start your journey",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Plan cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PlanCard(
                modifier = Modifier.weight(1f),
                label = "Monthly",
                price = "$9.99",
                period = "/mo",
                subtext = null,
                badge = null,
                selected = selectedPlan == "monthly",
                onClick = { selectedPlan = "monthly" }
            )
            PlanCard(
                modifier = Modifier.weight(1f),
                label = "Yearly",
                price = "$2.99",
                period = "/mo",
                subtext = "billed $35.99/year",
                badge = "7 Days Free",
                selected = selectedPlan == "yearly",
                onClick = { selectedPlan = "yearly" }
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Trial timeline
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                text = "Your free trial timeline",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF888888),
                modifier = Modifier.padding(bottom = 14.dp)
            )
            TimelineRow(dot = "🟢", day = "Today", detail = "Full access starts")
            TimelineConnector()
            TimelineRow(dot = "🔔", day = "Day 6", detail = "Reminder before billing")
            TimelineConnector()
            TimelineRow(dot = "💳", day = "Day 7", detail = "Billing starts")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "No payment due now",
            fontSize = 13.sp,
            color = Color(0xFF888888),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        // CTA
        GradientButton(
            text = "Start 7-day FREE trial",
            onClick = {
                Log.d("Onboarding", "Start trial tapped — plan: $selectedPlan")
                onFinish()
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom links
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Restore purchases",
                fontSize = 13.sp,
                color = Color(0xFF888888),
                modifier = Modifier.clickable {
                    Log.d("Onboarding", "Restore purchases tapped")
                }
            )
            Text(
                text = "  ·  ",
                fontSize = 13.sp,
                color = Color(0xFF555555)
            )
            Text(
                text = "Skip",
                fontSize = 13.sp,
                color = Color(0xFF888888),
                modifier = Modifier.clickable { onSkip() }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PlanCard(
    modifier: Modifier,
    label: String,
    price: String,
    period: String,
    subtext: String?,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Color.White else Color(0xFF333333)
    val bgColor = if (selected) Color(0xFF1E1E1E) else Color(0xFF111111)

    // Outer Box — top padding creates a consistent slot for the badge on both cards
    Box(
        modifier = modifier.padding(top = 12.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor
            ),
            colors = CardDefaults.cardColors(containerColor = bgColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = if (selected) Color.White else Color(0xFF888888),
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Price + period as one annotated string — never wraps
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)) {
                            append(price)
                        }
                        withStyle(SpanStyle(fontSize = 12.sp, color = Color(0xFF888888))) {
                            append(period)
                        }
                    },
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Always reserve subtext height so both cards stay the same size
                Text(
                    text = subtext ?: "",
                    fontSize = 11.sp,
                    color = Color(0xFF666666),
                    textAlign = TextAlign.Center,
                    minLines = 1,
                    maxLines = 1
                )
            }
        }

        // Badge sits in the 12dp top-padding slot, centred above the card
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-2).dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF4CAF50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(dot: String, day: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = dot, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = day,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = detail,
            fontSize = 13.sp,
            color = Color(0xFFAAAAAA)
        )
    }
}

@Composable
private fun TimelineConnector() {
    Row {
        Spacer(modifier = Modifier.width(11.dp)) // aligns with dot center
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(14.dp)
                .background(Color(0xFF333333))
        )
    }
}

// ─── Shared: Gradient button (matches blocker screen "Start Pushups") ───────

@Composable
private fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

// ─── Shared: Page indicator dots ────────────────────────────────────────────

@Composable
private fun PageIndicator(currentPage: Int, pageCount: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isActive) Color.White else Color(0xFF444444))
                    .size(width = if (isActive) 20.dp else 6.dp, height = 6.dp)
            )
        }
    }
}
