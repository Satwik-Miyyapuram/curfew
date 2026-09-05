package dev.curfew.app

import dev.curfew.app.data.AuditRow
import dev.curfew.app.data.CurfewRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The figures the Usage tab shows, rebuilt from the audit trail.
 *
 * The arithmetic itself is the core's and is tested there; what these prove is the join — that
 * start and end rows are paired into sessions correctly, that the awkward pairs (a session still
 * running, an end whose start has been pruned) do something sensible, and that the export writes
 * what is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StatsTest {

    /** 2026-09-04 09:30, Europe/London — the config's timezone. */
    private val now = TestRuntime.FRIDAY_0930
    private val day = 24 * 60 * 60L

    private suspend fun CurfewRuntime.note(kind: String, id: String, at: Long) =
        db.audit().add(AuditRow(at = at, kind = kind, detail = id))

    @Test
    fun `an empty history is a fortnight of zeroes`() = runTest {
        val stats = TestRuntime.create(now).stats(now = now)

        assertEquals(14, stats.days.size)
        assertEquals("2026-09-04", stats.days.last().day)
        assertEquals("2026-08-22", stats.days.first().day)
        assertTrue(stats.days.all { it.sessions == 0 && it.blockedSeconds == 0 })
        assertEquals(0, stats.currentStreak)
        assertEquals(0L, stats.totalBlockedSeconds)
    }

    @Test
    fun `a finished session lands on its day`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.note("session.started", "s1", now - 3 * 60 * 60)
        runtime.note("session.ended", "s1", now - 2 * 60 * 60)

        val stats = runtime.stats(now = now)
        val today = stats.days.single { it.day == "2026-09-04" }
        assertEquals(3600, today.blockedSeconds)
        assertEquals(1, today.sessions)
        assertEquals(1, stats.currentStreak)
    }

    @Test
    fun `a session still running is counted up to now`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.note("session.started", "s1", now - 1800)

        val stats = runtime.stats(now = now)
        assertEquals(1800, stats.days.single { it.day == "2026-09-04" }.blockedSeconds)
        assertEquals(1, stats.currentStreak)
    }

    /**
     * Thirty days of retention means a long session's start can be gone while its end is not. The
     * day still had blocking on it; how much of it is not something the app can honestly claim.
     */
    @Test
    fun `an end whose start was pruned counts the day but no time`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.note("session.ended", "s1", now - 2 * 60 * 60)

        val today = runtime.stats(now = now).days.single { it.day == "2026-09-04" }
        assertEquals(1, today.sessions)
        assertEquals(0, today.blockedSeconds)
    }

    @Test
    fun `two profiles blocking the same hour is one hour and two sessions`() = runTest {
        val runtime = TestRuntime.create(now)
        val start = now - 4 * 60 * 60
        runtime.note("session.started", "s1", start)
        runtime.note("session.started", "s2", start + 1800)
        runtime.note("session.ended", "s1", start + 3600)
        runtime.note("session.ended", "s2", start + 5400)

        val today = runtime.stats(now = now).days.single { it.day == "2026-09-04" }
        assertEquals(5400, today.blockedSeconds)
        assertEquals(2, today.sessions)
    }

    @Test
    fun `a streak counts back over yesterday when today has not started`() = runTest {
        val runtime = TestRuntime.create(now)
        for (offset in 1..3) {
            val at = now - offset * day
            runtime.note("session.started", "s$offset", at)
            runtime.note("session.ended", "s$offset", at + 3600)
        }

        val stats = runtime.stats(now = now)
        assertEquals(0, stats.days.single { it.day == "2026-09-04" }.sessions)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
    }

    @Test
    fun `a missed day breaks the streak but not the record`() = runTest {
        val runtime = TestRuntime.create(now)
        for (offset in listOf(1L, 2L, 3L, 5L)) {
            val at = now - offset * day
            runtime.note("session.started", "s$offset", at)
            runtime.note("session.ended", "s$offset", at + 3600)
        }

        val stats = runtime.stats(now = now)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
        assertEquals(4, stats.totalSessions)
    }

    @Test
    fun `history older than the window is left out`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.note("session.started", "s1", now - 20 * day)
        runtime.note("session.ended", "s1", now - 20 * day + 3600)

        val stats = runtime.stats(now = now)
        assertEquals(0, stats.totalSessions)
        assertEquals(0L, stats.totalBlockedSeconds)
    }

    @Test
    fun `the export writes the same days that are on screen`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.note("session.started", "s1", now - 3 * 60 * 60)
        runtime.note("session.ended", "s1", now - 2 * 60 * 60)

        val csv = runtime.statsCsv(now = now).trim().lines()
        assertEquals("day,blocked_seconds,sessions", csv.first())
        assertEquals(15, csv.size)
        assertEquals("2026-09-04,3600,1", csv.last())
    }

    /** Reading the figures is a read: nothing about the trail they came from may change. */
    @Test
    fun `asking for statistics does not write to the audit trail`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.note("session.started", "s1", now - 3600)
        val before = runtime.db.audit().recent(50)

        runtime.stats(now = now)

        assertEquals(before, runtime.db.audit().recent(50))
    }
}
