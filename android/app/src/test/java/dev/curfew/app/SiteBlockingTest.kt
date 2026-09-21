package dev.curfew.app

import dev.curfew.app.data.CurfewRuntime
import dev.curfew.app.enforce.EnforcementMode
import dev.curfew.app.enforce.Enforcer
import dev.curfew.policy.LockSet
import dev.curfew.policy.Observation
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
 * A blocked domain actually blocks, on the platform whose only way to enforce one is to read the URL.
 *
 * **The gap this fills.** Every site-blocking test there was checked a piece of the chain: that the
 * URL bar is found, that `cleanUrl` accepts a host, that `needsUrlReading` asks for content events,
 * that a domain rule matches its subdomains in the Rust core. None of them checked that a *domain rule*
 * blocks a *web observation* through the Android stack while a session is running — which is the thing
 * the user reported twice, and which was broken twice for different reasons (`flagReportViewIds`
 * missing, then the subscription narrowed off because the decision forgot that domains need the
 * address bar on Android).
 *
 * A domain rule is the interesting case because Windows never needs the URL reader for one: the
 * resolver and the hosts file enforce it. Android has neither, so the address bar is the whole
 * mechanism, and a rule that works on one platform and silently does nothing on the other is exactly
 * the class of bug this is here to catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SiteBlockingTest {

    private val now = TestRuntime.FRIDAY_0930
    private val reddit = Target.Domain(domain = "reddit.com")

    private fun config(rule: Target) = """
        schema_version = 1
        timezone = "Europe/London"

        [[profiles]]
        id = "study"
        name = "Study"

        [[profiles.rules]]
        target = { kind = "domain", domain = "${(rule as Target.Domain).domain}" }
        action = { kind = "block" }
    """.trimIndent()

    /** A running session, because nothing is enforced without one — which was half the report. */
    private suspend fun running(configToml: String): CurfewRuntime {
        val runtime = TestRuntime.create(
            configToml = configToml,
            mode = EnforcementMode.APP_AND_URL,
        )
        runtime.startSession(
            Session(
                id = "s1",
                profile = "study",
                source = SessionSource.Manual,
                startedAt = now,
                lock = LockSet(endsAt = now + 3600),
            ),
        )
        return runtime
    }

    @Test
    fun `a blocked domain blocks the page it is on`() = runTest {
        val runtime = running(config(reddit))
        val actions = EnforcerTest.RecordingActions()

        Enforcer(runtime, actions)
            .onObservation(Observation.Web(Url.parse("https://www.reddit.com/r/all")), now)

        assertEquals(
            "a blocked domain did not block; on Android the address bar is the only mechanism there is",
            listOf("web:www.reddit.com/r/all"),
            actions.blocked.map { it.first },
        )
    }

    /** A subdomain is covered, which is the whole reason a domain rule is not a hosts entry. */
    @Test
    fun `a subdomain of a blocked domain blocks too`() = runTest {
        val runtime = running(config(reddit))
        val actions = EnforcerTest.RecordingActions()

        Enforcer(runtime, actions)
            .onObservation(Observation.Web(Url.parse("https://old.reddit.com/")), now)

        assertTrue("a subdomain was not covered", actions.blocked.isNotEmpty())
    }

    /** And a host that merely ends in the same letters is not, which is the other half of the rule. */
    @Test
    fun `a lookalike domain is left alone`() = runTest {
        val runtime = running(config(reddit))
        val actions = EnforcerTest.RecordingActions()

        Enforcer(runtime, actions)
            .onObservation(Observation.Web(Url.parse("https://notreddit.com/")), now)

        assertTrue("a lookalike was blocked", actions.blocked.isEmpty())
    }

    /**
     * **And nothing is blocked with no session running**, which is what the device showed: the
     * notification said "Curfew is standing by" and no amount of navigating to a blocked domain did
     * anything, correctly. Worth a test because it is indistinguishable from a broken feature from the
     * outside, and the user reasonably read it as one.
     */
    @Test
    fun `nothing blocks without a running session`() = runTest {
        val runtime = TestRuntime.create(
            configToml = config(reddit),
            mode = EnforcementMode.APP_AND_URL,
        )
        val actions = EnforcerTest.RecordingActions()

        Enforcer(runtime, actions)
            .onObservation(Observation.Web(Url.parse("https://www.reddit.com/r/all")), now)

        assertTrue("something blocked with no session running", actions.blocked.isEmpty())
        assertEquals(listOf("web:www.reddit.com/r/all"), actions.allowed)
    }
}
