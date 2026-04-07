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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
private const val TOTAL_ONBOARDING_PAGES = 27

// ─── Onboarding answer store ─────────────────────────────────────────────────

class OnboardingViewModel {
    var userName by mutableStateOf("")
    var gender by mutableStateOf("")
    var pornFrequency by mutableStateOf("")
    var ageFirstExposure by mutableStateOf("")
    var escalationShift by mutableStateOf(false)
    var arousedWithoutPorn by mutableStateOf("")
    var symptoms by mutableStateOf<List<String>>(emptyList())
    var triedToQuit by mutableStateOf<List<String>>(emptyList())
    var feelingsAfter by mutableStateOf<List<String>>(emptyList())
    var goals by mutableStateOf<List<String>>(emptyList())
    var referralSource by mutableStateOf("")
    var reminderTime by mutableStateOf("")
}

class OnboardingActivity : ComponentActivity() {

    private val SKIP_ONBOARDING_FOR_DEV = false
    private lateinit var billingManager: BillingManager

    // Prices populated from Play once product details load; Compose observes these automatically
    private val monthlyPrice = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val yearlyTotal = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val yearlyMonthly = androidx.compose.runtime.mutableStateOf<String?>(null)

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

        // If onboarding already completed AND subscribed, go to main
        if (prefs.getBoolean(KEY_ONBOARDING_DONE, false)
            && prefs.getBoolean(KEY_SUBSCRIPTION_ACTIVE, false)
            && !forcePaywall) {
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

        billingManager.onProductDetailsLoaded = {
            monthlyPrice.value = billingManager.getFormattedPrice(BASE_PLAN_MONTHLY)
            yearlyTotal.value = billingManager.getFormattedPrice(BASE_PLAN_YEARLY)
            yearlyMonthly.value = billingManager.getYearlyMonthlyEquivalent()
            Log.d("BILLING_DEBUG", "Prices loaded — monthly=${monthlyPrice.value} yearly=${yearlyTotal.value} yearlyMo=${yearlyMonthly.value}")
        }

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
                        startPage = when {
                            forcePaywall -> 12
                            prefs.getBoolean(KEY_ONBOARDING_DONE, false) -> 12
                            else -> 0
                        },
                        monthlyPrice = monthlyPrice.value,
                        yearlyTotal = yearlyTotal.value,
                        yearlyMonthly = yearlyMonthly.value,
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
    monthlyPrice: String?,
    yearlyTotal: String?,
    yearlyMonthly: String?,
    onStartTrial: (planId: String) -> Unit,
    onRestore: () -> Unit
) {
    var page by remember { mutableIntStateOf(startPage) }
    val viewModel = remember { OnboardingViewModel() }

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
            0 -> SplashScreen(onNext = { page = 1 })
            1 -> NameInputScreen(viewModel = viewModel, onNext = { page = 2 }, onBack = { page = 0 })
            2 -> GenderScreen(viewModel = viewModel, onNext = { page = 3 }, onBack = { page = 1 })
            3 -> PornFrequencyScreen(viewModel = viewModel, onNext = { page = 4 }, onBack = { page = 2 })
            4 -> AgeFirstExposureScreen(viewModel = viewModel, onNext = { page = 5 }, onBack = { page = 3 })
            5 -> EscalationScreen(viewModel = viewModel, onNext = { page = 6 }, onBack = { page = 4 })
            6 -> TriedToQuitScreen(viewModel = viewModel, onNext = { page = 7 }, onBack = { page = 5 })
            7 -> FeelingsScreen(viewModel = viewModel, onNext = { page = 8 }, onBack = { page = 6 })
            8 -> SymptomsScreen(viewModel = viewModel, onNext = { page = 9 }, onBack = { page = 7 })
            9 -> EducationCarouselScreen(onNext = { page = 10 }, onBack = { page = 8 })
            10 -> WillpowerFailsScreen(onNext = { page = 11 }, onBack = { page = 9 })
            11 -> HowItWorksScreen(onNext = { page = 12 }, onBack = { page = 10 })
            12 -> PaywallScreen(
                monthlyPrice = monthlyPrice,
                yearlyTotal = yearlyTotal,
                yearlyMonthly = yearlyMonthly,
                onStartTrial = onStartTrial,
                onRestore = onRestore,
                onBack = { page = 11 }
            )
        }
    }
}

