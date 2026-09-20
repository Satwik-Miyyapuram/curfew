package dev.curfew.app.enforce

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sensitive-app guarantee.
 *
 * This list is the only thing standing between "Curfew reads one browser's address bar" and "Curfew
 * reads a payment screen", and it holds by a single check at the top of every accessibility entry
 * point. Nothing here can drive that method without a device, but what the check *asks* can be tested
 * exactly — and that is the part a future edit is most likely to get wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SensitiveAppsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        // Robolectric shares a filesystem between the tests in a class, so a package one test adds is
        // still there for the next one unless it is cleared.
        context.getSharedPreferences("curfew_sensitive", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // --- the curated list, which is not configurable -------------------------------------------------

    @Test
    fun `the upi and wallet apps are on the list`() {
        assertTrue(SensitiveApps.CURATED.contains("com.google.android.apps.nbu.paisa.user"))
        assertTrue(SensitiveApps.CURATED.contains("com.phonepe.app"))
        assertTrue(SensitiveApps.CURATED.contains("net.one97.paytm"))
        assertTrue(SensitiveApps.CURATED.contains("in.org.npci.upiapp"))
        assertTrue(SensitiveApps.CURATED.contains("com.google.android.apps.walletnfcrel"))
        assertTrue(SensitiveApps.CURATED.contains("com.samsung.android.spay"))
        assertTrue(SensitiveApps.CURATED.contains("com.paypal.android.p2pmobile"))
    }

    @Test
    fun `the major banks are on the list`() {
        assertTrue(SensitiveApps.CURATED.contains("com.sbi.lotusintouch"))
        assertTrue(SensitiveApps.CURATED.contains("com.csam.icici.bank.imobile"))
        assertTrue(SensitiveApps.CURATED.contains("com.snapwork.hdfc"))
        assertTrue(SensitiveApps.CURATED.contains("com.axis.mobile"))
    }

    @Test
    fun `credential apps are on the list`() {
        assertTrue(SensitiveApps.CURATED.contains("com.google.android.apps.authenticator2"))
        assertTrue(SensitiveApps.CURATED.contains("com.x8bit.bitwarden"))
        assertTrue(SensitiveApps.CURATED.contains("com.onepassword.android"))
    }

    /**
     * The other direction, and the one users notice: everything on the list is exempt from
     * inspection, so an over-long list silently makes an app the user asked to block unblockable.
     */
    @Test
    fun `apps that are not financial or credential are not on the list`() {
        assertFalse(SensitiveApps.CURATED.contains("com.instagram.android"))
        assertFalse(SensitiveApps.CURATED.contains("com.zhiliaoapp.musically"))
        assertFalse(SensitiveApps.CURATED.contains("com.android.chrome"))
        assertFalse(SensitiveApps.CURATED.contains("org.mozilla.firefox"))
        assertFalse(SensitiveApps.CURATED.contains("com.android.settings"))
        assertFalse(SensitiveApps.CURATED.contains("com.android.systemui"))
        assertFalse(SensitiveApps.CURATED.contains("dev.curfew.app"))
        assertFalse(SensitiveApps.CURATED.contains(""))
    }

    /**
     * A fresh install's own starter profile must not collide with the guarantee — otherwise the first
     * thing Curfew does would silently fail for an exempt app.
     */
    @Test
    fun `nothing the starter profile blocks is exempt`() {
        val starter = listOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.google.android.youtube",
            "com.twitter.android",
            "com.x.android",
            "com.facebook.katana",
            "com.reddit.frontpage",
            "com.snapchat.android",
            "com.netflix.mediaclient",
        )
        starter.forEach {
            assertFalse(
                "$it is blocked by a fresh install and must not be exempt",
                SensitiveApps.CURATED.contains(it),
            )
        }
    }

    // --- what the user may change -------------------------------------------------------------------

    /**
     * A regional bank the shipped list does not know is added by hand, and the addition is what the
     * accessibility check then asks about.
     */
    @Test
    fun `a package the user adds is resolved into the list`() {
        val regional = "com.example.regionalbank"
        assertFalse(SensitiveApps.resolve(context).contains(regional))

        SensitiveApps.addUserPackage(context, regional)

        assertTrue(SensitiveApps.resolve(context).contains(regional))
        assertTrue(SensitiveApps.isUserAdded(context, regional))
    }

    /**
     * The exemption exists because the second signal — a label word plus a biometric permission — is a
     * heuristic, and a heuristic that could not be overridden would make a budgeting app the user
     * genuinely wants to block unblockable, with nothing on any screen saying why.
     */
    @Test
    fun `a package the user removes stays removed`() {
        val budgeting = "com.example.budgetplanner"
        SensitiveApps.addUserPackage(context, budgeting)
        assertTrue(SensitiveApps.resolve(context).contains(budgeting))

        SensitiveApps.removeUserPackage(context, budgeting)

        assertFalse(SensitiveApps.resolve(context).contains(budgeting))
        assertFalse(SensitiveApps.isUserAdded(context, budgeting))
        assertTrue(
            "an exemption has to win over the label signal too, not just over the user's own addition",
            SensitiveApps.isUserExempt(context, budgeting),
        )
    }

    /**
     * The curated list is a promise made on the permission screen, and a promise with an off switch is
     * not a promise — so removing one is refused rather than obeyed quietly.
     */
    @Test
    fun `a curated package cannot be removed`() {
        val bank = "com.sbi.lotusintouch"

        SensitiveApps.removeUserPackage(context, bank)

        assertTrue(SensitiveApps.resolve(context).contains(bank))
        assertFalse(SensitiveApps.isUserExempt(context, bank))
    }

    /** Adding back something previously removed undoes the exemption, rather than leaving both. */
    @Test
    fun `adding a package back overrides an earlier exemption`() {
        val app = "com.example.moneything"
        SensitiveApps.removeUserPackage(context, app)
        assertTrue(SensitiveApps.isUserExempt(context, app))

        SensitiveApps.addUserPackage(context, app)

        assertFalse(SensitiveApps.isUserExempt(context, app))
        assertTrue(SensitiveApps.resolve(context).contains(app))
    }

    @Test
    fun `an empty or curated package name is not recorded as a user addition`() {
        SensitiveApps.addUserPackage(context, "   ")
        SensitiveApps.addUserPackage(context, "com.sbi.lotusintouch")

        assertEquals(emptySet<String>(), SensitiveApps.extraPackages(context))
    }

    /**
     * Resolution is a union, never a replacement: the curated list is present whatever the user has
     * done to the other two sources.
     */
    @Test
    fun `resolving always includes the curated list`() {
        SensitiveApps.removeUserPackage(context, "com.phonepe.app")
        SensitiveApps.addUserPackage(context, "com.example.regionalbank")

        val resolved = SensitiveApps.resolve(context)

        assertTrue(resolved.containsAll(SensitiveApps.CURATED))
        assertTrue(resolved.contains("com.example.regionalbank"))
    }

    // --- what the accessibility path actually consults -----------------------------------------------

    /**
     * The readers ask [Watchers], not [SensitiveApps], and the two have to agree.
     *
     * This is the difference between a guarantee and a copy of one. The readers run on every
     * accessibility event and cannot afford a `PackageManager` walk there, so the resolved value lives
     * in [Watchers] and is refreshed at the moments it can change. A test that only exercised
     * `resolve` would pass while every reader consulted a stale set.
     */
    @Test
    fun `the shared list the readers consult tracks a resolution`() {
        val regional = "com.example.regionalbank"
        Watchers.refreshSensitive(context)
        assertFalse(Watchers.sensitive().contains(regional))

        SensitiveApps.addUserPackage(context, regional)
        Watchers.refreshSensitive(context)

        assertTrue(
            "a reader would still be looking at the list from before the user's addition",
            Watchers.sensitive().contains(regional),
        )
        assertEquals(SensitiveApps.resolve(context), Watchers.sensitive())
    }

    /**
     * Before anything has refreshed, the shared value is the curated list rather than empty.
     *
     * An empty set means "nothing is exempt", so a reader starting before its first refresh would
     * read a bank's screen — the one failure this list exists to prevent. The curated list is a
     * constant and is therefore already correct.
     */
    @Test
    fun `the list starts as the curated set rather than empty`() {
        assertTrue(SensitiveApps.CURATED.isNotEmpty())
        assertTrue(Watchers.sensitive().contains("com.phonepe.app"))
    }
}
