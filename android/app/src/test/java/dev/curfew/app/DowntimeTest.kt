package dev.curfew.app

import dev.curfew.app.data.CurfewRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Curfew says about the time it was not running.
 *
 * No app can stop an OEM battery manager from killing a foreground service, so the honest thing is
 * to notice and say so. These tests pin the two halves of that promise: a real gap is always
 * reported, and an ordinary restart never is — a banner that cries wolf is one the user learns to
 * swipe away without reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DowntimeTest {

    private val now = TestRuntime.FRIDAY_0930

    @Test
    fun `a first run has nothing to report`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.restore(now)
        assertNull(runtime.downtime.value)
    }

    @Test
    fun `a short restart is not downtime`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.heartbeat(now)

        val restarted = CurfewRuntimeFactory.reopen(runtime, now + 60)
        restarted.restore(now + 60)
        assertNull(restarted.downtime.value)
    }

    @Test
    fun `a long silence is reported with both ends of the gap`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.heartbeat(now)

        val gap = 3 * 60 * 60L
        val restarted = CurfewRuntimeFactory.reopen(runtime, now + gap)
        restarted.restore(now + gap)

        val downtime = restarted.downtime.value!!
        assertEquals(now, downtime.from)
        assertEquals(now + gap, downtime.to)
        assertEquals(gap, downtime.seconds)
        assertTrue(!downtime.backwards)
    }

    @Test
    fun `a clock wound backwards is reported as such`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.heartbeat(now)

        val restarted = CurfewRuntimeFactory.reopen(runtime, now - 7200)
        restarted.restore(now - 7200)

        val downtime = restarted.downtime.value!!
        assertTrue(downtime.backwards)
        assertEquals(7200L, downtime.seconds)
    }

    @Test
    fun `a second of ntp drift is not a wound-back clock`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.heartbeat(now)

        val restarted = CurfewRuntimeFactory.reopen(runtime, now - 5)
        restarted.restore(now - 5)
        assertNull(restarted.downtime.value)
    }

    @Test
    fun `the gap is written to the audit log, not only to the screen`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.heartbeat(now)

        val gap = CurfewRuntime.DOWNTIME_SECONDS + 60
        val restarted = CurfewRuntimeFactory.reopen(runtime, now + gap)
        restarted.restore(now + gap)

        val kinds = restarted.db.audit().recent(50).map { it.kind }
        assertTrue("expected enforcement.gap in $kinds", kinds.contains("enforcement.gap"))
    }

    @Test
    fun `only the user clears the banner, and the next restart is then clean`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.heartbeat(now)

        val gap = 3 * 60 * 60L
        val restarted = CurfewRuntimeFactory.reopen(runtime, now + gap)
        restarted.restore(now + gap)
        assertTrue(restarted.downtime.value != null)

        restarted.acknowledgeDowntime()
        assertNull(restarted.downtime.value)

        // The acknowledgement also refreshes the heartbeat, so restoring again does not re-report
        // the gap the user has already been told about.
        val again = CurfewRuntimeFactory.reopen(restarted, now + gap + 60)
        again.restore(now + gap + 60)
        assertNull(again.downtime.value)
    }

    @Test
    fun `a session that outlives the gap is still running afterwards`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.reconcile(now)
        assertTrue(runtime.policy.sessions().running.isNotEmpty())
        runtime.heartbeat(now)

        // Killed for an hour, still inside the `mornings` window when it comes back.
        val restarted = CurfewRuntimeFactory.reopen(runtime, now + 3600)
        restarted.restore(now + 3600)

        assertTrue(restarted.downtime.value != null)
        assertEquals(1, restarted.policy.sessions().running.size)
    }
}
