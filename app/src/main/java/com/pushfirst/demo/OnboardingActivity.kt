package com.pushfirst.demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
private const val KEY_SUBSCRIPTION_ACTIVE = "subscription_active"

class OnboardingActivity : ComponentActivity() {

    private val SKIP_ONBOARDING_FOR_DEV = false
    private lateinit var billingManager: BillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // DEV FLAG: bypass onboarding and wipe all onboarding prefs so next run starts clean
        if (SKIP_ONBOARDING_FOR_DEV) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_ONBOARDING_DONE)
                .remove(KEY_SUBSCRIPTION_ACTIVE)
                .apply()
            launchMain()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val forcePaywall = intent.getBooleanExtra("force_paywall", false)

        // If onboarding already completed and we're not force-showing the paywall, go to main
        if (prefs.getBoolean(KEY_ONBOARDING_DONE, false) && !forcePaywall) {
            launchMain()
            return
        }

        billingManager = BillingManager(
            context = this,
            onPurchaseSuccess = {
                Log.d("BILLING_DEBUG", "Purchase succeeded — saving state and launching main")
                prefs.edit()
                    .putBoolean(KEY_ONBOARDING_DONE, true)
                    .putBoolean(KEY_SUBSCRIPTION_ACTIVE, true)
                    .apply()
                runOnUiThread { launchMain() }
            },
            onPurchaseCancelled = {
                Log.d("BILLING_DEBUG", "Purchase cancelled — staying on paywall")
            },
            onPurchaseError = { message ->
                Log.e("BILLING_DEBUG", "Purchase error: $message")
            }
        )

        billingManager.connect(onReady = {
            // On force_paywall (re-entry from MainActivity), check if user somehow already
            // has an active subscription and let them through if so.
            // On first-launch onboarding, never auto-skip — let the user complete the flow.
            if (forcePaywall) {
                lifecycleScope.launch {
                    val isSubscribed = billingManager.checkSubscriptionStatus()
                    if (isSubscribed) {
                        Log.d("BILLING_DEBUG", "Active subscription confirmed — skipping paywall")
                        withContext(Dispatchers.Main) { launchMain() }
                    }
                }
            }
        })

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            PushFirstTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingFlow(
                        startPage = if (forcePaywall) 5 else 0,
                        onStartTrial = { planId ->
                            billingManager.launchPurchaseFlow(this@OnboardingActivity, planId)
                        },
                        onRestore = {
                            billingManager.restorePurchases()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billingManager.isInitialized) billingManager.destroy()
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
private fun OnboardingFlow(
    startPage: Int = 0,
    onStartTrial: (planId: String) -> Unit,
    onRestore: () -> Unit
) {
    var page by remember { mutableIntStateOf(startPage) }

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
            0 -> ValuePropScreen(onNext = { page = 1 }, onBack = null)
            1 -> PornIsDrugScreen(onNext = { page = 2 }, onBack = { page = 0 })
            2 -> RealCostScreen(onNext = { page = 3 }, onBack = { page = 1 })
            3 -> WillpowerFailsScreen(onNext = { page = 4 }, onBack = { page = 2 })
            4 -> HowItWorksScreen(onNext = { page = 5 }, onBack = { page = 3 })
            5 -> PaywallScreen(onStartTrial = onStartTrial, onRestore = onRestore, onBack = { page = 4 })
        }
    }
}

// ─── Screen 1: Value Prop ───────────────────────────────────────────────────