// ─── Shared: Starry background ──────────────────────────────────────────────

@Composable
private fun StarryBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0D1A3A), Color(0xFF060A14)),
                    radius = 1800f
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rng = java.util.Random(12345L)
            repeat(100) {
                drawCircle(
                    color = Color.White.copy(alpha = rng.nextFloat() * 0.5f + 0.1f),
                    radius = rng.nextFloat() * 1.8f + 0.4f,
                    center = Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height)
                )
            }
        }
        content()
    }
}

// ─── Shared: Quiz top bar (back arrow + linear progress bar) ────────────────

@Composable
private fun QuizTopBar(currentPage: Int, totalPages: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "←",
            fontSize = 24.sp,
            color = Color.White,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onBack() }
                .padding(end = 16.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1E2044))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (currentPage.toFloat() / totalPages).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF00BFFF), Color(0xFF00FFCC))
                        )
                    )
            )
        }
    }
}

// ─── Screen: Splash ──────────────────────────────────────────────────────────

@Composable
private fun SplashScreen(onNext: () -> Unit) {
    StarryBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "💪", fontSize = 56.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "PushFirst",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 2.sp
                )
            }

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Replace every urge\nwith a ") }
                    withStyle(SpanStyle(color = Color(0xFF00D4FF))) { append("rep.") }
                },
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                lineHeight = 42.sp
            )

            Column {
                GradientButton(
                    text = "Get Started  →",
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

// ─── Screen: Name Input ──────────────────────────────────────────────────────

@Composable
private fun NameInputScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            QuizTopBar(currentPage = 1, totalPages = TOTAL_ONBOARDING_PAGES, onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = "First things first,",
                        fontSize = 16.sp,
                        color = Color(0xFF888888)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "What should we\ncall you?",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 38.sp
                    )
                }

                OutlinedTextField(
                    value = viewModel.userName,
                    onValueChange = { viewModel.userName = it },
                    placeholder = { Text("Name", color = Color(0xFF666666)) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00D4FF),
                        unfocusedBorderColor = Color(0xFF333355),
                        cursorColor = Color(0xFF00D4FF),
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    val enabled = viewModel.userName.isNotBlank()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (enabled)
                                    Brush.horizontalGradient(listOf(Color(0xFF4B0082), Color(0xFF4169E1)))
                                else
                                    Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF333333)))
                            )
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = if (enabled) rememberRipple(color = Color.White) else null
                            ) { if (enabled) onNext() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Continue",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) Color.White else Color(0xFF555555)
                        )
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

// ─── Screen: Gender ──────────────────────────────────────────────────────────

@Composable
private fun GenderScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val options = listOf("Male", "Female", "Other")

    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            QuizTopBar(currentPage = 2, totalPages = TOTAL_ONBOARDING_PAGES, onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = "Tell us about yourself,",
                        fontSize = 16.sp,
                        color = Color(0xFF888888)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "What's your gender?",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 38.sp
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    options.forEach { option ->
                        val selected = viewModel.gender == option
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) Color(0xFF00D4FF).copy(alpha = 0.12f)
                                    else Color(0xFF111122)
                                )
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Color(0xFF00D4FF) else Color(0xFF2A2A44),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = rememberRipple(color = Color(0xFF00D4FF))
                                ) { viewModel.gender = option },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option,
                                fontSize = 16.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color(0xFF00D4FF) else Color.White
                            )
                        }
                    }
                }

                Column {
                    val enabled = viewModel.gender.isNotBlank()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (enabled)
                                    Brush.horizontalGradient(listOf(Color(0xFF4B0082), Color(0xFF4169E1)))
                                else
                                    Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF333333)))
                            )
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = if (enabled) rememberRipple(color = Color.White) else null
                            ) { if (enabled) onNext() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Continue",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) Color.White else Color(0xFF555555)
                        )
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
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

