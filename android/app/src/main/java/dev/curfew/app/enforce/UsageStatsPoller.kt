package dev.curfew.app.enforce

import android.app.AppOpsManager
import android.app.usage.UsageEvents
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
 *
 * **This used to report an app the user had already left, for up to two minutes.** It read a
 * two-minute window of events, kept the last `MOVE_TO_FOREGROUND`, and called that "what is in
 * front". That is a log, not a state: pressing Home frequently emits no resume event for the
 * launcher at all, so the stale resume won. During a block that meant the block screen kept being
 * shown over the launcher and budget time kept being charged to an app that was not on screen.
 *
 * Three things fix it, and they are separable:
 *
 *  1. **Both edges.** A resume sets the candidate; a pause or stop *for that same package* clears it.
 *  2. **A watermark.** Each poll asks only for events since the last one it saw, instead of
 *     re-parsing 120 s of history every second. The service's own comment calls `queryEvents` the
 *     single most expensive thing in its loop, and this was the reason.
 *  3. **A fallback to the wide window.** A watermark that under-reads would *lose* a foreground
 *     change, and a missed block is worse than a late one — so the wide window is kept, and used
 *     whenever the incremental answer could be incomplete. See [queryFrom].
 *
 * Stateful now, and deliberately: the watermark and the carried candidate are the whole point. One
 * instance per process is what `EnforcementService` holds; constructing a second would restart the
 * watermark and re-read the wide window once, which is merely slower and not wrong.
 */
class UsageStatsPoller(private val context: Context) {

    /** The fold, which holds the foreground app and the watermark. See [UsageEventFold]. */
    private val fold = UsageEventFold()

    /** The app in the foreground, or null when nothing is or the permission is missing. */
    fun sample(now: Long): Observation? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null

        val to = now * 1000
        fold.accept(read(manager.queryEvents(queryFrom(to), to)))

        return fold.foreground
            ?.takeIf { it != context.packageName }
            ?.let { Observation.App(it) }
    }

    /**
     * Translate the platform's events into the two edges the fold understands.
     *
     * The event's own `eventType` integer is used rather than the named constants, because
     * `ACTIVITY_RESUMED` and `MOVE_TO_FOREGROUND` are the same value on API 29 and later and different
     * names for it in the SDK — comparing names would need an API branch for no gain. Paused and
     * stopped are kept apart from each other only so the fold reads as what it means; both clear the
     * foreground app.
     */
    private fun read(events: UsageEvents): List<UsageStep> {
        val step = UsageEvents.Event()
        val steps = ArrayList<UsageStep>()
        while (events.hasNextEvent()) {
            events.getNextEvent(step)
            steps += UsageStep(
                atMillis = step.timeStamp,
                kind = when (step.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> UsageStep.Kind.Resumed
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> UsageStep.Kind.Paused
                    UsageEvents.Event.ACTIVITY_STOPPED -> UsageStep.Kind.Stopped
                    else -> UsageStep.Kind.Other
                },
                packageName = step.packageName.orEmpty(),
            )
        }
        return steps
    }

    /** Forget everything: a restart or a permission change means the old watermark means nothing. */
    fun reset() {
        fold.reset()
    }

    /**
     * How far back to ask, in milliseconds.
     *
     * The watermark, when it is recent enough to be trusted — and the wide window when it is not. The
     * condition that matters is the doze gap: a poll 120 s or more after the last one has a watermark
     * older than any window it could have covered, and using it would silently skip whatever happened
     * in between. That is the case this fallback exists for, and it is why the wide window is still
     * here rather than deleted along with the re-reading.
     *
     * A watermark in the future (a clock correction) is treated the same way: read wide rather than
     * trust it.
     */
    private fun queryFrom(to: Long): Long {
        val wide = to - WINDOW_MILLIS
        val watermark = fold.newestTimestamp
        if (watermark <= 0L) return wide
        if (watermark > to) return wide
        // One millisecond past the last event seen, so an event exactly on the watermark is not read
        // twice. Harmless when it is, but it is the difference between a watermark and a re-read.
        return maxOf(wide, watermark + 1)
    }

    companion object {
        /**
         * How wide the fallback window is, and therefore how much history a cold poll reads.
         *
         * Wide enough to survive a doze-delayed poll, narrow enough that a long-closed app is never
         * reported as being in front. Unchanged from when it was the *only* window: it is now the
         * recovery case rather than the steady state.
         */
        private const val WINDOW_MILLIS = 120_000L

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