@Composable
private fun ValuePropScreen(onNext: () -> Unit, onBack: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        OnboardingTopBar(currentPage = 0, pageCount = 6, onBack = onBack)

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

        val redGlow = Shadow(color = Color(0xFFFF3B3B), offset = Offset.Zero, blurRadius = 20f)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) {
                        append("Want to watch porn?")
                    }
                    append("\n")
                    withStyle(SpanStyle(color = Color(0xFFFF3B3B), shadow = redGlow)) {
                        append("Do 20 push-ups first.")
                    }
                },
                fontSize = 29.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = CinzelFont,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                lineHeight = 39.sp
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
private fun HowItWorksScreen(onNext: () -> Unit, onBack: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        OnboardingTopBar(currentPage = 4, pageCount = 6, onBack = onBack)

        Spacer(modifier = Modifier.weight(0.3f))

        Text(
            text = "How it works",
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = CinzelFont,
            letterSpacing = 3.sp,
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

// ─── Screen 3: Porn is a drug ───────────────────────────────────────────────

@Composable
private fun PornIsDrugScreen(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val redGlow = Shadow(color = Color(0xFFFF3B3B), offset = Offset.Zero, blurRadius = 20f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        OnboardingTopBar(currentPage = 1, pageCount = 6, onBack = onBack)

        Spacer(modifier = Modifier.weight(0.5f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(0xFFFF3B3B), shadow = redGlow)) {
                        append("Porn")
                    }
                    withStyle(SpanStyle(color = Color.White)) {
                        append(" is a drug.")
                    }
                },
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = CinzelFont,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                lineHeight = 42.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = buildAnnotatedString {
                    append("It hijacks your brain's ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                        append("dopamine")
                    }
                    append(" system — the same chemical behind every addiction. Over time, you need more to feel anything.")
                },
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

// ─── Screen 4: The real cost ─────────────────────────────────────────────────

@Composable
private fun RealCostScreen(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val redGlow = Shadow(color = Color(0xFFFF3B3B), offset = Offset.Zero, blurRadius = 15f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        OnboardingTopBar(currentPage = 2, pageCount = 6, onBack = onBack)

        Spacer(modifier = Modifier.weight(0.4f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "The real cost.",
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = CinzelFont,
                letterSpacing = 3.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    "Kills your sex drive." to "Over 50% of heavy users report loss of interest in real sex.",
                    "Destroys relationships." to "Porn replaces your desire for real connection with a craving for more porn.",
                    "Hijacks your motivation." to "Elevated dopamine leaves you depressed, foggy, and unmotivated."
                ).forEach { (headline, subtext) ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = headline,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF3B3B),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            style = androidx.compose.ui.text.TextStyle(shadow = redGlow)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = subtext,
                            fontSize = 14.sp,
                            color = Color(0xFFAAAAAA),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
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

// ─── Screen 5: Willpower fails ───────────────────────────────────────────────

@Composable
private fun WillpowerFailsScreen(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val cyanGlow = Shadow(color = Color(0xFF00D4FF), offset = Offset.Zero, blurRadius = 20f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        OnboardingTopBar(currentPage = 3, pageCount = 6, onBack = onBack)

        Spacer(modifier = Modifier.weight(0.4f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) {
                        append("Willpower fails.\n")
                    }
                    withStyle(SpanStyle(color = Color(0xFF00D4FF), shadow = cyanGlow)) {
                        append("Replacement works.")
                    }
                },
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = CinzelFont,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                lineHeight = 42.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = buildAnnotatedString {
                    append("Science shows the most effective way to break an addiction is to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                        append("replace it with a physical habit")
                    }
                    append(". Every push-up rewires your brain.")
                },
                fontSize = 16.sp,
                color = Color(0xFFAAAAAA),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Backed by behavioral science research.",
            fontSize = 12.sp,
            color = Color(0xFF555555),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        GradientButton(
            text = "Next",
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ─── Screen 6: Paywall ──────────────────────────────────────────────────────

@Composable
private fun PaywallScreen(
    onStartTrial: (planId: String) -> Unit,
    onRestore: () -> Unit,
    onBack: (() -> Unit)?
) {
    var selectedPlan by remember { mutableStateOf(BASE_PLAN_YEARLY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        OnboardingTopBar(currentPage = 5, pageCount = 6, onBack = onBack)

        Spacer(modifier = Modifier.weight(0.2f))

        Text(
            text = "Start your journey",
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = CinzelFont,
            letterSpacing = 3.sp,
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
                selected = selectedPlan == BASE_PLAN_MONTHLY,
                onClick = { selectedPlan = BASE_PLAN_MONTHLY }
            )
            PlanCard(
                modifier = Modifier.weight(1f),
                label = "Yearly",
                price = "$2.99",
                period = "/mo",
                subtext = "billed $35.99/year",
                badge = "7 Days Free",
                selected = selectedPlan == BASE_PLAN_YEARLY,
                onClick = { selectedPlan = BASE_PLAN_YEARLY }
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
            text = if (selectedPlan == BASE_PLAN_YEARLY) "Start 7-day FREE trial" else "Subscribe now",
            onClick = {
                Log.d("BILLING_DEBUG", "Start trial tapped — plan: $selectedPlan")
                onStartTrial(selectedPlan)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom links
        Text(
            text = "Restore purchases",
            fontSize = 13.sp,
            color = Color(0xFF888888),
            modifier = Modifier.clickable {
                Log.d("BILLING_DEBUG", "Restore purchases tapped")
                onRestore()
            }
        )

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

// ─── Shared: Top nav bar (back arrow + page dots) ───────────────────────────

@Composable
private fun OnboardingTopBar(currentPage: Int, pageCount: Int, onBack: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        if (onBack != null) {
            Text(
                text = "←",
                fontSize = 24.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onBack() }
                    .padding(4.dp)
            )
        }
        PageIndicator(
            currentPage = currentPage,
            pageCount = pageCount,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ─── Shared: Page indicator dots ────────────────────────────────────────────

@Composable
private fun PageIndicator(currentPage: Int, pageCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
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
