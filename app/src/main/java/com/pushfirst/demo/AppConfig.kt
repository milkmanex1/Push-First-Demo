package com.pushfirst.demo

object AppConfig {
    const val IS_PRODUCTION = true
    val UNLOCK_DURATION_MS = if (IS_PRODUCTION) 15 * 60 * 1000L else 30_000L
    const val SHOW_SKIP_BUTTON = !IS_PRODUCTION
    const val Is_Stoic = true
}

