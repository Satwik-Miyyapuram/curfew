package dev.curfew.app.enforce

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Enforcement resumes by itself after a reboot or an app update.
 *
 * This is not a convenience. A lock that a restart clears is not a lock, and "turn it off and on
 * again" is the first thing anyone tries.
 *
 * Enforcement starts at the first unlock after boot, not before it. Starting earlier would need
 * the receiver to be direct-boot aware, and everything the service opens — the database, its key,
 * the sync store — lives in credential-encrypted storage that does not exist until the user has
 * unlocked once. Until then no app the user installed can run either, so there is nothing to
 * enforce against; the gap is the lock screen, and the lock screen is the phone's own.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> EnforcementService.start(context.applicationContext)
        }
    }
}
