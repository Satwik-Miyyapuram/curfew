package dev.curfew.app.enforce

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.curfew.app.CurfewApplication
import dev.curfew.app.R
import dev.curfew.app.block.BlockActivity
import dev.curfew.app.curfew
import dev.curfew.app.data.CurfewRuntime
import dev.curfew.policy.BlockReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The process that must not die.
 *
 * Enforcement lives in a foreground service rather than in the accessibility service because the
 * accessibility service can be revoked from Settings at any moment, and a session that stops when
 * the user revokes a permission is not a session. The notification is not decoration: on Android it
 * is the price of being allowed to keep running, and it is also the honest signal that Curfew is
 * currently doing something to the device.
 */
class EnforcementService : Service() {

    private lateinit var runtime: CurfewRuntime
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        runtime = curfew
        startForeground(NOTIFICATION_ID, notification(running = false))
        loop = runtime.scope.launch {
            runtime.restore()
            tick()
        }
    }

    /**
     * The heartbeat.
     *
     * A schedule can start or end with no user activity at all, so something has to notice. The
     * alarm set by [ScheduleAlarmReceiver] is the real mechanism — this loop is the belt to its
     * braces, deliberately slow, so that a missed alarm costs at most a minute of enforcement
     * rather than a whole session.
     */
    private suspend fun tick() {
        while (runtime.scope.isActive) {
            val now = runtime.clock.now()
            val events = runtime.calendarEvents(now)
            runtime.reconcile(now, events)
            ScheduleAlarmReceiver.scheduleNext(this, runtime.nextChange(now, events))
            updateNotification()
            // If the fallback detector is in use, this is also when the foreground app is sampled.
            if (!CurfewAccessibilityService.isEnabled(this)) {
                UsageStatsPoller(this).sample(now)?.let {
                    enforcer(this).onObservation(it, now)
                }
            }
            delay(POLL_MILLIS)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(runtime.activeProfiles.value.isNotEmpty()))
    }

    private fun notification(running: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CurfewApplication.CHANNEL_ENFORCEMENT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(if (running) R.string.notification_running else R.string.notification_idle))
            .setContentText(runtime.activeProfiles.value.joinToString(", "))
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loop?.cancel()
        super.onDestroy()
    }

    /**
     * A task removed from recents is a swipe-away, not a decision to stop enforcing. Restarting is
     * what makes "kill the app to unblock" stop working.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        start(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val POLL_MILLIS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, EnforcementService::class.java)
            context.startForegroundService(intent)
        }

        /** One enforcer per process, shared by the accessibility service and the poller. */
        @Volatile
        private var shared: Enforcer? = null

        fun enforcer(context: Context): Enforcer =
            shared ?: synchronized(this) {
                shared ?: Enforcer(context.curfew, AndroidActions(context.applicationContext))
                    .also { shared = it }
            }
    }
}

/** The enforcer's hands: what actually happens on the device when a decision comes back. */
class AndroidActions(private val context: Context) : Enforcer.Actions {

    override fun block(target: String, reason: BlockReason) {
        context.startActivity(BlockActivity.intent(context, target, reason))
    }

    override fun delay(target: String, seconds: Int) {
        context.startActivity(BlockActivity.delayIntent(context, target, seconds))
    }

    override fun allow(target: String) = Unit

    /**
     * Muting is best-effort on Android: without a notification listener Curfew cannot cancel a
     * posted notification, and asking for one would mean asking to read every notification on the
     * device — a far larger permission than the feature is worth. The rule is honoured by the
     * notification listener when the user has granted it, and the health screen says so plainly
     * when they have not.
     */
    override fun muteNotification(target: String) = Unit
}