// ─── Shared: Single-select quiz screen builder ──────────────────────────────
//  Reused by PornFrequency, AgeFirstExposure, and Escalation screens.

@Composable
private fun SingleSelectQuizScreen(
    currentPage: Int,
    subtext: String,
    question: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    autoAdvance: Boolean = false
) {
    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            QuizTopBar(currentPage = currentPage, totalPages = TOTAL_ONBOARDING_PAGES, onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(text = subtext, fontSize = 16.sp, color = Color(0xFF888888))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = question,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 36.sp
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    options.forEach { option ->
                        val isSelected = selected == option
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSelected) Color(0xFF00D4FF).copy(alpha = 0.12f)
                                    else Color(0xFF111122)
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF00D4FF) else Color(0xFF2A2A44),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = rememberRipple(color = Color(0xFF00D4FF))
                                ) { onSelect(option) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color(0xFF00D4FF) else Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                if (!autoAdvance) {
                    Column {
                        val enabled = selected.isNotBlank()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (enabled)
                                        Brush.horizontalGradient(listOf(Color(0xFF4B0082), Color(0xFF4169E1)))
                                    else
                                        Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF333333)))
                                )
                                .clickable(
                                    enabled = enabled,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = if (enabled) rememberRipple(color = Color.White) else null
                                ) { if (enabled) onNext() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Continue",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (enabled) Color.White else Color(0xFF555555)
                            )
                        }
                        Spacer(modifier = Modifier.height(48.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

// ─── Screen: Porn Frequency ──────────────────────────────────────────────────

@Composable
private fun PornFrequencyScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    SingleSelectQuizScreen(
        currentPage = 3,
        subtext = "Now let's understand your habits,",
        question = "How often do you watch porn?",
        options = listOf(
            "More than once a day",
            "Once a day",
            "A few times a week",
            "Less than once a week"
        ),
        selected = viewModel.pornFrequency,
        onSelect = { viewModel.pornFrequency = it },
        onNext = onNext,
        onBack = onBack
    )
}

// ─── Screen: Age First Exposure ──────────────────────────────────────────────

@Composable
private fun AgeFirstExposureScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    SingleSelectQuizScreen(
        currentPage = 4,
        subtext = "Let's understand your history,",
        question = "At what age did you first encounter explicit content?",
        options = listOf("12 or younger", "13 to 16", "17 to 24", "25 or older"),
        selected = viewModel.ageFirstExposure,
        onSelect = { viewModel.ageFirstExposure = it },
        onNext = onNext,
        onBack = onBack
    )
}

// ─── Screen: Escalation ──────────────────────────────────────────────────────

@Composable
private fun EscalationScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    var localSelection by remember { mutableStateOf(if (viewModel.escalationShift) "Yes" else "") }
    val scope = rememberCoroutineScope()

    SingleSelectQuizScreen(
        currentPage = 5,
        subtext = "Be honest with yourself,",
        question = "Have you noticed a shift toward more extreme or graphic material?",
        options = listOf("Yes", "No"),
        selected = localSelection,
        onSelect = { choice ->
            localSelection = choice
            viewModel.escalationShift = (choice == "Yes")
            scope.launch {
                kotlinx.coroutines.delay(300)
                onNext()
            }
        },
        onNext = onNext,
        onBack = onBack,
        autoAdvance = true
    )
}

// ─── Screen: Symptoms ────────────────────────────────────────────────────────

