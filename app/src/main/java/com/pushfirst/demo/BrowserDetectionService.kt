package com.pushfirst.demo

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ACCESSIBILITY SERVICE - Browser Detection
 *
 * Monitors browser windows to detect adult websites and trigger the blocking overlay.
 *
 * Detection flow:
 * 1. monitoringRunnable polls every 500ms via checkCurrentUrl()
 * 2. onAccessibilityEvent catches URL changes on navigation events
 * 3. During bypass, all processing is paused and bypassExpiryCheckRunnable is
 *    scheduled to fire exactly when the bypass expires
 * 4. On bypass expiry, only re-triggers if the address bar still shows an adult domain
 */
class BrowserDetectionService : AccessibilityService() {

    private var lastCheckedUrl: String? = null
    private var currentBrowserPackage: String? = null
    private var lastKnownAdultDomain: String? = null
    private var lastBlockTriggerTime: Long = 0
    private var lastTextChangeTime: Long = 0
    private var lastGooglePageTime: Long = 0
    private var lastSearchPhraseTriggerTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private val googleToBannedSiteDelay = 1000L
    private var isMonitoring = false
    private var bypassExpiryCheckScheduled = false

    private val blockedDomains: MutableSet<String> = mutableSetOf()
    private val blockedBaseDomains: MutableSet<String> = mutableSetOf()
    private var domainsLoaded = false

