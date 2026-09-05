package dev.curfew.policy

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.builtins.ListSerializer
import uniffi.curfew_ffi.Curfew
import uniffi.curfew_ffi.CurfewException
import uniffi.curfew_ffi.PlatformName
import uniffi.curfew_ffi.blockedApps as ffiBlockedApps
import uniffi.curfew_ffi.checkConfig
import uniffi.curfew_ffi.profilesJson as ffiProfilesJson
import uniffi.curfew_ffi.setBlockedApps as ffiSetBlockedApps

/**
 * The Kotlin face of the shared policy core.
 *
 * Everything on the Android side goes through this class and nothing reimplements a policy
 * decision. It is a thin translation layer on purpose: the JSON shapes below mirror the Rust
 * types exactly, and where Kotlin has to name something the core already named, it uses the same
 * word. If a question can be answered by the core, this class asks it rather than answering it.
 */
class Policy private constructor(private val inner: Curfew) {

    companion object {
        // The core tags most of its enums with `kind`; the three that use a different word say so
        // with @JsonClassDiscriminator below. Unknown keys are an error on purpose: a field this
        // side does not recognise means Kotlin and Rust have drifted, and silence would hide it.
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            classDiscriminator = "kind"
        }

        /** Load a config, or fail with the message the core wrote for the person who typed it. */
        fun load(configToml: String): Policy = Policy(Curfew(configToml))

        /**
         * Validate a config without loading it, for the import screen. Returns the core's summary,
         * or null with the error in [onError].
         */
        fun check(configToml: String): Result<String> = runCatching { checkConfig(configToml) }

        /**
         * Rewrite a config so [profile] blocks exactly [packages].
         *
         * The editing is the core's, not the UI's: a config is one document with one meaning, and
         * two platforms each writing rules their own way is how that stops being true. Rules the
         * picker does not own — a budget on an app, anything platform-specific — are left alone.
         */
        fun setBlockedApps(configToml: String, profile: String, packages: List<String>): Result<String> =
            runCatching { ffiSetBlockedApps(configToml, profile, packages) }

        /** The profiles a config defines, for a picker's profile chooser. */
        fun profiles(configToml: String): List<ProfileName> =
            runCatching {
                json.decodeFromString(ListSerializer(ProfileName.serializer()), ffiProfilesJson(configToml))
            }.getOrDefault(emptyList())

