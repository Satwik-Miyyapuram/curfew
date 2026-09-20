package dev.curfew.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.curfew.app.data.CurfewRuntime
import dev.curfew.app.enforce.EnforcementMode
import dev.curfew.app.enforce.SensitiveApps
import dev.curfew.policy.Policy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two things the config guard depends on, checked against the real application context.
 *
 * The guard is only as good as its inputs, and both of them come from somewhere the pure unit tests
 * replace: [SensitiveApps.resolve] walks the `PackageManager`, and `Policy` is the native binding
 * over a real parse. When the guard silently did nothing on a device that had a payment app blocked,
 * the question was which of those two was empty — so this asks, out loud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfigGuardInputsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The curated list is a constant, so this cannot be empty on any device. If it ever is, the
     * unblockable check is comparing against nothing and every bank app is blockable again.
     */
    @Test
    fun `the curated list is never empty`() {
        assertTrue(
            "no app is unblockable, so the guard has nothing to refuse",
            SensitiveApps.CURATED.isNotEmpty(),
        )
        assertTrue(
            "the Amazon app is blocked in a real config and has to be on the list",
            SensitiveApps.CURATED.contains("com.amazon.mShop.android.shopping"),
        )
    }

    /**
     * And resolving against a real context does not lose the curated list. The dynamic sources are
     * best-effort — a `PackageManager` that will not answer, or a label that does not match — but the
     * curated list is the floor and must survive either failing.
     */
    @Test
    fun `resolving keeps the curated list whatever the package manager says`() {
        val resolved = SensitiveApps.resolve(context)

        assertTrue(
            "resolution dropped curated entries, so a shipped guarantee is not being applied",
            resolved.containsAll(SensitiveApps.CURATED),
        )
        assertTrue(resolved.contains("com.amazon.mShop.android.shopping"))
    }

    /**
     * The full chain, on a config shaped like the device's, through the real runtime.
     *
     * This is the reproduction: a blocked Amazon app and an ordinary blocked app, read back through
     * `CurfewRuntime.create`'s own config path with the real `SensitiveApps.resolve`. Before the fix
     * this returned a config with no rules at all — the whole file discarded rather than narrowed.
     */
    @Test
    fun `a real runtime loads every rule, including the one on a payment app`() {
        val config = """
            schema_version = 1
            timezone = "Europe/Amsterdam"

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
        val runtime: CurfewRuntime = TestRuntime.create(
            configToml = config,
            mode = EnforcementMode.APP_AND_URL,
        )

        val loaded = runtime.config.read()

        assertTrue("the ordinary rule was lost", loaded.contains("com.facebook.katana"))
        assertTrue("the profile was lost", loaded.contains("\"study\""))
        assertTrue(
            "a block on an app Curfew merely will not read was dropped; blocking it is the user's call",
            loaded.contains("com.amazon.mShop.android.shopping"),
        )
    }

    /** And the core agrees: the loaded text is a policy it will accept and enforce. */
    @Test
    fun `the loaded config is one the core accepts`() {
        val loaded = TestRuntime.create(
            configToml = """
                schema_version = 1
                timezone = "Europe/Amsterdam"

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
            """.trimIndent(),
            mode = EnforcementMode.APP_AND_URL,
        ).config.read()

        assertTrue(Policy.check(loaded).isSuccess)
    }
}
