package dev.curfew.app.enforce

import dev.curfew.app.data.CurfewRuntime
import dev.curfew.policy.BlockReason
import dev.curfew.policy.Decision
import dev.curfew.policy.Observation

/**
 * What Curfew actually does about a foreground app.
 *
 * The class is deliberately free of Android types so the whole of the enforcement logic — including
 * the time accounting, which is the part most likely to be subtly wrong — can be tested without a
 * device. [Actions] is the seam: the service supplies a real implementation, a test supplies a
 * recording one.
 */
class Enforcer(
    private val runtime: CurfewRuntime,
    private val actions: Actions,
) {
    /** What the enforcer is allowed to do to the world outside it. */
    interface Actions {
        /** Show the block screen for [target], naming the profile that asked for it. */
        fun block(target: String, reason: BlockReason)

        /** Show the "are you sure?" delay screen; the user may still proceed after [seconds]. */
        fun delay(target: String, seconds: Int)

        /** Nothing to do; take down anything currently shown for [target]. */
        fun allow(target: String)

        fun muteNotification(target: String)
    }

    private var current: String? = null
    private var charging: List<String> = emptyList()
    private var since: Long = 0

    /**
     * A new foreground app, window or page.
     *
     * Time is charged to the *previous* target on the way out rather than to the current one on a
     * timer, because a phone in a pocket generates no events at all and a timer would happily
     * charge an hour of screen-off time against a budget.
     */
    suspend fun onObservation(observation: Observation, now: Long) {
        val target = identity(observation)
        if (target == null) {
            flush(now)
            current = null
            charging = emptyList()
            return
        }
        if (target != current) {
            flush(now)
            current = target
            since = now
            charging = runtime.policy.chargedKeys(now, observation)
            for (key in charging) runtime.recordLaunch(key, now)
        }

        when (val decision = runtime.decide(observation, now)) {
            Decision.Allow -> actions.allow(target)
            is Decision.Block -> {
                // A blocked app must not also accrue time against its own budget: the seconds it
                // spends on screen are seconds of the block screen, not of the app.
                current = null
                charging = emptyList()
                actions.block(target, decision.reason)
            }
            is Decision.Delay -> actions.delay(target, decision.seconds)
            Decision.Mute -> actions.muteNotification(target)
        }
    }

    /** The screen went off, or the device idled. Stop charging time to anything. */
    suspend fun onIdle(now: Long) {
        flush(now)
        current = null
        charging = emptyList()
    }

    private suspend fun flush(now: Long) {
        if (current == null) return
        val start = since
        val seconds = (now - start).toInt()
        since = now
        // Guard against a clock that moved backwards (a timezone change, an NTP correction) rather
        // than recording a negative slice, which would hand the user free budget.
        if (seconds <= 0) return
        for (key in charging) runtime.recordUsage(key, start, seconds)
    }

    /**
     * How the enforcer tells one foreground thing from another. This is only used to notice a
     * change; what a budget is charged against comes from the core.
     */
    private fun identity(observation: Observation): String? = when (observation) {
        is Observation.App -> "app:${observation.`package`}"
        is Observation.Web -> "web:${observation.url.host}${observation.url.path}"
        is Observation.Window -> "window:${observation.exe}"
        is Observation.FileOpen -> "file:${observation.path}"
        is Observation.Notification -> null
        Observation.Idle -> null
    }
}
