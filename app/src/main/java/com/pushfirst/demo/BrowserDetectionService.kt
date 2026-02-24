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
 * This service monitors browser windows to detect when users navigate to adult websites.
 * 
 * HOW IT WORKS:
 * 1. Listens for window state changes (when browser tabs open/change)
 * 2. Extracts the current URL from the browser window
 * 3. Checks against hardcoded list of adult domains
 * 4. If match found, triggers blocking overlay
 * 
 * PERMISSIONS REQUIRED:
 * - User must enable in: Settings > Accessibility > Push First
 * - This requires manual user action (cannot be granted programmatically)
 * 
 * LIMITATIONS (for demo):
 * - Only works with browsers that expose URLs via Accessibility API
 * - May not work with all browsers or browser versions
 * - Some browsers may not expose URL information
 */
class BrowserDetectionService : AccessibilityService() {

    private var lastCheckedUrl: String? = null
    private var lastCheckTime: Long = 0
    private var currentBrowserPackage: String? = null
    private var lastKnownAdultDomain: String? = null // Track if we're on an adult site
    private var lastBlockTriggerTime: Long = 0 // Track when we last triggered blocking
    private var lastTextChangeTime: Long = 0 // Track when user last typed in address bar
    private var lastGooglePageTime: Long = 0 // Track when we were last on Google page
    private var lastSearchPhraseTriggerTime: Long = 0 // For demo: cooldown after triggering on search phrase
    private val handler = Handler(Looper.getMainLooper())
    private val debounceDelay = 300L // 300ms debounce (reduced for faster detection)
    private val textChangeCooldown = 500L // Ignore URL detection for 500ms after text changes (reduced to allow faster detection)
    private val googleToBannedSiteDelay = 1000L // Delay after leaving Google before detecting banned sites (prevents hover preview false positives)
    private var isMonitoring = false
    
