package com.pushfirst.demo

object AppConfig {
    const val IS_PRODUCTION = false
    val UNLOCK_DURATION_MS = if (IS_PRODUCTION) 45 * 60 * 1000L else 30_000L
    const val SHOW_SKIP_BUTTON = !IS_PRODUCTION
    const val SHOW_DEV_BACK_ARROWS = !IS_PRODUCTION
    const val Is_Stoic = true
    const val CENSOR_SITE_NAME = false
}

