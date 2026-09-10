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
    /**
     * Curfew's own package, which enforcement always leaves alone. Passed in rather than read from
     * a context so this class stays free of Android types; empty in a test that does not care.
     */
    private val selfPackage: String = "",
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
    private var observation: Observation? = null
    private var charging: List<String> = emptyList()
    private var since: Long = 0

    /**
     * A new foreground app, window or page.
     *
     * Time is charged to the *previous* target on the way out, and by [onTick] while it stays in
     * front. Only the second of those runs on a timer, and it charges nothing once [onIdle] has
     * said the screen is off: a phone in a pocket generates no events at all, and a timer that did
     * not know that would happily charge an hour of screen-off time against a budget.
     */
    suspend fun onObservation(observation: Observation, now: Long) {
        // A notification is not a foreground change: it must neither end the current target's slice
        // nor start one of its own, so it is decided on its own and nothing else moves.
        if (observation is Observation.Notification) {
            if (runtime.decide(observation, now) == Decision.Mute) {
                actions.muteNotification(observation.`package`)
            }
            return
        }
        // Curfew's own screens are never blocked. Otherwise a rule broad enough to catch this
        // package — "block everything", a wildcard, an app list built from the launcher — puts the
        // block screen over the settings that would let the user fix it, and the block screen's own
        // relaunch keeps it there. The lock is still a promise: nothing here ends a session, it
        // only refuses to point the enforcement at the one app that has to stay reachable.
        if (observation is Observation.App && observation.`package` == selfPackage) {
            actions.allow("app:${observation.`package`}")
            return
        }
        val target = identity(observation)
        if (target == null) {
            stop(now)
            return
        }
        if (target != current) {
            flush(now)
            current = target
            this.observation = observation
            since = now
            charging = runtime.policy.chargedKeys(now, observation)
            for (key in charging) runtime.recordLaunch(key, now)
        }
        decide(target, observation, now)
    }

    /**
     * Time passed and nothing changed.
     *
     * The app in front is still in front, so no observation arrives — and until this existed, that
     * was the whole story: the slice was only written when the app was left, and the decision was
     * only made when it was entered, so a budget of fifteen minutes let a single sitting run for
     * as long as it liked and only settled up afterwards. A tick writes what has been spent so far
     * and asks again, which is how a budget runs out *while* the app is open.
     *
     * Nothing is charged with no target, which is what [onIdle] leaves behind: a screen that is
     * off is not a slice of anything.
     */
    suspend fun onTick(now: Long) {
        val target = current ?: return
        val observation = observation ?: return
        flush(now)
        decide(target, observation, now)
    }

    /** The screen went off, or the device idled. Stop charging time to anything. */
    suspend fun onIdle(now: Long) = stop(now)

    private suspend fun stop(now: Long) {
        flush(now)
        current = null
        observation = null
        charging = emptyList()
    }

    private suspend fun decide(target: String, observation: Observation, now: Long) {
        when (val decision = runtime.decide(observation, now)) {
            Decision.Allow -> actions.allow(target)
            is Decision.Block -> {
                // A blocked app must not also accrue time against its own budget: the seconds it
                // spends on screen are seconds of the block screen, not of the app.
                current = null
                this.observation = null
                charging = emptyList()
                actions.block(target, decision.reason)
            }
            is Decision.Delay -> actions.delay(target, decision.seconds)
            Decision.Mute -> actions.muteNotification(target)
        }
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
