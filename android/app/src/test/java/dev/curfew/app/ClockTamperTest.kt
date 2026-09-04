package dev.curfew.app

import dev.curfew.policy.Lock
import dev.curfew.policy.LockSet
import dev.curfew.policy.Refused
import dev.curfew.policy.Session
import dev.curfew.policy.SessionSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Changing the system clock is the cheapest bypass there is: no root, no admin, no uninstall, and
 * on most devices it is three taps. What is proved here is that it buys nothing — a lock is judged
 * against time the monotonic clock vouches for, and time it cannot vouch for is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClockTamperTest {

    private val now = TestRuntime.FRIDAY_0930

    /**
     * The shared config's `mornings` schedule covers this profile at this hour, and reconcile is
     * entitled to strengthen a running session back up to the window's end. That is correct, and it
     * is not what is under test here, so these tests run without any schedule at all: what is left
     * is the timer, and the only thing that can move it is time.
     */
    private val unscheduled = TestRuntime.CONFIG.substringBefore("[[weekly]]")

    private suspend fun lockedRuntime(clock: MovableClock, endsAt: Long) =
        TestRuntime.create(now, configToml = unscheduled, clock = clock).also { runtime ->
            runtime.startSession(
                Session(
                    id = "s1",
                    profile = "deep-work",
                    source = SessionSource.Manual,
                    startedAt = now,
                    lock = LockSet(conditions = listOf(Lock.Timer), endsAt = endsAt),
                ),
            )
            runtime.trustedNow()
        }

    @Test
    fun `winding the clock forward does not end a timed lock`() = runTest {
        val clock = MovableClock(now)
        val runtime = lockedRuntime(clock, endsAt = now + 2 * 3600)

        // A minute really passes; the user claims four hours.
        clock.advance(60)
        clock.wind(4 * 3600)
        val trusted = runtime.trustedNow()

        assertEquals(now + 60, trusted)
        runtime.reconcile(trusted)
        assertEquals(1, runtime.policy.sessions().running.size)
    }

    @Test
    fun `winding the clock forward cannot make the session endable`() = runTest {
        val clock = MovableClock(now)
        val runtime = lockedRuntime(clock, endsAt = now + 2 * 3600)

        clock.advance(60)
        clock.wind(10 * 3600)

        try {
            runtime.endSession("s1", now = runtime.trustedNow())
            fail("a timed lock ended because the clock was moved")
        } catch (refused: Refused) {
            assertNotNull(refused.refusal)
        }
    }

    @Test
    fun `time that really passes does end the lock`() = runTest {
        val clock = MovableClock(now)
        val runtime = lockedRuntime(clock, endsAt = now + 2 * 3600)

        clock.advance(2 * 3600 + 1)
        val trusted = runtime.trustedNow()

        assertEquals(now + 2 * 3600 + 1, trusted)
        runtime.reconcile(trusted)
        assertTrue(runtime.policy.sessions().running.isEmpty())
    }

    @Test
    fun `a refused clock change is reported and written to the audit trail`() = runTest {
        val clock = MovableClock(now)
        val runtime = lockedRuntime(clock, endsAt = now + 2 * 3600)
        assertNull(runtime.clockTamper.value)

        clock.advance(60)
        clock.wind(3 * 3600)
        val trusted = runtime.trustedNow()

        val tamper = runtime.clockTamper.value
        assertNotNull(tamper)
        assertTrue(tamper!!.forward)
        // The whole three hours are refused, not three hours minus the minute: the minute that
        // really passed was credited by the monotonic clock, and the wind is on top of it.
        assertEquals(3 * 3600, tamper.seconds)
        assertTrue(runtime.db.audit().recent(100).any { it.kind == "enforcement.clock" })

        // And it is the user who clears the notice, not the next tick.
        runtime.trustedNow()
        assertNotNull(runtime.clockTamper.value)
        runtime.acknowledgeClockTamper()
        assertNull(runtime.clockTamper.value)
        assertEquals(trusted, runtime.trustedNow())
    }

    @Test
    fun `an honest reboot is not called tampering and its downtime counts`() = runTest {
        val clock = MovableClock(now)
        val runtime = lockedRuntime(clock, endsAt = now + 2 * 3600)

        clock.reboot(downtime = 8 * 3600)
        val trusted = runtime.trustedNow()

        assertEquals(now + 8 * 3600, trusted)
        assertNull("a restart must not be reported as a clock change", runtime.clockTamper.value)
        runtime.reconcile(trusted)
        assertTrue("the lock outlived its own end time", runtime.policy.sessions().running.isEmpty())
    }

    @Test
    fun `the baseline survives a restart, so restarting does not reset what was refused`() = runTest {
        val clock = MovableClock(now)
        val runtime = lockedRuntime(clock, endsAt = now + 2 * 3600)
        clock.advance(60)
        runtime.trustedNow()

        // A new runtime over the same database is what a restart looks like from here. The wall
        // clock is wound forward first, which is what an attacker would do while the app is dead.
        clock.wind(6 * 3600)
        val restarted = CurfewRuntimeFactory.reopen(runtime, clock.now(), clock)
        restarted.restore(clock.now())

        assertEquals(now + 60, restarted.trustedNow())
        assertFalse(restarted.policy.sessions().running.isEmpty())
    }

    @Test
    fun `the clock going backwards is refused as well`() = runTest {
        val clock = MovableClock(now)
        val runtime = lockedRuntime(clock, endsAt = now + 2 * 3600)

        clock.advance(60)
        clock.wind(-5 * 3600)

        assertEquals(now + 60, runtime.trustedNow())
        assertFalse(runtime.clockTamper.value!!.forward)
    }

    @Test
    fun `ordinary clock correction is not treated as tampering`() = runTest {
        val clock = MovableClock(now)
        val runtime = lockedRuntime(clock, endsAt = now + 2 * 3600)

        // Five seconds of NTP drift over five minutes: normal life, not an attack.
        clock.advance(300)
        clock.wind(5)

        assertEquals(now + 300, runtime.trustedNow())
        assertNull(runtime.clockTamper.value)
    }
}