// Triple = (textBeforeBold, boldText, textAfterBold)
private val SYMPTOM_SECTIONS = listOf(
    "Mental" to listOf(
        Triple("Feeling ", "unmotivated", ""),
        Triple("", "Lack of ambition", " to pursue goals"),
        Triple("Difficulty ", "concentrating", ""),
        Triple("", "Poor memory", " or 'brain fog'"),
        Triple("General ", "anxiety", "")
    ),
    "Physical" to listOf(
        Triple("", "Tiredness", " and lethargy"),
        Triple("", "Low libido", " or sex drive"),
        Triple("", "Weak erections", " without porn"),
        Triple("Low energy", "", " / fatigue"),
        Triple("Disrupted ", "sleep", "")
    ),
    "Social" to listOf(
        Triple("", "Low self-confidence", ""),
        Triple("", "Feeling unattractive", " or unworthy of love"),
        Triple("", "Unsuccessful", " or unenjoyable sex"),
        Triple("", "Reduced desire", " to socialize"),
        Triple("", "Feeling isolated", " from others")
    )
)

@Composable
private fun SymptomsScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // Fixed top bar — no progress bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    fontSize = 24.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onBack() }
                        .padding(end = 16.dp)
                )
                Text(
                    text = "Symptoms",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                // Spacer to balance the back arrow width
                Spacer(modifier = Modifier.width(40.dp))
            }

            // Scrollable symptom list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Red alert banner
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFCC2222))
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = "Excessive porn use can have negative impacts psychologically.",
                            fontSize = 15.sp,
                            color = Color.White,
                            lineHeight = 22.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Select any symptoms below:",
                        fontSize = 16.sp,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                SYMPTOM_SECTIONS.forEach { (category, items) ->
                    item {
                        Text(
                            text = category,
                            fontSize = 15.sp,
                            color = Color(0xFF888888),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items.forEach { (before, boldWord, after) ->
                        val label = before + boldWord + after
                        item {
                            val isSelected = label in viewModel.symptoms
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF111122))
                                    .border(1.dp, Color(0xFF2A2A44), RoundedCornerShape(50))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = rememberRipple(color = Color(0xFFCC2222))
                                    ) {
                                        viewModel.symptoms = if (isSelected)
                                            viewModel.symptoms - label
                                        else
                                            viewModel.symptoms + label
                                    }
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Circle radio indicator
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isSelected) Color(0xFFCC2222) else Color.Transparent)
                                        .border(2.dp, if (isSelected) Color(0xFFCC2222) else Color(0xFF666688), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(Color.White)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(color = Color.White)) { append(before) }
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(boldWord) }
                                        withStyle(SpanStyle(color = Color.White)) { append(after) }
                                    },
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            }

            // Fixed bottom button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
                    .height(56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFCC2222))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = rememberRipple(color = Color.White)
                    ) { onNext() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Reboot my brain",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

// ─── Screen: Tried To Quit ────────────────────────────────────────────────────

@Composable
private fun TriedToQuitScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val options = listOf(
        "Nothing yet",
        "Willpower alone",
        "Screen time limiters",
        "Cold turkey",
        "Accountability partners",
        "Other apps"
    )

    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            QuizTopBar(currentPage = 7, totalPages = TOTAL_ONBOARDING_PAGES, onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = "You said these symptoms sound familiar, so I'd like to ask:",
                        fontSize = 15.sp,
                        color = Color(0xFF888888),
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "What have you already tried to quit?",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 36.sp
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEach { option ->
                        val isSelected = option in viewModel.triedToQuit
                        val atMax = viewModel.triedToQuit.size >= 3 && !isSelected
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSelected) Color(0xFF00D4FF).copy(alpha = 0.12f)
                                    else Color(0xFF111122)
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF00D4FF) else Color(0xFF2A2A44),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable(
                                    enabled = !atMax,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = rememberRipple(color = Color(0xFF00D4FF))
                                ) {
                                    viewModel.triedToQuit = if (isSelected)
                                        viewModel.triedToQuit - option
                                    else
                                        viewModel.triedToQuit + option
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color(0xFF00D4FF) else if (atMax) Color(0xFF444466) else Color.White
                            )
                        }
                    }
                }

                Column {
                    val enabled = viewModel.triedToQuit.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (enabled)
                                    Brush.horizontalGradient(listOf(Color(0xFF4B0082), Color(0xFF4169E1)))
                                else
                                    Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF333333)))
                            )
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = if (enabled) rememberRipple(color = Color.White) else null
                            ) { if (enabled) onNext() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Continue",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) Color.White else Color(0xFF555555)
                        )
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

