package dev.curfew.app

import dev.curfew.app.ui.describeDays
import dev.curfew.app.ui.describeMatcher
import dev.curfew.app.ui.describePadding
import dev.curfew.app.ui.describeWindow
import dev.curfew.app.ui.hhMmToMinutes
import dev.curfew.app.ui.minutesToHhMm
import dev.curfew.policy.EventMatcher
import dev.curfew.policy.WeeklySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading and writing the schedule forms do.
 *
 * Every one of these is a place a form can lie: a time that renders one way and parses another, a
 * window that crosses midnight and reads as empty, a padding of zero described as if it were
 * something. The forms themselves are Compose; what is provable without a device is exactly this,
 * and it is where the mistakes are.
 */
class ScheduleEditorTest {

    @Test
    fun `a time renders the way it will be typed back in`() {
        assertEquals("09:00", minutesToHhMm(540))
        assertEquals("23:30", minutesToHhMm(1_410))
        assertEquals("00:00", minutesToHhMm(0))
    }

    /** Midnight at the end of the day is the same clock face as midnight at the start. */
    @Test
    fun `1440 minutes reads as midnight rather than as an impossible hour`() {
        assertEquals("00:00", minutesToHhMm(1_440))
    }

    @Test
    fun `what the field renders is what the field accepts`() {
        for (minutes in listOf(0, 1, 59, 540, 1_410, 1_439)) {
            assertEquals(minutes, hhMmToMinutes(minutesToHhMm(minutes)))
        }
    }

    @Test
    fun `the shapes people actually type are accepted`() {
        assertEquals(545, hhMmToMinutes("9:5"))
        assertEquals(545, hhMmToMinutes("09:05"))
        assertEquals(545, hhMmToMinutes(" 9 : 5 "))
    }

    /**
     * A rejected time has to be rejected, not rounded. A field that turned 25:00 into 01:00 would
     * silently move a window to a different day.
     */
    @Test
    fun `what is not a time is refused rather than guessed at`() {
        for (bad in listOf("", "9", "24:00", "9:60", "-1:00", "9:00:00", "nine", "9:aa")) {
            assertNull("$bad was read as a time", hhMmToMinutes(bad))
        }
    }

    @Test
    fun `the common day sets are named the way people say them`() {
        assertEquals("Every day", describeDays(emptyList()))
        assertEquals("Weekdays", describeDays(listOf(0, 1, 2, 3, 4)))
        assertEquals("Weekends", describeDays(listOf(5, 6)))
        // Sorted on the way out, so the chips being tapped out of order does not reorder the week.
        assertEquals("Mon, Wed", describeDays(listOf(2, 0)))
    }

    /** Day 0 is Monday in the core, and the list has to agree or every window shifts a day. */
    @Test
    fun `day zero is monday`() {
        assertEquals("Mon", describeDays(listOf(0)))
        assertEquals("Sun", describeDays(listOf(6)))
    }

    private fun window(start: Int, end: Int, days: List<Int> = emptyList()) =
        WeeklySchedule(id = "w", profile = "deep-work", days = days, startMinute = start, endMinute = end)

    @Test
    fun `an ordinary window reads as its two times`() {
        assertEquals("Weekdays · 09:00 - 17:00", describeWindow(window(540, 1_020, listOf(0, 1, 2, 3, 4))))
    }

    /**
     * The one range a person routinely misreads. "23:00 - 07:00" looks like an empty window unless
     * it says otherwise, and it is the single most common thing anyone asks a blocker for.
     */
    @Test
    fun `a window crossing midnight says so`() {
        assertEquals("Every day · 23:00 - 07:00 (overnight)", describeWindow(window(1_380, 420)))
    }

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
