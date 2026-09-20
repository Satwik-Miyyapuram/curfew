package dev.curfew.app.enforce

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracts and normalizes URLs from browser address bars via AccessibilityNodeInfo.
 *
 * Browsers expose the active website URL in their address bar (e.g. `url_bar`).
 * This extractor knows the resource IDs used by popular browsers (Chrome, Brave, Samsung Internet,
 * Firefox, Edge, Opera, DuckDuckGo, Vivaldi), cleans and normalizes the text into a candidate URL
 * string, and rejects non-URL input (search queries, blank pages, and placeholder text).
 *
 * **The browser set is closed, and that is a privacy property rather than a tidiness one.** The
 * package-name heuristics this used to carry — `endsWith(".browser")`, `contains(".chrome")` —
 * admitted anything that happened to be named like a browser, including `com.chromecast.*`. Those
 * packages were then passed to [`extractUrl`], which called `rootInActiveWindow` and probed three
 * fabricated view ids inside them on every content event. Reading the window of an arbitrary app
 * the user never asked Curfew to inspect is not a wrong decision, it is the thing that must never
 * happen. An unknown browser now degrades to "plain app": still blockable as an app, with its web
 * traffic covered by the DNS and extension layers.
 *
 * **Reading a URL requires `flagReportViewIds`.** Android strips view resource ids from the node
 * tree unless the service asks for them, and without the flag every lookup here returns an empty
 * list — silently, because null is also the legitimate answer for a new tab. The flag is set in
 * `res/xml/accessibility_service_config.xml`; if this file ever returns null for a browser the user
 * is demonstrably looking at, that flag is the first thing to check.
 */
object BrowserUrlExtractor {

    // Map of package names to known URL bar view resource IDs. This map *is* the browser list:
    // `isBrowser` is its key set, so adding a browser here is the one way to gain URL rules for it,
    // and nothing is ever guessed from a package name.
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

    /**
     * Whether [packageName] is a browser whose address bar Curfew knows how to read.
     *
     * An exact match against the curated map, and nothing else. See the class comment for why the
     * loose predicates that used to be here were removed rather than tightened.
     */
    fun isBrowser(packageName: String): Boolean = packageName in BROWSER_URL_BAR_IDS

    /**
     * The view ids to look for in [packageName], or null when it is not a known browser.
     *
     * The caller looks the ids up once per event and passes them in, so that a package which is not
     * a browser can never reach the node access at all.
     */
    fun urlBarIds(packageName: String): List<String>? = BROWSER_URL_BAR_IDS[packageName]

    /**
     * Inspect [root] to find the active URL bar for [ids].
     * Returns a cleaned candidate URL or null if no valid URL is active.
     *
     * [ids] is required rather than defaulted: a package that is not on the curated list has no
     * known view id, and the version of this that fabricated `"$packageName:id/url_bar"` was both
     * three allocations and three IPC calls for no result, *and* a read of a window Curfew had no
     * business reading.
     */
    fun extractUrl(root: AccessibilityNodeInfo?, ids: List<String>): String? {
        if (root == null || ids.isEmpty()) return null

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
     *
     * **The shape test comes first, and that order is the fix.** Placeholder detection used to be a
     * prefix test on the raw text, so any host starting with the word "search" — `search.brave.com`,
     * `search.yahoo.com`, `searchengineland.com` — was discarded before it was ever parsed, and a
     * rule blocking one of them silently never matched. A placeholder and a hostname live in the same
     * field, and what distinguishes them is not the first six letters: a placeholder is a phrase with
     * spaces in it and a host is not. Asking the shape question first answers both correctly.
     */
    fun cleanUrl(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null

        // Explicitly internal pages, whatever they look like otherwise. These are the browser's own
        // chrome rather than the web, and none of them is a host.
        if (INTERNAL_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }) return null

        val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)

        // A placeholder string always contains spaces ("Search or type web address", "Search Brave
        // or type URL"), and a URL never does — an unencoded space is not a URL. This one test kills
        // every placeholder without looking at a single letter of it.
        if (trimmed.contains(' ')) return null

        if (!hasScheme) {
            // Without a scheme, a dot is what makes it a host rather than a search term.
            if (!trimmed.contains('.')) return null
            val hostPart = trimmed.substringBefore('/').substringBefore(':')
            val tld = hostPart.substringAfterLast('.', "")
            if (tld.length < 2) return null
        }

        return trimmed
    }

    /**
     * Browser-internal schemes. Anything starting with one of these is the browser's own UI, not a
     * page, and is never a target.
     */
    private val INTERNAL_PREFIXES = listOf(
        "about:",
        "chrome://",
        "brave://",
        "edge://",
        "opera://",
        "vivaldi://",
        "firefox://",
        "moz-extension://",
        "chrome-extension://",
        "file://",
    )
}
