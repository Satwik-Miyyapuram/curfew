package dev.curfew.app.enforce

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How big the unblockable set gets on a real device, which is the size of the hole a bug would make.
 *
 * The app picker filters this set out of its list, so an over-broad set is not a cosmetic problem: it
 * is a picker with nothing in it. That happened, and the cause was not this class — but nothing here
 * was checking the size either, so the first suspicion had to be ruled out by measurement rather than
 * by reading.
 *
 * Robolectric's package manager is not a real device's, so these are a floor and not a prediction of
 * what a phone produces. What they *can* catch is the difference between "the curated list" and
 * "everything installed", which is the failure mode that matters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SensitiveAppsFootprintTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `resolution is the curated list plus a little, not everything installed`() {
        context.getSharedPreferences("curfew_sensitive", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val resolved = SensitiveApps.resolve(context)
        val installed = context.packageManager.getInstalledApplications(0).map { it.packageName }

        assertTrue("nothing is unblockable", resolved.isNotEmpty())
        assertTrue(
            "the curated list was lost",
            resolved.containsAll(SensitiveApps.CURATED),
        )
        // The real assertion: the dynamic signal must not have swallowed the whole device. A generous
        // bound, because Robolectric's package set is small and odd — the point is that this cannot
        // silently become "every app".
        assertTrue(
            "resolution covered ${resolved.size} of ${installed.size} installed packages, which is " +
                "not a curation",
            resolved.size <= SensitiveApps.CURATED.size + installed.size / 2,
        )
    }

    /**
     * The curated list itself, held to a size a person could scroll past in a settings screen. It is
     * not a hard limit — it grows as banks are found — but a jump from forty to four hundred is a bug
     * and not a curation.
     */
    @Test
    fun `the curated list is a curated size`() {
        assertTrue(
            "the shipped list is ${SensitiveApps.CURATED.size} entries, which is not a curation",
            SensitiveApps.CURATED.size < 200,
        )
    }

    /** No user additions or exemptions means the resolved set is the curated list plus the signal. */
    @Test
    fun `an untouched install resolves to the shipped list`() {
        context.getSharedPreferences("curfew_sensitive", Context.MODE_PRIVATE)
            .edit().clear().commit()

        assertTrue(SensitiveApps.extraPackages(context).isEmpty())
        assertTrue(SensitiveApps.exemptPackages(context).isEmpty())
        assertTrue(SensitiveApps.resolve(context).containsAll(SensitiveApps.CURATED))
    }
}