// ─── Screen: Feelings After ───────────────────────────────────────────────────

@Composable
private fun FeelingsScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val options = listOf(
        "😣" to "Guilty",
        "😶" to "Empty",
        "😔" to "Ashamed",
        "😞" to "Unmotivated",
        "😤" to "Like I wasted time",
        "😰" to "Anxious"
    )

    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            QuizTopBar(currentPage = 8, totalPages = TOTAL_ONBOARDING_PAGES, onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(text = "Let's zoom in...", fontSize = 16.sp, color = Color(0xFF888888))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "How does watching porn make you feel afterward?",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 36.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Choose up to 2", fontSize = 14.sp, color = Color(0xFF666688))
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEach { (emoji, label) ->
                        val isSelected = label in viewModel.feelingsAfter
                        val atMax = viewModel.feelingsAfter.size >= 2 && !isSelected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSelected) Color(0xFF00D4FF).copy(alpha = 0.12f)
                                    else Color(0xFF111122)
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF00D4FF) else Color(0xFF2A2A44),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable(
                                    enabled = !atMax,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = rememberRipple(color = Color(0xFF00D4FF))
                                ) {
                                    viewModel.feelingsAfter = if (isSelected)
                                        viewModel.feelingsAfter - label
                                    else
                                        viewModel.feelingsAfter + label
                                }
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = emoji, fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = label,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color(0xFF00D4FF) else if (atMax) Color(0xFF444466) else Color.White
                            )
                        }
                    }
                }

                Column {
                    val enabled = viewModel.feelingsAfter.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (enabled)
                                    Brush.horizontalGradient(listOf(Color(0xFF4B0082), Color(0xFF4169E1)))
                                else
                                    Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF333333)))
                            )
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = if (enabled) rememberRipple(color = Color.White) else null
                            ) { if (enabled) onNext() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Continue",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) Color.White else Color(0xFF555555)
                        )
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

// ─── Shared: Education slide (full-screen solid color, emoji hero) ───────────