        /** The packages a picker should open with ticked. */
        fun blockedApps(configToml: String, profile: String): List<String> =
            runCatching { ffiBlockedApps(configToml, profile) }.getOrDefault(emptyList())
    }

    /** The config as the core would write it back: canonical, and safe to diff. */
    /**
     * The core object itself, for [Sync], which hands it to the mirror.
     *
     * Deliberately the only way out of this class. Sync needs the core to be able to *start* a
     * session a peer began, and it must not be able to reach in and write a weaker lock: handing
     * over the object rather than the sessions keeps every change on the far side going through
     * the same door a button press does.
     */
    internal fun core(): Curfew = inner

    fun configToml(): String = inner.configToml()

    /**
     * Replace the config. Running sessions are deliberately untouched — a settings edit is not a
     * way out of a lock (design invariant 2).
     */
    fun setConfig(configToml: String) = inner.setConfig(configToml)

    /** What to do about what the user is looking at. */
    fun decide(now: Long, observation: Observation, usage: UsageState = UsageState()): Decision {
        val out = inner.decide(
            now,
            json.encodeToString(observation),
            PlatformName.ANDROID,
            json.encodeToString(usage),
        )
        return json.decodeFromString(out)
    }

    /**
     * The usage keys this observation should be charged against.
     *
     * Time and launches are metered per rule target, and only the core knows which target an
     * observation falls under — a visit to `old.reddit.com` belongs to a rule on `reddit.com`.
     * Guessing that here is how a budget silently stops counting, so it is asked rather than
     * guessed.
     */
    fun chargedKeys(now: Long, observation: Observation): List<String> =
        inner.chargedKeys(now, json.encodeToString(observation), PlatformName.ANDROID)

    fun startSession(session: Session) = inner.startSession(json.encodeToString(session))

    /**
     * End a session, given whatever the platform managed to prove. Throws [Refused] carrying the
     * core's own refusal when the lock is not satisfied, so the UI can ask for exactly what is
     * missing rather than saying "no".
     */
    fun endSession(id: String, now: Long, satisfied: List<Lock> = emptyList()) {
        try {
            inner.endSession(id, now, json.encodeToString(satisfied))
        } catch (e: CurfewException.Refused) {
            throw Refused(json.decodeFromString<Refusal>(e.refusal))
        }
    }

    /** Start the 24-hour delayed release, returning the instant it lands. Never movable later. */
    fun requestRelease(id: String, now: Long): Long =
        try {
            inner.requestRelease(id, now)
        } catch (e: CurfewException.Refused) {
            throw Refused(json.decodeFromString<Refusal>(e.refusal))
        }

    /** Bring sessions into line with the schedules. Returns the ids of sessions it started. */
    fun reconcile(now: Long, events: List<CalendarEvent>, idSeed: String): List<String> =
        inner.reconcile(now, json.encodeToString(events), idSeed)

    /** Sessions whose time is up, removed and returned so the caller can log and notify. */
    fun reap(now: Long): List<Session> = json.decodeFromString(inner.reap(now))

    fun sessions(): Sessions = json.decodeFromString(inner.sessionsJson())

    /** Restore sessions read back from storage. A lock survives a reboot; this is how. */
    fun restoreSessions(sessions: Sessions) =
        inner.restoreSessions(json.encodeToString(sessions))


    // --- trusted time ---------------------------------------------------------------------------

    /**
     * Judge a reading of the device's two clocks. The returned [ClockVerdict.now] is the time every
     * later decision should use: a wall clock moved forward while the device was running buys no
     * time, because uptime cannot be set and is what the verdict is measured against.
     */
    fun observeClock(wall: Long, uptime: Long, bootId: Long): ClockVerdict =
        json.decodeFromString(inner.observeClock(wall, uptime, bootId.toULong()))

    /** The current trusted time, or null before the first reading. */
    fun trustedNow(): Long? = inner.trustedNow()

    /** The witness as JSON, to be written down: a restart must not reset the baseline. */
    fun clockWitness(): String? = inner.clockWitnessJson()

    /** Restore a witness written by [clockWitness]. */
    fun restoreClock(witnessJson: String) = inner.restoreClock(witnessJson)

    fun activeProfiles(now: Long): List<String> = inner.activeProfiles(now)

    fun mergedLock(now: Long): LockSet = json.decodeFromString(inner.mergedLockJson(now))

    fun activations(now: Long, events: List<CalendarEvent>): List<Activation> =
        json.decodeFromString(inner.activationsJson(now, json.encodeToString(events)))

    /**
     * When the schedules could next change, so the service can set one alarm instead of polling.
     * Polling is what doze punishes, and a blocker that doze kills is not a blocker.
     */
    fun nextChangeAfter(now: Long, events: List<CalendarEvent>): Long? =
        inner.nextChangeAfter(now, json.encodeToString(events))
}

/** Thrown when the core refuses to end a session, carrying the reason it gave. */
class Refused(val refusal: Refusal) : Exception(refusal.toString())

// --- the wire shapes -----------------------------------------------------------------------------
//
// These mirror the Rust types exactly. Adding a variant on one side without the other is a
// deserialization failure in the boundary tests, which is precisely the drift we want to be loud.

@Serializable
sealed interface Observation {
    @Serializable
    @SerialName("app")
    data class App(val `package`: String, val screen: String? = null) : Observation

    @Serializable
    @SerialName("window")
    data class Window(val exe: String, val title: String) : Observation

    @Serializable
    @SerialName("web")
    data class Web(val url: Url) : Observation

    @Serializable
    @SerialName("notification")
    data class Notification(val `package`: String, val title: String) : Observation

    @Serializable
    @SerialName("file_open")
    data class FileOpen(val path: String) : Observation

    @Serializable
    @SerialName("idle")
    data object Idle : Observation
}

