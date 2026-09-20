package dev.curfew.app.enforce

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracts and normalizes URLs from browser address bars via AccessibilityNodeInfo.
 *
 * Browsers expose the active website URL in their address bar (e.g. `url_bar`).
 * This extractor knows the resource IDs used by popular browsers (Chrome, Brave, Samsung Internet,
 * Firefox, Edge, Opera, DuckDuckGo, Vivaldi), cleans and normalizes the text into a candidate URL
 * string, and rejects non-URL input (search queries, blank pages, and placeholder text).
 */
object BrowserUrlExtractor {

    // Map of package names to known URL bar view resource IDs.
    private val BROWSER_URL_BAR_IDS = mapOf(
        // Google Chrome
        "com.android.chrome" to listOf("com.android.chrome:id/url_bar", "com.android.chrome:id/search_box_text"),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
        "com.chrome.canary" to listOf("com.chrome.canary:id/url_bar"),

        // Brave Browser
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.brave.browser_beta" to listOf("com.brave.browser_beta:id/url_bar"),
        "com.brave.browser_nightly" to listOf("com.brave.browser_nightly:id/url_bar"),

        // Samsung Internet
        "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
        "com.sec.android.app.sbrowser.beta" to listOf("com.sec.android.app.sbrowser.beta:id/location_bar_edit_text"),

        // Mozilla Firefox & Firefox Focus
        "org.mozilla.firefox" to listOf(
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/url_bar_title",
            "org.mozilla.firefox:id/toolbar",
        ),
        "org.mozilla.firefox_beta" to listOf(
            "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox_beta:id/url_bar_title",
        ),
        "org.mozilla.fenix" to listOf(
            "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
            "org.mozilla.fenix:id/url_bar_title",
        ),
        "org.mozilla.focus" to listOf("org.mozilla.focus:id/display_url"),

        // Microsoft Edge
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "com.microsoft.emmx.beta" to listOf("com.microsoft.emmx.beta:id/url_bar"),
        "com.microsoft.emmx.canary" to listOf("com.microsoft.emmx.canary:id/url_bar"),

        // Opera
        "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
        "com.opera.mini.native" to listOf("com.opera.mini.native:id/url_field"),
        "com.opera.gx" to listOf("com.opera.gx:id/url_field"),

        // DuckDuckGo
        "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),

        // Vivaldi
        "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
    )

    private val BROWSER_PACKAGES = BROWSER_URL_BAR_IDS.keys

    /** True if [packageName] is a known web browser. */
    fun isBrowser(packageName: String): Boolean =
        packageName in BROWSER_PACKAGES ||
            packageName.endsWith(".browser") ||
            packageName.contains(".chrome")

    /**
     * Inspect [root] to find the active URL bar for [packageName].
     * Returns a cleaned candidate URL or null if no valid URL is active.
     */
    fun extractUrl(root: AccessibilityNodeInfo?, packageName: String): String? {
        if (root == null) return null

        val ids = BROWSER_URL_BAR_IDS[packageName] ?: listOf(
            "$packageName:id/url_bar",
            "$packageName:id/location_bar_edit_text",
            "$packageName:id/url_field",
        )

        for (id in ids) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val text = node.text?.toString()
                    val candidate = cleanUrl(text)
                    if (candidate != null) return candidate
                }
            }
        }

        return null
    }

    /**
     * Cleans [raw] text from an address bar, returning a valid URL or host if it resembles one.
     * Rejects placeholder text, internal browser pages, and search queries.
     */
    fun cleanUrl(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Ignore common placeholder hints and internal pages
        if (trimmed.startsWith("Search", ignoreCase = true) ||
            trimmed.startsWith("Type ", ignoreCase = true) ||
            trimmed.equals("New Tab", ignoreCase = true) ||
            trimmed.equals("about:blank", ignoreCase = true) ||
            trimmed.startsWith("chrome://", ignoreCase = true) ||
            trimmed.startsWith("brave://", ignoreCase = true) ||
            trimmed.startsWith("edge://", ignoreCase = true)
        ) {
            return null
        }

        // A valid URL or domain:
        // Either has an explicit scheme (http://, https://) OR has a dot and no unencoded spaces
        val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)

        if (!hasScheme) {
            if (trimmed.contains(' ')) return null
            if (!trimmed.contains('.')) return null
            val hostPart = trimmed.substringBefore('/').substringBefore(':')
            val tld = hostPart.substringAfterLast('.', "")
            if (tld.length < 2) return null
        } else {
            if (trimmed.contains(' ')) return null
        }

        return trimmed
    }
}
