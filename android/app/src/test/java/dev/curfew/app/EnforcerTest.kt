package dev.curfew.app

import dev.curfew.app.data.CurfewRuntime
import dev.curfew.app.enforce.Enforcer
import dev.curfew.policy.BlockReason
import dev.curfew.policy.LockSet
import dev.curfew.policy.Observation
import dev.curfew.policy.Session
import dev.curfew.policy.SessionSource
import dev.curfew.policy.Url
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The time accounting, which is the part of enforcement most likely to be quietly wrong.
 *
 * A budget that under-counts is a blocker that does not block; a budget that over-counts takes time
 * the user was owed. Neither failure is visible from the outside, so each way of getting it wrong
 * has a named test here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EnforcerTest {

    private lateinit var runtime: CurfewRuntime
    private lateinit var actions: RecordingActions
    private lateinit var enforcer: Enforcer

    private val now = TestRuntime.FRIDAY_0930

    class RecordingActions : Enforcer.Actions {
        val blocked = mutableListOf<Pair<String, BlockReason>>()
        val delayed = mutableListOf<Pair<String, Int>>()
        val allowed = mutableListOf<String>()
        val muted = mutableListOf<String>()

        override fun block(target: String, reason: BlockReason) {
            blocked += target to reason
        }

        override fun delay(target: String, seconds: Int) {
            delayed += target to seconds
        }

        override fun allow(target: String) {
            allowed += target
        }

        override fun muteNotification(target: String) {
            muted += target
        }
    }

    @Before
    fun setUp() = runTest {
        runtime = TestRuntime.create(now)
        actions = RecordingActions()
        enforcer = Enforcer(runtime, actions)
        runtime.startSession(
            Session(
                id = "s1",
                profile = "deep-work",
                source = SessionSource.Manual,
                startedAt = now,
                lock = LockSet(endsAt = now + 3600),
            ),
        )
    }

    private fun web(url: String) = Observation.Web(Url.parse(url))

    @Test
    fun `a blocked app is replaced and told why`() = runTest {
        enforcer.onObservation(Observation.App("com.instagram.android"), now)

        assertEquals(1, actions.blocked.size)
        assertEquals("app:com.instagram.android", actions.blocked[0].first)
        assertEquals(BlockReason.Blocked("deep-work"), actions.blocked[0].second)
    }

    @Test
    fun `a delayed app gets the pause the rule asked for`() = runTest {
        enforcer.onObservation(Observation.App("com.slack"), now)

        assertEquals(listOf("app:com.slack" to 15), actions.delayed)
    }

    @Test
    fun `an app with no rule is left alone`() = runTest {
        enforcer.onObservation(Observation.App("org.gnu.emacs"), now)

        assertEquals(listOf("app:org.gnu.emacs"), actions.allowed)
    }

    @Test
    fun `a muted notification source is muted, not blocked`() = runTest {
        enforcer.onObservation(Observation.Notification("com.whatsapp", "Hi"), now)

        assertEquals(listOf("com.whatsapp"), actions.muted.map { it })
        assertTrue(actions.blocked.isEmpty())
    }

    // --- what gets charged, and to which key -----------------------------------------------------

    @Test
    fun `time is charged when the target changes, not while it is still open`() = runTest {
        enforcer.onObservation(web("https://reddit.com/r/all"), now)
        // Still the same page five minutes later: nothing is written until it is left.
        enforcer.onObservation(web("https://reddit.com/r/all"), now + 300)
        assertEquals(emptyMap<String, Int>(), spent())

        enforcer.onObservation(Observation.App("org.gnu.emacs"), now + 300)
        assertEquals(mapOf("domain:reddit.com" to 300), spent())
    }

    @Test
    fun `a subdomain is charged against the rule that covers it`() = runTest {
        enforcer.onObservation(web("https://old.reddit.com/r/all"), now)
        enforcer.onIdle(now + 120)

        assertEquals(mapOf("domain:reddit.com" to 120), spent())
    }

    @Test
    fun `going idle stops the clock`() = runTest {
        enforcer.onObservation(web("https://reddit.com/"), now)
        enforcer.onIdle(now + 60)
        // Half an hour in a pocket must not be charged to anything. The gap stays inside the
        // session: once the session ends nothing is charged at all, which is a different test.
        enforcer.onObservation(web("https://reddit.com/"), now + 1800)
        enforcer.onIdle(now + 1860)

        assertEquals(mapOf("domain:reddit.com" to 120), spent())
    }

    @Test
    fun `a blocked app accrues nothing against its own budget`() = runTest {
        enforcer.onObservation(Observation.App("com.instagram.android"), now)
        enforcer.onObservation(Observation.App("org.gnu.emacs"), now + 600)

        assertEquals(emptyMap<String, Int>(), spent())
    }

    @Test
    fun `a clock that moves backwards does not hand out free budget`() = runTest {
        enforcer.onObservation(web("https://reddit.com/"), now)
        // A timezone correction, an NTP step: the slice is discarded rather than recorded negative.
        enforcer.onIdle(now - 500)

        assertEquals(emptyMap<String, Int>(), spent())
    }

    @Test
    fun `a launch is counted once per switch, not once per observation`() = runTest {
        repeat(3) { enforcer.onObservation(Observation.App("com.twitter.android"), now) }
        assertEquals(1, launches("app:com.twitter.android"))

        enforcer.onObservation(Observation.App("org.gnu.emacs"), now + 10)
        enforcer.onObservation(Observation.App("com.twitter.android"), now + 20)
        assertEquals(2, launches("app:com.twitter.android"))
    }

    @Test
    fun `a launch limit blocks once it is reached`() = runTest {
        // Three opens are allowed; the fourth is not, and the count comes from storage rather than
        // from anything the enforcer remembers.
        repeat(3) { index ->
            enforcer.onObservation(Observation.App("com.twitter.android"), now + index * 10)
            enforcer.onObservation(Observation.App("org.gnu.emacs"), now + index * 10 + 5)
        }
        actions.blocked.clear()
        enforcer.onObservation(Observation.App("com.twitter.android"), now + 100)

        assertEquals(
            BlockReason.LaunchLimitReached("deep-work", 3),
            actions.blocked.single().second,
        )
    }

    @Test
    fun `an exhausted budget blocks, and the reason says how long it was`() = runTest {
        enforcer.onObservation(web("https://reddit.com/"), now)
        enforcer.onIdle(now + 1200)
        enforcer.onObservation(web("https://reddit.com/"), now + 1300)

        assertEquals(
            BlockReason.BudgetExhausted("deep-work", 1200),
            actions.blocked.single().second,
        )
    }

    @Test
    fun `nothing is enforced once the session is over`() = runTest {
        runtime.endSession("s1", now = now + 10)
        actions.blocked.clear()

        enforcer.onObservation(Observation.App("com.instagram.android"), now + 20)

        assertTrue(actions.blocked.isEmpty())
        assertEquals(listOf("app:com.instagram.android"), actions.allowed)
    }

    private suspend fun spent(): Map<String, Int> =
        runtime.usage(now + 7200).usage
            .mapValues { (_, c) -> c.rollups.sumOf { it.seconds } }
            .filterValues { it > 0 }

    private suspend fun launches(key: String): Int =
        runtime.usage(now + 7200).launches[key]?.opens?.size ?: 0
}
