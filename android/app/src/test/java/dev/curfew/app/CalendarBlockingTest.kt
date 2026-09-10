package dev.curfew.app

import dev.curfew.app.enforce.Enforcer
import dev.curfew.policy.BlockReason
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.EventMatcher
import dev.curfew.policy.Lock
import dev.curfew.policy.Observation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A meeting in the calendar, all the way through to an app that will not open.
 *
 * Gap 4 of `docs/UX-FLOWS.md`: the calendar path had tests at both ends — the reader turns provider
 * rows into events, the core matches events to rules — and nothing joining them. This is the join:
 * the rule the Events screen writes, the event the provider would have handed over, the session
 * that starts because of it, and the block a person actually runs into. Everything except the
 * provider read itself, which is what a phone is for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarBlockingTest {

    private val now = TestRuntime.FRIDAY_0930

    /** The config without its weekly window, so nothing but the calendar can start a session. */
    private val calendarOnly = TestRuntime.CONFIG.substringBefore("[[weekly]]")

    private fun standup(
        start: Long = now - 300,
        end: Long = now + 1500,
        title: String = "Team standup",
        busy: Boolean = true,
    ) = CalendarEvent(id = "e1", title = title, start = start, end = end, busy = busy)

    private val rule = CalendarSchedule(
        id = "standups",
        profile = "deep-work",
        matcher = EventMatcher(title = "*standup*", busyOnly = true),
        locks = listOf(Lock.Timer),
    )

    @Test
    fun `a matching event starts the session the rule names`() = runTest {
        val runtime = TestRuntime.create(now, configToml = calendarOnly)
        runtime.saveCalendarRule(rule).getOrThrow()
        // Saving deliberately does not read the calendar — that happens on the poll — so nothing is
        // running until the events arrive.
        assertTrue(runtime.policy.activeProfiles(now).isEmpty())

        runtime.reconcile(now, listOf(standup()))
        assertEquals(listOf("deep-work"), runtime.policy.activeProfiles(now))
    }

    @Test
    fun `the block a meeting starts is the block the profile describes`() = runTest {
        val runtime = TestRuntime.create(now, configToml = calendarOnly)
        runtime.saveCalendarRule(rule).getOrThrow()
        runtime.reconcile(now, listOf(standup()))

        val actions = EnforcerTest.RecordingActions()
        Enforcer(runtime, actions).onObservation(Observation.App("com.instagram.android"), now)

        assertEquals(1, actions.blocked.size)
        val (target, reason) = actions.blocked.single()
        assertEquals("app:com.instagram.android", target)
        assertTrue("the block did not name the profile", reason is BlockReason.Blocked)
    }

    @Test
    fun `an event nobody's rule matches starts nothing`() = runTest {
        val runtime = TestRuntime.create(now, configToml = calendarOnly)
        runtime.saveCalendarRule(rule).getOrThrow()

        runtime.reconcile(now, listOf(standup(title = "Lunch")))
        assertTrue(runtime.policy.activeProfiles(now).isEmpty())
    }

    /** `busyOnly` is the difference between a meeting and a note someone left in the day. */
    @Test
    fun `an event marked free does not start a busy-only rule`() = runTest {
        val runtime = TestRuntime.create(now, configToml = calendarOnly)
        runtime.saveCalendarRule(rule).getOrThrow()

        runtime.reconcile(now, listOf(standup(busy = false)))
        assertTrue(runtime.policy.activeProfiles(now).isEmpty())
    }

    /** The padding is the point of the field: the block starts before the meeting does. */
    @Test
    fun `padding starts the block before the meeting`() = runTest {
        val runtime = TestRuntime.create(now, configToml = calendarOnly)
        runtime.saveCalendarRule(rule.copy(padBeforeSeconds = 600)).getOrThrow()
        val meeting = standup(start = now + 300, end = now + 1800)

        runtime.reconcile(now, listOf(meeting))
        assertEquals(listOf("deep-work"), runtime.policy.activeProfiles(now))
    }

    @Test
    fun `the session ends when the meeting does`() = runTest {
        val runtime = TestRuntime.create(now, configToml = calendarOnly)
        runtime.saveCalendarRule(rule).getOrThrow()
        val meeting = standup(end = now + 600)
        runtime.reconcile(now, listOf(meeting))
        assertEquals(listOf("deep-work"), runtime.policy.activeProfiles(now))

        // The event is still in the calendar; it is simply over.
        runtime.reconcile(now + 900, listOf(meeting))
        assertTrue(runtime.policy.activeProfiles(now + 900).isEmpty())
    }

    /**
     * Invariant 2 where it is easiest to break by accident: deleting the meeting is not a way out
     * of the block it started. The rule stops firing for future events; the promise stands.
     */
    @Test
    fun `deleting the meeting does not end the session it started`() = runTest {
        val runtime = TestRuntime.create(now, configToml = calendarOnly)
        runtime.saveCalendarRule(rule.copy(locks = listOf(Lock.DeviceCredential))).getOrThrow()
        runtime.reconcile(now, listOf(standup()))
        assertEquals(listOf("deep-work"), runtime.policy.activeProfiles(now))

        runtime.reconcile(now + 60, emptyList())
        assertEquals(listOf("deep-work"), runtime.policy.activeProfiles(now + 60))
    }

    @Test
    fun `a paused calendar rule ignores a meeting that matches it`() = runTest {
        val runtime = TestRuntime.create(now, configToml = calendarOnly)
        runtime.saveCalendarRule(rule.copy(enabled = false)).getOrThrow()

        runtime.reconcile(now, listOf(standup()))
        assertTrue(runtime.policy.activeProfiles(now).isEmpty())
        assertFalse(runtime.calendarSchedules().single { it.id == "standups" }.enabled)
    }
}
