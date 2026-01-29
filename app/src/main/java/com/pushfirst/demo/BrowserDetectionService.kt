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
    private val handler = Handler(Looper.getMainLooper())
    private val debounceDelay = 300L // 300ms debounce (reduced for faster detection)
    private var isMonitoring = false
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
         * Hardcoded list of adult domains to block
         * In production, this would be fetched from a backend or config file
         */
        private val ADULT_DOMAINS = listOf(
            "pornhub.com",
            "www.pornhub.com",
            "xvideos.com",
            "www.xvideos.com",
            "xnxx.com",
            "www.xnxx.com",
            "hentai",
            "xhamster.com",
            "www.xhamster.com",
            "redtube.com",
            "www.redtube.com",
            "youporn.com",
            "www.youporn.com",
            "tube8.com",
            "www.tube8.com",
            "spankwire.com",
            "www.spankwire.com",
            "keezmovies.com",
            "www.keezmovies.com",
            "porn.com",
            "www.porn.com",
            "spankbang.com",
            "www.spankbang.com",
            "cornhub.website",
            "www.cornhub.website"
        )

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
        // Start continuous monitoring
        isMonitoring = true
        handler.post(monitoringRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Check if this is a browser window
        if (!isBrowserPackage(packageName)) return

        // Process window state changes, content changes, and window content changes
        val shouldProcess = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        if (shouldProcess) {
            // Store the browser package name for later use
            currentBrowserPackage = packageName
            
            // Try to extract URL from the event first (faster than traversing hierarchy)
            val urlFromEvent = extractUrlFromEvent(event)
            if (urlFromEvent != null) {
                // Skip Chrome internal pages
                if (!urlFromEvent.contains("chrome://") && 
                    !urlFromEvent.contains("chrome-error://") &&
                    !urlFromEvent.contains("data:text/html") &&
                    !urlFromEvent.contains("about:") &&
                    urlFromEvent != "google.com" &&
                    !urlFromEvent.contains("google.com/search")) {
                    // Only process if URL changed
                    if (urlFromEvent != lastCheckedUrl) {
                        lastCheckedUrl = urlFromEvent
                        Log.d(TAG, "✅ URL changed to: $urlFromEvent")
                        checkAndBlockIfAdultSite(urlFromEvent)
                    }
                    return
                }
            }
            
            // Trigger immediate check (monitoring will also catch it)
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
            
            // Try multiple extraction methods
            var url = extractUrlFromViewHierarchy(rootNode)
            
            // Fallback: Try extracting from all text nodes if EditText method failed
            if (url == null) {
                url = extractUrlFromAllTextNodes(rootNode)
            }
            
            // Fallback: Try extracting from window title/content description
            if (url == null) {
                url = extractUrlFromWindowInfo(rootNode)
            }
            
            if (url != null) {
                // Skip Chrome's internal pages and error pages
                if (url.contains("chrome://") || 
                    url.contains("chrome-error://") ||
                    url.contains("data:text/html") ||
                    url.contains("about:") ||
                    url == "google.com" || // Skip Google search page
                    url.contains("google.com/search")) {
                    Log.d(TAG, "Skipping Chrome internal page: $url")
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
     * Uses multiple methods:
     * 1. Check event text/content description
     * 2. Traverse view hierarchy to find address bar (most reliable for Chrome)
     */
    private fun extractUrlFromEvent(event: AccessibilityEvent): String? {
        // Method 1: Check window title/text (some browsers show URL in title)
        event.text?.forEach { textItem ->
            val text = textItem.toString()
            val url = extractUrlFromText(text)
            if (url != null) {
                return url
            }
        }

        // Method 2: Check content description
        val contentDescription = event.contentDescription?.toString() ?: ""
        val urlFromDesc = extractUrlFromText(contentDescription)
        if (urlFromDesc != null) {
            return urlFromDesc
        }

        // Method 3: Traverse view hierarchy to find address bar
        // This is the most reliable method for Chrome
        val rootNode = rootInActiveWindow ?: return null
        val urlFromHierarchy = extractUrlFromViewHierarchy(rootNode)
        if (urlFromHierarchy != null) {
            return urlFromHierarchy
        }

        return null
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

            // Check each EditText for URL content
            for (node in editTextNodes) {
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                
                // Check text for URLs with protocol
                if (text.contains("http://") || text.contains("https://")) {
                    val urlPattern = Regex("https?://([^/\\s?]+)")
                    val match = urlPattern.find(text)
                    val domain = match?.groupValues?.get(1)?.lowercase()
                    if (domain != null && !domain.contains("chrome") && !domain.contains("google")) {
                        return domain.removePrefix("www.")
                    }
                }
                
                // Check content description for URLs with protocol
                if (contentDesc.contains("http://") || contentDesc.contains("https://")) {
                    val urlPattern = Regex("https?://([^/\\s?]+)")
                    val match = urlPattern.find(contentDesc)
                    val domain = match?.groupValues?.get(1)?.lowercase()
                    if (domain != null && !domain.contains("chrome") && !domain.contains("google")) {
                        return domain.removePrefix("www.")
                    }
                }
                
                // Check for domain patterns without protocol (address bar often shows just domain)
                // Only check if text is short (likely address bar, not page content)
                if (text.length < 100 && !text.contains(" ") && !text.contains("\n")) {
                    val domainPattern = Regex("([a-zA-Z0-9][a-zA-Z0-9-]*\\.(com|net|org|io|co|tv|xxx|site|website))")
                    val match = domainPattern.find(text)
                    if (match != null) {
                        val domain = match.value.lowercase()
                        // Skip Chrome internal and Google domains
                        if (!domain.contains("chrome") && 
                            !domain.contains("google") &&
                            !domain.contains("localhost") &&
                            !domain.startsWith("www.google")) {
                            return domain.removePrefix("www.")
                        }
                    }
                }
            }
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
     */
    private fun extractUrlFromText(text: String): String? {
        if (text.isBlank()) return null
        
        // Try multiple URL patterns
        // Pattern 1: Full URL with protocol
        val urlPattern1 = Regex("https?://([^/\\s?]+)")
        val match1 = urlPattern1.find(text)
        if (match1 != null) {
            val domain = match1.groupValues[1].lowercase()
            // Remove www. prefix for consistent matching
            return domain.removePrefix("www.")
        }
        
        // Pattern 2: Domain without protocol (e.g., "pornhub.com")
        val domainPattern = Regex("([a-zA-Z0-9-]+\\.(com|net|org|io|co|tv|xxx))")
        val match2 = domainPattern.find(text)
        if (match2 != null) {
            val domain = match2.groupValues[1].lowercase()
            return domain.removePrefix("www.")
        }
        
        return null
    }

    /**
     * Check if URL matches any adult domain and trigger blocking if so
     */
    private fun checkAndBlockIfAdultSite(url: String) {
        val domain = url.lowercase()
        
        // Check against adult domains list first
        val isAdultSite = ADULT_DOMAINS.any { adultDomain ->
            domain.contains(adultDomain, ignoreCase = true)
        }

        if (isAdultSite) {
            // Track that we're on an adult site
            lastKnownAdultDomain = domain
            
            // Check if unlock is still active (happy time)
            if (UnlockManager.isUnlocked(this)) {
                val remaining = UnlockManager.getRemainingSeconds(this)
                Log.d(TAG, "✅ Happy time active! Remaining: ${remaining}s - Skipping block")
                return
            }
            
            // Prevent re-triggering blocking too frequently (within 5 seconds) for the same domain
            // This prevents spam but allows re-triggering if overlay was dismissed or domain changed
            val timeSinceLastBlock = System.currentTimeMillis() - lastBlockTriggerTime
            val isSameDomain = domain == lastKnownAdultDomain
            if (timeSinceLastBlock < 5000 && isSameDomain) {
                // Silently skip - no need to log every check
                return
            }
            
            Log.d(TAG, "🚫 Adult site detected: $domain - Triggering block")
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
        } else {
            // Not an adult site - clear tracking
            lastKnownAdultDomain = null
        }
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
