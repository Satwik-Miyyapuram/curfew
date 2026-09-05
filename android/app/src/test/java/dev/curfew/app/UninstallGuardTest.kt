package dev.curfew.app

import androidx.test.core.app.ApplicationProvider
import dev.curfew.app.enforce.CurfewDeviceAdmin
import dev.curfew.app.enforce.UninstallGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Uninstall protection that does not need device admin.
 *
 * What matters here is the shape of the refusal: it fires only while a lock is held, only on a
 * screen that could actually remove an app, and only when that screen is about Curfew. Getting the
 * last condition wrong would mean pressing Back on somebody uninstalling an unrelated app, which is
 * a far worse bug than failing to guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UninstallGuardTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `nothing is guarded when no lock is running`() {
        assertFalse(
            UninstallGuard.shouldIntervene(
                "com.android.settings",
                lockHeld = false,
                mentionsCurfew = true,
            ),
        )
    }

    @Test
    fun `curfew's own settings page is guarded while a lock runs`() {
        assertTrue(
            UninstallGuard.shouldIntervene(
                "com.android.settings",
                lockHeld = true,
                mentionsCurfew = true,
            ),
        )
    }

    /** Somebody uninstalling a different app mid-session is doing something Curfew has no say in. */
    @Test
    fun `a settings page about another app is left alone`() {
        assertFalse(
            UninstallGuard.shouldIntervene(
                "com.android.settings",
                lockHeld = true,
                mentionsCurfew = false,
            ),
        )
    }

    @Test
    fun `an ordinary app is never guarded`() {
        assertFalse(
            UninstallGuard.shouldIntervene(
                "com.example.notes",
                lockHeld = true,
                mentionsCurfew = true,
            ),
        )
        assertFalse(UninstallGuard.watches("com.example.notes"))
    }

    /** The vendor uninstall flows, where the stock package installer is never seen. */
    @Test
    fun `the vendor security apps are watched too`() {
        assertTrue(UninstallGuard.watches("com.miui.securitycenter"))
        assertTrue(UninstallGuard.watches("com.samsung.android.lool"))
        assertTrue(UninstallGuard.watches("com.android.packageinstaller"))
    }

    /** One flag: the admin receiver and the accessibility guard must not disagree. */
    @Test
    fun `device admin and the guard read the same lock flag`() {
        UninstallGuard.setLockHeld(context, true)
        assertTrue(CurfewDeviceAdmin.isLockHeld(context))

        CurfewDeviceAdmin.setLockHeld(context, false)
        assertFalse(UninstallGuard.isLockHeld(context))
    }
}
