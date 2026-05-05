package com.pushfirst.demo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.Executors
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
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
import android.media.SoundPool
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
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
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
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
    var reminderTime by mutableStateOf("")
}

class OnboardingActivity : ComponentActivity() {

    private val SKIP_ONBOARDING_FOR_DEV = false
    private lateinit var billingManager: BillingManager
    private var onboardingPoseAnalyzer: PoseAnalyzer? = null
    private val cameraExecutorForOnboarding = lazy { Executors.newSingleThreadExecutor() }

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
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false

        // CRITICAL: Initialize MediaPipe from Activity.onCreate() on main thread
        // MUST NOT be initialized inside Compose composition or LaunchedEffect
        onboardingPoseAnalyzer = PoseAnalyzer(context = this)
        onboardingPoseAnalyzer?.initialize()

        setContent {
            PushFirstTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingFlow(
                        startPage = when {
                            forcePaywall -> 20
                            prefs.getBoolean(KEY_ONBOARDING_DONE, false) -> 20
                            else -> 0
                        },
                        monthlyPrice = monthlyPrice.value,
                        yearlyTotal = yearlyTotal.value,
                        yearlyMonthly = yearlyMonthly.value,
                        poseAnalyzer = onboardingPoseAnalyzer,
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
        onboardingPoseAnalyzer?.close()
        onboardingPoseAnalyzer = null
        if (cameraExecutorForOnboarding.isInitialized()) cameraExecutorForOnboarding.value.shutdown()
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
    poseAnalyzer: PoseAnalyzer?,
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
            1 -> PreQuizScreen(onNext = { page = 2 }, onBack = if (AppConfig.SHOW_DEV_BACK_ARROWS) {{ page = 0 }} else null)
            2 -> NameInputScreen(viewModel = viewModel, onNext = { page = 3 }, onBack = { page = 1 })
            3 -> GenderScreen(viewModel = viewModel, onNext = { page = 4 }, onBack = { page = 2 })
            4 -> PornFrequencyScreen(viewModel = viewModel, onNext = { page = 5 }, onBack = { page = 3 })
            5 -> AgeFirstExposureScreen(viewModel = viewModel, onNext = { page = 6 }, onBack = { page = 4 })
            6 -> EscalationScreen(viewModel = viewModel, onNext = { page = 7 }, onBack = { page = 5 })
            7 -> SymptomsScreen(viewModel = viewModel, onNext = { page = 8 }, onBack = { page = 6 })
            8 -> FeelingsScreen(viewModel = viewModel, onNext = { page = 9 }, onBack = { page = 7 })
            9 -> TriedToQuitScreen(viewModel = viewModel, onNext = { page = 10 }, onBack = { page = 8 })
            10 -> EducationCarouselScreen(onNext = { page = 11 }, onBack = { page = 9 })
            11 -> ScienceAgreeScreen(onNext = { page = 12 }, onBack = { page = 10 })
            12 -> HowItWorksBlockScreen(onNext = { page = 13 }, onBack = { page = 11 })
            13 -> HowItWorksPushScreen(onNext = { page = 14 }, onBack = { page = 12 })
            14 -> HowItWorksChoiceScreen(onNext = { page = 15 }, onBack = { page = 13 })
            15 -> RewiringBenefitsScreen(onNext = { page = 16 }, onBack = { page = 14 })
            16 -> GoalsScreen(viewModel = viewModel, onNext = { page = 17 }, onBack = { page = 15 })
            17 -> SocialProofScreen(onNext = { page = 18 }, onBack = { page = 16 })
            18 -> CameraTrialScreen(
                poseAnalyzer = poseAnalyzer,
                onNext = { page = 19 },
                onBack = if (AppConfig.SHOW_DEV_BACK_ARROWS) {{ page = 17 }} else null
            )
            19 -> YourPlanScreen(
                viewModel = viewModel,
                onNext = { page = 20 },
                onBack = { page = 18 }
            )
            20 -> PaywallScreen(
                monthlyPrice = monthlyPrice,
                yearlyTotal = yearlyTotal,
                yearlyMonthly = yearlyMonthly,
                onStartTrial = onStartTrial,
                onRestore = onRestore,
                onBack = if (AppConfig.SHOW_DEV_BACK_ARROWS) {{ page = 19 }} else null
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
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Layer 1: Full-screen background image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data("file:///android_asset/Onboarding/splash1.jpeg")
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Layer 5: Bottom fade — dark vignette so button area is clean
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF010510).copy(alpha = 0.85f),
                            Color(0xFF010510)
                        )
                    )
                )
        )

        // Content on top of all background layers
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(0.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset(y = (-72).dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "PushFirst",
                        fontSize = 55.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily(Font(R.font.bebas_neue)),
                        color = Color.White,
                        letterSpacing = 3.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file:///android_asset/Icon/icon_white.png")
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .height(52.dp)
                            .clip(RoundedCornerShape(22.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                        append("Replace every ")
                    }
                    withStyle(SpanStyle(color = Color(0xFFE86CFF), fontWeight = FontWeight.ExtraBold)) {
                        append("urge")
                    }
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                        append("\nwith a ")
                    }
                    withStyle(SpanStyle(color = Color(0xFF42F5FF), fontWeight = FontWeight.ExtraBold)) {
                        append("rep.")
                    }
                },
                modifier = Modifier.offset(y = 36.dp),
                fontSize = 30.sp,
                textAlign = TextAlign.Center,
                lineHeight = 42.sp
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

// ─── Screen: Pre-Quiz ────────────────────────────────────────────────────────

@Composable
private fun PreQuizScreen(onNext: () -> Unit, onBack: (() -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data("file:///android_asset/Onboarding/splash1.jpeg")
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF010510).copy(alpha = 0.85f),
                            Color(0xFF010510)
                        )
                    )
                )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 32.dp)
        ) {
            val screenHeight = maxHeight

            if (onBack != null) {
                Text(
                    text = "←",
                    fontSize = 24.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 8.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }
                )
            }

            Text(
                text = "Welcome.",
                fontSize = 54.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = screenHeight * 0.10f)
            )

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                            append("Let's find out if you have a problem with ")
                        }
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                            append("porn")
                        }
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                            append(".")
                        }
                    },
                    fontSize = 29.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 42.sp,
                    modifier = Modifier.padding(bottom = 88.dp)
                )
                GradientButton(
                    text = "Start Quiz →",
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 48.dp)
                )
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

                Column(modifier = Modifier.navigationBarsPadding()) {
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
                    Spacer(modifier = Modifier.height(16.dp))
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

                Column(modifier = Modifier.navigationBarsPadding()) {
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
                    Spacer(modifier = Modifier.height(16.dp))
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

// ─── (HowItWorksScreen removed — replaced by HowPushFirstWorksScreen) ───────

@Composable
private fun _HowItWorksScreen_DELETED(onNext: () -> Unit, onBack: (() -> Unit)?) {
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
    val scope = rememberCoroutineScope()
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
                                ) {
                                    onSelect(option)
                                    if (autoAdvance) {
                                        scope.launch {
                                            kotlinx.coroutines.delay(300)
                                            onNext()
                                        }
                                    }
                                },
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
        onBack = onBack,
        autoAdvance = true
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
        onBack = onBack,
        autoAdvance = true
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

    SingleSelectQuizScreen(
        currentPage = 5,
        subtext = "Be honest with yourself,",
        question = "Have you noticed a shift toward more extreme or graphic material?",
        options = listOf("Yes", "No"),
        selected = localSelection,
        onSelect = { choice ->
            localSelection = choice
            viewModel.escalationShift = (choice == "Yes")
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
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Choose up to 3",
                        fontSize = 14.sp,
                        color = Color(0xFF666688)
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

                Column(modifier = Modifier.navigationBarsPadding()) {
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
                    Spacer(modifier = Modifier.height(16.dp))
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

                Column(modifier = Modifier.navigationBarsPadding()) {
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
                    Spacer(modifier = Modifier.height(16.dp))
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
    val imageAssetPath: String? = null,
    val gradientBtn: Boolean = false
)

private val EDUCATION_SLIDES = listOf(
    SlideData(Color(0xFFC91C21), "🧠", "Porn is a drug",
        imageAssetPath = "Onboarding/brain2.png",
        body = "Using porn releases a chemical in the brain called dopamine. This chemical makes you feel good — it's why you feel pleasure when you watch porn."),
    SlideData(Color(0xFFC91C21), "💔", "Porn destroys relationships",
        imageAssetPath = "Onboarding/heartbreak2.png",
        body = "Porn reduces your hunger for a real relationship and replaces it with the hunger for more porn."),
    SlideData(Color(0xFFC91C21), "⚡", "Porn shatters sex drive",
        imageAssetPath = "Onboarding/bed1.jpeg",
        body = "More than 50% of porn addicts have reported a loss of interest in real sex, and an overall decrease in their sex drive."),
    SlideData(Color(0xFFC91C21), "😔", "Feeling unhappy?",
        imageAssetPath = "Onboarding/sad.png",
        body = "An elevated dopamine level means you need more dopamine to feel good. This is why so many heavy porn users report feeling depressed, unmotivated, and anti-social."),
    SlideData(Color(0xFF0A1628), "🌱", "Path to Recovery",
        imageAssetPath = "Onboarding/plant1.png",
        body = "Recovery is possible. By abstaining from porn, your brain can reset its dopamine sensitivity, leading to healthier relationships and improved well-being.",
        gradientBtn = true)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EducationCarouselScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { EDUCATION_SLIDES.size })
    val scope = rememberCoroutineScope()
    val currentSlide = EDUCATION_SLIDES[pagerState.currentPage]
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(EDUCATION_SLIDES.lastIndex)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            flingBehavior = flingBehavior
        ) { index ->
            val slide = EDUCATION_SLIDES[index]
            val context = LocalContext.current
            val heroBitmap = remember(slide.imageAssetPath) {
                slide.imageAssetPath?.let { path ->
                    context.assets.open(path).use { BitmapFactory.decodeStream(it) }
                }
            }
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
                    if (heroBitmap != null) {
                        Image(
                            bitmap = heroBitmap.asImageBitmap(),
                            contentDescription = slide.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        )
                    } else {
                        Text(text = slide.emoji, fontSize = 120.sp, textAlign = TextAlign.Center)
                    }
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

        if (AppConfig.SHOW_DEV_BACK_ARROWS) {
            Text(
                text = "←",
                fontSize = 24.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 20.dp, top = 8.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }
            )
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

// ─── Screen: Science Agree (page 10) ─────────────────────────────────────────

@Composable
private fun ScienceAgreeScreen(onNext: () -> Unit, onBack: () -> Unit) {
    StarryBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = "←", fontSize = 24.sp, color = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }
                        .padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.weight(0.2f))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(text = "🌿", fontSize = 26.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Row {
                    repeat(5) {
                        Text(text = "★", fontSize = 20.sp, color = Color(0xFF00D4FF))
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "🌿", fontSize = 26.sp)
            }

            Spacer(modifier = Modifier.height(28.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "We know that", fontSize = 26.sp, color = Color.White, textAlign = TextAlign.Center)
                Text(text = "Quitting is hard.", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF6B00), textAlign = TextAlign.Center)

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "Science agrees - the best method is to", fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 28.sp)
                Text(text = "Replace.", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00D4FF), textAlign = TextAlign.Center)

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "And what better replacement than", fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 28.sp)
                Text(text = "Exercise?", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00D4FF), textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.weight(1f))

            val context = LocalContext.current

            val citationText = buildAnnotatedString {
                withStyle(SpanStyle(color = Color(0xFF888888), fontSize = 14.sp)) {
                    append("Backed by ")
                }
                pushStringAnnotation(tag = "URL", annotation = "https://pmc.ncbi.nlm.nih.gov/articles/PMC12304691/#:~:text=These%20findings%20suggest,addictive%20behaviors")
                withStyle(SpanStyle(
                    color = Color(0xFF00D4FF),
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline
                )) {
                    append("longitudinal studies")
                }
                pop()
                withStyle(SpanStyle(color = Color(0xFF888888), fontSize = 14.sp)) {
                    append(", ")
                }
                pushStringAnnotation(tag = "URL", annotation = "https://www.mdpi.com/2076-3425/15/8/794")
                withStyle(SpanStyle(
                    color = Color(0xFF00D4FF),
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline
                )) {
                    append("systematic reviews")
                }
                pop()
                withStyle(SpanStyle(color = Color(0xFF888888), fontSize = 14.sp)) {
                    append(" and ")
                }
                pushStringAnnotation(tag = "URL", annotation = "https://newsroom.ucla.edu/releases/kicking-an-addiction-replace-with-joy-ucla-expert-new-book#:~:text=%E2%80%9CPeople%20with%20the%20most%20success,%E2%80%9D")
                withStyle(SpanStyle(
                    color = Color(0xFF00D4FF),
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline
                )) {
                    append("behavioral science experts")
                }
                pop()
                withStyle(SpanStyle(color = Color(0xFF888888), fontSize = 14.sp)) {
                    append(".")
                }
            }

            ClickableText(
                text = citationText,
                style = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                onClick = { offset ->
                    citationText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(annotation.item))
                            context.startActivity(intent)
                        }
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth().height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(color = Color.Black)) { onNext() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "See How PushFirst Works", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─── Screens: How It Works (pages 12–14) ─────────────────────────────────────

@Composable
private fun HowItWorksScreen(
    lottieAssetPath: String,
    title: String,
    body: String,
    titleFontSize: TextUnit = 42.sp,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset(lottieAssetPath))
    val progress by animateLottieCompositionAsState(composition = composition, iterations = LottieConstants.IterateForever)
    val bodySentences = remember(body) {
        body.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StarryBackground {}

        Text(
            text = "←",
            fontSize = 24.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 16.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))

            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = title, fontSize = titleFontSize, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00D4FF), textAlign = TextAlign.Center)

            Spacer(modifier = Modifier.height(16.dp))

            if (bodySentences.size > 1) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    bodySentences.forEach { sentence ->
                        Text(
                            text = sentence,
                            fontSize = 21.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            lineHeight = 30.sp
                        )
                    }
                }
            } else {
                Text(text = body, fontSize = 21.sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 30.sp)
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(color = Color.Black)) { onNext() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Continue", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

@Composable
private fun HowItWorksBlockScreen(onNext: () -> Unit, onBack: () -> Unit) {
    HowItWorksScreen(
        lottieAssetPath = "Onboarding/Simple Rejected Animation.json",
        title = "Step 1 — Block.",
        body = "The moment you visit an adult site, PushFirst steps in. The urge gets intercepted before it wins.",
        onNext = onNext,
        onBack = onBack
    )
}

@Composable
private fun HowItWorksPushScreen(onNext: () -> Unit, onBack: () -> Unit) {
    HowItWorksScreen(
        lottieAssetPath = "Onboarding/Military Push Ups.json",
        title = "Step 2 — Push.",
        body = "Do push-ups to unlock. The AI counts every rep in real time.",
        onNext = onNext,
        onBack = onBack
    )
}

@Composable
private fun HowItWorksChoiceScreen(onNext: () -> Unit, onBack: () -> Unit) {
    HowItWorksScreen(
        lottieAssetPath = "Onboarding/Sandy Loading.json",
        title = "Step 3 — Decide.",
        body = "After your reps, you earn 45 minutes. By then, most urges are already gone. You did the work — now you decide.",
        titleFontSize = 36.sp,
        onNext = onNext,
        onBack = onBack
    )
}

// ─── Screen: Rewiring Benefits (page 15) ─────────────────────────────────────

@Composable
private fun RewiringBenefitsScreen(onNext: () -> Unit, onBack: () -> Unit) {
    data class Expert(
        val initials: String,
        val name: String,
        val bold: String,
        val body: String,
        val blueCheck: Boolean = false,
        val imageAssetPath: String? = null
    )
    val experts = listOf(
        Expert("AH", "Andrew Huberman, Ph.D",
            "Drastically improve your life.",
            "Resetting your dopamine balance by taking a break from highly stimulating content can dramatically improve motivation, emotional stability, and everyday pleasure.",
            imageAssetPath = "Onboarding/huberman.jpg"),
        Expert("SB", "Steven Bartlett",
            "There's no good in porn.",
            "Pornography doesn't have an educational role — it's only an open window for a market that brings emptiness and addiction.",
            imageAssetPath = "Onboarding/Steven-Bartlett.jpg"),
        Expert("T", "Tyler — PushFirst user",
            "I feel like a different person!",
            "3 weeks in. I'm talking more, sleeping better, actually present with people. Didn't expect it to hit this fast..", blueCheck = true,
            imageAssetPath = "Onboarding/testimonial2.jpeg"),
        Expert("A", "Aiden — PushFirst user",
            "My friends noticed before I said anything.",
            "Didn't tell anyone what I was doing. Two weeks later my mate asked what changed. He told me I was way more talkative, witty, and sociable.", blueCheck = true,
            imageAssetPath = "Onboarding/testimonial3.jpeg"),
        Expert("N", "Noah — PushFirst user",
            "500 push-ups a week and I didn't plan any of it.",
            "Started as punishment basically. Now people at the gym are asking what I'm on. Not gonna lie, it feels great.", blueCheck = true,
            imageAssetPath = "Onboarding/testimonial4.jpeg"),
        Expert("L", "Liam — PushFirst user",
            "Gone up a shirt size. Walk differently now.",
            "3 months in. The muscle is real. The confidence is real. Didn't expect both to come from the same thing.", blueCheck = true,
            imageAssetPath = "Onboarding/testimonial7.jpeg"),
        Expert("E", "Ethan — PushFirst user",
            "Turns out I wasn't lazy. I was just drained.",
            "Woke up before my alarm wanting to do stuff. Hadn't happened in years. Week two.", blueCheck = true,
            imageAssetPath = "Onboarding/testimonial6.jpeg"),
        Expert("K", "Anonymous — PushFirst user",
            "Life feels colorful again.",
            "Rediscovering things I abandoned years ago. Didn't realize how much was being taken until it stopped.", blueCheck = true,
            imageAssetPath = "Icon/temp.png")
    )

    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "←", fontSize = 24.sp, color = Color.White,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }.padding(end = 16.dp))
                Text(text = "Rewiring Benefits", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.width(40.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                experts.forEach { expert ->
                    item {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                if (expert.imageAssetPath != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data("file:///android_asset/${expert.imageAssetPath}")
                                            .build(),
                                        contentDescription = expert.name,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(50)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color(0xFF1A2A4A)), contentAlignment = Alignment.Center) {
                                        Text(text = expert.initials, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = expert.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                                Text(text = if (expert.blueCheck) "🔵" else "✅", fontSize = 15.sp)
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0D1B3E)).padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text(text = expert.bold, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 22.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = expert.body, fontSize = 13.sp, color = Color(0xFFAABBCC), lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()
                    .height(56.dp).clip(RoundedCornerShape(20.dp)).background(Color.White)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(color = Color.Black)) { onNext() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Continue", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

// ─── Screen: Goals (page 13) ─────────────────────────────────────────────────

private data class GoalOption(val emoji: String, val label: String, val color: Color)
private val GOAL_OPTIONS = listOf(
    GoalOption("❤️", "Stronger relationships", Color(0xFFE53935)),
    GoalOption("👤", "Improved self-confidence", Color(0xFF1E88E5)),
    GoalOption("😊", "Improved mood and happiness", Color(0xFFFDD835)),
    GoalOption("⚡", "More energy and motivation", Color(0xFFFF6B00)),
    GoalOption("💪", "Get fit & build muscle", Color(0xFF00D4FF)),
    GoalOption("🧠", "Improve mental clarity", Color(0xFF7B1FA2)),
    GoalOption("🚫", "Quit porn for good", Color(0xFFE53935)),
    GoalOption("😴", "Sleep better", Color(0xFF283593))
)

@Composable
private fun GoalsScreen(viewModel: OnboardingViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            QuizTopBar(currentPage = 13, totalPages = TOTAL_ONBOARDING_PAGES, onBack = onBack)

            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(text = "Choose your goals", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Select the goals you wish to achieve during your reboot.", fontSize = 14.sp, color = Color(0xFF888888), lineHeight = 20.sp)
                Spacer(modifier = Modifier.height(12.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                GOAL_OPTIONS.forEach { goal ->
                    item {
                        val isSelected = goal.label in viewModel.goals
                        val atMax = viewModel.goals.size >= 3 && !isSelected
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                .background(if (isSelected) goal.color.copy(alpha = 0.15f) else Color(0xFF111122))
                                .border(if (isSelected) 2.dp else 1.dp, if (isSelected) goal.color else Color(0xFF2A2A44), RoundedCornerShape(50))
                                .clickable(enabled = !atMax, interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(color = goal.color)) {
                                    viewModel.goals = if (isSelected) viewModel.goals - goal.label else viewModel.goals + goal.label
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(goal.color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                Text(text = goal.emoji, fontSize = 18.sp)
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(text = goal.label, fontSize = 15.sp, color = if (atMax) Color(0xFF444466) else Color.White, modifier = Modifier.weight(1f), lineHeight = 21.sp)
                            Box(
                                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(50))
                                    .background(if (isSelected) goal.color else Color.Transparent)
                                    .border(2.dp, if (isSelected) goal.color else Color(0xFF555577), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color.White))
                            }
                        }
                    }
                }
            }

            val enabled = viewModel.goals.isNotEmpty()
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()
                    .height(56.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (enabled) Color.White else Color(0xFF2A2A3A))
                    .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(color = Color.Black)) { if (enabled) onNext() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Track these goals", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (enabled) Color(0xFF111111) else Color(0xFF555566))
            }
        }
    }
}

// ─── Screen: Social Proof (page 14) ──────────────────────────────────────────

@Composable
private fun SocialProofScreen(onNext: () -> Unit, onBack: () -> Unit) {
    data class Review(
        val initial: String,
        val bgColor: Color,
        val name: String,
        val handle: String,
        val body: String,
        val imageAssetPath: String
    )
    val reviews = listOf(
        Review("D", Color(0xFF00ACC1), "Daniel K.", "@danielk_gains",
            "\"Tried to cheat the push-up counter on day one. Couldn't. Ended up just doing them. That was 6 weeks ago.\"",
            imageAssetPath = "Onboarding/socialproof1.jpg"),
        Review("R", Color(0xFF5C6BC0), "Ryan S.", "@ryanfit_reboot",
            "\"The detection is accurate. Like uncomfortably accurate. No half reps, no cheating. Honestly that's what makes it work.\"",
            imageAssetPath = "Onboarding/socialproof2.jpeg"),
        Review("J", Color(0xFFFF6B00), "Jake M.", "@jakemreboot",
            "\"It's not just a blocker. Having to earn access back physically changed how I think about it. Nothing else did that.\"",
            imageAssetPath = "Onboarding/socialproof3.jpeg")
    )

    StarryBackground {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (AppConfig.SHOW_DEV_BACK_ARROWS) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
                    Text(
                        text = "←",
                        fontSize = 24.sp,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(24.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(text = "🌿", fontSize = 26.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Row { repeat(5) { Text(text = "★", fontSize = 20.sp, color = Color(0xFFFFD700)) } }
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "🌿", fontSize = 26.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("PushFirst was made for\n") }
                    withStyle(SpanStyle(color = Color(0xFF00D4FF), fontWeight = FontWeight.ExtraBold)) { append("people like you.") }
                },
                fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Overlapping avatars
            Box(modifier = Modifier.height(96.dp).width(208.dp)) {
                reviews.forEachIndexed { i, r ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file:///android_asset/${r.imageAssetPath}")
                            .build(),
                        contentDescription = r.name,
                        modifier = Modifier
                            .offset(x = (i * 56).dp)
                            .size(96.dp)
                            .clip(RoundedCornerShape(50))
                            .border(3.dp, Color.White, RoundedCornerShape(50)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "Join thousands on their journey", fontSize = 14.sp, color = Color(0xFF888888))

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                reviews.forEach { r ->
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0D1B3E)).border(1.dp, Color(0xFF1E3060), RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data("file:///android_asset/${r.imageAssetPath}")
                                        .build(),
                                    contentDescription = r.name,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(50)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = r.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(text = r.handle, fontSize = 12.sp, color = Color(0xFF666688))
                                }
                                Row { repeat(5) { Text(text = "★", fontSize = 12.sp, color = Color(0xFFFFD700)) } }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = r.body, fontSize = 14.sp, color = Color(0xFFBBBBCC), lineHeight = 21.sp)
                        }
                    }
                }
            }

            Column(modifier = Modifier.navigationBarsPadding()) {
                GradientButton(text = "Continue", onClick = onNext, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ─── Screen: Camera Trial (page 15) ──────────────────────────────────────────

private enum class CameraTrialState { SELECT, SETUP, INSTRUCTION, ACTIVE }

@Composable
private fun CameraTrialScreen(poseAnalyzer: PoseAnalyzer?, onNext: () -> Unit, onBack: (() -> Unit)?) {
    var state by remember { mutableStateOf(CameraTrialState.SELECT) }

    when (state) {
        CameraTrialState.SELECT -> CameraTrialSelectState(
            onPushUps = { state = CameraTrialState.SETUP },
            onSkip = onNext,
            onBack = onBack
        )
        CameraTrialState.SETUP -> CameraTrialSetupState(
            onContinue = { state = CameraTrialState.INSTRUCTION },
            onBack = { state = CameraTrialState.SELECT }
        )
        CameraTrialState.INSTRUCTION -> CameraTrialInstructionState(
            onContinue = { state = CameraTrialState.ACTIVE },
            onBack = { state = CameraTrialState.SETUP }
        )
        CameraTrialState.ACTIVE -> CameraTrialActiveState(
            poseAnalyzer = poseAnalyzer,
            onDone = onNext,
            onSkip = onNext
        )
    }
}

@Composable
private fun CameraTrialSelectState(onPushUps: () -> Unit, onSkip: () -> Unit, onBack: (() -> Unit)?) {
    StarryBackground {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (onBack != null) {
                    Text(text = "←", fontSize = 24.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.CenterStart)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }.padding(4.dp))
                }
            }

            Column {
                Text(text = "Try out the AI push-up detector!", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, lineHeight = 36.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "You can also skip this", fontSize = 14.sp, color = Color(0xFF888888))
                Spacer(modifier = Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "⏳", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "See your first reps counted!", fontSize = 15.sp, color = Color(0xFF00D4FF), fontWeight = FontWeight.SemiBold)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Push-ups — fully active
                Row(
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF0D1B3E))
                        .border(2.dp, Color(0xFF4DA3FF), RoundedCornerShape(16.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(color = Color(0xFF00D4FF))) { onPushUps() }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "💪", fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "Push-ups", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                    Text(text = "›", fontSize = 22.sp, color = Color(0xFF00D4FF))
                }

                // Squats — greyed out, not clickable
                Row(
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A0F1E))
                        .border(2.dp, Color(0xFF5A5A78), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "🦵", fontSize = 28.sp, color = Color(0xFF444455))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "Squats", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF444455), modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF1A1A2A))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(text = "Coming soon", fontSize = 10.sp, color = Color(0xFF555566))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "›", fontSize = 22.sp, color = Color(0xFF333344))
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "🔒  Your camera feed never leaves your device.", fontSize = 12.sp, color = Color(0xFF555566), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                        .clip(RoundedCornerShape(50)).border(1.dp, Color(0xFF444455), RoundedCornerShape(50))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(color = Color.White)) { onSkip() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "Try later", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun CameraTrialInstructionState(onContinue: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A14)))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(text = "←", fontSize = 24.sp, color = Color.White,
                modifier = Modifier.statusBarsPadding().padding(20.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() })

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data("file:///android_asset/Onboarding/PushupsFront.webp")
                        .crossfade(false)
                        .build(),
                    imageLoader = remember {
                        ImageLoader.Builder(context).components { add(ImageDecoderDecoder.Factory()) }.build()
                    },
                    contentDescription = "Push-up demonstration",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFF060A20).copy(alpha = 0.95f)).navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Push-ups", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Put your entire body in frame and exercise like in the video!",
                    fontSize = 15.sp, color = Color(0xFFBBBBCC), textAlign = TextAlign.Center, lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                GradientButton(text = "Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CameraTrialSetupState(onContinue: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A14)))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(text = "←", fontSize = 24.sp, color = Color.White,
                modifier = Modifier.statusBarsPadding().padding(20.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() })

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data("file:///android_asset/Onboarding/placePhone.webp")
                        .crossfade(false)
                        .build(),
                    imageLoader = remember {
                        ImageLoader.Builder(context).components { add(ImageDecoderDecoder.Factory()) }.build()
                    },
                    contentDescription = "Phone placement guide",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFF060A20).copy(alpha = 0.95f)).navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Setup", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "Place your phone on the floor facing you in a well-lit area.", fontSize = 15.sp, color = Color(0xFFBBBBCC), textAlign = TextAlign.Center, lineHeight = 22.sp)
                Spacer(modifier = Modifier.height(20.dp))
                GradientButton(text = "Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CameraTrialActiveState(poseAnalyzer: PoseAnalyzer?, onDone: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var repCount by remember { mutableIntStateOf(0) }
    var landmarks by remember { mutableStateOf<List<NormalizedLandmark>?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val orbScale = remember { Animatable(1f) }
    var lastRepCount by remember { mutableIntStateOf(0) }

    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }
    var soundId by remember { mutableIntStateOf(0) }
    var soundLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        soundId = soundPool.load(context, R.raw.rep_count_flashpoint, 1)
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) soundLoaded = true
        }
    }

    LaunchedEffect(repCount) {
        if (repCount > lastRepCount) {
            lastRepCount = repCount
            if (soundLoaded && soundId != 0) {
                soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
            }
            orbScale.animateTo(1.28f, animationSpec = tween(80, easing = FastOutSlowInEasing))
            orbScale.animateTo(1.0f, animationSpec = tween(220, easing = FastOutSlowInEasing))
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Wire callbacks to local state — analyzer was initialized in Activity.onCreate()
    DisposableEffect(poseAnalyzer) {
        poseAnalyzer?.onRepCountChanged = { repCount = it }
        poseAnalyzer?.onStateChanged = {}
        poseAnalyzer?.onValidPoseChanged = {}
        onDispose {
            poseAnalyzer?.onRepCountChanged = {}
            poseAnalyzer?.onStateChanged = {}
            poseAnalyzer?.onValidPoseChanged = {}
        }
    }

    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(33); landmarks = poseAnalyzer?.getCurrentLandmarks() }
    }

    LaunchedEffect(previewViewRef) {
        val pv = previewViewRef ?: return@LaunchedEffect
        while (poseAnalyzer?.isPoseLandmarkerInitialized() != true) { kotlinx.coroutines.delay(100) }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProviderRef = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build().also { it.setAnalyzer(cameraExecutor) { img -> poseAnalyzer?.analyzeFrame(img) } }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef?.unbindAll()
            cameraExecutor.shutdown()
            soundPool.release()
            // poseAnalyzer is owned by OnboardingActivity — do NOT close it here
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Camera area — 70%
        Box(modifier = Modifier.fillMaxWidth().weight(0.7f)) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; previewViewRef = this } },
                modifier = Modifier.fillMaxSize()
            )

            PoseOverlay(landmarks = landmarks, modifier = Modifier.fillMaxSize())

            val orbBaseColor = when {
                repCount >= 15 -> Color(0xFFB347FF) // bright purple
                repCount >= 10 -> Color(0xFFFF8C00) // bright orange
                repCount >= 5 -> Color(0xFF39FF14) // bright green
                else -> Color(0xFF00D4FF) // default cyan
            }

            // Glowing animated orb counter
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .offset(y = 34.dp)
                    .size(224.dp)
                    .graphicsLayer(scaleX = orbScale.value, scaleY = orbScale.value),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(orbBaseColor.copy(alpha = 0.25f), Color.Transparent),
                            center = center, radius = radius
                        ),
                        radius = radius, center = center
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(orbBaseColor.copy(alpha = 0.55f), orbBaseColor.copy(alpha = 0.30f), Color.Transparent),
                            center = center, radius = radius * 0.75f
                        ),
                        radius = radius * 0.75f, center = center
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFFFFFF).copy(alpha = 0.95f), orbBaseColor.copy(alpha = 0.85f), orbBaseColor.copy(alpha = 0.60f)),
                            center = center, radius = radius * 0.45f
                        ),
                        radius = radius * 0.45f, center = center
                    )
                }
                Text(
                    text = "$repCount",
                    fontSize = 58.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    style = LocalTextStyle.current.copy(
                        shadow = Shadow(
                            color = Color(0xFF003344),
                            offset = Offset(0f, 1f),
                            blurRadius = 4f
                        )
                    )
                )
            }

            // Top hint card
            Box(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A0A14).copy(alpha = 0.85f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "📷", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Keep your whole body in frame.", fontSize = 13.sp, color = Color.White)
                }
            }
        }

        // Bottom dark panel — 30%
        Box(
            modifier = Modifier.fillMaxWidth().weight(0.3f)
                .background(Color(0xFF060A14))
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                    .height(52.dp).clip(RoundedCornerShape(50)).border(2.dp, Color(0xFF00D4FF), RoundedCornerShape(50))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(color = Color.White)) { onSkip() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = if (repCount > 1) "Finish Pushups" else "Try later", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

// ─── Screen: Your Plan (page 17) ─────────────────────────────────────────────

@Composable
private fun YourPlanScreen(viewModel: OnboardingViewModel, onNext: () -> Unit, onBack: (() -> Unit)?) {
    val days = listOf(
        "📱" to ("Day 0: First Rep" to "Do a short set. Earn your first unlock."),
        "⏸️" to ("Day 1: Pause Added" to "Reps create a pause before urges win."),
        "🔥" to ("Day 2: Urges Ease" to "Movement redirects cravings in seconds."),
        "🧠" to ("Day 3: Clearer Focus" to "Brain fog starts lifting."),
        "🌙" to ("Day 4: Calmer Evenings" to "Less late-night urges, better sleep."),
        "📉" to ("Day 5: Urges Drop" to "Cravings shrink as reps add up."),
        "✨" to ("Day 7: Mindset Shift" to "Hard-first becomes your default.")
    )

    StarryBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                if (onBack != null) {
                    Text(text = "←", fontSize = 24.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.CenterStart)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }.padding(4.dp))
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(text = "Your Plan is Ready", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(6.dp))
                        val name = viewModel.userName.ifBlank { "you" }
                        Text(text = "Based on your profile, here's your 7-day program, $name", fontSize = 14.sp, color = Color(0xFF888888), textAlign = TextAlign.Center, lineHeight = 20.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0D1B3E)).border(1.dp, Color(0xFF1E3060), RoundedCornerShape(12.dp))
                            .padding(18.dp)
                    ) {
                        days.forEachIndexed { i, (icon, titleDesc) ->
                            val (title, desc) = titleDesc
                            Row(verticalAlignment = Alignment.Top) {
                                Text(text = icon, fontSize = 22.sp, modifier = Modifier.padding(top = 2.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text(text = desc, fontSize = 12.sp, color = Color(0xFF8899AA), lineHeight = 18.sp)
                                }
                            }
                            if (i < days.lastIndex) {
                                Row {
                                    Spacer(modifier = Modifier.width(15.dp))
                                    Box(modifier = Modifier.width(2.dp).height(16.dp).background(Color(0xFF1E3A5F)))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Subscribe to unlock your full program and start today.", fontSize = 13.sp, color = Color(0xFF888888), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            GradientButton(text = "Start My Program  →", onClick = onNext, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            Spacer(modifier = Modifier.navigationBarsPadding().height(20.dp))
        }
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

    StarryBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    Text(
                        text = "←",
                        fontSize = 24.sp,
                        color = Color.White,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onBack() }
                    )
                } else {
                    Spacer(modifier = Modifier.width(24.dp))
                }
            }

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

        if (selectedPlan == BASE_PLAN_YEARLY) {
            // Trial timeline
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF101C36))
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
        }

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
    val bgColor = if (selected) Color(0xFF1B2A4A) else Color(0xFF101C36)

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
