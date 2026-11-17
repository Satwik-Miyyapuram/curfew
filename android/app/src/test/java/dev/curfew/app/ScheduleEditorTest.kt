package dev.curfew.app

import dev.curfew.app.ui.describeMatcher
import dev.curfew.app.ui.describePadding
import dev.curfew.policy.EventMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading and writing the schedule forms do.
 *
 * Every one of these is a place a form can lie: a matcher described as narrower than it is, a
 * padding of zero described as if it were something. The forms themselves are Compose; what is provable without a device is exactly this,
 * and it is where the mistakes are.
 */
class ScheduleEditorTest {

    @Test
    fun `an empty matcher says it catches everything`() {
        assertEquals("Every event", describeMatcher(EventMatcher()))
    }

    @Test
    fun `a matcher reads as the sentence it is`() {
        val matcher = EventMatcher(title = "*focus*", calendar = "Work", busyOnly = true)
        assertEquals("Events on the Work calendar, titled *focus*, marked busy", describeMatcher(matcher))
    }

    /** A field left blank is not a field matching the empty string, and must not be described. */
    @Test
    fun `a blank field is not mentioned`() {
        assertEquals("Every event", describeMatcher(EventMatcher(title = "", calendar = "  ")))
    }

    @Test
    fun `no padding is described as nothing at all`() {
        assertNull(describePadding(0, 0))
    }

    @Test
    fun `padding is described in the minutes it was entered in`() {
        assertEquals("Starts 5 min early", describePadding(300, 0))
        assertEquals("Runs 10 min late", describePadding(0, 600))
        assertEquals("Starts 5 min early, runs 10 min late", describePadding(300, 600))
    }
}
