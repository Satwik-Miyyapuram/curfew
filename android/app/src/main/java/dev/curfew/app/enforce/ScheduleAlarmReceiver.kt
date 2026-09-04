package dev.curfew.app.enforce

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The alarm that wakes Curfew at the next moment a schedule could change.
 *
 * Polling is what doze punishes, and a blocker that doze kills is not a blocker; so instead of
 * asking "has anything changed?" every minute, Curfew asks the core when the next change *is* and
 * sleeps until then. The alarm is exact because a session that starts at 09:03 instead of 09:00 is
 * three minutes of the thing the user asked not to have.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The service does the work; this only ensures something is awake to do it. It is also the
        // path back from a force-stop, since an alarm restarts the process.
        EnforcementService.start(context.applicationContext)
    }

    companion object {
        private const val REQUEST = 100

        fun scheduleNext(context: Context, at: Long?) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST,
                Intent(context, ScheduleAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (at == null) {
                manager.cancel(pending)
                return
            }
            val millis = at * 1000
            // Without the exact-alarm permission the alarm still fires, just late. That is a
            // degradation the health screen reports rather than a failure to enforce: the service's
            // own slow loop catches the change within a minute either way.
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            }
        }

        private fun canScheduleExact(manager: AlarmManager): Boolean =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
    }
}
