package dev.curfew.app.data

import android.content.Context
import androidx.room.Room
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.Consumption
import dev.curfew.policy.Decision
import dev.curfew.policy.Launches
import dev.curfew.policy.Lock
import dev.curfew.policy.LockSet
import dev.curfew.policy.Observation
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

    suspend fun requestRelease(id: String, now: Long = clock.now()): Long = gate.withLock {
        val at = policy.requestRelease(id, now)
        audit(now, "release.requested", "$id at $at")
        persist(now)
        at
    }

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

    /** The events a calendar rule could be looking at right now. Empty without the permission. */
    fun calendarEvents(now: Long = clock.now()): List<CalendarEvent> =
        calendar.events(now - CalendarReader.WINDOW_SECONDS, now + CalendarReader.WINDOW_SECONDS)

    // --- persistence -------------------------------------------------------------------------------

    /** Read sessions back after a reboot, a force-stop or an update. */
    suspend fun restore(now: Long = clock.now()) = gate.withLock {
        db.state().get(KEY_SESSIONS)?.let { policy.restoreSessions(Policy.json.decodeFromString(it)) }
        refresh(now)
    }

    private suspend fun persist(now: Long) {
        db.state().put(StateRow(KEY_SESSIONS, Policy.json.encodeToString(Sessions.serializer(), policy.sessions())))
        refresh(now)
    }

    private fun refresh(now: Long) {
        _lock.value = policy.mergedLock(now)
        _profiles.value = policy.activeProfiles(now)
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

    object System : Clock {
        override fun now(): Long = java.lang.System.currentTimeMillis() / 1000
    }
}
