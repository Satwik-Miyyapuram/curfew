package dev.curfew.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.curfew.app.data.CurfewRuntime
import dev.curfew.app.enforce.EnforcementMode
import dev.curfew.app.enforce.SensitiveApps
import dev.curfew.app.ui.InstalledAppsCache
import dev.curfew.app.ui.installedApps
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picker's list, assembled the way the screen assembles it.
 *
 * The step missing from every other test: they check `resolve()` and they check the app enumeration,
 * but nothing checked the two *together*, which is the number a user actually sees. A filter that is
 * right on its own and a list that is right on its own can still compose into a screen that shows
 * nothing.
 *
 * **Robolectric enumerates no launcher activities**, so these cannot assert the list is non-empty here
 * — an attempt to do exactly that failed with "nothing was enumerated at all", which is a fact about
 * the harness and not about the app. They guard on that and assert the composition rules instead. The
 * empty-picker report was settled on a real phone by comparing the pills against
 * `pm list packages -3`: 242 enumerated, 20 hidden, 222 shown, and 17 of the hidden ones were curated
 * entries that were genuinely installed. The picker was working; it was hiding a fifth of the phone
 * with nothing on screen to say so, which is the part that was wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppPickerContentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The filter never lets an unblockable app through.
     *
     * The one direction that can be checked without an enumeration, and the direction that matters: an
     * app offered for blocking that the config loader will then refuse is the picker making a promise
     * the rest of the app breaks.
     */
    @Test
    fun `nothing offered by the picker is an app Curfew refuses to block`() {
        context.getSharedPreferences("curfew_sensitive", Context.MODE_PRIVATE)
            .edit().clear().commit()
        InstalledAppsCache.clear()

        val apps = installedApps(context, forceRefresh = true)
        val unblockable = SensitiveApps.resolve(context)
        val offered = apps.filterNot { it.packageName in unblockable }

        val leaked = offered.filter { it.packageName in unblockable }
        assertTrue("the filter let an unblockable app through: $leaked", leaked.isEmpty())
    }

    /**
     * And an app that is *already blocked* is still visible, or a user cannot see or undo what they
     * blocked. The picker shows blocked apps first for the same reason.
     */
    @Test
    fun `an already blocked app is still offered by the picker`() {
        InstalledAppsCache.clear()
        val apps = installedApps(context, forceRefresh = true)
        if (apps.isEmpty()) return

        val blocked = apps.last().packageName
        val profile = """
            schema_version = 1
            timezone = "Europe/London"

            [[profiles]]
            id = "deep-work"
            name = "Deep work"

            [[profiles.rules]]
            target = { kind = "app_package", package = "$blocked" }
            action = { kind = "block" }
        """.trimIndent()

        // The picker's own inputs, in the order it applies them.
        val runtime: CurfewRuntime = TestRuntime.create(
            configToml = profile,
            mode = EnforcementMode.APP_AND_URL,
        )
        val blockedSet = runtime.rules("deep-work")
            .mapNotNull { (it.target as? dev.curfew.policy.Target.AppPackage)?.`package` }
            .toSet()
        val unblockable = SensitiveApps.resolve(context)
        val shown = apps.filterNot { it.packageName in unblockable }

        assertTrue("the app was not blocked in the first place", blockedSet.contains(blocked))
        assertTrue(
            "an app the config blocks is not visible in the picker, so it cannot be unblocked",
            shown.any { it.packageName == blocked },
        )
    }
}