    // Core polling loop — checks URL every 500ms.
    // During bypass, checkCurrentUrl() returns early so this is cheap.
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkCurrentUrl()
                handler.postDelayed(this, 500)
            }
        }
    }

    // Scheduled to fire exactly when the bypass window expires.
    // Only re-triggers blocking if we can positively read an adult domain in the address bar.
    // Null URL (scrolling, hidden address bar) = no trigger; polling loop handles recovery.
    private val bypassExpiryCheckRunnable = Runnable {
        bypassExpiryCheckScheduled = false
        Log.d(TAG, "Bypass expiry: checking if reblock needed")
        try {
            val root = rootInActiveWindow ?: run {
                Log.d(TAG, "Bypass expiry: no root node — skipping")
                return@Runnable
            }
            val activePkg = root.packageName?.toString() ?: ""
            if (!isBrowserPackage(activePkg)) {
                Log.d(TAG, "Bypass expiry: not in browser — skipping")
                return@Runnable
            }
            val url = extractUrlFromViewHierarchy(root)
            Log.d(TAG, "Bypass expiry: extracted URL = $url")
            if (url != null && isDomainBlocked(url)) {
                Log.d(TAG, "Bypass expiry: still on adult site $url — triggering reblock")
                triggerBlockingOverlay(url)
            } else {
                Log.d(TAG, "Bypass expiry: URL not adult ($url) — no reblock, polling will handle it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bypass expiry check error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "BrowserDetectionService"

        private val SEARCH_PHRASE_DEMO_ENABLED = AppConfig.SEARCH_PHRASE_DEMO_ENABLED
        private const val SEARCH_PHRASE_DEMO_TRIGGER = "premium corn videos 4k"
        private const val SEARCH_PHRASE_DEMO_FAKE_DOMAIN = "cornhub.website"
        private const val SEARCH_PHRASE_TRIGGER_COOLDOWN_MS = 10_000L
        private const val BLOCKED_DOMAINS_FILE = "adult_domains.txt.txt"

        // Persisted for alarm-receiver fallback only
        private const val PREFS_NAME = "pushfirst_detection_prefs"
        private const val KEY_LAST_BLOCK_DOMAIN = "last_block_domain"
        private const val KEY_LAST_BLOCK_TS = "last_block_ts"
        private const val KEY_LAST_BROWSER_PACKAGE = "last_browser_package"
        private const val LAST_BLOCK_MAX_AGE_MS = 5 * 60 * 1000L

        @Volatile private var serviceInstance: BrowserDetectionService? = null
        @Volatile private var expiryHandledUntilMs: Long = 0L

        fun isServiceEnabled(context: android.content.Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val serviceName = "${context.packageName}/${BrowserDetectionService::class.java.name}"
            return enabledServices.contains(serviceName)
        }

        fun performExpiryCheckFromAlarmIfAvailable(): Boolean {
            val svc = serviceInstance ?: return false
            svc.performExpiryCheckFromAlarm()
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected")
        serviceInstance = this
        loadBlockedDomains()
        isMonitoring = true
        handler.post(monitoringRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (!isBrowserPackage(packageName)) return

        currentBrowserPackage = packageName

        // During bypass: skip expensive event processing, schedule exact re-check at expiry.
        if (UnlockManager.isBlockingBypassed(this)) {
            scheduleBypassExpiryCheckIfNeeded()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            lastTextChangeTime = System.currentTimeMillis()
            Log.d(TAG, "Text change detected in address bar, starting cooldown")
            if (SEARCH_PHRASE_DEMO_ENABLED) checkSearchBarForDemoPhrase(event)
            return
        }

        val shouldProcess = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        if (shouldProcess) {
            Log.d(TAG, "Processing event type: ${event.eventType}")
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastTextChangeTime = 0
                Log.d(TAG, "Window state changed (navigation detected), clearing cooldown")
            }

            val urlFromEvent = extractUrlFromEvent(event)
            Log.d(TAG, "extractUrlFromEvent returned: $urlFromEvent")

            if (urlFromEvent != null) {
                if (!urlFromEvent.contains("chrome://") &&
                    !urlFromEvent.contains("chrome-error://") &&
                    !urlFromEvent.contains("data:text/html") &&
                    !urlFromEvent.contains("about:") &&
                    !urlFromEvent.contains("google.com") &&
                    !urlFromEvent.contains("google.")) {
                    if (urlFromEvent != lastCheckedUrl) {
                        lastCheckedUrl = urlFromEvent
                        Log.d(TAG, "✅ URL changed to: $urlFromEvent (event type: ${event.eventType})")
                        checkAndBlockIfAdultSite(urlFromEvent)
                    } else {
                        Log.d(TAG, "URL unchanged: $urlFromEvent")
                    }
                    return
                } else {
                    Log.d(TAG, "Skipping URL (Chrome internal or Google): $urlFromEvent")
                }
            }

            Log.d(TAG, "Triggering checkCurrentUrl after 200ms delay")
            handler.postDelayed({ checkCurrentUrl() }, 200)
        }
    }

    /**
     * Polls the address bar for the current URL and checks if it's an adult site.
     * Also handles unlock/bypass expiry while the user is already on a page.
     */
    private fun checkCurrentUrl() {
        try {
            val bypassRemainingMs = UnlockManager.getBlockingBypassRemainingMs(this)
            if (bypassRemainingMs > 0) {
                scheduleBypassExpiryCheckIfNeeded()
                return
            }

            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.d(TAG, "No root node available")
                return
            }

            val url = extractUrlFromViewHierarchy(rootNode)

            if (url != null && (url.contains("google.com") || url.contains("google.") || url == "google")) {
                lastGooglePageTime = System.currentTimeMillis()
                Log.d(TAG, "On Google page detected — clearing adult domain tracking")
                lastKnownAdultDomain = null
                return
            }

            if (url != null) {
                if (url.contains("chrome://") ||
                    url.contains("chrome-error://") ||
                    url.contains("data:text/html") ||
                    url.contains("about:") ||
                    url == "google.com" ||
                    url == "www.google.com" ||
                    url.contains("google.com/search") ||
                    url.startsWith("google.")) {
                    Log.d(TAG, "Skipping page (Chrome internal or Google): $url")
                    lastKnownAdultDomain = null
                    return
                }
                if (url != lastCheckedUrl) {
                    lastCheckedUrl = url
                    Log.d(TAG, "✅ Detected browser navigation to: $url")
                }
                checkAndBlockIfAdultSite(url)
            } else {
                // URL null = address bar hidden (scrolling, fullscreen, etc.) — clear stale state
                lastKnownAdultDomain = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking current URL: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun scheduleBypassExpiryCheckIfNeeded() {
        if (bypassExpiryCheckScheduled) return
        val remainingMs = UnlockManager.getBlockingBypassRemainingMs(this)
        if (remainingMs <= 0L) return
        val delayMs = remainingMs + 75L
        bypassExpiryCheckScheduled = true
        handler.postDelayed(bypassExpiryCheckRunnable, delayMs)
        Log.d(TAG, "Scheduled bypass expiry re-check in ${delayMs}ms")
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        val browserPackages = listOf(
            "com.android.chrome",
            "com.chrome.browser",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "org.mozilla.firefox",
            "com.samsung.android.sbrowser",
            "com.sec.android.app.sbrowser"
        )
        return browserPackages.any { packageName.contains(it, ignoreCase = true) }
    }

    /**
     * Extract URL from accessibility event.
     * Only checks the address bar (EditText) to avoid false positives from page content.
     */
    private fun extractUrlFromEvent(event: AccessibilityEvent): String? {
        val rootNode = rootInActiveWindow ?: return null
        return extractUrlFromViewHierarchy(rootNode)
    }

    /**
     * Traverse the view hierarchy to find the URL in the browser address bar.
     *
     * IMPORTANT: Only checks the address bar for URLs with http:// or https:// protocol,
     * or a plain domain pattern. This ensures we only detect actual navigation, not
     * search results or page content.
     */
    private fun extractUrlFromViewHierarchy(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectEditTextNodes(rootNode, editTextNodes)

            for (node in editTextNodes) {
                // Force the node to pull the latest data from the live view.
                // Without this, Samsung Internet returns a stale cached URL.
                try { node.refresh() } catch (_: Exception) {}
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                Log.d(TAG, "Checking EditText node - focused: ${node.isFocused}, text: $text, contentDesc: $contentDesc")

                if (node.isFocused) {
                    Log.d(TAG, "Skipping focused EditText (user is typing)")
                    continue
                }

                if (text.contains("http://") || text.contains("https://")) {
                    val urlPattern = Regex("https?://([^/\\s?]+)")
                    val match = urlPattern.find(text)
                    val domain = match?.groupValues?.get(1)?.lowercase()
                    if (domain != null) {
                        if (domain.contains("chrome") || domain.contains("google")) continue
                        Log.d(TAG, "✅ Extracted domain from address bar text: $domain")
                        return domain.removePrefix("www.")
                    }
                }

                if (contentDesc.contains("http://") || contentDesc.contains("https://")) {
                    val urlPattern = Regex("https?://([^/\\s?]+)")
                    val match = urlPattern.find(contentDesc)
                    val domain = match?.groupValues?.get(1)?.lowercase()
                    if (domain != null) {
                        if (domain.contains("chrome") || domain.contains("google")) continue
                        Log.d(TAG, "✅ Extracted domain from address bar contentDesc: $domain")
                        return domain.removePrefix("www.")
                    }
                }

                val domainPattern = Regex("([a-zA-Z0-9][a-zA-Z0-9-]*\\.[a-zA-Z]{2,})")
                val textTrimmed = text.trim()
                Log.d(TAG, "Checking for domain pattern in text: '$textTrimmed'")
                val domainMatch = domainPattern.find(textTrimmed)
                if (domainMatch != null) {
                    val matchedDomain = domainMatch.groupValues[1]
                    Log.d(TAG, "Domain pattern matched: '$matchedDomain'")
                    val domain = matchedDomain.lowercase()
                    if (domain.contains("chrome") || domain.contains("google")) continue
                    if (domain.length in 4..253 && !domain.contains(" ")) {
                        Log.d(TAG, "✅ Extracted domain from address bar text (no protocol): $domain")
                        return domain.removePrefix("www.")
                    } else {
                        Log.d(TAG, "Domain '$domain' failed validation: length=${domain.length}, hasSpace=${domain.contains(" ")}")
                    }
                } else {
                    Log.d(TAG, "No domain pattern match found in text: '$textTrimmed'")
                }

                val contentDescTrimmed = contentDesc.trim()
                Log.d(TAG, "Checking for domain pattern in contentDesc: '$contentDescTrimmed'")
                val contentDescDomainMatch = domainPattern.find(contentDescTrimmed)
                if (contentDescDomainMatch != null) {
                    val matchedDomain = contentDescDomainMatch.groupValues[1]
                    Log.d(TAG, "Domain pattern matched in contentDesc: '$matchedDomain'")
                    val domain = matchedDomain.lowercase()
                    if (domain.contains("chrome") || domain.contains("google")) continue
                    if (domain.length in 4..253 && !domain.contains(" ")) {
                        Log.d(TAG, "✅ Extracted domain from address bar contentDesc (no protocol): $domain")
                        return domain.removePrefix("www.")
                    } else {
                        Log.d(TAG, "Domain '$domain' failed validation: length=${domain.length}, hasSpace=${domain.contains(" ")}")
                    }
                } else {
                    Log.d(TAG, "No domain pattern match found in contentDesc: '$contentDescTrimmed'")
                }
            }
            Log.d(TAG, "No URL found in any EditText node")
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing view hierarchy: ${e.message}", e)
        } finally {
            editTextNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        }
        return null
    }

    /**
     * Recursively collect address-bar-like nodes from the view hierarchy.
     * Strict criteria to avoid false positives from search results and link previews.
     */
private fun collectEditTextNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        val isAddressBarLike = className.contains("EditText", ignoreCase = true) ||
            className.contains("Omnibox", ignoreCase = true) ||
            className.contains("UrlBar", ignoreCase = true) ||
            className.contains("LocationBar", ignoreCase = true) ||
            contentDesc.contains("address bar", ignoreCase = true) ||
            contentDesc.contains("url bar", ignoreCase = true) ||
            contentDesc.contains("search or enter address", ignoreCase = true) ||
            contentDesc.contains("enter address", ignoreCase = true) ||
            contentDesc.contains("search or enter web address", ignoreCase = true) ||
            contentDesc.contains("address field", ignoreCase = true)

        if (isAddressBarLike) {
            result.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectEditTextNodes(child, result)
                child.recycle()
            }
        }
    }

    private fun collectAllText(node: AccessibilityNodeInfo, result: StringBuilder) {
        node.text?.let { result.append(it).append(" ") }
        node.contentDescription?.let { result.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllText(child, result)
                child.recycle()
            }
        }
    }

    private fun extractUrlFromText(text: String): String? {
        if (text.isBlank()) return null
        val urlPattern = Regex("https?://([^/\\s?]+)")
        val match = urlPattern.find(text) ?: return null
        return match.groupValues[1].lowercase().removePrefix("www.")
    }

    /**
     * Load blocked domains from assets file into a Set for fast lookup.
     * Called once on service start.
     */
    private fun loadBlockedDomains() {
        if (domainsLoaded) {
            Log.d(TAG, "Blocked domains already loaded (${blockedDomains.size} domains)")
            return
        }
        try {
            val inputStream = assets.open(BLOCKED_DOMAINS_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var count = 0
            while (reader.readLine().also { line = it } != null) {
                line?.let { domain ->
                    val normalizedDomain = domain.trim().lowercase()
                    if (normalizedDomain.isNotEmpty()) {
                        blockedDomains.add(normalizedDomain)
                        // Also store the base name (before first dot) for browsers that
                        // abbreviate URLs by stripping the TLD (e.g. Samsung Internet showing
                        // "fuq" instead of "fuq.com")
                        val base = normalizedDomain.substringBefore(".")
                        if (base.length >= 2) blockedBaseDomains.add(base)
                        count++
                    }
                }
            }
            reader.close()
            inputStream.close()
            domainsLoaded = true
            Log.d(TAG, "✅ Loaded $count blocked domains from assets file")
            val testDomains = listOf("pornhub.com", "xvideos.com", "xnxx.com")
            for (testDomain in testDomains) {
                val found = blockedDomains.contains(testDomain.lowercase()) ||
                           blockedDomains.contains("www.${testDomain.lowercase()}")
                Log.d(TAG, "Test domain '$testDomain' in blocked set: $found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading blocked domains from assets: ${e.message}", e)
        }
    }

    /**
     * Check if a domain (or any parent domain) is in the blocked set.
     * Supports subdomain blocking: if example.com is blocked, sub.example.com is also blocked.
     */
    private fun isDomainBlocked(host: String): Boolean {
        if (blockedDomains.isEmpty()) {
            Log.w(TAG, "⚠️ Blocked domains set is empty! File may not have loaded correctly.")
            return false
        }
        val normalizedHost = host.lowercase().trim()
        if (normalizedHost.isEmpty()) return false
        val hostWithoutWww = normalizedHost.removePrefix("www.")
        if (blockedDomains.contains(normalizedHost) || blockedDomains.contains(hostWithoutWww)) {
            Log.d(TAG, "✅ Exact match found: $normalizedHost")
            return true
        }
        val parts = hostWithoutWww.split(".")
        if (parts.size > 2) {
            for (i in 1 until parts.size - 1) {
                val parentDomain = parts.subList(i, parts.size).joinToString(".")
                if (blockedDomains.contains(parentDomain)) {
                    Log.d(TAG, "✅ Domain blocked via parent domain: $hostWithoutWww -> $parentDomain")
                    return true
                }
            }
        }
        // Handle browsers that strip the TLD and show only the base name (e.g. "fuq" for fuq.com)
        if (!hostWithoutWww.contains(".") && blockedBaseDomains.contains(hostWithoutWww)) {
            Log.d(TAG, "✅ Base domain match: $hostWithoutWww")
            return true
        }
        Log.d(TAG, "❌ Domain not blocked: $hostWithoutWww (checked ${blockedDomains.size} domains)")
        return false
    }

    /**
     * Check if URL matches any adult domain and trigger blocking if so.
     */
    private fun checkAndBlockIfAdultSite(url: String) {
        Log.d(TAG, "checkAndBlockIfAdultSite called with: $url")

        val host = if (url.contains("://")) {
            try { Uri.parse(url).host } catch (e: Exception) {
                Log.e(TAG, "Error parsing URL: $url", e); return
            }
        } else {
            url.trim()
        }

        if (host == null || host.isEmpty()) {
            Log.d(TAG, "No host found in URL: $url"); return
        }
        Log.d(TAG, "Extracted host: $host")

        val isAdultSite = isDomainBlocked(host)
        Log.d(TAG, "Is adult site? $isAdultSite (checked against ${blockedDomains.size} domains)")

        if (isAdultSite) {
            lastKnownAdultDomain = host

            if (UnlockManager.isUnlocked(this)) {
                Log.d(TAG, "✅ Happy time active! Remaining: ${UnlockManager.getRemainingSeconds(this)}s - Skipping block")
                return
            }

            // Delay detection briefly after leaving Google to avoid hover-preview false positives
            val timeSinceGoogle = System.currentTimeMillis() - lastGooglePageTime
            if (timeSinceGoogle < googleToBannedSiteDelay) {
                Log.d(TAG, "⚠️ Just left Google page (${timeSinceGoogle}ms ago) — delaying detection")
                handler.postDelayed({
                    val currentUrl = extractUrlFromViewHierarchy(rootInActiveWindow)
                    if (currentUrl != null) {
                        val currentHost = if (currentUrl.contains("://")) {
                            try { Uri.parse(currentUrl).host } catch (_: Exception) { null }
                        } else currentUrl.trim()
                        if (currentHost != null && currentHost.lowercase() == host.lowercase()) {
                            Log.d(TAG, "✅ Still on banned site after delay - triggering block")
                            Log.w("BLOCK_DEBUG", "TRIGGERING OVERLAY for: $host")
                            triggerBlockingOverlay(host)
                        } else {
                            Log.d(TAG, "Not on banned site anymore - was a hover preview")
                        }
                    } else {
                        Log.d(TAG, "Not on banned site anymore - was a hover preview")
                    }
                }, googleToBannedSiteDelay - timeSinceGoogle)
                return
            }

            if (UnlockManager.isBlockingBypassed(this)) {
                Log.d(TAG, "Skipping block - blocking is temporarily bypassed (countdown period)")
                return
            }

            val timeSinceLastBlock = System.currentTimeMillis() - lastBlockTriggerTime
            val isSameDomain = host.lowercase() == lastKnownAdultDomain?.lowercase()
            Log.d(TAG, "Time since last block: ${timeSinceLastBlock}ms, isSameDomain: $isSameDomain")
            if (timeSinceLastBlock < 5000 && isSameDomain) {
                Log.d(TAG, "Skipping block - too soon since last block")
                return
            }

            Log.d(TAG, "🚫 Adult site detected: $host - Triggering block")
            Log.w("BLOCK_DEBUG", "TRIGGERING OVERLAY for: $host")
            triggerBlockingOverlay(host)
        } else {
            lastKnownAdultDomain = null
        }
    }

    /**
     * Start the blocking overlay service for the given domain.
     */
    private fun triggerBlockingOverlay(domain: String) {
        lastBlockTriggerTime = System.currentTimeMillis()
        persistLastBlockedDomain(domain)
        currentBrowserPackage?.let { pkg ->
            try {
                getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_BROWSER_PACKAGE, pkg).apply()
            } catch (_: Exception) { }
        }
        try {
            val intent = Intent(this, BlockingOverlayService::class.java)
            intent.putExtra("blocked_domain", domain)
            currentBrowserPackage?.let { intent.putExtra("browser_package", it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                startService(intent)
            }
            Log.d(TAG, "✅ BlockingOverlayService started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting BlockingOverlayService: ${e.message}", e)
            e.printStackTrace()
        }
    }

    /**
     * Demo only: check search bar text for the trigger phrase and show overlay.
     */
    private fun checkSearchBarForDemoPhrase(event: AccessibilityEvent) {
        if (!SEARCH_PHRASE_DEMO_ENABLED) return
        val searchBarText = event.text?.firstOrNull()?.toString()?.trim()
            ?: getSearchBarTextIncludingFocused(rootInActiveWindow)
        if (searchBarText.isNullOrEmpty()) return
        if (!searchBarText.contains(SEARCH_PHRASE_DEMO_TRIGGER, ignoreCase = true)) return
        val now = System.currentTimeMillis()
        if (now - lastSearchPhraseTriggerTime < SEARCH_PHRASE_TRIGGER_COOLDOWN_MS) return
        lastSearchPhraseTriggerTime = now
        Log.d(TAG, "Search phrase demo: detected trigger -> showing overlay for $SEARCH_PHRASE_DEMO_FAKE_DOMAIN")
        triggerBlockingOverlay(SEARCH_PHRASE_DEMO_FAKE_DOMAIN)
    }

    private fun getSearchBarTextIncludingFocused(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditTextNodes(rootNode, editTextNodes)
        val focused = editTextNodes.firstOrNull { it.isFocused }
        val node = focused ?: editTextNodes.firstOrNull { (it.text?.toString() ?: "").isNotBlank() }
        val text = node?.text?.toString()?.trim()
        editTextNodes.forEach { it.recycle() }
        return if (text.isNullOrEmpty()) null else text
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        handler.removeCallbacks(monitoringRunnable)
        handler.removeCallbacks(bypassExpiryCheckRunnable)
        Log.d(TAG, "Accessibility Service destroyed")
        serviceInstance = null
    }

    // ─── Persistence (for alarm-receiver fallback) ────────────────────────────

    private fun persistLastBlockedDomain(domain: String) {
        try {
            getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_BLOCK_DOMAIN, domain)
                .putLong(KEY_LAST_BLOCK_TS, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist last blocked domain: ${e.message}", e)
        }
    }

    private fun getPersistedLastBlockedDomainIfRecent(): String? {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val ts = prefs.getLong(KEY_LAST_BLOCK_TS, 0L)
            if (ts == 0L) return null
            if (System.currentTimeMillis() - ts <= LAST_BLOCK_MAX_AGE_MS) {
                prefs.getString(KEY_LAST_BLOCK_DOMAIN, null)
            } else null
        } catch (_: Exception) { null }
    }

    // ─── Alarm-receiver delegate ──────────────────────────────────────────────
    // Called by ReblockAlarmReceiver if the service is alive when the alarm fires.
    // Same rule: only reblock if the address bar positively shows an adult domain.

    fun performExpiryCheckFromAlarm() {
        val now = System.currentTimeMillis()
        if (now < expiryHandledUntilMs) {
            Log.d(TAG, "Expiry delegate: already handled recently, skipping")
            return
        }
        val domain = getPersistedLastBlockedDomainIfRecent() ?: run {
            Log.d(TAG, "Expiry delegate: no fresh domain, skipping")
            return
        }
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "Expiry delegate: no root node — skipping")
            return
        }
        val activePkg = root.packageName?.toString() ?: ""
        if (!isBrowserPackage(activePkg)) {
            Log.d(TAG, "Expiry delegate: not in browser — skipping")
            return
        }
        val url = extractUrlFromViewHierarchy(root)
        if (url != null && isDomainBlocked(url)) {
            Log.d(TAG, "Expiry delegate: confirmed on adult site $url — triggering reblock")
            expiryHandledUntilMs = now + 5000L
            triggerBlockingOverlay(domain)
        } else {
            Log.d(TAG, "Expiry delegate: URL not adult ($url) — skipping")
        }
    }
}
