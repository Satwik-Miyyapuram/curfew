package dev.curfew.app

import androidx.test.core.app.ApplicationProvider
import dev.curfew.app.enforce.LockFlag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lock flag, and the promise that nothing stands in front of a plain uninstall.
 *
 * Uninstalling is the last way out of a lock that has gone wrong, and it has to be the ordinary one
 * every user knows: no device admin to deactivate first, no adb, no screen pressed Back on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LockFlagTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 1_700_000_000L

    @Test
    fun `the flag holds while set and clears when cleared`() {
        LockFlag.set(context, true)
        assertTrue(LockFlag.isHeld(context, now))

        LockFlag.set(context, false)
        assertFalse(LockFlag.isHeld(context, now))
    }

    @Test
    fun `a flag left behind after the lock's end time holds nothing`() {
        LockFlag.set(context, true, until = now + 60)

        assertTrue(LockFlag.isHeld(context, now))
        assertFalse(LockFlag.isHeld(context, now + 61))
    }

    @Test
    fun `Curfew is never a device admin, so Android always lets it be uninstalled`() {
        val manifest = java.io.File(UntranslatedCopy.sourceRoot, "../../../../AndroidManifest.xml").readText()

        assertFalse(manifest.contains("BIND_DEVICE_ADMIN"))
        assertFalse(manifest.contains("android.app.device_admin"))
    }
}
