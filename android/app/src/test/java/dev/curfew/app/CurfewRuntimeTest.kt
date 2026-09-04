package dev.curfew.app

import dev.curfew.policy.Lock
import dev.curfew.policy.LockSet
import dev.curfew.policy.Refusal
import dev.curfew.policy.Refused
import dev.curfew.policy.Session
import dev.curfew.policy.SessionSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The promise a lock makes, on the Android side of the boundary.
 *
 * The core's own tests prove that a locked session refuses to end. What is proved here is that
 * nothing on this side can route around it: not a restart, not a settings edit, not a second
 * request for a release, not a calendar event vanishing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CurfewRuntimeTest {

    private val now = TestRuntime.FRIDAY_0930

    private fun session(locks: List<Lock>, endsAt: Long? = null) = Session(
        id = "s1",
        profile = "deep-work",
        source = SessionSource.Manual,
        startedAt = now,
        lock = LockSet(conditions = locks, endsAt = endsAt),
    )

    @Test
    fun `a locked session refuses to end and says what is missing`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.startSession(session(listOf(Lock.DeviceCredential)))

        try {
            runtime.endSession("s1", now = now + 60)
            fail("a locked session ended without the lock being satisfied")
        } catch (refused: Refused) {
            val locked = refused.refusal as Refusal.Locked
            assertEquals(listOf(Lock.DeviceCredential), locked.missing)
        }
    }

    @Test
    fun `the lock that the platform proved is what lets it end`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.startSession(session(listOf(Lock.DeviceCredential)))

        runtime.endSession("s1", satisfied = listOf(Lock.DeviceCredential), now = now + 60)

        assertTrue(runtime.policy.sessions().running.isEmpty())
    }

    @Test
    fun `a session survives a restart with its lock intact`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.startSession(session(listOf(Lock.DeviceCredential), endsAt = now + 3600))

        // A new runtime over the same database is what a reboot looks like from here.
        val restarted = CurfewRuntimeFactory.reopen(runtime, now)
        restarted.restore(now + 5)

        val restored = restarted.policy.sessions().running.single()
        assertEquals(listOf(Lock.DeviceCredential), restored.lock.conditions)
        assertEquals(now + 3600, restored.lock.endsAt)
    }

    @Test
    fun `replacing the config does not release a running session`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.startSession(session(listOf(Lock.Timer), endsAt = now + 3600))

        // The most obvious way out: delete the profile that is running.
        runtime.setConfig("schema_version = 1\ntimezone = \"Europe/London\"\n").getOrThrow()

        assertEquals(1, runtime.policy.sessions().running.size)
        try {
            runtime.endSession("s1", now = now + 60)
            fail("emptying the config ended a locked session")
        } catch (refused: Refused) {
            assertTrue(refused.refusal is Refusal.Locked)
        }
    }

    @Test
    fun `an invalid config is rejected and the working one is left in place`() = runTest {
        val runtime = TestRuntime.create(now)

        val result = runtime.setConfig("schema_version = ")

        assertTrue(result.isFailure)
        assertTrue(runtime.policy.configToml().contains("deep-work"))
    }

    @Test
    fun `a delayed release lands in the future and never moves closer`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.startSession(session(listOf(Lock.DeviceCredential)))

        val first = runtime.requestRelease("s1", now)
        assertTrue(first > now)

        // Asking again an hour later must not restart or shorten the wait.
        val second = runtime.requestRelease("s1", now + 3600)
        assertEquals(first, second)
    }

    @Test
    fun `the merged lock is what the UI is given`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.startSession(session(listOf(Lock.Timer), endsAt = now + 600))
        runtime.startSession(
            session(listOf(Lock.DeviceCredential), endsAt = now + 1800)
                .copy(id = "s2", profile = "deep-work"),
        )

        val merged = runtime.lock.value
        assertTrue(merged.conditions.contains(Lock.Timer))
        assertTrue(merged.conditions.contains(Lock.DeviceCredential))
        // The strictest end wins: the device is not free until the last session is.
        assertEquals(now + 1800, merged.endsAt)
    }

    @Test
    fun `every session change is written to the audit trail`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.startSession(session(emptyList()))
        runtime.endSession("s1", now = now + 60)

        val kinds = runtime.db.audit().recent(10).map { it.kind }
        assertTrue(kinds.contains("session.started"))
        assertTrue(kinds.contains("session.ended"))
    }

    @Test
    fun `pruning drops history no rule can reach and keeps the rest`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.recordUsage("domain:reddit.com", now - 26 * 3600, 100)
        runtime.recordUsage("domain:reddit.com", now - 3600, 200)

        runtime.prune(now)

        val kept = runtime.usage(now).usage["domain:reddit.com"]!!.rollups
        assertEquals(listOf(200), kept.map { it.seconds })
    }

    @Test
    fun `the weekly schedule starts a session and reconciling twice does not start two`() = runTest {
        val runtime = TestRuntime.create(now)

        val started = runtime.reconcile(now)
        assertEquals(1, started.size)
        assertNotNull(runtime.policy.sessions().running.singleOrNull())

        val again = runtime.reconcile(now + 60)
        assertTrue(again.isEmpty())
        assertEquals(1, runtime.policy.sessions().running.size)
    }

    @Test
    fun `a session started by a schedule ends when the window closes`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.reconcile(now)

        // 12:00 Europe/London is the end of the `mornings` window.
        runtime.reconcile(now + 3 * 3600)

        assertTrue(runtime.policy.sessions().running.isEmpty())
    }
}