@Composable
private fun EducationSlide(
    currentPage: Int,
    bgColor: Color,
    emoji: String,
    title: String,
    body: String,
    useGradientButton: Boolean = false,
    onNext: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Text(text = emoji, fontSize = 80.sp, textAlign = TextAlign.Center)

            Spacer(modifier = Modifier.weight(0.6f))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 34.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = body,
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            PageIndicator(
                currentPage = currentPage,
                pageCount = 17,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            if (useGradientButton) {
                GradientButton(
                    text = "Next  >",
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = rememberRipple(color = Color.Black)
                        ) { onNext() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Next  >",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─── Screen: Education Carousel (pages 9–13 collapsed into swipeable pager) ──

private data class SlideData(
    val bg: Color,
    val emoji: String,
    val title: String,
    val body: String,
    val gradientBtn: Boolean = false
)

private val EDUCATION_SLIDES = listOf(
    SlideData(Color(0xFFD32F2F), "🧠", "Porn is a drug",
        "Using porn releases a chemical in the brain called dopamine. This chemical makes you feel good — it's why you feel pleasure when you watch porn."),
    SlideData(Color(0xFFD32F2F), "💔", "Porn destroys relationships",
        "Porn reduces your hunger for a real relationship and replaces it with the hunger for more porn."),
    SlideData(Color(0xFFD32F2F), "⚡", "Porn shatters sex drive",
        "More than 50% of porn addicts have reported a loss of interest in real sex, and an overall decrease in their sex drive."),
    SlideData(Color(0xFFD32F2F), "😔", "Feeling unhappy?",
        "An elevated dopamine level means you need more dopamine to feel good. This is why so many heavy porn users report feeling depressed, unmotivated, and anti-social."),
    SlideData(Color(0xFF0A1628), "🌱", "Path to Recovery",
        "Recovery is possible. By abstaining from porn, your brain can reset its dopamine sensitivity, leading to healthier relationships and improved well-being.",
        gradientBtn = true)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EducationCarouselScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { EDUCATION_SLIDES.size })
    val scope = rememberCoroutineScope()
    val currentSlide = EDUCATION_SLIDES[pagerState.currentPage]

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { index ->
            val slide = EDUCATION_SLIDES[index]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(slide.bg)
                    .systemBarsPadding()
                    .padding(horizontal = 28.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = slide.emoji, fontSize = 80.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = slide.title,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 34.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = slide.body,
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
                }
            }
        }

        // Fixed bottom overlay — dots + button floating above the pager
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(currentSlide.bg)   // matches settled slide so no flash
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PageIndicator(
                currentPage = pagerState.currentPage,
                pageCount = EDUCATION_SLIDES.size,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            val isLast = pagerState.currentPage == EDUCATION_SLIDES.lastIndex

            if (currentSlide.gradientBtn) {
                GradientButton(
                    text = if (isLast) "Next  >" else "Next  >",
                    onClick = {
                        if (isLast) onNext()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = rememberRipple(color = Color.Black)
                        ) {
                            if (isLast) onNext()
                            else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Next  >",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
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
    monthlyPrice: String?,
    yearlyTotal: String?,
    yearlyMonthly: String?,
    onStartTrial: (planId: String) -> Unit,
    onRestore: () -> Unit,
    onBack: (() -> Unit)?
) {
    var selectedPlan by remember { mutableStateOf(BASE_PLAN_YEARLY) }
    val resolvedMonthly = monthlyPrice ?: "$9.99"
    val resolvedYearlyTotal = yearlyTotal ?: "$35.99"
    val resolvedYearlyMonthly = yearlyMonthly ?: "$2.99"

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
                price = resolvedMonthly,
                period = "/mo",
                subtext = null,
                badge = null,
                selected = selectedPlan == BASE_PLAN_MONTHLY,
                onClick = { selectedPlan = BASE_PLAN_MONTHLY }
            )
            PlanCard(
                modifier = Modifier.weight(1f),
                label = "Yearly",
                price = resolvedYearlyTotal,
                period = "/yr",
                subtext = "$resolvedYearlyMonthly/mo",
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

        Spacer(modifier = Modifier.height(12.dp))

        // Dynamic subscription terms
        Text(
            text = if (selectedPlan == BASE_PLAN_YEARLY)
                "7-day free trial, then $resolvedYearlyTotal/year ($resolvedYearlyMonthly/mo). Cancel anytime in Google Play settings. You won't be charged during the trial period."
            else
                "$resolvedMonthly/month. Cancel anytime in Google Play settings.",
            fontSize = 12.sp,
            color = Color(0xFF666666),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
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

        Spacer(modifier = Modifier.height(10.dp))

        // Legal links
        val uriHandler = LocalUriHandler.current
        val legalText = buildAnnotatedString {
            append("By subscribing, you agree to our ")
            pushStringAnnotation(tag = "URL", annotation = "https://pushfirst.netlify.app/terms")
            withStyle(SpanStyle(color = Color(0xFF888888), fontWeight = FontWeight.SemiBold)) {
                append("Terms of Service")
            }
            pop()
            append(" and ")
            pushStringAnnotation(tag = "URL", annotation = "https://pushfirst.netlify.app/privacy")
            withStyle(SpanStyle(color = Color(0xFF888888), fontWeight = FontWeight.SemiBold)) {
                append("Privacy Policy")
            }
            pop()
            append(".")
        }
        ClickableText(
            text = legalText,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 11.sp,
                color = Color(0xFF555555),
                textAlign = TextAlign.Center
            ),
            onClick = { offset ->
                legalText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                    color = Color(0xFFAAAAAA),
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
