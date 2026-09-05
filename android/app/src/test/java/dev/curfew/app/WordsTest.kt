package dev.curfew.app

import dev.curfew.app.block.BlockActivity
import dev.curfew.app.ui.dayLabel
import dev.curfew.app.ui.describeLock
import dev.curfew.app.ui.describeTarget
import dev.curfew.app.ui.duration
import dev.curfew.app.ui.relative
import dev.curfew.policy.BlockReason
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Curfew says when it says no.
 *
 * These read like trivial string tests, and they are the difference between a tool someone keeps
 * and one they uninstall in a bad moment. Every refusal has to name what happened, whose rule it
 * was, and when it ends; none of them may leak the core's internal key syntax at a person.
 */
class WordsTest {

    @Test
    fun `every block reason produces a sentence, and none of them blame the user`() {
        val reasons = listOf(
            BlockReason.Blocked("Deep work"),
            BlockReason.NotAllowlisted("Deep work"),
            BlockReason.BudgetExhausted("Deep work", 1800),
            BlockReason.LaunchLimitReached("Deep work", 3),
        )

        reasons.forEach { reason ->
            val text = BlockActivity.explain(reason)
            assertTrue(text, text.endsWith("."))
            assertFalse(text, text.contains("null"))
        }
    }

    @Test
    fun `an exhausted budget is reported in minutes, not seconds`() {
        val text = BlockActivity.explain(BlockReason.BudgetExhausted("Deep work", 1800))

        assertTrue(text, text.contains("30 minutes"))
    }

    @Test
    fun `a reached launch limit says the number the user chose`() {
        val text = BlockActivity.explain(BlockReason.LaunchLimitReached("Deep work", 3))

        assertTrue(text, text.contains("3 times"))
    }

    @Test
    fun `durations are said the way a person would say them`() {
        assertEquals("45 sec", duration(45))
        assertEquals("20 min", duration(1200))
        assertEquals("2 hr", duration(7200))
        assertEquals("1 hr 30 min", duration(5400))
        // A negative slice is a bug elsewhere; it must not become "-5 min" on someone's screen.
        assertEquals("0 sec", duration(-300))
    }

    @Test
    fun `relative times read forwards and backwards`() {
        assertEquals("now", relative(100, 100))
        assertEquals("in 20 min", relative(1300, 100))
        assertEquals("20 min ago", relative(100, 1300))
    }

    @Test
    fun `the core's key syntax never reaches the screen`() {
        val shown = listOf(
            "app:com.instagram.android",
            "domain:reddit.com",
            "keyword:crypto",
            "notif:com.whatsapp",
            "device",
        ).map(::describeTarget)

        assertTrue(shown.toString(), shown.none { it.contains(":") })
        assertEquals("the whole device", describeTarget("device"))
    }

    @Test
    fun `every lock can say what it wants`() {
        val locks = listOf(
            Lock.Timer,
            Lock.Confirm,
            Lock.DeviceCredential,
            Lock.Challenge(ChallengeKind.TYPING),
            Lock.Challenge(ChallengeKind.MATH),
            Lock.PeerRelease("phone"),
            Lock.Token("safe"),
            Lock.RestartRequired,
        )

        locks.forEach { lock ->
            val text = describeLock(lock)
            assertTrue(lock.toString(), text.isNotBlank())
            assertFalse(text, text.contains("Lock"))
        }
    }

    /**
     * The preview timeline groups rows by day, so this label is what stops a block tomorrow
     * morning from being read as one tonight. It has to follow the device's own midnight, not
     * a fixed number of seconds from now.
     */
    @Test
    fun `a day label says today, tomorrow, or a date`() {
        val zone = java.util.TimeZone.getDefault()
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
            val c = java.util.Calendar.getInstance(zone)
            c.clear()
            c.set(year, month - 1, day, hour, minute, 0)
            return c.timeInMillis / 1000
        }

        val now = at(2026, 9, 4, 22, 0)
        assertEquals("Today", dayLabel(now + 600, now))
        // Two hours later is the next calendar day, even though it is barely any time away.
        assertEquals("Tomorrow", dayLabel(at(2026, 9, 5, 0, 30), now))
        assertEquals("Tomorrow", dayLabel(at(2026, 9, 5, 23, 0), now))
        // Anything further reads as a date rather than a day name nobody can place.
        assertTrue(dayLabel(at(2026, 9, 6, 9, 0), now) !in listOf("Today", "Tomorrow"))
        // And across a new year, where the day-of-year arithmetic would otherwise go backwards.
        val newYearsEve = at(2026, 12, 31, 23, 0)
        assertEquals("Tomorrow", dayLabel(at(2027, 1, 1, 9, 0), newYearsEve))
    }

}
