package dev.curfew.app.enforce

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import dev.curfew.policy.Observation

/**
 * The fallback for people who will not grant the accessibility service.
 *
 * It is honestly worse and the health screen says so: usage stats are sampled, so a blocked app can
 * be on screen for up to a poll interval before Curfew notices, and nothing here can see a web page
 * at all. It exists because a blocker that refuses to work without the most alarming permission on
 * the system is a blocker most people will not install.
 */
class UsageStatsPoller(private val context: Context) {

    /** The app in the foreground, or null when nothing is or the permission is missing. */
    fun sample(now: Long): Observation? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null

        // A window wide enough to survive a doze-delayed poll, narrow enough that a long-closed app
        // is never reported as being in front.
        val events = manager.queryEvents((now - WINDOW_SECONDS) * 1000, now * 1000)
        val event = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = event.packageName
            }
        }
        return last?.takeIf { it != context.packageName }?.let { Observation.App(it) }
    }

    companion object {
        private const val WINDOW_SECONDS = 120L

        fun hasPermission(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            // `unsafeCheckOpNoThrow` only exists from API 29; below that the same question is asked
            // through the deprecated `checkOpNoThrow`, which is the only spelling those releases have.
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
