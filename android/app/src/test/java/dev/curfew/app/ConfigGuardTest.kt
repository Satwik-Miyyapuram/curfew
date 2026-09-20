package dev.curfew.app

import dev.curfew.app.data.ConfigStore
import dev.curfew.app.enforce.EnforcementMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config guard against the shape of a real config file, character for character.
 *
 * The device's own file is written by the core, not by hand, and it looks nothing like the tidy
 * fixtures elsewhere: the target is a `[profiles.rules.target]` table with `kind` and `package` on
 * separate lines, the action is a nested table, and every rule carries an empty `platforms = []`. A
 * guard that only worked on the tidy spelling would silently do nothing on every real device — which
 * is what the first version of this did, and why this test exists.
 *
 * **What the guard is *not* allowed to refuse is as important as what it is**, and that is the second
 * half of this file. It once dropped every rule naming a payment or banking app. Which apps a person
 * blocks is theirs to decide, and the reading restriction belongs to the accessibility service rather
 * than to the config, so that was removed — and these tests are what stop it coming back.
 *
 * No Robolectric: `ConfigStore` touches only a file and `Log`, and on this path not even `Log`.
 */
class ConfigGuardTest {

    private val realShapedConfig = """
        schema_version = 1
        timezone = "Europe/Amsterdam"
        weekly = []
        calendar_sources = []
        tokens = []

        [[profiles]]
        id = "study"
        name = "Study"
        description = ""

        [[profiles.rules]]
        platforms = []

        [profiles.rules.target]
        kind = "app_package"
        package = "com.amazon.mShop.android.shopping"

        [profiles.rules.action]
        kind = "block"

        [[profiles.rules]]
        platforms = []

        [profiles.rules.target]
        kind = "app_package"
        package = "com.facebook.katana"

        [profiles.rules.action]
        kind = "block"
    """.trimIndent()

    private fun store(contents: String, mode: EnforcementMode = EnforcementMode.APP_AND_URL): ConfigStore {
        val file = File.createTempFile("curfew-guard", ".toml").apply { deleteOnExit() }
        file.writeText(contents)
        return ConfigStore(file = file, mode = { mode })
    }

    /**
     * **A payment or banking app is loadable, in either mode.**
     *
     * The regression guard for the rule that was removed. `com.amazon.mShop.android.shopping` is on
     * the shipped sensitive list, so this fails the moment anything starts refusing to load a block on
     * an app Curfew merely will not read.
     */
    @Test
    fun `a rule blocking a payment app is loaded, not dropped`() {
        EnforcementMode.entries.forEach { mode ->
            val read = store(realShapedConfig, mode).read()

            assertTrue(
                "$mode dropped a block on an app Curfew is not allowed to read; blocking it is the " +
                    "user's decision",
                read.contains("com.amazon.mShop.android.shopping"),
            )
            assertTrue(read.contains("com.facebook.katana"))
            assertEmpty(dropped = store(realShapedConfig, mode).droppedRules())
        }
    }

    /** And the whole file comes through untouched, in the core's own spelling. */
    @Test
    fun `a config with no url rules is loaded exactly as written`() {
        assertEquals(realShapedConfig, store(realShapedConfig).read())
    }

    /**
     * The one thing the guard does refuse: a URL rule while the app-only detector is running, because
     * a detector that may not read a window cannot enforce one and a rule that silently does nothing
     * is the failure this whole pass was about.
     */
    @Test
    fun `a url rule is refused in app-only mode and the reason is recorded`() {
        val store = store(realShapedConfig + urlRule, EnforcementMode.APP_ONLY)

        assertFalse("a url rule loaded where no window can be read", store.read().contains("example.invalid"))
        assertTrue(
            "the dropped rules were not recorded, so no screen can explain the gap",
            store.droppedRules().isNotEmpty(),
        )
        // The file is not rewritten: the edit stays the user's and becomes enforceable on a mode change.
        assertTrue(store.readRaw().contains("example.invalid"))
    }

    /** And the same rule is fine in the mode that can read a window. */
    @Test
    fun `a url rule loads in app-and-url mode`() {
        assertTrue(
            store(realShapedConfig + urlRule, EnforcementMode.APP_AND_URL).read().contains("example.invalid"),
        )
    }

    /**
     * A domain rule survives app-only mode: the resolver and the hosts file enforce it without reading
     * a browser, so refusing it would stop blocking the sites the user asked for through the layers
     * that need no window at all.
     */
    @Test
    fun `a domain rule survives app-only mode`() {
        assertTrue(
            store(realShapedConfig + domainRule, EnforcementMode.APP_ONLY).read().contains("reddit.com"),
        )
    }

    private val urlRule = """

        [[profiles.rules]]
        platforms = []

        [profiles.rules.target]
        kind = "url"
        pattern = "*://example.invalid/*"

        [profiles.rules.action]
        kind = "block"
    """

    private val domainRule = """

        [[profiles.rules]]
        platforms = []

        [profiles.rules.target]
        kind = "domain"
        domain = "reddit.com"

        [profiles.rules.action]
        kind = "block"
    """

    private fun assertEmpty(dropped: List<String>) {
        assertTrue("the guard dropped something it should not have: $dropped", dropped.isEmpty())
    }
}
