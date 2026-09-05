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

    // --- editing schedules ------------------------------------------------------------------------
    //
    // The schedule screen builds these objects, not TOML. Writing a config document from Kotlin
    // would be a second definition of what a schedule is, and it is the one written in the UI
    // language that drifts.

    /** Every weekly window, in the order the config holds them. */
    fun weekly(): List<WeeklySchedule> =
        json.decodeFromString(ListSerializer(WeeklySchedule.serializer()), inner.weeklyJson())

    /** Every calendar rule. */
    fun calendars(): List<CalendarSchedule> =
        json.decodeFromString(ListSerializer(CalendarSchedule.serializer()), inner.calendarsJson())

    /**
     * Add a window, or replace the one with this id.
     *
     * Refused if the result would not be a config the core would load — a window naming a profile
     * that is not there, a time outside the day. On a refusal the config is exactly as it was, so
     * a rejected form does not poison the next save.
     */
    fun upsertWeekly(window: WeeklySchedule) = invalid {
        inner.upsertWeekly(json.encodeToString(window))
    }

    /** Delete a window. Deleting one that is not there is not an error. */
    fun removeWeekly(id: String) = inner.removeWeekly(id)

    /** Add a calendar rule, or replace the one with this id. See [upsertWeekly]. */
    fun upsertCalendar(rule: CalendarSchedule) = invalid {
        inner.upsertCalendar(json.encodeToString(rule))
    }

    /**
     * Delete a calendar rule.
     *
     * A session it already started keeps running: that is a promise already made, and a settings
     * edit is not a way out of a lock. The rule simply stops starting new ones.
     */
    fun removeCalendar(id: String) = inner.removeCalendar(id)

    /**
     * Turn the binding's config error into one carrying the core's own words.
     *
     * The message is written for whoever wrote the config, which for an edit made in the app is
     * the person looking at the form, so it is what the screen shows.
     */
    private inline fun <T> invalid(body: () -> T): T =
        try {
            body()
        } catch (e: CurfewException.Config) {
            throw InvalidSchedule(e.detail)
        }

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
     * End a session, given the conditions the caller itself witnessed. Throws [Refused] carrying
     * the core's own refusal when the lock is not satisfied, so the UI can ask for exactly what is
     * missing rather than saying "no".
     *
     * Only [Lock.Timer], [Lock.Confirm] and [Lock.Challenge] may be passed here, because those are
     * the conditions this code is the only witness to. A credential, a tag, a restart and a peer
     * release are proved to the core by [recordCredential], [scanToken], [observeBoot] and
     * [observeReleases]; listing them in `satisfied` ends nothing.
     */
    fun endSession(id: String, now: Long, satisfied: List<Lock> = emptyList()) {
        try {
            inner.endSession(id, now, json.encodeToString(satisfied))
        } catch (e: CurfewException.Refused) {
            throw Refused(json.decodeFromString<Refusal>(e.refusal))
        }
    }

    // --- evidence -----------------------------------------------------------------------------

    /**
     * Tell the core the device credential prompt has just succeeded for this session.
     *
     * Called after `BiometricPrompt` reports success and *before* [endSession]. The two are
     * separate so a lock asking for a credential and a tag can be satisfied by doing both, a short
     * walk apart, rather than being impossible to satisfy at all. The proof is not kept for long,
     * and never across a restart.
     */
    fun recordCredential(id: String, now: Long) = inner.recordCredential(id, now)

    /**
     * Present a physical tag — read over NFC, or typed in when there is no reader.
     *
     * The payload is fingerprinted and dropped; nothing about it is stored or logged. A tag this
     * lock does not name and a tag that is not a Curfew tag at all are refused identically, so a
     * scan cannot be used to discover which tags a config knows about.
     */
    fun scanToken(id: String, payload: String, now: Long) {
        try {
            inner.scanToken(id, payload, now)
        } catch (e: CurfewException.Refused) {
            throw Refused(json.decodeFromString<Refusal>(e.refusal))
        }
    }

    /**
     * Give the release a peer's lock is waiting on this device for.
     *
     * There is no way to take one back. A release that could be withdrawn would let one device
     * re-shut a lock the user had already been told they were out of, and a lock that can come back
     * is not a promise.
     */
    fun releasePeer(id: String, now: Long) = inner.releasePeer(id, now)

    /** Sessions whose lock asks *this* device for the release, for the button that gives it. */
    fun releasable(): List<String> = inner.releasable()

    /** The releases this device has given, for the sync layer to publish. */
    fun releases(): Set<String> = json.decodeFromString(inner.releasesJson())

    /** The same, still encoded, for writing straight down beside the sessions. */
    fun releasesJson(): String = inner.releasesJson()

    /** Adopt what the op-log says about releases, and the id this device is known by there. */
    fun observeReleases(deviceId: String, released: Map<String, Set<String>>) =
        inner.observeReleases(deviceId, json.encodeToString(released))

    /** Restore the releases this device gave before it was last killed, as [releasesJson] wrote them. */
    fun restoreReleases(releasesJson: String) = inner.restoreReleases(releasesJson)

    /**
     * Take a reading of how long the device has been up, in seconds.
     *
     * Uptime rather than the wall clock, on purpose: uptime can only go backwards by rebooting, so
     * a clock moved forward in Settings cannot be made to look like the restart a lock asked for.
     */
    fun observeBoot(uptimeSeconds: Long) = inner.observeBoot(uptimeSeconds)

    /** The boot bookkeeping as JSON, to be written down beside the sessions. */
    fun boots(): String = inner.bootsJson()

    /** Restore boot bookkeeping written by [boots]. A relaunch is not a restart; this is why. */
    fun restoreBoots(bootsJson: String) = inner.restoreBoots(bootsJson)

    /** Which of a session's conditions this device can already prove, so the UI can stop asking. */
    fun proven(id: String, now: Long): List<Lock> =
        json.decodeFromString(inner.provenJson(id, now))

    /** Start the 24-hour delayed release, returning the instant it lands. Never movable later. */
    fun requestRelease(id: String, now: Long): Long =
        try {
            inner.requestRelease(id, now)
        } catch (e: CurfewException.Refused) {
            throw Refused(json.decodeFromString<Refusal>(e.refusal))
        }

    // --- the escape hatch ---------------------------------------------------------------------

    /**
     * End a session by spending an emergency pass.
     *
     * Throws [NoPass] when the ration says no, carrying when the next one becomes available: the
     * only part of that answer anybody can act on. Throws [Refused] when the pass was spent and the
     * session was not there to end — the pass is gone either way, because a ration that only
     * counted successes could be probed for free with a stale id.
     */
    fun spendPass(id: String, now: Long) {
        try {
            inner.spendPass(id, now)
        } catch (e: CurfewException.NoPass) {
            throw NoPass(json.decodeFromString<PassRefusal>(e.refusal))
        } catch (e: CurfewException.Refused) {
            throw Refused(json.decodeFromString<Refusal>(e.refusal))
        }
    }

    /** How many passes could be spent inside the rolling window right now. */
    fun passesRemaining(now: Long): Int = inner.passesRemaining(now).toInt()

    /** Why a pass cannot be spent, or null when one can. */
    fun passRefusal(now: Long): PassRefusal? =
        inner.passRefusalJson(now)?.let { json.decodeFromString(it) }

    fun passes(): Passes = json.decodeFromString(inner.passesJson())

    /**
     * Restore the spent ration from storage. Merged, never replaced: a pass heard from a peer since
     * the file was written must not be forgotten by reading an older copy of our own.
     */
    fun restorePasses(passes: Passes) = inner.restorePasses(json.encodeToString(passes))

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
     * Everything that will be running between [from] and [to]: the preview timeline, "here is what
     * tomorrow will block".
     *
     * A calendar rule is the one kind of schedule a user cannot check by reading their own settings
     * — it depends on meetings other people put in their calendar — so seeing it before it happens
     * is what makes it something worth attaching a lock to.
     */
    fun upcoming(from: Long, to: Long, events: List<CalendarEvent>): List<Activation> =
        json.decodeFromString(inner.upcomingJson(from, to, json.encodeToString(events)))

    /**
     * When the schedules could next change, so the service can set one alarm instead of polling.
     * Polling is what doze punishes, and a blocker that doze kills is not a blocker.
     */
    fun nextChangeAfter(now: Long, events: List<CalendarEvent>): Long? =
        inner.nextChangeAfter(now, json.encodeToString(events))
}

