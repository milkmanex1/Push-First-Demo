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
            "www.porn.com"
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
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED

        if (shouldProcess) {
            Log.d(TAG, "Browser event detected: package=$packageName, type=${event.eventType}")
            // Store the browser package name for later use
            currentBrowserPackage = packageName
            
            // Trigger immediate check (monitoring will also catch it)
            handler.postDelayed({
                checkCurrentUrl()
            }, 200) // Small delay to ensure view hierarchy is ready
        }
    }

    /**
     * Continuously check the current URL from the active window
     * This method polls the address bar to catch URL changes
     */
    private fun checkCurrentUrl() {
        try {
            val rootNode = rootInActiveWindow ?: return
            
            // Extract URL from view hierarchy
            val url = extractUrlFromViewHierarchy(rootNode)
            
            if (url != null && url != lastCheckedUrl) {
                // Skip Chrome's internal pages and error pages
                if (url.contains("chrome://") || 
                    url.contains("chrome-error://") ||
                    url.contains("data:text/html") ||
                    url.contains("about:") ||
                    url == "google.com" || // Skip Google search page
                    url.contains("google.com/search")) {
                    Log.d(TAG, "Skipping Chrome internal page: $url")
                    return
                }
                
                lastCheckedUrl = url
                Log.d(TAG, "✅ Detected browser navigation to: $url")
                checkAndBlockIfAdultSite(url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking current URL: ${e.message}")
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
                Log.d(TAG, "Found URL from event text: $url")
                return url
            }
        }

        // Method 2: Check content description
        val contentDescription = event.contentDescription?.toString() ?: ""
        val urlFromDesc = extractUrlFromText(contentDescription)
        if (urlFromDesc != null) {
            Log.d(TAG, "Found URL from content description: $urlFromDesc")
            return urlFromDesc
        }

        // Method 3: Traverse view hierarchy to find address bar
        // This is the most reliable method for Chrome
        val rootNode = rootInActiveWindow ?: return null
        val urlFromHierarchy = extractUrlFromViewHierarchy(rootNode)
        if (urlFromHierarchy != null) {
            Log.d(TAG, "Found URL from view hierarchy: $urlFromHierarchy")
            return urlFromHierarchy
        }

        return null
    }

    /**
     * Traverse the view hierarchy to find the URL/address bar
     * Chrome's address bar can be found by searching for EditText views
     * with URL-like content or specific class names
     */
    private fun extractUrlFromViewHierarchy(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        try {
            // Method 1: Search for EditText nodes (address bar is typically an EditText)
            val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
            collectEditTextNodes(rootNode, editTextNodes)

            // Check each EditText for URL content
            for (node in editTextNodes) {
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                
                // Log for debugging
                if (text.isNotEmpty() || contentDesc.isNotEmpty()) {
                    Log.d(TAG, "Found EditText - text: '$text', desc: '$contentDesc'")
                }
                
                // Check if this looks like a URL bar (contains http/https or domain pattern)
                val combinedText = "$text $contentDesc"
                
                // Check for full URLs
                if (text.contains("http://") || text.contains("https://")) {
                    val urlPattern = Regex("https?://([^/\\s?]+)")
                    val match = urlPattern.find(text)
                    val domain = match?.groupValues?.get(1)?.lowercase()
                    if (domain != null && !domain.contains("chrome") && !domain.contains("google")) {
                        Log.d(TAG, "Found URL from EditText: $domain")
                        return domain.removePrefix("www.")
                    }
                }
                
                // Check for domain patterns
                val url = extractUrlFromText(combinedText)
                if (url != null && !url.contains("chrome") && !url.contains("google")) {
                    Log.d(TAG, "Found domain from EditText: $url")
                    return url
                }
            }

            // Method 2: Search all nodes for URL patterns (fallback)
            val allText = StringBuilder()
            collectAllText(rootNode, allText)
            val fullText = allText.toString()
            
            // Extract URL from all text
            val url = extractUrlFromText(fullText)
            if (url != null && !url.contains("chrome") && !url.contains("google")) {
                Log.d(TAG, "Found domain from all text: $url")
                return url
            }
            
            // Method 3: Look for specific Chrome address bar hints
            // Chrome's address bar might have hints like "Search Google or type a URL"
            // But we want the actual URL, so look for nodes with URL patterns
            val urlPattern = Regex("https?://([^/\\s?]+)")
            val matches = urlPattern.findAll(fullText)
            for (match in matches) {
                val domain = match.groupValues[1].lowercase()
                if (!domain.contains("chrome") && 
                    !domain.contains("google.com") && 
                    !domain.contains("data:") &&
                    domain.contains(".")) {
                    Log.d(TAG, "Found domain from pattern matching: $domain")
                    return domain.removePrefix("www.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing view hierarchy: ${e.message}", e)
        }

        return null
    }

    /**
     * Recursively collect all EditText nodes from the view hierarchy
     */
    private fun collectEditTextNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""
        
        // Collect EditText nodes and also nodes that might be address bars
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("Omnibox", ignoreCase = true) || // Chrome's address bar component
            className.contains("UrlBar", ignoreCase = true)) {
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
        
        Log.d(TAG, "Checking domain: $domain against adult domains list")
        
        // Check if unlock is still active (happy time)
        if (UnlockManager.isUnlocked(this)) {
            val remaining = UnlockManager.getRemainingSeconds(this)
            Log.d(TAG, "✅ Happy time active! Remaining: ${remaining}s - Skipping block")
            return
        }
        
        // Check against adult domains list
        val isAdultSite = ADULT_DOMAINS.any { adultDomain ->
            val matches = domain.contains(adultDomain, ignoreCase = true)
            if (matches) {
                Log.d(TAG, "Match found: $domain contains $adultDomain")
            }
            matches
        }

        if (isAdultSite) {
            Log.d(TAG, "🚫 Adult site detected: $domain - Triggering block")
            try {
                // Start blocking overlay service
                val intent = Intent(this, BlockingOverlayService::class.java)
                intent.putExtra("blocked_domain", domain)
                // Pass the browser package name so we can return to it later
                currentBrowserPackage?.let {
                    intent.putExtra("browser_package", it)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    @Suppress("DEPRECATION")
                    startService(intent)
                }
                Log.d(TAG, "✅ BlockingOverlayService started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting BlockingOverlayService: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Domain $domain is not in blocked list")
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
