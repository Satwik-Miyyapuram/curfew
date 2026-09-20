package dev.curfew.app

import dev.curfew.app.data.ConfigStore
import dev.curfew.app.enforce.EnforcementMode
import dev.curfew.app.enforce.SensitiveApps
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The line between the two detectors, enforced where it can actually be broken.
 *
 * `EnforcementMode` is a choice between a service that may read a window and one that may not, and
 * the second one cannot enforce a URL or keyword rule — not weakly, not less often, but at all. The
 * failure this guards against is the one this whole round of work was about: a rule the user wrote
 * that quietly does nothing, on a screen that says it is running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppOnlyConfigTest {

    private val urlRule = """
        schema_version = 1
        timezone = "Europe/London"

        [[profiles]]
        id = "deep-work"
        name = "Deep work"

        [[profiles.rules]]
        target = { kind = "url", pattern = "*://*/watch*" }
        action = { kind = "block" }
    """.trimIndent()

    private val appRule = """
        schema_version = 1
        timezone = "Europe/London"

        [[profiles]]
        id = "deep-work"
        name = "Deep work"

        [[profiles.rules]]
        target = { kind = "app_package", package = "com.instagram.android" }
        action = { kind = "block" }
    """.trimIndent()

    private fun store(mode: EnforcementMode, contents: String): ConfigStore {
        val file = File.createTempFile("curfew-mode", ".toml").apply { deleteOnExit() }
        file.writeText(contents)
        return ConfigStore(file = file, mode = { mode })
    }

    /** The rule is refused rather than loaded and ignored, and the reason is not a crash. */
    @Test
    fun `a url rule is not loaded in app-only mode`() {
        val read = store(EnforcementMode.APP_ONLY, urlRule).read()

        assertFalse("a window-less detector cannot enforce a url rule", read.contains("watch"))
        assertTrue("the config still has to be a valid one", read.contains("schema_version"))
        // And the file itself is untouched, because the edit is the user's and becomes enforceable
        // again the moment they switch mode.
        assertTrue(store(EnforcementMode.APP_ONLY, urlRule).readRaw().contains("watch"))
    }

    @Test
    fun `the same url rule loads in app-and-url mode`() {
        assertTrue(store(EnforcementMode.APP_AND_URL, urlRule).read().contains("watch"))
    }

    /**
     * The refusal is specific to the rule kinds that need a window. An app block is decided from the
     * window-state event's package name and works in both modes, so app-only mode must not become a
     * mode that blocks nothing.
     */
    @Test
    fun `an app rule loads in both modes`() {
        assertTrue(store(EnforcementMode.APP_ONLY, appRule).read().contains("com.instagram.android"))
        assertTrue(store(EnforcementMode.APP_AND_URL, appRule).read().contains("com.instagram.android"))
    }

    @Test
    fun `a keyword rule is refused in app-only mode too`() {
        val keyword = urlRule.replace(
            """target = { kind = "url", pattern = "*://*/watch*" }""",
            """target = { kind = "keyword", text = "shorts" }""",
        )
        assertFalse(store(EnforcementMode.APP_ONLY, keyword).read().contains("shorts"))
        assertTrue(store(EnforcementMode.APP_AND_URL, keyword).read().contains("shorts"))
    }

    /**
     * A domain rule is enforced by the resolver and the hosts file, not by reading a browser, so it
     * survives app-only mode — and it must, or the mode would silently stop blocking the sites the
     * user asked for through the layers that do not need a window at all.
     */
    @Test
    fun `a domain rule survives app-only mode`() {
        val domain = urlRule.replace(
            """target = { kind = "url", pattern = "*://*/watch*" }""",
            """target = { kind = "domain", domain = "reddit.com" }""",
        )
        assertTrue(store(EnforcementMode.APP_ONLY, domain).read().contains("reddit.com"))
    }

    // --- apps that may not be blocked, in either mode ------------------------------------------------

    private val bankRule = """
        schema_version = 1
        timezone = "Europe/London"

        [[profiles]]
        id = "deep-work"
        name = "Deep work"

        [[profiles.rules]]
        target = { kind = "app_package", package = "com.phonepe.app" }
        action = { kind = "block" }
    """.trimIndent()

    /**
     * **A payment or banking app is blockable, in either mode.**
     *
     * This is the regression guard for a rule that existed briefly and was removed: `ConfigStore` used
     * to refuse a config that blocked an app on the sensitive list. Which apps a person blocks is
     * theirs to decide — the reading restriction belongs to the accessibility service, not to the
     * config — and the reason it looked justified was wrong in a way worth keeping visible: Curfew's
     * core mutes a blocked target's notifications, so blocking a bank *sounds* like it silences its
     * one-time passwords. It does not. `CurfewNotificationListener` returns early for every package on
     * the sensitive list, so a blocked bank app is blocked on screen and its notifications still
     * arrive. Blocking one costs a block screen and nothing else.
     */
    @Test
    fun `a bank app can be blocked in either mode`() {
        EnforcementMode.entries.forEach { mode ->
            val read = store(mode, bankRule).read()

            assertTrue(
                "$mode dropped a block on a payment app; blocking it is the user's decision",
                read.contains("com.phonepe.app"),
            )
        }
    }

    /** And the whole shipped list with it, not just the one app this test names. */
    @Test
    fun `every app Curfew will not read is still blockable`() {
        assertTrue(SensitiveApps.CURATED.isNotEmpty())
        SensitiveApps.CURATED.forEach { packageName ->
            val rule = appRule.replace("com.instagram.android", packageName)
            assertTrue(
                "$packageName was not blockable, though Curfew only refuses to *read* it",
                store(EnforcementMode.APP_AND_URL, rule).read().contains(packageName),
            )
        }
    }

    /**
     * And nothing is dropped from a config that names one, so a user with a bank app blocked keeps
     * every other rule too. This was the shape that exposed the old rule: a real config of
     * thirty-odd rules, one of them Amazon, whose *entire* ruleset was silently discarded.
     */
    @Test
    fun `every rule survives in a config that blocks a payment app`() {
        val mixed = """
            schema_version = 1
            timezone = "Europe/London"

            [[profiles]]
            id = "deep-work"
            name = "Deep work"

            [[profiles.rules]]
            target = { kind = "app_package", package = "com.instagram.android" }
            action = { kind = "block" }

            [[profiles.rules]]
            target = { kind = "app_package", package = "com.phonepe.app" }
            action = { kind = "block" }

            [[profiles.rules]]
            target = { kind = "domain", domain = "reddit.com" }
            action = { kind = "block" }

            [[profiles.rules]]
            target = { kind = "app_package", package = "com.facebook.katana" }
            action = { kind = "block" }
        """.trimIndent()
        val store = store(EnforcementMode.APP_AND_URL, mixed)
        val read = store.read()

        assertTrue("the payment app was dropped", read.contains("com.phonepe.app"))
        assertTrue("an ordinary app was dropped with it", read.contains("com.instagram.android"))
        assertTrue("a later rule was dropped with it", read.contains("com.facebook.katana"))
        assertTrue("the domain rule was dropped with it", read.contains("reddit.com"))
        assertTrue("the profile was dropped with it", read.contains("deep-work"))
        assertTrue("something was reported as dropped: ${store.droppedRules()}", store.droppedRules().isEmpty())
    }

    /** Ordinary blocking is untouched, in both modes. */
    @Test
    fun `an ordinary app is still blockable in both modes`() {
        EnforcementMode.entries.forEach { mode ->
            assertTrue(
                "$mode stopped blocking an ordinary app",
                store(mode, appRule).read().contains("com.instagram.android"),
            )
        }
    }
}
