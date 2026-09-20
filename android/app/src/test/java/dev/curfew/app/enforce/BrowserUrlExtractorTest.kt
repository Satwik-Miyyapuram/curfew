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

    @Test
    fun `cleanUrl extracts valid domains and URLs`() {
        assertEquals("reddit.com", BrowserUrlExtractor.cleanUrl("reddit.com"))
        assertEquals("reddit.com/r/all", BrowserUrlExtractor.cleanUrl("reddit.com/r/all"))
        assertEquals("https://reddit.com", BrowserUrlExtractor.cleanUrl("https://reddit.com"))
        assertEquals("http://instagram.com/feed", BrowserUrlExtractor.cleanUrl("http://instagram.com/feed"))
        assertEquals("www.novelbuddy.me", BrowserUrlExtractor.cleanUrl("www.novelbuddy.me"))
        assertEquals("comix.to/comic/123", BrowserUrlExtractor.cleanUrl("comix.to/comic/123"))
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
    }
}
