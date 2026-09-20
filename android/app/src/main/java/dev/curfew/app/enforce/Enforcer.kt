package dev.curfew.app.enforce

import dev.curfew.app.data.CurfewRuntime
import dev.curfew.policy.BlockReason
import dev.curfew.policy.Decision
import dev.curfew.policy.Observation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Curfew actually does about a foreground app.
 *
 * The class is deliberately free of Android types so the whole of the enforcement logic — including
 * the time accounting, which is the part most likely to be subtly wrong — can be tested without a
 * device. [Actions] is the seam: the service supplies a real implementation, a test supplies a
 * recording one.
 *
 * **Every entry point runs on one lane, in order.** The fields below are plain `var`s and [flush]
 * suspends at the point it writes, so two calls interleaving would drop a slice, charge one twice,
 * charge it to the wrong target, or leave [since] pointing at the wrong instant — in the part of the
 * app that decides whether a budget is spent. That was possible: the accessibility service, the
 * charge loop and the screen-off receiver each `launch`ed into a `Dispatchers.Default` scope, which
 * is a pool of threads, and `launch` gives no ordering between them either — so a stale observation
 * could even be applied after a newer one.
 *
 * The tests drive this class sequentially, which is exactly why the race never showed there. Rather
 * than trust every caller to be careful, the confinement is here: [lane] is a single-parallelism
 * view of the default dispatcher, and every public entry point hops onto it. A channel would also
 * give ordering, and would be the next step if the work ever needs to be dropped rather than queued.
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

        /**
         * Leave the page the user is on, without closing the app.
         *
         * Only ever asked for a web target, and only in addition to [block]: the block screen says
         * what happened, and this is what stops it having to be shown over a browser that is still
         * sitting on the blocked page. It exists here rather than in the caller because the caller
         * peeking at a decision was the reason one browser event used to run the whole policy three
         * times — the decision-maker now tells the actor what to do, once.
         */
        fun navigateBack(target: String)
    }

    /**
     * The one lane every mutation of this object happens on.
     *
     * `limitedParallelism(1)` rather than a private thread: the work is mostly suspension on the
     * runtime's own locks, and a dedicated thread would only add a context switch. What matters is
     * that no two of these can be inside the fields below at once, and that they arrive in the order
     * they were submitted.
     */
    private val lane = Dispatchers.Default.limitedParallelism(1)

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
     *
     * Runs on [lane]; see the class comment.
     */
    suspend fun onObservation(observation: Observation, now: Long) =
        withContext(lane) { onObservationLocked(observation, now) }

    private suspend fun onObservationLocked(observation: Observation, now: Long) {
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
        val decision = runtime.decide(observation, now)
        if (decision is Decision.Block) {
            if (target != current) {
                flush(now)
                current = target
                this.observation = observation
                since = now
                charging = emptyList()
            }
            act(target, decision.reason)
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
     *
     * Runs on [lane]; see the class comment. This one matters most: a tick that interleaved with an
     * observation could charge the same seconds twice, or charge them to the app the user has just
     * left.
     */
    suspend fun onTick(now: Long) = withContext(lane) {
        val target = current ?: return@withContext
        val observation = observation ?: return@withContext
        flush(now)
        decide(target, observation, now)
    }

    /**
     * The screen went off, or the device idled. Stop charging time to anything.
     *
     * Runs on [lane]; see the class comment.
     */
    suspend fun onIdle(now: Long) = withContext(lane) { stop(now) }

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
                charging = emptyList()
                act(target, decision.reason)
            }
            is Decision.Delay -> actions.delay(target, decision.seconds)
            Decision.Mute -> actions.muteNotification(target)
        }
    }

    /**
     * What a block means, in one place.
     *
     * There are two callers — the observation path, which returns as soon as it has blocked, and the
     * tick path — and inlining `actions.block` in both is exactly how the two would come to
     * disagree. A blocked *page* is left as well as reported: the block screen has its own button
     * for that, but a browser sitting on the blocked page behind a "back to home" prompt is a worse
     * answer than going back to what the user was reading.
     */
    private fun act(target: String, reason: BlockReason) {
        actions.block(target, reason)
        if (target.startsWith("web:")) actions.navigateBack(target)
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
