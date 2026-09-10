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
    private var sync: Job? = null
    private var watch: Job? = null
    private var charge: Job? = null
    private var screen: android.content.BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        runtime = curfew
        startForeground(NOTIFICATION_ID, notification(running = false))
        loop = runtime.scope.launch {
            runtime.restore()
            tick()
        }
        // Beside enforcement, never in front of it. Bringing the sync node up opens sockets and
        // takes a multicast lock, and on this device that call did not come back — which stalled
        // the tick loop behind it and meant nothing was enforced at all. Sync is the feature that
        // may fail; enforcement is the promise that may not, so the promise does not wait on it.
        sync = runtime.scope.launch(kotlinx.coroutines.Dispatchers.IO) { syncLoop() }
        // The notification, the widget and the tile follow what is being enforced rather than
        // waiting for the next tick. Ending a session updates `activeProfiles` the moment the core
        // accepts it, but until this the three surfaces that tell the user whether a block is on
        // went on saying it was for up to thirty seconds — which is exactly long enough to read as
        // the End button having done nothing.
        watch = runtime.scope.launch {
            runtime.activeProfiles.collect { updateNotification() }
        }
        charge = runtime.scope.launch { chargeLoop() }
        screen = screenReceiver().also {
            registerReceiver(
                it,
                android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
            )
        }
    }

    /**
     * The meter.
     *
     * An app that stays in front sends no events, and the poller only samples what is in front,
     * so nothing here ever told the enforcer that time was passing. A budget therefore never ran
     * out while the app it covered was open: the slice was written when the app was left, and the
     * block came on the *next* launch. This loop says "time passed" every few seconds and the
     * enforcer charges the slice and decides again, so a budget ends a sitting rather than
     * forbidding the next one.
     *
     * Runs whether or not the accessibility service is on, since both detectors have the same
     * blind spot. The wall clock rather than the trusted one, because a clock moved forward here
     * only charges a budget faster, and the trusted reading writes to the database each time.
     */
    private suspend fun chargeLoop() {
        while (runtime.scope.isActive) {
            delay(CHARGE_MILLIS)
            runCatching { enforcer(this).onTick(runtime.clock.now()) }
                .onFailure { android.util.Log.w(TAG, "charging failed", it) }
        }
    }

    /**
     * A screen that is off is not a slice of anything. Without this the meter above would charge
     * a phone in a pocket for whatever was in front when it went dark — the exact failure the old
     * charge-on-leave design was built to avoid. When the device is unlocked again the next window
     * event or poll starts a fresh slice; nothing is resumed by guesswork.
     */
    private fun screenReceiver() = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runtime.scope.launch { enforcer(context).onIdle(runtime.clock.now()) }
        }
    }

    /**
     * Sync, on its own thread, forever.
     *
     * Every call into the sync node blocks: bringing it up opens sockets and takes a multicast
     * lock, and a pass talks to peers that may simply not answer — measured here at forty-seven
     * seconds for a single pass on a network with none. A `withTimeout` cannot help, because the
     * blocking is inside the FFI call and there is no suspension point to cancel at. So sync gets
     * its own coroutine on the IO dispatcher and enforcement never waits on it: the tick loop is
     * the promise the app makes, and it now runs on time whatever the network is doing.
     */
    private suspend fun syncLoop() {
        runCatching { startSync() }
        while (runtime.scope.isActive) {
            // Its own clock reading, since the tick's is not shared any more.
            val now = runtime.trustedNow()
            runCatching { runtime.syncPass(now) }
            // Whatever the devices screen shows about sync is read here and cached, because both
            // of those calls take the node's lock. Done after the pass rather than before it, so
            // what lands in the flows is the state the pass left behind.
            runCatching { runtime.sync?.observe(now) }
            delay(SYNC_MILLIS)
        }
    }

    /**
     * Bring sync up, if the device has any.
     *
     * Attached to this service rather than to the application object because the node holds
     * sockets, and the process that is allowed to hold sockets for a long time is this one. A
     * device that has never been paired still opens its store: it costs one file, and it means the
     * pairing screen has an identity to show without any ceremony first.
     */
    private fun startSync() {
        val hub = runtime.sync ?: dev.curfew.app.data.SyncHub.create(this, deviceName())?.also {
            runtime.attachSync(it)
        } ?: return
        hub.start()
    }

    /** What this device calls itself to its peers. Advisory: nothing is ever decided from it. */
    private fun deviceName(): String = android.os.Build.MODEL ?: "Android"

    /**
     * The heartbeat.
     *
     * A schedule can start or end with no user activity at all, so something has to notice. The
     * alarm set by [ScheduleAlarmReceiver] is the real mechanism — this loop is the belt to its
     * braces, deliberately slow, so that a missed alarm costs at most a minute of enforcement
     * rather than a whole session.
     */
    private suspend fun tick() {
        var previous = 0L
        while (runtime.scope.isActive) {
            // A tick that arrives late is a block that starts late, and until this line there was
            // nothing anywhere that said so: sync once held the session gate across a call measured
            // at fifty-two seconds, and the only symptom was a schedule quietly starting a minute
            // after its time. Warned about rather than counted, because the cause is always
            // something holding the loop up, and the log is where that gets found.
            val woke = android.os.SystemClock.elapsedRealtime()
            if (previous != 0L && woke - previous > POLL_MILLIS * 2) {
                android.util.Log.w(TAG, "enforcement ran ${woke - previous - POLL_MILLIS}ms late")
            }
            previous = woke
            // Trusted time, not the wall clock: a device whose clock was moved forward must not
            // be able to reconcile a lock away, and this loop is the thing that would do it.
            val now = runtime.trustedNow()
            val events = runtime.calendarEvents(now)
            runtime.reconcile(now, events)
            // Written after reconciling, so the recorded time is one Curfew was demonstrably
            // enforcing at, rather than one it merely woke up at.
            runtime.heartbeat(now)
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
        // The other two faces of the same fact. Driven from here rather than left to the system's
        // own widget refresh, which is half-hourly at best: a widget that is thirty minutes stale
        // about whether a block is on is worse than no widget.
        dev.curfew.app.ui.widget.CurfewWidget.refresh(this)
        runCatching { CurfewTileService.refresh(this) }
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
        runtime.sync?.stop()
        screen?.let { runCatching { unregisterReceiver(it) } }
        charge?.cancel()
        watch?.cancel()
        sync?.cancel()
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
        private const val TAG = "Curfew"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MILLIS = 30_000L
        /** How often the app in front is charged for the time it has had. */
        private const val CHARGE_MILLIS = 5_000L
        /** How often peers are talked to. Slower than enforcement: nothing waits on it. */
        private const val SYNC_MILLIS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, EnforcementService::class.java)
            context.startForegroundService(intent)
        }

        /** One enforcer per process, shared by the accessibility service and the poller. */
        @Volatile
        private var shared: Enforcer? = null

        fun enforcer(context: Context): Enforcer =
            shared ?: synchronized(this) {
                shared ?: Enforcer(
                    context.curfew,
                    AndroidActions(context.applicationContext),
                    context.packageName,
                ).also { shared = it }
            }
    }
}

/** The enforcer's hands: what actually happens on the device when a decision comes back. */
class AndroidActions(private val context: Context) : Enforcer.Actions {

    override fun block(target: String, reason: BlockReason) {
        // A background activity start is refused on modern Android unless the app is allowed to
        // draw over other apps, and the refusal is silent from in here. Caught and logged so that
        // a block that never appears leaves a trace pointing at the permission, rather than
        // looking like a scheduling bug.
        runCatching { context.startActivity(BlockActivity.intent(context, target, reason, context.curfew.profileName(reason.profile))) }
            .onFailure { android.util.Log.w("Curfew", "block screen refused for $target", it) }
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