@Serializable
data class Url(val raw: String, val host: String, val path: String, val query: String) {
    companion object {
        /**
         * Split a URL the way the core does. Kept in step with `Url::parse` in Rust and checked
         * against it in [dev.curfew.policy] tests, because a browser hands us a raw string and
         * something has to do this on the way in.
         */
        fun parse(raw: String): Url {
            val trimmed = raw.trim()
            val afterScheme = trimmed.substringAfter("://", trimmed)
            val cut = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val authority = if (cut < 0) afterScheme else afterScheme.substring(0, cut)
            val rest = if (cut < 0) "" else afterScheme.substring(cut)
            val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
            val withoutFragment = rest.substringBefore('#')
            val path = withoutFragment.substringBefore('?')
            val query = withoutFragment.substringAfter('?', "")
            return Url(raw = trimmed, host = host, path = path, query = query)
        }
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("decision")
sealed interface Decision {
    @Serializable
    @SerialName("allow")
    data object Allow : Decision

    @Serializable
    @SerialName("block")
    data class Block(val reason: BlockReason) : Decision

    @Serializable
    @SerialName("delay")
    data class Delay(val seconds: Int) : Decision

    @Serializable
    @SerialName("mute")
    data object Mute : Decision
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("reason")
sealed interface BlockReason {
    val profile: String

    @Serializable
    @SerialName("blocked")
    data class Blocked(override val profile: String) : BlockReason

    @Serializable
    @SerialName("not_allowlisted")
    data class NotAllowlisted(override val profile: String) : BlockReason

    @Serializable
    @SerialName("budget_exhausted")
    data class BudgetExhausted(override val profile: String, val seconds: Int) : BlockReason

    @Serializable
    @SerialName("launch_limit_reached")
    data class LaunchLimitReached(override val profile: String, val count: Int) : BlockReason
}

@Serializable
sealed interface Lock {
    @Serializable @SerialName("timer") data object Timer : Lock

    @Serializable @SerialName("confirm") data object Confirm : Lock

    @Serializable @SerialName("device_credential") data object DeviceCredential : Lock

    @Serializable
    @SerialName("challenge")
    data class Challenge(val challenge: ChallengeKind) : Lock

    @Serializable @SerialName("peer_release") data class PeerRelease(val deviceId: String) : Lock

    @Serializable @SerialName("token") data class Token(val id: String) : Lock

    @Serializable @SerialName("restart_required") data object RestartRequired : Lock
}

@Serializable
enum class ChallengeKind {
    @SerialName("typing") TYPING,

    @SerialName("math") MATH,
}

@Serializable
data class LockSet(
    val conditions: List<Lock> = emptyList(),
    @SerialName("ends_at") val endsAt: Long? = null,
    @SerialName("delayed_release_at") val delayedReleaseAt: Long? = null,
) {
    val isLocked: Boolean get() = conditions.isNotEmpty()
}

@Serializable
sealed interface SessionSource {
    @Serializable @SerialName("manual") data object Manual : SessionSource

    @Serializable @SerialName("weekly") data class Weekly(val schedule: String) : SessionSource

    @Serializable
    @SerialName("calendar")
    data class Calendar(val schedule: String, val event: String) : SessionSource
}

@Serializable
data class Session(
    val id: String,
    val profile: String,
    val source: SessionSource,
    @SerialName("started_at") val startedAt: Long,
    val lock: LockSet,
)

@Serializable
data class Sessions(val running: List<Session> = emptyList())

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("refusal")
sealed interface Refusal {
    @Serializable @SerialName("not_running") data object NotRunning : Refusal

    @Serializable
    @SerialName("locked")
    data class Locked(
        val missing: List<Lock>,
        @SerialName("ends_at") val endsAt: Long? = null,
        @SerialName("delayed_release_at") val delayedReleaseAt: Long? = null,
    ) : Refusal
}

@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val calendar: String = "",
    val location: String = "",
    val start: Long,
    val end: Long,
    @SerialName("all_day") val allDay: Boolean = false,
    val busy: Boolean = false,
    /**
     * Categories the provider gave the event.
     *
     * Empty on Android: the calendar provider has no category column, and inventing one from the
     * title would make a rule fire on a word the user never tagged anything with.
     */
    val categories: List<String> = emptyList(),
)

@Serializable
sealed interface ActivationSource {
    @Serializable @SerialName("weekly") data class Weekly(val schedule: String) : ActivationSource

    @Serializable
    @SerialName("calendar")
    data class Calendar(val schedule: String, val event: String) : ActivationSource
}

@Serializable
data class Activation(
    val profile: String,
    val source: ActivationSource,
    val start: Long,
    val end: Long,
    val locks: List<Lock> = emptyList(),
)

/** Time spent and apps opened, as the core wants them: keyed by [target key][Rollup]. */
@Serializable
data class UsageState(
    val usage: Map<String, Consumption> = emptyMap(),
    val launches: Map<String, Launches> = emptyMap(),
)

@Serializable
data class Consumption(val rollups: List<Rollup> = emptyList())

@Serializable
data class Rollup(val at: Long, val seconds: Int)

@Serializable
data class Launches(
    /** The core calls this field `at`; `opens` is what it means on this side of the boundary. */
    @SerialName("at") val opens: List<Long> = emptyList(),
)

/** A profile as a chooser needs it: the id rules refer to, and the name a person reads. */
@Serializable
data class ProfileName(val id: String, val name: String)

/**
 * What one reading of the device's clocks turned out to mean.
 *
 * [refusedForward] and [refusedBackward] are seconds the wall clock claimed, or lost, that the
 * monotonic clock did not support within a single boot — always tampering, and never credited.
 * [unverified] is time across a reboot, which honest downtime and a clock change look identical
 * from here, so it is credited and reported rather than refused.
 */
@Serializable
data class ClockVerdict(
    val now: Long,
    @SerialName("refused_forward") val refusedForward: Long,
    @SerialName("refused_backward") val refusedBackward: Long,
    val unverified: Long,
    val tampered: Boolean,
)
