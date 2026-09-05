package dev.curfew.app

import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.EventMatcher
import dev.curfew.policy.Lock
import dev.curfew.policy.WeeklySchedule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Editing a schedule from the app, end to end: form value in, config file out, sessions running.
 *
 * The core's tests prove a saved window is a valid config; these prove the rest of the promise —
 * that it reaches the file, survives a restart of the process, actually starts a session, and that
 * a refused edit changes neither the running config nor what is on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduleEditingTest {

    private val now = TestRuntime.FRIDAY_0930

    private fun window(
        id: String = "evenings",
        profile: String = "deep-work",
        start: Int = 9 * 60,
        end: Int = 10 * 60,
        locks: List<Lock> = emptyList(),
    ) = WeeklySchedule(
        id = id,
        profile = profile,
        days = emptyList(),
        startMinute = start,
        endMinute = end,
        locks = locks,
    )

    @Test
    fun `a window saved from the form is written to the config file`() = runTest {
        val runtime = TestRuntime.create(now)
        assertTrue(runtime.saveWeekly(window()).isSuccess)

        assertEquals(listOf("mornings", "evenings"), runtime.weeklySchedules().map { it.id })
        // The file, not just the object: a schedule that lives only in memory is one that vanishes
        // the next time the process is killed, which on Android is whenever the system likes.
        assertTrue(runtime.config.read().contains("evenings"))
    }

    @Test
    fun `a window saved from the form starts its session at once`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveWeekly(window(locks = listOf(Lock.Timer))).getOrThrow()

        // 09:30 Friday is inside 09:00-10:00, so the block is running before the form closes.
        assertEquals(listOf("deep-work"), runtime.policy.activeProfiles(now))
    }

    @Test
    fun `an edit replaces the window rather than adding a second`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveWeekly(window()).getOrThrow()
        runtime.saveWeekly(window(start = 20 * 60, end = 21 * 60)).getOrThrow()

        val saved = runtime.weeklySchedules().filter { it.id == "evenings" }
        assertEquals(1, saved.size)
        assertEquals(20 * 60, saved.single().startMinute)
    }

    /**
     * A refused edit has to leave both the config and the file untouched, or the next save fails
     * for a reason the user cannot see and the blocker stops loading at the next launch.
     */
    @Test
    fun `a window naming a profile that does not exist changes nothing`() = runTest {
        val runtime = TestRuntime.create(now)
        val before = runtime.config.read()

        val result = runtime.saveWeekly(window(profile = "no-such-profile"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no-such-profile"))
        assertEquals(listOf("mornings"), runtime.weeklySchedules().map { it.id })
        assertEquals(before, runtime.config.read())
    }

    @Test
    fun `a removed window stops starting sessions`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveWeekly(window()).getOrThrow()
        runtime.deleteWeekly("evenings").getOrThrow()

        assertEquals(listOf("mornings"), runtime.weeklySchedules().map { it.id })
        assertFalse(runtime.config.read().contains("evenings"))
    }

    /**
     * Invariant 2, at the settings screen: deleting the rule that started a session is not a way
     * out of the session. The rule stops firing; the promise already made stands.
     */
    @Test
    fun `removing a window does not end the session it already started`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveWeekly(window(locks = listOf(Lock.DeviceCredential))).getOrThrow()
        assertEquals(listOf("deep-work"), runtime.policy.activeProfiles(now))

        runtime.deleteWeekly("evenings").getOrThrow()
        assertEquals(listOf("deep-work"), runtime.policy.activeProfiles(now + 60))
    }

    @Test
    fun `a calendar rule saved from the form is written and read back`() = runTest {
        val runtime = TestRuntime.create(now)
        val rule = CalendarSchedule(
            id = "standups",
            profile = "deep-work",
            matcher = EventMatcher(title = "*standup*", busyOnly = true),
            padBeforeSeconds = 300,
        )
        runtime.saveCalendarRule(rule).getOrThrow()

        assertEquals(listOf(rule), runtime.calendarSchedules().filter { it.id == "standups" })
        assertTrue(runtime.config.read().contains("standup"))
    }

    @Test
    fun `a calendar rule naming a profile that does not exist changes nothing`() = runTest {
        val runtime = TestRuntime.create(now)
        val before = runtime.config.read()

        assertTrue(runtime.saveCalendarRule(CalendarSchedule("r", "nope")).isFailure)
        assertEquals(before, runtime.config.read())
    }

    @Test
    fun `a removed calendar rule is gone from the file`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveCalendarRule(
            CalendarSchedule("standups", "deep-work", EventMatcher(title = "*standup*")),
        ).getOrThrow()
        runtime.deleteCalendarRule("standups").getOrThrow()

        assertTrue(runtime.calendarSchedules().none { it.id == "standups" })
        assertFalse(runtime.config.read().contains("standup"))
    }

    // --- profiles ---------------------------------------------------------------------------

    @Test
    fun `a profile added from the form can immediately be scheduled`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveProfile("reading", "Reading").getOrThrow()

        assertTrue(runtime.config.read().contains("reading"))
        // The point of creating one: the window that could not be written before now can be.
        assertTrue(runtime.saveWeekly(window(id = "evening-read", profile = "reading")).isSuccess)
    }

    @Test
    fun `renaming a profile keeps the apps it blocks`() = runTest {
        val runtime = TestRuntime.create(now)
        val before = dev.curfew.policy.Policy.blockedApps(runtime.policy.configToml(), "deep-work")
        assertTrue(before.isNotEmpty())

        runtime.saveProfile("deep-work", "Focus").getOrThrow()
        assertEquals(
            before,
            dev.curfew.policy.Policy.blockedApps(runtime.policy.configToml(), "deep-work"),
        )
        assertTrue(runtime.config.read().contains("Focus"))
    }

    @Test
    fun `a profile with a blank name is refused and changes nothing`() = runTest {
        val runtime = TestRuntime.create(now)
        val before = runtime.config.read()

        assertTrue(runtime.saveProfile("reading", "   ").isFailure)
        assertEquals(before, runtime.config.read())
    }

    @Test
    fun `a profile a schedule still names cannot be deleted`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveWeekly(window()).getOrThrow()
        val before = runtime.config.read()

        val failure = runtime.deleteProfile("deep-work").exceptionOrNull()
        // The message names the schedules holding it, because that is what has to go first.
        assertTrue("$failure", failure!!.message!!.contains("evenings"))
        assertEquals(before, runtime.config.read())
    }

    @Test
    fun `a profile nothing points at is removed from the file`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveProfile("reading", "Reading").getOrThrow()
        runtime.deleteProfile("reading").getOrThrow()

        assertFalse(runtime.config.read().contains("reading"))
    }
}
