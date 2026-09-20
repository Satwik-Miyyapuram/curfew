package dev.curfew.app.enforce

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.curfew.app.curfew
import dev.curfew.policy.Decision
import dev.curfew.policy.Observation
import kotlinx.coroutines.launch

/**
 * The only way Android lets an app take a notification back down.
 *
 * This service is optional on purpose. Granting notification access means granting the ability to
 * read every notification on the device, which is a far larger permission than "silence Instagram
 * while I am working" deserves; Curfew therefore works without it and says so on the health screen.
 * When it is granted, the rule the user wrote is honoured exactly and nothing else is looked at:
 * the package name goes to the core, the core answers mute or not, and the notification's contents
 * are never read, stored, or logged.
 */
class CurfewNotificationListener : NotificationListenerService() {

    /**
     * The sensitive list, resolved once when the listener starts.
     *
     * Same reasoning as in the two accessibility services: the check runs before anything else on
     * every notification, and two of its three sources need a `PackageManager`.
     */
    private val sensitive: Set<String> by lazy { SensitiveApps.resolve(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val from = sbn.packageName ?: return
        // **A financial notification is never touched.** Not muted, not inspected, not logged. The
        // cost of getting this wrong is asymmetric in a way that decides it: one extra notification
        // on a lock screen is an annoyance, and a one-time password that never arrives is money
        // stuck in flight — during the two minutes when the user can do least about it.
        //
        // This matters even though blocking a bank app is allowed: `SensitiveApps` keeps such an app
        // blockable from its package name alone, and the core mutes notifications for anything a
        // block covers. Without this, asking Curfew to block a bank app would silence its OTPs.
        if (from in sensitive) return
        // Curfew's own ongoing notification is what tells the user enforcement is running; a rule
        // must never be able to silence it.
        if (from == packageName) return
        val runtime = curfew
        runtime.scope.launch {
            val now = runtime.clock.now()
            val observation = Observation.Notification(from, title = "")
            if (runtime.decide(observation, now) == Decision.Mute) {
                // Cancelling rather than snoozing: a snoozed notification returns mid-session,
                // which is precisely the interruption the rule was written to prevent.
                runCatching { cancelNotification(sbn.key) }
            }
        }
    }

    companion object {
        /** Whether the user has granted notification access to Curfew specifically. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, CurfewNotificationListener::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it)?.equals(expected) == true
            }
        }
    }
}
