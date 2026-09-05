package dev.curfew.app.data

import android.content.Context
import androidx.room.Room
import dev.curfew.app.enforce.CurfewDeviceAdmin
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.Consumption
import dev.curfew.policy.Decision
import dev.curfew.policy.Launches
import dev.curfew.policy.Lock
import dev.curfew.policy.LockSet
import dev.curfew.policy.Observation
import dev.curfew.policy.PassRefusal
import dev.curfew.policy.Passes
import dev.curfew.policy.Policy
import dev.curfew.policy.Rollup
import dev.curfew.policy.Session
import dev.curfew.policy.Sessions
import dev.curfew.policy.UsageState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The object graph, and the only writer of policy state.
 *
 * Every mutation of a session goes through this class, holding [gate], because the invariant that
 * makes Curfew trustworthy — a lock, once taken, is a promise — depends on there being exactly one
 * writer. The policy answers *what*; this class answers *when it is written down*, and the two are
 * kept apart on purpose.
 */
class CurfewRuntime internal constructor(
    private val context: Context,
    val policy: Policy,
    val config: ConfigStore,
    val db: CurfewDatabase,
    val clock: Clock,
) {
    private val gate = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lock = MutableStateFlow(LockSet())

    /** What the UI shows: the strictest lock currently in force, merged across sessions. */
    val lock: StateFlow<LockSet> = _lock.asStateFlow()

    private val _releasable = MutableStateFlow<List<String>>(emptyList())

    /**
     * Sessions whose lock names this device as the one that must let them out.
     *
     * Kept as a flow rather than asked for on demand because it changes when a *peer's* session
     * arrives, not when anything happens here: the screen has to grow a button on its own.
     */
    val releasable: StateFlow<List<String>> = _releasable.asStateFlow()

    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val activeProfiles: StateFlow<List<String>> = _profiles.asStateFlow()

    // --- decisions ---------------------------------------------------------------------------------

    /** What to do about what is in front of the user, given everything already spent today. */
    suspend fun decide(observation: Observation, now: Long = clock.now()): Decision =
        policy.decide(now, observation, usage(now))

    /**
     * Time actually spent, read back from storage.
     *
     * The core is a pure function and holds no history, so every decision that involves a budget
     * has to be handed the history. Only the last day is read: no rule can look further back than a
     * daily refill, so nothing older can change an answer.
     */
    suspend fun usage(now: Long): UsageState {
        val since = now - LOOKBACK_SECONDS
        val usage = db.usage().usageSince(since)
            .groupBy { it.target }
            .mapValues { (_, rows) -> Consumption(rows.map { Rollup(it.at, it.seconds) }) }
        val launches = db.usage().launchesSince(since)
            .groupBy { it.target }
            .mapValues { (_, rows) -> Launches(rows.map { it.at }) }
        return UsageState(usage = usage, launches = launches)
    }

    suspend fun recordUsage(target: String, at: Long, seconds: Int) {
        db.usage().addUsage(UsageRow(target = target, at = at, seconds = seconds))
    }

    suspend fun recordLaunch(target: String, at: Long) {
        db.usage().addLaunch(LaunchRow(target = target, at = at))
    }

    // --- sessions ----------------------------------------------------------------------------------

    /** Bring sessions into line with the schedules, persist, and return the ids that started. */
    suspend fun reconcile(now: Long = clock.now(), events: List<CalendarEvent> = emptyList()): List<String> =
        gate.withLock {
            val started = policy.reconcile(now, events, idSeed = now.toString())
            for (id in started) audit(now, "session.started", id)
            for (session in policy.reap(now)) audit(now, "session.ended", session.id)
            persist(now)
            started
        }

    suspend fun startSession(session: Session) = gate.withLock {
        policy.startSession(session)
        audit(session.startedAt, "session.started", session.id)
        persist(session.startedAt)
    }

    /**
     * End a session. Throws [dev.curfew.policy.Refused] — carrying exactly what the lock still
     * wants — rather than returning a boolean, so no caller can end a session by ignoring a result.
     */
    suspend fun endSession(id: String, satisfied: List<Lock> = emptyList(), now: Long = clock.now()) =
        gate.withLock {
            policy.endSession(id, now, satisfied)
            audit(now, "session.ended", id)
            persist(now)
        }

    /**
     * Note that the device credential prompt has just succeeded for this session.
     *
     * Called from the UI the moment `BiometricPrompt` reports success, separately from ending, so
     * a lock asking for a credential and a tag can be satisfied by doing both a short walk apart.
     * The proof lasts a couple of minutes and never survives a restart.
     */
    suspend fun recordCredential(id: String, now: Long = clock.now()) = gate.withLock {
        policy.recordCredential(id, now)
    }

    /**
     * Present a physical tag, read over NFC or typed in.
     *
     * The payload is fingerprinted inside the core and dropped; it is never stored, never logged
     * and never audited, because an audit row holding the tag would be the tag. Throws
     * [dev.curfew.policy.Refused] when the lock still wants something — including when the tag is
     * simply not the one it asked for, which is deliberately indistinguishable from a stranger.
     */
    suspend fun scanToken(id: String, payload: String, now: Long = clock.now()) = gate.withLock {
        policy.scanToken(id, payload, now)
        audit(now, "session.ended", id)
        persist(now)
    }

    /**
     * Give the release a peer's lock is waiting on this device for.
     *
     * Recorded even for a session this device has never heard of: the lock it opens is usually on
     * the other device. It cannot be taken back, so the UI asks first.
     */
    suspend fun releasePeer(id: String, now: Long = clock.now()) = gate.withLock {
        policy.releasePeer(id, now)
        audit(now, "release.given", id)
        db.state().put(StateRow(KEY_RELEASES, policy.releasesJson()))
        persist(now)
    }

    /** Which of a session's conditions are already proved, so the UI stops asking for them. */
    fun proven(id: String, now: Long = clock.now()): List<Lock> = policy.proven(id, now)

    suspend fun requestRelease(id: String, now: Long = clock.now()): Long = gate.withLock {
        val at = policy.requestRelease(id, now)
        audit(now, "release.requested", "$id at $at")
        persist(now)
        at
    }

    /**
     * End a session by spending an emergency pass.
     *
     * Throws [dev.curfew.policy.NoPass] when the ration says no. The pass is written down before
     * the session is ended and persisted immediately afterwards either way, because a pass this
     * device forgot is a pass the user gets back for free.
     */
    suspend fun spendPass(id: String, now: Long = clock.now()) = gate.withLock {
        try {
            policy.spendPass(id, now)
            audit(now, "pass.spent", id)
        } finally {
            persist(now)
        }
    }

    /** How many emergency passes are left, and why there are none when there are none. */
    fun passesRemaining(now: Long = clock.now()): Int = policy.passesRemaining(now)

    fun passRefusal(now: Long = clock.now()): PassRefusal? = policy.passRefusal(now)

    /** Replace the config. Running sessions are untouched — a settings edit is not a way out. */
    suspend fun setConfig(toml: String): Result<Unit> = gate.withLock {
        config.write(toml).onSuccess {
            policy.setConfig(toml)
            audit(clock.now(), "config.replaced", "")
            refresh(clock.now())
        }
    }

    suspend fun nextChange(now: Long = clock.now(), events: List<CalendarEvent> = emptyList()): Long? =
        policy.nextChangeAfter(now, events)

    // --- calendar ----------------------------------------------------------------------------------

    private val calendar = CalendarReader(context)

    /**
     * Events the other devices' calendars hold, from the last sync pass.
     *
     * Held here rather than fetched, because sync runs after enforcement: a meeting only the PC can
     * see reaches this device's rules on the next tick, which is the latency everything else in the
     * mirror has. It is deliberately not persisted — a peer's calendar is derived state, and after
     * a restart the peer will say it again.
     */
    @Volatile
    private var peerEvents: List<CalendarEvent> = emptyList()

    /** What this device's own calendar provider says. Empty without the permission. */
    fun localCalendarEvents(now: Long = clock.now()): List<CalendarEvent> =
        calendar.events(now - CalendarReader.WINDOW_SECONDS, now + CalendarReader.WINDOW_SECONDS)

    /**
     * Every event a calendar rule could be looking at right now: this device's own, plus the ones
     * its paired devices published. A phone that was never given calendar permission is still
     * blocked during a meeting the PC can see.
     */
    fun calendarEvents(now: Long = clock.now()): List<CalendarEvent> =
        (localCalendarEvents(now) + peerEvents).sortedWith(compareBy({ it.start }, { it.id }))

    // --- sync --------------------------------------------------------------------------------------

    private var hub: SyncHub? = null

    /** Sync, once it has been opened. Null on a device where opening the store failed. */
    val sync: SyncHub? get() = hub

    fun attachSync(hub: SyncHub) {
        this.hub = hub
    }

    /**
     * One sync pass: publish what this device is enforcing, adopt what the others are, and store
     * the budgets the log says are now shared.
     *
     * Held under [gate] like every other session write, because adoption starts sessions: a peer's
     * news arriving is a change to what is running here, and there is exactly one writer of that.
     *
     * The budgets come back and are written as they are. Rows are keyed by target and instant, so a
     * slice this device already had is replaced by an identical one and a slice that happened on
     * the PC is added once — an hour spent on one device cannot be spent again on the other.
     */
    suspend fun syncPass(now: Long = clock.now()): dev.curfew.policy.Pass? {
        val hub = hub ?: return null
        val before = usage(now)
        // Only this device's own events are published; the peers' are merged in for enforcement
        // only, so a calendar cannot be echoed back and forth between two devices.
        val seen = runCatching { localCalendarEvents(now) }.getOrDefault(emptyList())
        val pass = gate.withLock {
            val pass = runCatching {
                hub.sync.pass(policy, now, before.usage, before.launches, seen)
            }.getOrElse {
                audit(now, "sync.failed", it.message.orEmpty())
                return@withLock null
            }
            for ((target, spent) in pass.usage) {
                for (rollup in spent.rollups) {
                    db.usage().addUsage(UsageRow(target = target, at = rollup.at, seconds = rollup.seconds))
                }
            }
            for ((target, opened) in pass.launches) {
                for (at in opened.opens) db.usage().addLaunch(LaunchRow(target = target, at = at))
            }
            for (id in pass.adopted) audit(now, "sync.adopted", id)
            for (id in pass.stillLocked) audit(now, "sync.refused", id)
            persist(now)
            pass
        } ?: return null
        peerEvents = pass.calendar
        hub.record(pass)
        if (pass.published > 0) {
            hub.push(now)
            hub.save()
        }
        return pass
    }

    // --- persistence -------------------------------------------------------------------------------

    /** Read sessions back after a reboot, a force-stop or an update. */
    suspend fun restore(now: Long = clock.now()) = gate.withLock {
        db.state().get(KEY_SESSIONS)?.let { policy.restoreSessions(Policy.json.decodeFromString(it)) }
        // Merged, not replaced, and restored before anything can be spent: a ration a restart
        // forgot would be a week's worth of escape hatches for the price of a force-stop.
        db.state().get(KEY_PASSES)?.let {
            runCatching { policy.restorePasses(Policy.json.decodeFromString(it)) }
        }
        // The clock witness is restored before anything is judged: a restart that reset the
        // baseline would hand an attacker exactly what moving the clock was meant to buy.
        db.state().get(KEY_CLOCK)?.let { runCatching { policy.restoreClock(it) } }
        // Which boot each running session was first seen in. Without it a relaunch would look like
        // the restart a lock asked for, and force-stopping the app would open every one of them.
        db.state().get(KEY_BOOTS)?.let { runCatching { policy.restoreBoots(it) } }
        db.state().get(KEY_RELEASES)?.let {
            runCatching { policy.restoreReleases(it) }
        }
        detectDowntime(now)
        refresh(now)
    }

    // --- trusted time ------------------------------------------------------------------------------

    private val _clockTamper = MutableStateFlow<ClockTamper?>(null)

    /**
     * The last attempt to move the clock while Curfew was running, if there was one.
     *
     * Distinct from [downtime]: a gap means nothing was enforced, whereas this means enforcement
     * was working and refused what it was told. It is worth saying out loud, because the honest
     * answer to "why is my lock still on?" is that the device was asked to lie about the time.
     */
    val clockTamper: StateFlow<ClockTamper?> = _clockTamper.asStateFlow()

    suspend fun acknowledgeClockTamper() {
        _clockTamper.value = null
    }

    /**
     * Read both device clocks, decide what the reading is worth, and return the time everything
     * else should use.
     *
     * Every caller that would otherwise reach for the wall clock goes through here. The wall clock
     * is a code path like any other, and invariant 2 says no code path may shorten a lock.
     */
    suspend fun trustedNow(): Long {
        val verdict = policy.observeClock(clock.now(), clock.uptime(), clock.bootId())
        // Taken from the same reading, and from uptime rather than the wall clock: uptime can only
        // go backwards by rebooting, so a clock moved forward in Settings cannot be dressed up as
        // the restart a lock asked for.
        policy.observeBoot(clock.uptime())
        policy.clockWitness()?.let { db.state().put(StateRow(KEY_CLOCK, it)) }
        db.state().put(StateRow(KEY_BOOTS, policy.boots()))
        if (verdict.tampered) {
            _clockTamper.value = ClockTamper(
                at = verdict.now,
                forwardSeconds = verdict.refusedForward,
                backwardSeconds = verdict.refusedBackward,
            )
            val direction =
                if (verdict.refusedForward > 0) "forward ${verdict.refusedForward}s"
                else "back ${verdict.refusedBackward}s"
            audit(verdict.now, "enforcement.clock", "refused $direction")
        }
        return verdict.now
    }

    // --- downtime ----------------------------------------------------------------------------------

    private val _downtime = MutableStateFlow<Downtime?>(null)

    /**
     * A stretch during which Curfew was not running, or the clock jumped, if there was one.
     *
     * Curfew cannot stop an OEM battery manager from killing it, and it will not pretend the gap
     * did not happen: a blocker that silently stops enforcing is worse than one that says so. The
     * banner is the honest version of "it was off between these times", and it is the user's to
     * dismiss with [acknowledgeDowntime].
     */
    val downtime: StateFlow<Downtime?> = _downtime.asStateFlow()

    /**
     * Note that enforcement is alive at [now].
     *
     * Called from the service loop, so the recorded time is never more than one tick behind. The
     * write is deliberately cheap — one row, one number — because it happens forever.
     */
    suspend fun heartbeat(now: Long = clock.now()) {
        db.state().put(StateRow(KEY_HEARTBEAT, now.toString()))
    }

    suspend fun acknowledgeDowntime() {
        _downtime.value = null
        db.state().put(StateRow(KEY_HEARTBEAT, clock.now().toString()))
    }

    /**
     * Compare the last heartbeat with the current time.
     *
     * Two different things show up here. A last heartbeat well in the past means the process was
     * not running — killed, force-stopped, or the device was off. A last heartbeat in the *future*
     * means the clock moved backwards, which is the oldest way to try to cheat a timed lock; the
     * core is not fooled by it, because a session's end is stored as an instant, but the user is
     * still owed the information.
     */
    private suspend fun detectDowntime(now: Long) {
        val last = db.state().get(KEY_HEARTBEAT)?.toLongOrNull()
        db.state().put(StateRow(KEY_HEARTBEAT, now.toString()))
        if (last == null) return
        val gap = now - last
        when {
            gap < -CLOCK_SKEW_SECONDS -> {
                _downtime.value = Downtime(from = last, to = now, backwards = true)
                audit(now, "enforcement.clock", "moved back ${-gap}s")
            }
            gap > DOWNTIME_SECONDS -> {
                _downtime.value = Downtime(from = last, to = now, backwards = false)
                audit(now, "enforcement.gap", "${gap}s")
            }
        }
    }

    private suspend fun persist(now: Long) {
        db.state().put(StateRow(KEY_SESSIONS, Policy.json.encodeToString(Sessions.serializer(), policy.sessions())))
        db.state().put(StateRow(KEY_PASSES, Policy.json.encodeToString(Passes.serializer(), policy.passes())))
        db.state().put(StateRow(KEY_BOOTS, policy.boots()))
        refresh(now)
    }

    private fun refresh(now: Long) {
        _lock.value = policy.mergedLock(now)
        _profiles.value = policy.activeProfiles(now)
        _releasable.value = policy.releasable()
        // The device-admin receiver has to answer the deactivation prompt synchronously, so what it
        // needs to know is written down here rather than looked up there.
        runCatching {
            CurfewDeviceAdmin.setLockHeld(context, policy.sessions().running.isNotEmpty())
        }
    }

    private suspend fun audit(at: Long, kind: String, detail: String) {
        db.audit().add(AuditRow(at = at, kind = kind, detail = detail))
    }

    /** Drop history no rule can reach any more. Called from the daily maintenance worker. */
    suspend fun prune(now: Long = clock.now()) {
        db.usage().pruneUsage(now - LOOKBACK_SECONDS)
        db.usage().pruneLaunches(now - LOOKBACK_SECONDS)
        db.audit().prune(now - AUDIT_RETENTION_SECONDS)
    }

    companion object {
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_PASSES = "passes"
        private const val KEY_HEARTBEAT = "heartbeat"
        private const val KEY_CLOCK = "clock_witness"
        private const val KEY_BOOTS = "boots"
        private const val KEY_RELEASES = "releases"

        /**
         * How long a silence has to be before it is worth reporting. The service ticks every
         * thirty seconds, so anything under a few minutes is a slow device or a doze window, not
         * a killed process, and saying so would only teach the user to ignore the banner.
         */
        const val DOWNTIME_SECONDS = 5L * 60

        /** A minute of backwards drift is NTP correcting itself, not someone winding the clock back. */
        const val CLOCK_SKEW_SECONDS = 60L

        /** A day plus an hour of slack, so a daily refill boundary is never read from an empty table. */
        const val LOOKBACK_SECONDS = 25L * 60 * 60

        /** Thirty days of history is enough to answer "what did it do to me?" and no more. */
        const val AUDIT_RETENTION_SECONDS = 30L * 24 * 60 * 60

        /**
         * The real runtime, with the encrypted database.
         *
         * Tests construct the class directly with an in-memory database instead: SQLCipher is a
         * native library that is not present on a JVM test run, and there is nothing about
         * encryption-at-rest for the policy logic to get wrong.
         */
        fun create(context: Context, clock: Clock = Clock.System): CurfewRuntime {
            val config = ConfigStore(File(context.filesDir, "curfew.toml"))
            val db = Room.databaseBuilder(context, CurfewDatabase::class.java, "curfew.db")
                .openHelperFactory(SupportOpenHelperFactory(DatabaseKey.passphrase(context)))
                .build()
            // A config that fails to load is a bug in a previous write, not a reason to run with no
            // policy at all: fall back to the empty config so the app still opens and can be fixed.
            val policy = runCatching { Policy.load(config.read()) }
                .getOrElse { Policy.load(ConfigStore(File(context.filesDir, "unused")).read()) }
            return CurfewRuntime(context.applicationContext, policy, config, db, clock)
        }
    }
}

/** Wall-clock time, injected so tests can talk about a Tuesday morning without waiting for one. */
fun interface Clock {
    fun now(): Long

    /**
     * Seconds since boot, from a source the user cannot set.
     *
     * This is what makes a timer lock hold: a wall clock that runs ahead of uptime within one boot
     * is demonstrably wrong by the difference, and the difference is refused. The default tracks
     * [now] exactly, which is the honest device a test means when it says nothing about uptime.
     */
    fun uptime(): Long = now()

    /**
     * A value that changes on every restart, so a reboot is never read as uptime running backwards
     * and a restart never looks like tampering. Zero is a fine default: a device whose uptime went
     * down has plainly rebooted whatever its boot id says.
     */
    fun bootId(): Long = 0

    object System : Clock {
        override fun now(): Long = java.lang.System.currentTimeMillis() / 1000

        override fun uptime(): Long = android.os.SystemClock.elapsedRealtime() / 1000
    }
}
