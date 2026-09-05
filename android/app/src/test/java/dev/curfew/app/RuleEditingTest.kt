package dev.curfew.app

import dev.curfew.policy.Action
import dev.curfew.policy.Platform
import dev.curfew.policy.Rule
import dev.curfew.policy.Target
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Blocking something that is not an app, from the phone.
 *
 * The app picker only ever writes "block this app". These prove the other half — a site, an
 * address, a word, a window title — reaches the file, replaces rather than duplicates, comes back
 * out in a shape the screen can list, and that a refused edit leaves the file alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RuleEditingTest {

    private val now = TestRuntime.FRIDAY_0930

    @Test
    fun `a site blocked from the phone is written to the config file`() = runTest {
        val runtime = TestRuntime.create(now)
        val target = Target.Domain(domain = "example.invalid")

        assertTrue(runtime.saveRule("deep-work", Rule(target = target)).isSuccess)

        assertTrue(runtime.config.read().contains("example.invalid"))
        assertEquals(
            listOf(target),
            runtime.rulesBeyondApps("deep-work")
                .map { it.target }
                .filter { it == target },
        )
    }

    /**
     * Two rules on one target would mean two lines in the list and two answers to one question, so
     * saying it twice has to leave one.
     */
    @Test
    fun `blocking the same site twice leaves one rule`() = runTest {
        val runtime = TestRuntime.create(now)
        val rule = Rule(target = Target.Domain(domain = "example.invalid"))
        runtime.saveRule("deep-work", rule).getOrThrow()
        runtime.saveRule("deep-work", rule).getOrThrow()

        val matching = runtime.rulesBeyondApps("deep-work").filter { it.target == rule.target }
        assertEquals(1, matching.size)
    }

    @Test
    fun `a word and a window title come back in the shape the screen lists`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveRule("deep-work", Rule(target = Target.Keyword(text = "gambling"))).getOrThrow()
        runtime.saveRule(
            "deep-work",
            Rule(
                target = Target.WindowTitle(pattern = "* - YouTube*"),
                platforms = listOf(Platform.WINDOWS),
            ),
        ).getOrThrow()

        val rules = runtime.rulesBeyondApps("deep-work")
        val word = rules.single { it.target is Target.Keyword }
        assertEquals(Action.Block, word.action)
        assertTrue(word.platforms.isEmpty())

        val title = rules.single { it.target is Target.WindowTitle }
        assertEquals(listOf(Platform.WINDOWS), title.platforms)
    }

    /**
     * The list under the picker leaves apps out because the picker above already shows them; a row
     * repeating one would read as a second, separate block on the same app.
     */
    @Test
    fun `app blocks stay out of the sites and words list`() = runTest {
        val runtime = TestRuntime.create(now)
        runtime.saveRule(
            "deep-work",
            Rule(target = Target.AppPackage(`package` = "com.example.app")),
        ).getOrThrow()

        assertTrue(runtime.rulesBeyondApps("deep-work").none { it.target is Target.AppPackage })
        // But it is in the config, so the picker sees it.
        assertTrue(runtime.config.read().contains("com.example.app"))
    }

    @Test
    fun `unblocking removes the rule from the file`() = runTest {
        val runtime = TestRuntime.create(now)
        val target = Target.Url(pattern = "*://*/watch*")
        runtime.saveRule("deep-work", Rule(target = target)).getOrThrow()
        assertTrue(runtime.config.read().contains("*://*/watch*"))

        assertTrue(runtime.deleteRule("deep-work", target).isSuccess)
        assertFalse(runtime.config.read().contains("*://*/watch*"))
    }

    @Test
    fun `blocking into a profile that does not exist changes nothing`() = runTest {
        val runtime = TestRuntime.create(now)
        val before = runtime.config.read()

        val result = runtime.saveRule(
            "no-such-profile",
            Rule(target = Target.Domain(domain = "example.invalid")),
        )

        assertTrue(result.isFailure)
        assertEquals(before, runtime.config.read())
    }

    /**
     * Invariant 2: a lock is a promise. Unblocking is a settings edit, and a settings edit never
     * ends a session — the session keeps the rules it started with until its own lock lets it go.
     */
    @Test
    fun `unblocking does not end a running session`() = runTest {
        val runtime = TestRuntime.create(now)
        val target = Target.Domain(domain = "example.invalid")
        runtime.saveRule("deep-work", Rule(target = target)).getOrThrow()
        val running = runtime.policy.sessions().running.map { it.id }

        runtime.deleteRule("deep-work", target).getOrThrow()

        assertEquals(running, runtime.policy.sessions().running.map { it.id })
    }
}