/** Thrown when the core refuses to end a session, carrying the reason it gave. */
class Refused(val refusal: Refusal) : Exception(refusal.toString())

/** Thrown when there was no emergency pass to spend, carrying why. */
class NoPass(val refusal: PassRefusal) : Exception(refusal.toString())

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

    @Serializable
    @SerialName("peer_release")
    // The field is named on both sides: the core writes `device_id`, and a lock whose only
    // difference from the Rust one is a spelling would fail to cross with no rule to point at.
    data class PeerRelease(@SerialName("device_id") val deviceId: String) : Lock

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

/** Emergency passes already spent, as instants. Grow-only: merging can never give one back. */
@Serializable
data class Passes(val used: List<Long> = emptyList())

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("refusal")
sealed interface PassRefusal {
    /** The hatch was never switched on. Not a shortage — nothing to wait for. */
    @Serializable @SerialName("disabled") data object Disabled : PassRefusal

    @Serializable
    @SerialName("quota_spent")
    data class QuotaSpent(@SerialName("next_at") val nextAt: Long) : PassRefusal

    @Serializable
    @SerialName("cooling_down")
    data class CoolingDown(val until: Long) : PassRefusal
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

/** A schedule the core would not accept, with the core's own explanation of why. */
class InvalidSchedule(message: String) : Exception(message)

/**
 * A window that runs on the clock: this profile, these days, between these two times.
 *
 * Days are 0 = Monday, and an empty list means every day. [endMinute] at or before [startMinute]
 * means the window runs past midnight, which is the shape every "nothing after 11pm" rule has.
 */
@Serializable
data class WeeklySchedule(
    val id: String,
    val profile: String,
    val days: List<Int> = emptyList(),
    @SerialName("start_minute") val startMinute: Int,
    @SerialName("end_minute") val endMinute: Int,
    val locks: List<Lock> = emptyList(),
)

/** A rule that runs a profile for as long as a matching calendar event does, plus its padding. */
@Serializable
data class CalendarSchedule(
    val id: String,
    val profile: String,
    val matcher: EventMatcher = EventMatcher(),
    /** Start this many seconds early, so the lock is already up when the meeting begins. */
    @SerialName("pad_before_seconds") val padBeforeSeconds: Int = 0,
    @SerialName("pad_after_seconds") val padAfterSeconds: Int = 0,
    val locks: List<Lock> = emptyList(),
)

/** All of these must match. An empty matcher matches every event on every calendar. */
@Serializable
data class EventMatcher(
    /** Glob over the event title. */
    val title: String? = null,
    /** Exact calendar name, case-insensitive. */
    val calendar: String? = null,
    /** Glob over the location. */
    val location: String? = null,
    @SerialName("busy_only") val busyOnly: Boolean = false,
    @SerialName("all_day") val allDay: Boolean? = null,
    val categories: List<String> = emptyList(),
    @SerialName("min_duration_seconds") val minDurationSeconds: Long? = null,
    @SerialName("max_duration_seconds") val maxDurationSeconds: Long? = null,
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
