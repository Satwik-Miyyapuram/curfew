package dev.curfew.app

import dev.curfew.app.enforce.Enforcer
import dev.curfew.policy.LockSet
import dev.curfew.policy.Observation
import dev.curfew.policy.Rule
import dev.curfew.policy.Session
import dev.curfew.policy.SessionSource
import dev.curfew.policy.Target
import dev.curfew.policy.Url
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Apps and sites belong to a profile, not to the app.
 *
 * Gap 5 of `docs/UX-FLOWS.md`. The Apps screen says which profile it is showing for a reason: two
 * profiles that block the same list are two names for one thing, and the whole point of "Study" and
 * "Evenings" being different is that they refuse different things. What had never been checked is
 * the part a user notices — that a site blocked under one profile is reachable while the other is
 * the one running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerProfileRulesTest {

    private val now = TestRuntime.FRIDAY_0930
    private val reddit = Target.Domain(domain = "reddit.com")
    private val news = Target.Domain(domain = "news.invalid")

    private suspend fun twoProfiles(runtime: dev.curfew.app.data.CurfewRuntime) {
        runtime.saveProfile("evenings", "Evenings").getOrThrow()
        runtime.saveRule("evenings", Rule(target = news)).getOrThrow()
    }

    private suspend fun run(
        runtime: dev.curfew.app.data.CurfewRuntime,
        profile: String,
    ): EnforcerTest.RecordingActions {
        runtime.startSession(
            Session(
                id = "s-$profile",
                profile = profile,
                source = SessionSource.Manual,
                startedAt = now,
                lock = LockSet(endsAt = now + 3600),
            ),
        )
        return EnforcerTest.RecordingActions()
    }

    @Test
    fun `a site added to one profile is not added to the other`() = runTest {
        val runtime = TestRuntime.create(now)
        twoProfiles(runtime)

        assertTrue(runtime.rulesBeyondApps("evenings").any { it.target == news })
        assertTrue(
            "a rule leaked into the profile it was not saved under",
            runtime.rulesBeyondApps("deep-work").none { it.target == news },
        )
    }

    @Test
    fun `the running profile is the one that decides`() = runTest {
        val runtime = TestRuntime.create(now)
        twoProfiles(runtime)

        val actions = run(runtime, "evenings")
        val enforcer = Enforcer(runtime, actions)
        enforcer.onObservation(Observation.Web(Url.parse("https://news.invalid/front")), now)
        // reddit.com is deep-work's rule, and deep-work is not what is running.
        enforcer.onObservation(Observation.Web(Url.parse("https://reddit.com/r/all")), now)

        assertEquals(listOf("web:news.invalid/front"), actions.blocked.map { it.first })
        assertTrue("the other profile's list was enforced too", actions.allowed.isNotEmpty())
    }

    @Test
    fun `the same address blocked under both profiles is blocked under either`() = runTest {
        val runtime = TestRuntime.create(now)
        twoProfiles(runtime)
        runtime.saveRule("evenings", Rule(target = reddit)).getOrThrow()

        val actions = run(runtime, "evenings")
        Enforcer(runtime, actions)
            .onObservation(Observation.Web(Url.parse("https://reddit.com/r/all")), now)
        assertEquals(listOf("web:reddit.com/r/all"), actions.blocked.map { it.first })
    }

    @Test
    fun `removing a site from one profile leaves the other alone`() = runTest {
        val runtime = TestRuntime.create(now)
        twoProfiles(runtime)
        runtime.saveRule("deep-work", Rule(target = news)).getOrThrow()

        runtime.deleteRule("evenings", news).getOrThrow()

        assertTrue(runtime.rulesBeyondApps("evenings").none { it.target == news })
        assertTrue(
            "removing from one profile removed it from both",
            runtime.rulesBeyondApps("deep-work").any { it.target == news },
        )
    }
}
