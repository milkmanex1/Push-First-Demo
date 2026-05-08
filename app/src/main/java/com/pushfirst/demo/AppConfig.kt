package com.pushfirst.demo

object AppConfig {
    val IS_PRODUCTION = !BuildConfig.DEBUG
    val UNLOCK_DURATION_MS = if (IS_PRODUCTION) 45 * 60 * 1000L else 30_000L
    val SHOW_SKIP_BUTTON = !IS_PRODUCTION
    val SHOW_DEV_BACK_ARROWS = !IS_PRODUCTION
    val SKIP_ONBOARDING_FOR_DEV = !IS_PRODUCTION
    val SEARCH_PHRASE_DEMO_ENABLED = !IS_PRODUCTION
    const val Is_Stoic = true
    const val CENSOR_SITE_NAME = false
}

