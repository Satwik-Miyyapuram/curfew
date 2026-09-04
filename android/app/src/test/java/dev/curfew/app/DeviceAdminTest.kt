package dev.curfew.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.curfew.app.enforce.CurfewDeviceAdmin
import dev.curfew.policy.Lock
import dev.curfew.policy.LockSet
import dev.curfew.policy.Session
import dev.curfew.policy.SessionSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Uninstall protection, and the promise attached to it.
 *
 * Device admin is the strongest thing Curfew asks for, so what matters is not only that it makes
 * uninstalling harder but that it stops doing so the moment no lock is running. A blocker that can
 * leave a device stuck is not a blocker anyone should install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAdminTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val now = TestRuntime.FRIDAY_0930
    private val unscheduled = TestRuntime.CONFIG.substringBefore("[[weekly]]")

    @Test
    fun `a running lock is written down where the receiver can read it`() = runTest {
        val runtime = TestRuntime.create(now, configToml = unscheduled)
        assertFalse(CurfewDeviceAdmin.isLockHeld(context))

        runtime.startSession(
            Session(
                id = "s1",
                profile = "deep-work",
                source = SessionSource.Manual,
                startedAt = now,
                lock = LockSet(conditions = listOf(Lock.Timer), endsAt = now + 3600),
            ),
        )

        assertTrue(CurfewDeviceAdmin.isLockHeld(context))
    }

    @Test
    fun `once the lock is over the flag is cleared, so uninstalling is ordinary again`() = runTest {
        val runtime = TestRuntime.create(now, configToml = unscheduled)
        runtime.startSession(
            Session(
                id = "s1",
                profile = "deep-work",
                source = SessionSource.Manual,
                startedAt = now,
                lock = LockSet(conditions = listOf(Lock.Timer), endsAt = now + 3600),
            ),
        )

        runtime.reconcile(now + 3601)

        assertFalse(
            "a device left admin-protected after every lock ended would be a trap",
            CurfewDeviceAdmin.isLockHeld(context),
        )
    }

    @Test
    fun `the deactivation warning says what is being given up, and says it differently when nothing is`() {
        CurfewDeviceAdmin.setLockHeld(context, true)
        val duringLock = CurfewDeviceAdmin()
            .onDisableRequested(context, Intent())
            .toString()
        assertTrue(duringLock.contains("lock is running"))

        CurfewDeviceAdmin.setLockHeld(context, false)
        val idle = CurfewDeviceAdmin().onDisableRequested(context, Intent()).toString()
        assertTrue(idle.contains("uninstalled normally"))
    }

    @Test
    fun `the request names the app and asks for no other power`() {
        val intent = CurfewDeviceAdmin.requestIntent(context)
        val explanation = intent
            .getCharSequenceExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION)
            .toString()

        assertTrue(explanation.contains("cannot erase"))
        assertFalse("admin is optional, so it is not asked for as a requirement", explanation.contains("must"))
    }
}
