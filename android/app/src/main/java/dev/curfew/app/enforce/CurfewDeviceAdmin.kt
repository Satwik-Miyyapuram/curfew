package dev.curfew.app.enforce

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit

/**
 * Uninstall protection, and nothing else.
 *
 * Android refuses to uninstall an app that is an active device admin, which closes the last easy
 * exit from a running lock: uninstalling the blocker. This receiver therefore claims no policies at
 * all — no wipe, no password rules, no camera lock (see `res/xml/device_admin.xml`). It exists to be
 * active, not to be able to do anything.
 *
 * It is optional, and it is not a trap. Admin is only worth holding while a lock is actually
 * running; once nothing is locked, deactivating it and uninstalling Curfew are ordinary again. That
 * is what keeps the device recoverable, which no blocker gets to compromise.
 */
class CurfewDeviceAdmin : DeviceAdminReceiver() {

    /**
     * The one warning Android will show before deactivation.
     *
     * A device admin cannot actually veto its own removal, and pretending otherwise would be a lie
     * told to the user. What it can do is say what is about to be given up, and Curfew says it in
     * terms of the lock the person is presumably trying to escape.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        if (isLockHeld(context)) {
            "A lock is running. Turning this off makes Curfew uninstallable again, which is the " +
                "one way left to end that lock early. Nothing else about the lock changes."
        } else {
            "Curfew can be uninstalled normally after this. No lock is running, so nothing is lost."
        }

    override fun onDisabled(context: Context, intent: Intent) {
        setLockHeld(context, false)
    }

    companion object {
        private const val PREFS = "curfew_admin"
        private const val KEY_LOCK_HELD = "lock_held"

        fun component(context: Context): ComponentName =
            ComponentName(context, CurfewDeviceAdmin::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)
                .isAdminActive(component(context))

        /**
         * The intent that asks for admin. The explanation is shown inside Android's own dialog,
         * which is the only place a user will read it at the moment it matters.
         */
        fun requestIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "This stops Curfew being uninstalled while a lock is running. It grants no " +
                        "other control over the device: Curfew cannot erase it, lock it, or change " +
                        "any password.",
                )

        /** Deactivate, so the app can be uninstalled again. Safe to call when not active. */
        fun release(context: Context) {
            if (isActive(context)) {
                context.getSystemService(DevicePolicyManager::class.java)
                    .removeActiveAdmin(component(context))
            }
        }

        /**
         * Whether a lock is currently held.
         *
         * Kept in preferences rather than read from the database, because a broadcast receiver has
         * no coroutine scope and the deactivation warning has to be returned synchronously.
         */
        fun isLockHeld(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCK_HELD, false)

        fun setLockHeld(context: Context, held: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_LOCK_HELD, held) }
        }
    }
}
