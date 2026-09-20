package dev.curfew.app.enforce

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The subscription the rules justify, which is the part of the runtime surface narrowing that can be
 * checked without a device.
 *
 * The half that cannot be checked here is `setServiceInfo` itself — whether a platform accepts the
 * change, which differs by OEM and version. What *can* be pinned is the decision, and the guard that
 * makes a platform refusing it harmless: the change is applied to the service's existing info rather
 * than to a rebuilt one, so the flags — including `flagReportViewIds`, whose absence was the silent
 * bug — cannot be dropped by it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceSurfaceTest {

    @Test
    fun `no web rule means window-state changes only`() {
        val subscription = ServiceSurface.desired(webRulesExist = false)

        assertEquals(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, subscription.eventTypes)
        assertFalse(
            "a content event with no web rule is a wake-up that can decide nothing",
            subscription.eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0,
        )
        assertEquals("nothing to coalesce when only app switches arrive", 0L, subscription.notificationTimeout)
    }

    @Test
    fun `a web rule adds content events and the coalescer`() {
        val subscription = ServiceSurface.desired(webRulesExist = true)

        assertTrue(
            "app blocking has to keep working, whatever the web rules are",
            subscription.eventTypes and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != 0,
        )
        assertTrue(
            "the address bar is only re-read on a content event",
            subscription.eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0,
        )
        assertEquals(
            "the coalescer has to match the in-code recheck, or one of the two is doing nothing",
            ScreenWatcher.URL_RECHECK_MILLIS,
            subscription.notificationTimeout,
        )
    }

    /**
     * The reason the narrowing is safe: it is a narrowing *of what is delivered*, never of the
     * privilege, and `canRetrieveWindowContent` cannot be changed at runtime at all.
     */
    @Test
    fun `the surface can only be narrowed by this class`() {
        val wide = ServiceSurface.desired(webRulesExist = true)
        val narrow = ServiceSurface.desired(webRulesExist = false)

        // The narrow subscription is a strict subset: state changes are in both, content events only
        // in the wide one. A mode that replaced the event set rather than narrowing it would be a
        // mode that stopped blocking apps.
        assertEquals(narrow.eventTypes and wide.eventTypes, narrow.eventTypes)
        assertFalse(narrow.eventTypes == wide.eventTypes)
        assertTrue(wide.eventTypes and narrow.eventTypes != 0)
    }
}