    // Blocked domains loaded from assets file (loaded once on service start)
    private val blockedDomains: MutableSet<String> = mutableSetOf()
    private var domainsLoaded = false
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkCurrentUrl()
                handler.postDelayed(this, 500) // Check every 500ms
            }
        }
    }

    companion object {
        private const val TAG = "BrowserDetectionService"

        /**
         * Feature flag: when true, popup also triggers when user types the demo phrase in the search bar.
         * When false, behavior is unchanged (only URL-based blocking).
         */
        private const val SEARCH_PHRASE_DEMO_ENABLED = true

        /** Demo phrase that triggers the overlay when typed in the browser search bar (only if SEARCH_PHRASE_DEMO_ENABLED is true). */
        private const val SEARCH_PHRASE_DEMO_TRIGGER = "premium corn videos 4k"

        /** Domain shown in the popup when triggered by the demo phrase (demo only). */
        private const val SEARCH_PHRASE_DEMO_FAKE_DOMAIN = "cornhub.website"

        /** Cooldown (ms) between search-phrase triggers to avoid spam. */
        private const val SEARCH_PHRASE_TRIGGER_COOLDOWN_MS = 10_000L
        
        /** Path to the blocked domains file in assets */
        private const val BLOCKED_DOMAINS_FILE = "adult_domains.txt.txt"

        /**
         * Check if Accessibility Service is enabled
         */
        fun isServiceEnabled(context: android.content.Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            val serviceName = "${context.packageName}/${BrowserDetectionService::class.java.name}"
            return enabledServices.contains(serviceName)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected")
        // Load blocked domains from assets file (once)
        loadBlockedDomains()
        // Start continuous monitoring
        isMonitoring = true
        handler.post(monitoringRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Check if this is a browser window
        if (!isBrowserPackage(packageName)) return

        // Track text changes to implement cooldown (prevent detection while typing)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            lastTextChangeTime = System.currentTimeMillis()
            Log.d(TAG, "Text change detected in address bar, starting cooldown")
            // Demo feature: when flag is on, check search bar for trigger phrase (use event text for reliability)
            if (SEARCH_PHRASE_DEMO_ENABLED) {
                checkSearchBarForDemoPhrase(event)
            }
            return // Don't process text change events for URL detection
        }

        // Process window state changes and content changes (but NOT text changes while typing)
        // TYPE_VIEW_TEXT_CHANGED fires when user types in address bar - we track it but skip processing
        // to avoid triggering popup before user clicks a link
        val shouldProcess = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        if (shouldProcess) {
            Log.d(TAG, "Processing event type: ${event.eventType}")
            
            // If this is a window state change (actual navigation), clear cooldown to allow detection
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastTextChangeTime = 0 // Clear cooldown on navigation
                Log.d(TAG, "Window state changed (navigation detected), clearing cooldown")
            }
            
            // Store the browser package name for later use
            currentBrowserPackage = packageName
            
            // Try to extract URL from the event first (faster than traversing hierarchy)
            val urlFromEvent = extractUrlFromEvent(event)
            Log.d(TAG, "extractUrlFromEvent returned: $urlFromEvent")
            
            if (urlFromEvent != null) {
                // Skip Chrome internal pages and Google
                if (!urlFromEvent.contains("chrome://") && 
                    !urlFromEvent.contains("chrome-error://") &&
                    !urlFromEvent.contains("data:text/html") &&
                    !urlFromEvent.contains("about:") &&
                    !urlFromEvent.contains("google.com") &&
                    !urlFromEvent.contains("google.")) {
                    // Only process if URL changed
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
            
            // Trigger immediate check (monitoring will also catch it)
            Log.d(TAG, "Triggering checkCurrentUrl after 200ms delay")
            handler.postDelayed({
                checkCurrentUrl()
            }, 200) // Small delay to ensure view hierarchy is ready
        }
    }

    /**
     * Continuously check the current URL from the active window
     * This method polls the address bar to catch URL changes
     * Also checks if unlock has expired while on an adult site
     */
    private fun checkCurrentUrl() {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.d(TAG, "No root node available")
                return
            }
            
            // ONLY check address bar (EditText nodes) - do NOT scan page content
            // Scanning page content causes false positives (e.g., detecting domain in Google search results)
            // We ONLY use extractUrlFromViewHierarchy which checks the address bar EditText
            // DO NOT use extractUrlFromAllTextNodes or extractUrlFromWindowInfo - they scan page content
            val url = extractUrlFromViewHierarchy(rootNode)
            Log.d(TAG, "checkCurrentUrl: extracted URL = $url")
            
            // Check if we're on Google page AFTER extracting URL
            // This prevents false positives from Google search results, but only if the actual URL is Google
            if (url != null && (url.contains("google.com") || url.contains("google.") || url == "google")) {
                lastGooglePageTime = System.currentTimeMillis()
                Log.d(TAG, "On Google page detected (extracted URL is Google) - skipping URL detection")
                if (lastKnownAdultDomain != null) {
                    lastKnownAdultDomain = null
                }
                return
            }
            
            if (url != null) {
                // Skip Chrome's internal pages and error pages
                // Skip Google domains (but be specific to avoid false negatives)
                if (url.contains("chrome://") || 
                    url.contains("chrome-error://") ||
                    url.contains("data:text/html") ||
                    url.contains("about:") ||
                    url == "google.com" ||
                    url == "www.google.com" ||
                    url.contains("google.com/search") ||
                    url.startsWith("google.")) {
                    Log.d(TAG, "Skipping page (Chrome internal or Google): $url")
                    // Clear adult domain tracking if we're on a non-adult page
                    if (lastKnownAdultDomain != null) {
                        lastKnownAdultDomain = null
                    }
                    return
                }
                
                // Check if URL changed
                val urlChanged = url != lastCheckedUrl
                if (urlChanged) {
                    lastCheckedUrl = url
                    Log.d(TAG, "✅ Detected browser navigation to: $url")
                }
                
                // Always check current URL against adult sites
                // This handles both new navigations and unlock expiration cases
                checkAndBlockIfAdultSite(url)
            } else {
                // URL is null - clear adult domain tracking if we can't detect URL
                // This prevents false positives when URL extraction fails
                if (lastKnownAdultDomain != null) {
                    lastKnownAdultDomain = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking current URL: ${e.message}", e)
            e.printStackTrace()
        }
    }

    /**
     * Check if the package is a known browser
     */
    private fun isBrowserPackage(packageName: String): Boolean {
        val browserPackages = listOf(
            "com.android.chrome",
            "com.chrome.browser",
            "com.microsoft.emmx", // Edge
            "com.brave.browser",
            "com.opera.browser",
            "org.mozilla.firefox",
            "com.samsung.android.sbrowser"
        )
        return browserPackages.any { packageName.contains(it, ignoreCase = true) }
    }

    /**
     * Extract URL from accessibility event
     * 
     * IMPORTANT: Only checks the address bar (EditText) to avoid false positives from page content.
     * Event text/content description may contain page content (e.g., Google search results),
     * so we ONLY use the view hierarchy method which checks the address bar.
     */
    private fun extractUrlFromEvent(event: AccessibilityEvent): String? {
        // ONLY use view hierarchy to check address bar - do NOT check event text/content
        // Event text may contain page content (e.g., search results) which causes false positives
        val rootNode = rootInActiveWindow ?: return null
        return extractUrlFromViewHierarchy(rootNode)
    }

    /**
     * Traverse the view hierarchy to find the URL/address bar
     * Chrome's address bar can be found by searching for EditText views
     * with URL-like content or specific class names
     * 
     * IMPORTANT: Only checks the address bar for URLs with http:// or https:// protocol.
     * This ensures we only detect actual navigation, not search results or page content.
     */
    private fun extractUrlFromViewHierarchy(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        try {
            // Search for EditText nodes (address bar is typically an EditText)
            val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
            collectEditTextNodes(rootNode, editTextNodes)

            // Check each EditText for URL content (address bar)
            for (node in editTextNodes) {
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                
                Log.d(TAG, "Checking EditText node - focused: ${node.isFocused}, text: $text, contentDesc: $contentDesc")
                
                // Skip if user is currently typing (focused EditText) - wait for navigation
                if (node.isFocused) {
                    Log.d(TAG, "Skipping focused EditText (user is typing)")
                    continue
                }
                
                // Check text for URLs with protocol
                if (text.contains("http://") || text.contains("https://")) {
                    val urlPattern = Regex("https?://([^/\\s?]+)")
                    val match = urlPattern.find(text)
                    val domain = match?.groupValues?.get(1)?.lowercase()
                    if (domain != null) {
                        // Skip Google and Chrome domains
                        if (domain.contains("chrome") || domain.contains("google")) {
                            Log.d(TAG, "Skipping Google/Chrome domain: $domain")
                            continue
                        }
                        Log.d(TAG, "✅ Extracted domain from address bar text: $domain")
                        return domain.removePrefix("www.")
                    }
                }
                
                // Check content description for URLs with protocol
                if (contentDesc.contains("http://") || contentDesc.contains("https://")) {
                    val urlPattern = Regex("https?://([^/\\s?]+)")
                    val match = urlPattern.find(contentDesc)
                    val domain = match?.groupValues?.get(1)?.lowercase()
                    if (domain != null) {
                        // Skip Google and Chrome domains
                        if (domain.contains("chrome") || domain.contains("google")) {
                            Log.d(TAG, "Skipping Google/Chrome domain: $domain")
                            continue
                        }
                        Log.d(TAG, "✅ Extracted domain from address bar contentDesc: $domain")
                        return domain.removePrefix("www.")
                    }
                }
                
                // Also check for domain names without protocol (e.g., "cornhub.website")
                // Only extract if it looks like a valid domain (not search text or page content)
                // Match domain pattern: alphanumeric + dots + TLD (at least 2 chars)
                val domainPattern = Regex("([a-zA-Z0-9][a-zA-Z0-9-]*\\.[a-zA-Z]{2,})")
                val textTrimmed = text.trim()
                Log.d(TAG, "Checking for domain pattern in text: '$textTrimmed'")
                val domainMatch = domainPattern.find(textTrimmed)
                if (domainMatch != null) {
                    val matchedDomain = domainMatch.groupValues[1]
                    Log.d(TAG, "Domain pattern matched: '$matchedDomain'")
                    // Only accept if the matched domain is the main content (not part of a longer string)
                    // This prevents matching domains in search results or page content
                    val domain = matchedDomain.lowercase()
                    // Skip Google and Chrome domains
                    if (domain.contains("chrome") || domain.contains("google")) {
                        Log.d(TAG, "Skipping Google/Chrome domain: $domain")
                        continue
                    }
                    // Only extract if it's a reasonable domain length and looks like a real domain
                    if (domain.length in 4..253 && !domain.contains(" ")) {
                        Log.d(TAG, "✅ Extracted domain from address bar text (no protocol): $domain")
                        return domain.removePrefix("www.")
                    } else {
                        Log.d(TAG, "Domain '$domain' failed validation: length=${domain.length}, hasSpace=${domain.contains(" ")}")
                    }
                } else {
                    Log.d(TAG, "No domain pattern match found in text: '$textTrimmed'")
                }
                
                // Also check content description for domain without protocol
                val contentDescTrimmed = contentDesc.trim()
                Log.d(TAG, "Checking for domain pattern in contentDesc: '$contentDescTrimmed'")
                val contentDescDomainMatch = domainPattern.find(contentDescTrimmed)
                if (contentDescDomainMatch != null) {
                    val matchedDomain = contentDescDomainMatch.groupValues[1]
                    Log.d(TAG, "Domain pattern matched in contentDesc: '$matchedDomain'")
                    val domain = matchedDomain.lowercase()
                    // Skip Google and Chrome domains
                    if (domain.contains("chrome") || domain.contains("google")) {
                        Log.d(TAG, "Skipping Google/Chrome domain: $domain")
                        continue
                    }
                    // Only extract if it's a reasonable domain length and looks like a real domain
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
        }

        return null
    }
    
    /**
     * Extract URL from all text nodes in the hierarchy (fallback method)
     * This is less precise but can catch URLs that aren't in EditText fields
     */
    private fun extractUrlFromAllTextNodes(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        
        try {
            val allText = StringBuilder()
            collectAllText(rootNode, allText)
            val fullText = allText.toString()
            
                if (fullText.isNotEmpty()) {
                // Look for URLs with protocol
                val urlPattern = Regex("https?://([^/\\s?]+)")
                val matches = urlPattern.findAll(fullText)
                
                for (match in matches) {
                    val domain = match.groupValues[1].lowercase()
                    // Skip Chrome internal domains and Google
                    if (!domain.contains("chrome") && 
                        !domain.contains("google") &&
                        !domain.contains("localhost") &&
                        !domain.contains("127.0.0.1")) {
                        return domain.removePrefix("www.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting URL from all text nodes: ${e.message}", e)
        }
        
        return null
    }
    
    /**
     * Extract URL from window info (title, content description)
     */
    private fun extractUrlFromWindowInfo(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        
        try {
            // Check window title/content description
            val text = rootNode.text?.toString() ?: ""
            val contentDesc = rootNode.contentDescription?.toString() ?: ""
            
            // Try extracting from text
            val urlFromText = extractUrlFromText(text)
            if (urlFromText != null && !urlFromText.contains("chrome") && !urlFromText.contains("google")) {
                return urlFromText
            }
            
            // Try extracting from content description
            val urlFromDesc = extractUrlFromText(contentDesc)
            if (urlFromDesc != null && !urlFromDesc.contains("chrome") && !urlFromDesc.contains("google")) {
                return urlFromDesc
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting URL from window info: ${e.message}", e)
        }
        
        return null
    }

    /**
     * Recursively collect all EditText nodes from the view hierarchy
     * Also collects nodes that might be address bars based on class name or content description
     */
    private fun collectEditTextNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        
        // Collect EditText nodes and also nodes that might be address bars
        val isAddressBarLike = className.contains("EditText", ignoreCase = true) ||
            className.contains("Omnibox", ignoreCase = true) || // Chrome's address bar component
            className.contains("UrlBar", ignoreCase = true) ||
            className.contains("LocationBar", ignoreCase = true) ||
            contentDesc.contains("url", ignoreCase = true) ||
            contentDesc.contains("address", ignoreCase = true) ||
            contentDesc.contains("search", ignoreCase = true) ||
            (text.isNotEmpty() && (text.contains("http://") || text.contains("https://") || 
             Regex("[a-zA-Z0-9][a-zA-Z0-9-]*\\.(com|net|org|io|co|tv|xxx)").containsMatchIn(text)))
        
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

    /**
     * Recursively collect all text from the view hierarchy
     */
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

    /**
     * Extract URL from text using regex
     * IMPORTANT: Only checks for URLs with http:// or https:// protocol to avoid
     * triggering on search bar text. This ensures we only detect actual navigation.
     */
    private fun extractUrlFromText(text: String): String? {
        if (text.isBlank()) return null
        
        // Only check for URLs with protocol to ensure actual navigation, not search bar typing
        val urlPattern = Regex("https?://([^/\\s?]+)")
        val match = urlPattern.find(text)
        if (match != null) {
            val domain = match.groupValues[1].lowercase()
            // Remove www. prefix for consistent matching
            return domain.removePrefix("www.")
        }
        
        return null
    }

    /**
     * Load blocked domains from assets file into a Set for fast lookup
     * This is called once when the service starts
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
                    // Trim whitespace and convert to lowercase for case-insensitive matching
                    val normalizedDomain = domain.trim().lowercase()
                    if (normalizedDomain.isNotEmpty()) {
                        blockedDomains.add(normalizedDomain)
                        count++
                    }
                }
            }
            
            reader.close()
            inputStream.close()
            domainsLoaded = true
            Log.d(TAG, "✅ Loaded $count blocked domains from assets file")
            
            // Debug: Check if a known adult domain is in the set
            val testDomains = listOf("pornhub.com", "xvideos.com", "xnxx.com")
            for (testDomain in testDomains) {
                val found = blockedDomains.contains(testDomain.lowercase()) || 
                           blockedDomains.contains("www.${testDomain.lowercase()}")
                Log.d(TAG, "Test domain '$testDomain' in blocked set: $found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading blocked domains from assets: ${e.message}", e)
            e.printStackTrace()
            // Fallback: service will continue but won't block any sites
        }
    }
    
    /**
     * Check if a domain (or any of its parent domains) is in the blocked set
     * Supports subdomain blocking: if example.com is blocked, sub.example.com is also blocked
     * 
     * @param host The hostname to check (e.g., "sub.example.com")
     * @return true if the domain or any parent domain is blocked
     */
    private fun isDomainBlocked(host: String): Boolean {
        if (blockedDomains.isEmpty()) {
            Log.w(TAG, "⚠️ Blocked domains set is empty! File may not have loaded correctly.")
            return false
        }
        
        val normalizedHost = host.lowercase().trim()
        if (normalizedHost.isEmpty()) {
            return false
        }
        
        // Remove www. prefix for consistent matching (domains in file may or may not have www)
        val hostWithoutWww = normalizedHost.removePrefix("www.")
        
        // Check exact match first (with and without www)
        if (blockedDomains.contains(normalizedHost) || blockedDomains.contains(hostWithoutWww)) {
            Log.d(TAG, "✅ Exact match found: $normalizedHost")
            return true
        }
        
        // Check parent domains (subdomain blocking)
        // For "sub.example.com", check: "example.com" (skip "com" as it's too broad)
        val parts = hostWithoutWww.split(".")
        if (parts.size > 2) {
            // Start from the rightmost part and build parent domains
            // For "sub.example.com": parts = ["sub", "example", "com"]
            // Check: "example.com" (skip "com" TLD)
            // Need at least 3 parts (subdomain.domain.tld) to have a parent domain to check
            for (i in 1 until parts.size - 1) {
                val parentDomain = parts.subList(i, parts.size).joinToString(".")
                if (blockedDomains.contains(parentDomain)) {
                    Log.d(TAG, "✅ Domain blocked via parent domain: $hostWithoutWww -> $parentDomain")
                    return true
                }
            }
        }
        
        Log.d(TAG, "❌ Domain not blocked: $hostWithoutWww (checked ${blockedDomains.size} domains)")
        return false
    }
    
    /**
     * Check if URL matches any adult domain and trigger blocking if so
     */
    private fun checkAndBlockIfAdultSite(url: String) {
        Log.d(TAG, "checkAndBlockIfAdultSite called with: $url")
        
        // Extract host from URL - handle both full URLs and plain domains
        val host = if (url.contains("://")) {
            // Full URL with protocol - use Uri.parse
            try {
                val uri = Uri.parse(url)
                uri.host
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing URL: $url", e)
                return
            }
        } else {
            // Plain domain (what extractUrlFromViewHierarchy returns)
            url.trim()
        }
        
        if (host == null || host.isEmpty()) {
            Log.d(TAG, "No host found in URL: $url")
            return
        }
        
        Log.d(TAG, "Extracted host: $host")
        
        // Check against blocked domains set
        val isAdultSite = isDomainBlocked(host)
        
        Log.d(TAG, "Is adult site? $isAdultSite (checked against ${blockedDomains.size} domains)")

        if (isAdultSite) {
            // Track that we're on an adult site
            lastKnownAdultDomain = host
            
            // Check if unlock is still active (happy time)
            if (UnlockManager.isUnlocked(this)) {
                val remaining = UnlockManager.getRemainingSeconds(this)
                Log.d(TAG, "✅ Happy time active! Remaining: ${remaining}s - Skipping block")
                return
            }
            
            // CRITICAL: If we just left Google, delay detection to avoid hover preview false positives
            val timeSinceGoogle = System.currentTimeMillis() - lastGooglePageTime
            if (timeSinceGoogle < googleToBannedSiteDelay) {
                Log.d(TAG, "⚠️ Just left Google page (${timeSinceGoogle}ms ago) - delaying detection to avoid hover preview false positive")
                // Schedule delayed check
                handler.postDelayed({
                    // Re-check if we're still on the banned site
                    val currentUrl = extractUrlFromViewHierarchy(rootInActiveWindow)
                    if (currentUrl != null) {
                        // Extract host from current URL (handles both full URLs and plain domains)
                        val currentHost = if (currentUrl.contains("://")) {
                            try {
                                Uri.parse(currentUrl).host
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            currentUrl.trim()
                        }
                        if (currentHost != null && currentHost.lowercase() == host.lowercase()) {
                            Log.d(TAG, "✅ Still on banned site after delay - triggering block")
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
            
            // Check if blocking is temporarily bypassed (e.g., during countdown overlay)
            if (UnlockManager.isBlockingBypassed(this)) {
                Log.d(TAG, "Skipping block - blocking is temporarily bypassed (countdown period)")
                return
            }
            
            // Prevent re-triggering blocking too frequently (within 5 seconds) for the same domain
            // This prevents spam but allows re-triggering if overlay was dismissed or domain changed
            val timeSinceLastBlock = System.currentTimeMillis() - lastBlockTriggerTime
            val isSameDomain = host.lowercase() == lastKnownAdultDomain?.lowercase()
            Log.d(TAG, "Time since last block: ${timeSinceLastBlock}ms, isSameDomain: $isSameDomain")
            if (timeSinceLastBlock < 5000 && isSameDomain) {
                Log.d(TAG, "Skipping block - too soon since last block")
                return
            }
            
            Log.d(TAG, "🚫 Adult site detected: $host - Triggering block")
            triggerBlockingOverlay(host)
        } else {
            // Not an adult site - clear tracking
            lastKnownAdultDomain = null
        }
    }
    
    /**
     * Trigger the blocking overlay service
     */
    private fun triggerBlockingOverlay(domain: String) {
        lastBlockTriggerTime = System.currentTimeMillis()
        try {
            // Start blocking overlay service
            val intent = Intent(this, BlockingOverlayService::class.java)
            intent.putExtra("blocked_domain", domain)
            // Pass the browser package name so we can return to it later
            currentBrowserPackage?.let {
                intent.putExtra("browser_package", it)
            }
            // Add flag to ensure service restarts even if already running
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
     * Demo only (when SEARCH_PHRASE_DEMO_ENABLED): check search bar text for the trigger phrase
     * and show overlay with fake domain message if matched.
     * Uses the accessibility event's text when available (the view that changed); otherwise falls back to hierarchy.
     */
    private fun checkSearchBarForDemoPhrase(event: AccessibilityEvent) {
        if (!SEARCH_PHRASE_DEMO_ENABLED) return
        // Prefer text from the event (the view that just had text changed) - most reliable
        val searchBarText = event.text?.firstOrNull()?.toString()?.trim()
            ?: getSearchBarTextIncludingFocused(rootInActiveWindow)
        if (searchBarText.isNullOrEmpty()) {
            Log.d(TAG, "Search phrase demo: no text in event or search bar")
            return
        }
        Log.d(TAG, "Search phrase demo: checking text (length=${searchBarText.length})")
        val phrase = SEARCH_PHRASE_DEMO_TRIGGER
        if (!searchBarText.contains(phrase, ignoreCase = true)) return
        val now = System.currentTimeMillis()
        if (now - lastSearchPhraseTriggerTime < SEARCH_PHRASE_TRIGGER_COOLDOWN_MS) {
            Log.d(TAG, "Search phrase demo: cooldown active, skipping")
            return
        }
        lastSearchPhraseTriggerTime = now
        Log.d(TAG, "Search phrase demo: detected '$phrase' in search bar -> triggering overlay for ${SEARCH_PHRASE_DEMO_FAKE_DOMAIN}")
        triggerBlockingOverlay(SEARCH_PHRASE_DEMO_FAKE_DOMAIN)
    }

    /**
     * Gets the current text from the browser search/address bar, including when the user is typing (focused EditText).
     * Used only for the search-phrase demo feature.
     */
    private fun getSearchBarTextIncludingFocused(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditTextNodes(rootNode, editTextNodes)
        // Prefer focused node (user is typing in search bar)
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
        Log.d(TAG, "Accessibility Service destroyed")
    }
}
