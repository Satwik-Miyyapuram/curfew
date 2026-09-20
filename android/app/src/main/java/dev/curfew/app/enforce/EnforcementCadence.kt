package dev.curfew.app.enforce

/**
 * How often the enforcement loop wakes, and what it is allowed to do when it does.
 *
 * **Why this is its own object rather than constants on the service.** `EnforcementService` is an Android
 * `Service`, so nothing about it can be tested without Robolectric — which does not run on this host
 * (Conscrypt ships no `windows-aarch_64` build). A policy that lives in a plain object can be tested by the
 * ordinary JUnit suite, and the decision "how often do we wake, and is this tick worth the cost" is exactly
 * the thing that needs a test: it is a claim about battery, and a claim about battery that nothing checks is
 * how the documented strategy and the built one drifted apart in the first place (P1-8).
 *
 * **The documented strategy** is `docs/ARCHITECTURE.md` §10: *"pollers run at 1s while a session is active,
 * 15s idle, and stop entirely when no rule can fire."* Two of those three are built here. The third is not,
 * and the reason is in [POLL_IDLE_MILLIS] and in §10 itself: the heartbeat this loop writes is what
 * Android's downtime report measures against, and the alarm it arms is what starts the *next* scheduled
 * window. A loop that stopped would make the first lie and the second never happen.
 */
object EnforcementCadence {

    /** A session is running: the app in front is being judged, so the answer has to be prompt. */
    const val POLL_ACTIVE_MILLIS = 1_000L

    /**
     * Nothing is running.
     *
     * **Not a stop, and 15 s rather than minutes, for two reasons that are both load-bearing.**
     *
     * 1. `CurfewRuntime.DOWNTIME_SECONDS` is 300, and the heartbeat written here is what the downtime
     *    report measures against. Any interval at or above that would report downtime that did not happen,
     *    on every cycle — the honest-downtime feature would become a liar.
     * 2. `ScheduleAlarmReceiver.scheduleNext` is armed from this loop and nowhere else, so a loop that
     *    stopped would leave the *next* scheduled window with no alarm. A session added by a config edit
     *    would reconcile immediately, and then nothing would wake the loop for the window after it.
     *
     * 15 s is the figure §10 documents and is twenty times under the threshold, which is the margin that
     * makes (1) hold even if a tick runs late.
     */
    const val POLL_IDLE_MILLIS = 15_000L

    /**
     * Work that is incidental to enforcement, and therefore does not need the fast cadence.
     *
     * The heartbeat is a database write, the alarm is an `AlarmManager` call and `updateNotification` is an
     * IPC to the system. Running those at [POLL_ACTIVE_MILLIS] would cost more battery than the faster poll
     * saves — the opposite of what this change is for. At 15 s the heartbeat is still twenty times more
     * frequent than the threshold it feeds.
     */
    const val INCIDENTAL_MILLIS = 15_000L

    /** How often the app in front is charged for the time it has had. */
    const val CHARGE_MILLIS = 5_000L

    /** How often peers are talked to. Slower than enforcement: nothing waits on it. */
    const val SYNC_MILLIS = 60_000L

    /** How long to wait between enforcement passes, given whether anything is being enforced. */
    fun poll(active: Boolean): Long = if (active) POLL_ACTIVE_MILLIS else POLL_IDLE_MILLIS

    /**
     * Whether this tick should also do the incidental work.
     *
     * `sinceIncidental` is milliseconds since the last time it ran; `0` means it has never run, so the
     * first pass always does — the heartbeat has to be written once before anything can measure against it.
     */
    fun incidentalDue(sinceIncidental: Long): Boolean =
        sinceIncidental == 0L || sinceIncidental >= INCIDENTAL_MILLIS

    /**
     * Whether there is anything to charge and anything to look at.
     *
     * **Both the meter and the foreground sample are worthless with no active profile**, and both cost
     * real battery: the meter writes a usage slice, and the sample is a `UsageStatsManager` binder call —
     * the single most expensive thing in the loop.
     *
     * This is safe rather than merely cheap, and the reason is in the core: `engine::decide` iterates
     * `state.active_profiles`, so with none active it returns `Decision::Allow` without reading anything
     * else. There is no rule that can act on a foreground observation when no profile is running, so a
     * sample taken then cannot change an answer — it is a no-op with a battery cost.
     */
    fun enforcementWorkDue(active: Boolean): Boolean = active
}
