package dev.curfew.app.enforce

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Enforcement resumes by itself after a reboot or an app update.
 *
 * This is not a convenience. A lock that a restart clears is not a lock, and "turn it off and on
 * again" is the first thing anyone tries. `LOCKED_BOOT_COMPLETED` is handled too so that the
 * service is up before the user has unlocked the device for the first time.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> EnforcementService.start(context.applicationContext)
        }
    }
}
