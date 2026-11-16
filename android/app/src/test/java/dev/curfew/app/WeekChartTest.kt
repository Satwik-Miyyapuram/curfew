package dev.curfew.app

import dev.curfew.app.ui.WeekWindow
import dev.curfew.app.ui.calendarBlockAt
import dev.curfew.app.ui.firstSegment
import dev.curfew.app.ui.spanOfMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The week chart's arithmetic, pinned without a screen.
 *
 * Two things here are easy to get wrong in ways that only show up on a real device, which is exactly
 * why they are asserted rather than eyeballed:
 *
 * 1. **Which column a day lands in.** `Calendar.DAY_OF_WEEK` starts the week on Sunday and the policy
 *    module starts it on Monday, so the offset is a subtraction and not the constant. Getting it
 *    wrong shifts every calendar block by a day, and a chart that is one day out looks completely
 *    plausible.
 * 2. **The overnight split.** A block that crosses midnight has to be two pieces, and the version of
 *    `firstSegment` that clipped its answer before the chart tested for a spill never drew the second
 *    one at all.
 *
 * Everything runs in a fixed zone, because a test that passes in London and fails in Auckland is a
 * test that tells you about the machine rather than about the code.
 */
class WeekChartTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** An epoch second for a given local wall clock in [utc]. */
    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = Calendar.getInstance(utc).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis / 1000L

    // 2026-09-21 is a Monday, so the whole week can be walked from one date.
    private val monday = at(2026, 9, 21, 9, 0)

    @Test
    fun `monday lands in the first column`() {
        val block = calendarBlockAt(monday, monday + 3600, 0, 0, "Focus", utc)
        assertEquals(listOf(0), block?.days)
        assertEquals(9 * 60, block?.startMinute)
        assertEquals(10 * 60, block?.endMinute)
    }

    @Test
    fun `sunday lands in the last column, not the first`() {
        // The off-by-one this test exists for: `DAY_OF_WEEK - MONDAY` on a Sunday is -1, and a
        // version that forgot the `+ 7` put every Sunday meeting on Monday.
        val sunday = at(2026, 9, 27, 9, 0)
        val block = calendarBlockAt(sunday, sunday + 3600, 0, 0, "Focus", utc)
        assertEquals(listOf(6), block?.days)
    }

    @Test
    fun `every weekday maps to its own column`() {
        for (offset in 0..6) {
            val start = monday + offset * 24L * 3600L
            val block = calendarBlockAt(start, start + 1800, 0, 0, "Focus", utc)
            assertEquals("day $offset", listOf(offset), block?.days)
        }
    }

    @Test
    fun `padding extends the block in both directions`() {
        // A ten-minute lead-in means the lock is up ten minutes before the meeting, and the chart
        // has to show what the app will actually do rather than what the invitation says.
        val block = calendarBlockAt(monday, monday + 3600, 600, 600, "Focus", utc)
        assertEquals(8 * 60 + 50, block?.startMinute)
        assertEquals(10 * 60 + 10, block?.endMinute)
    }

    @Test
    fun `padding expands a short block rather than cancelling it`() {
        // The core adds both pads to a five-minute event, so a five-minute meeting with a five-minute
        // buffer either side blocks for fifteen minutes. The first version of this function
        // *subtracted* them and produced a negative length, which drew nothing at all — the chart
        // silently omitting the very blocks the rule exists to catch.
        val block = calendarBlockAt(monday, monday + 300, 300, 300, "Focus", utc)
        assertEquals(8 * 60 + 55, block?.startMinute)
        assertEquals(9 * 60 + 10, block?.endMinute)
    }

    @Test
    fun `a zero-length event with no padding is not drawn`() {
        assertNull(calendarBlockAt(monday, monday, 0, 0, "Focus", utc))
    }

    @Test
    fun `a block crossing midnight is two pieces`() {
        val start = at(2026, 9, 21, 23, 0)
        val end = at(2026, 9, 22, 1, 30)
        val block = calendarBlockAt(start, end, 0, 0, "Focus", utc)
        assertEquals(23 * 60, block?.startMinute)
        assertEquals(90, block?.endMinute)
        val span = spanOfMinutes(block!!.startMinute, block.endMinute, block.endMinute < block.startMinute)
        assertEquals(150, span)
        val (tonight, tomorrow) = firstSegment(block.startMinute, span)
        assertEquals(60, tonight)
        assertEquals(90, tomorrow)
    }

    @Test
    fun `a same-day block has no spill`() {
        val (tonight, tomorrow) = firstSegment(9 * 60, 510)
        assertEquals(510, tonight)
        assertEquals(0, tomorrow)
    }

    @Test
    fun `an all-day block reaches the end of its column`() {
        val start = at(2026, 9, 21, 0, 0)
        val end = at(2026, 9, 22, 0, 0)
        val block = calendarBlockAt(start, end, 0, 0, "Focus", utc)
        assertEquals(0, block?.startMinute)
        assertEquals(0, block?.endMinute)
        // 00:00 to 00:00 is the full day, not a zero-length window: the same rule that makes a
        // weekly window with equal times a whole day rather than an empty one.
        assertEquals(1440, spanOfMinutes(block!!.startMinute, block.endMinute, true))
    }

    @Test
    fun `the profile name is carried through for the caption`() {
        val block: WeekWindow? = calendarBlockAt(monday, monday + 3600, 0, 0, "Focus", utc)
        assertEquals(listOf("Focus"), block?.profileNames)
    }
}