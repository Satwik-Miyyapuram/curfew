package dev.curfew.app

import dev.curfew.app.block.BlockActivity
import dev.curfew.app.ui.clockMinute
import dev.curfew.app.ui.countdown
import dev.curfew.app.ui.dayLabel
import dev.curfew.app.ui.describePassRefusal
import dev.curfew.policy.PassRefusal
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

    /**
     * A countdown is an amount of time, never a clock face.
     *
     * The bug this pins: the Now screen and the block screen each had their own countdown, and below
     * an hour they disagreed. Fourteen minutes remaining read "14m" on Now and "14:00" on the block
     * screen — and "14:00" reads as two in the afternoon, so the screen somebody is staring at while
     * an app is blocked appeared to say their session ends at 2pm.
     */
    @Test
    fun `a countdown is an amount of time and not a time of day`() {
        assertEquals("1:12", countdown(4320))
        assertEquals("14m", countdown(840))
        assertEquals("48s", countdown(48))
        // A negative slice is a bug elsewhere; it must not become "-14m" on someone's screen.
        assertEquals("0s", countdown(-60))
    }

    /** …and the one screen that wants the seconds gets them without changing the rule. */
    @Test
    fun `the block screen shows seconds without inventing a clock face`() {
        assertEquals("14m 09s", countdown(849, withSeconds = true))
        // Above an hour it is the same as everywhere else: seconds on a two-hour wait are noise.
        assertEquals("1:12", countdown(4320, withSeconds = true))
        assertEquals("48s", countdown(48, withSeconds = true))
    }

    /**
     * A window's own times are 24-hour and say "midnight", because the user types them back in.
     *
     * This is the one clock in the app that deliberately ignores the device's 12/24-hour setting, and
     * the wraparound is the part worth pinning: a window ending at or before midnight is the core's
     * way of spelling "and on into tomorrow", so a value past 24 hours or below zero has to land on a
     * real time rather than on "25:00".
     */
    @Test
    fun `a window's times are clock faces a person can type back in`() {
        assertEquals("midnight", clockMinute(0))
        assertEquals("09:00", clockMinute(540))
        assertEquals("23:59", clockMinute(1439))
        assertEquals("01:00", clockMinute(25 * 60))
        assertEquals("23:00", clockMinute(-60))
    }

    /**
     * The two screens agree wherever agreement is expected, which is the property that was broken.
     *
     * They are allowed to differ in exactly one band — under an hour but over a minute, where the
     * block screen adds the ticking seconds — and nowhere else. Stated as a rule over every band
     * rather than as two literals, because two literals can both be right while the functions still
     * drift apart the next time somebody edits one of them.
     */
    @Test
    fun `both screens say the same thing about the same session`() {
        for (left in listOf(30L, 59L, 60L, 840L, 3599L, 3600L, 4320L, 25 * 3600L)) {
            val plain = countdown(left)
            val ticking = countdown(left, withSeconds = true)
            if (left in 60 until 3600) {
                assertTrue(
                    "the block screen lost its seconds at $left: $ticking",
                    ticking.startsWith(plain) && ticking.length > plain.length,
                )
            } else {
                assertEquals("the two screens disagree about $left seconds", plain, ticking)
            }
        }
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

    @Test
    fun `a refused pass says when rather than only no`() {
        val now = 1_788_510_600L

        val spent = describePassRefusal(PassRefusal.QuotaSpent(now + 2 * 86_400), now)
        assertTrue("a user cannot plan around a refusal with no time in it", spent.contains("in 48 hr"))

        val cooling = describePassRefusal(PassRefusal.CoolingDown(now + 3600), now)
        assertTrue(cooling.contains("in 1 hr"))

        // The disabled case is the one with nothing to wait for, so it says so instead of
        // implying a pass is on its way — and warns that switching it on will not help now.
        val off = describePassRefusal(PassRefusal.Disabled, now)
        assertTrue(off.contains("switched off"))
        assertTrue(off.contains("not unlock"))
    }

}
