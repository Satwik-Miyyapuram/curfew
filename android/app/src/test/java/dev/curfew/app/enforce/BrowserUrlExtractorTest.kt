package dev.curfew.app.enforce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlExtractorTest {

    @Test
    fun `isBrowser identifies known browser packages`() {
        assertTrue(BrowserUrlExtractor.isBrowser("com.android.chrome"))
        assertTrue(BrowserUrlExtractor.isBrowser("com.brave.browser"))
        assertTrue(BrowserUrlExtractor.isBrowser("com.sec.android.app.sbrowser"))
        assertTrue(BrowserUrlExtractor.isBrowser("org.mozilla.firefox"))
        assertTrue(BrowserUrlExtractor.isBrowser("com.microsoft.emmx"))
        assertTrue(BrowserUrlExtractor.isBrowser("com.opera.browser"))
        assertTrue(BrowserUrlExtractor.isBrowser("com.duckduckgo.mobile.android"))
        assertTrue(BrowserUrlExtractor.isBrowser("com.vivaldi.browser"))

        assertFalse(BrowserUrlExtractor.isBrowser("com.spotify.music"))
        assertFalse(BrowserUrlExtractor.isBrowser("com.whatsapp"))
        assertFalse(BrowserUrlExtractor.isBrowser("com.sec.android.app.launcher"))
    }

    /**
     * The packages the old substring heuristics used to admit.
     *
     * Each of these was treated as a browser, which meant `rootInActiveWindow` and three fabricated
     * view-id probes inside an app the user never asked Curfew to inspect — and a bank, a wallet or
     * anything else can be named like this. Reading an arbitrary app's window is the one outcome
     * that must never happen, so the set is closed and these are the regression.
     */
    @Test
    fun `a package that is merely named like a browser is not one`() {
        assertFalse(BrowserUrlExtractor.isBrowser("com.example.mybrowser"))
        assertFalse(BrowserUrlExtractor.isBrowser("com.company.chrometools"))
        assertFalse(BrowserUrlExtractor.isBrowser("com.google.android.apps.chromecast.app"))
        assertFalse(BrowserUrlExtractor.isBrowser("com.example.chrome"))
        assertFalse(BrowserUrlExtractor.isBrowser("org.example.browser"))
    }

    /** No package can reach the node lookup without a known url-bar id. Nothing is guessed. */
    @Test
    fun `url bar ids are known only for the curated list`() {
        assertNull(BrowserUrlExtractor.urlBarIds("com.example.mybrowser"))
        assertNull(BrowserUrlExtractor.urlBarIds("com.google.android.apps.chromecast.app"))
        assertEquals(
            listOf("com.android.chrome:id/url_bar", "com.android.chrome:id/search_box_text"),
            BrowserUrlExtractor.urlBarIds("com.android.chrome"),
        )
    }

    @Test
    fun `cleanUrl extracts valid domains and URLs`() {
        assertEquals("reddit.com", BrowserUrlExtractor.cleanUrl("reddit.com"))
        assertEquals("reddit.com/r/all", BrowserUrlExtractor.cleanUrl("reddit.com/r/all"))
        assertEquals("https://reddit.com", BrowserUrlExtractor.cleanUrl("https://reddit.com"))
        assertEquals("http://instagram.com/feed", BrowserUrlExtractor.cleanUrl("http://instagram.com/feed"))
        assertEquals("www.novelbuddy.me", BrowserUrlExtractor.cleanUrl("www.novelbuddy.me"))
        assertEquals("comix.to/comic/123", BrowserUrlExtractor.cleanUrl("comix.to/comic/123"))
    }

    /**
     * A host that starts with the word "search" is a host, not placeholder text.
     *
     * Placeholder detection used to be a prefix test on the raw text, so `search.brave.com` and
     * `searchengineland.com` were discarded before they were parsed and a rule blocking one of them
     * could never match. The distinguishing feature is the shape — a placeholder has spaces, a host
     * does not — and the shape test now runs first.
     */
    @Test
    fun `a host beginning with search is a host`() {
        assertEquals("search.brave.com", BrowserUrlExtractor.cleanUrl("search.brave.com"))
        assertEquals("search.yahoo.com/search?p=x", BrowserUrlExtractor.cleanUrl("search.yahoo.com/search?p=x"))
        assertEquals("searchengineland.com", BrowserUrlExtractor.cleanUrl("searchengineland.com"))
        assertEquals("search.marginalia.nu", BrowserUrlExtractor.cleanUrl("search.marginalia.nu"))
        // And the placeholder it was confused with is still rejected, because it is a phrase.
        assertNull(BrowserUrlExtractor.cleanUrl("Search or type web address"))
        assertNull(BrowserUrlExtractor.cleanUrl("Search Brave or type URL"))
    }

    @Test
    fun `cleanUrl rejects placeholders, internal pages and search terms`() {
        assertNull(BrowserUrlExtractor.cleanUrl(null))
        assertNull(BrowserUrlExtractor.cleanUrl(""))
        assertNull(BrowserUrlExtractor.cleanUrl("   "))
        assertNull(BrowserUrlExtractor.cleanUrl("Search Brave or type URL"))
        assertNull(BrowserUrlExtractor.cleanUrl("Search or type web address"))
        assertNull(BrowserUrlExtractor.cleanUrl("New Tab"))
        assertNull(BrowserUrlExtractor.cleanUrl("about:blank"))
        assertNull(BrowserUrlExtractor.cleanUrl("chrome://settings"))
        assertNull(BrowserUrlExtractor.cleanUrl("brave://flags"))
        assertNull(BrowserUrlExtractor.cleanUrl("how to make coffee"))
        assertNull(BrowserUrlExtractor.cleanUrl("justaword"))
        // The other internal schemes, which used to slip through to the shape test and were then
        // rejected for having no dot rather than for being browser chrome.
        assertNull(BrowserUrlExtractor.cleanUrl("edge://settings"))
        assertNull(BrowserUrlExtractor.cleanUrl("file:///sdcard/x.html"))
        assertNull(BrowserUrlExtractor.cleanUrl("vivaldi://about"))
    }
}
