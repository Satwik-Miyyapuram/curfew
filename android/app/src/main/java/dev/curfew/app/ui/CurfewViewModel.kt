package dev.curfew.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.curfew.app.data.AuditRow
import dev.curfew.app.data.CurfewRuntime
import dev.curfew.app.data.Downtime
import dev.curfew.app.curfew
import dev.curfew.policy.Activation
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.Lock
import dev.curfew.policy.WeeklySchedule
import dev.curfew.policy.Policy
import dev.curfew.policy.ProfileName
import dev.curfew.policy.LockSet
import dev.curfew.policy.NoPass
import dev.curfew.policy.PassRefusal
import dev.curfew.policy.Refused
import dev.curfew.policy.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the screens read, and the only place they are allowed to change anything.
 *
 * The view model asks the runtime; it never asks the policy core directly and it never decides
 * anything itself. That matters most for ending a session: the button calls [endSession], the core
 * refuses, and the UI's job is to say what is missing — not to decide whether the refusal was
 * reasonable.
 */
class CurfewViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime: CurfewRuntime = app.curfew

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runtime.restore()
            // A second is the coarsest tick that still lets a countdown read like a countdown. The
            // work behind it is a few reads of in-memory state plus one query, and it stops with
            // the screen because the view model is scoped to the UI.
            while (true) {
                refresh()
                delay(1_000)
            }
        }
    }

    /** Re-read everything the screens show. Cheap enough to do on a timer; see above. */
    suspend fun refresh() {
        val now = runtime.clock.now()
        val events = runCatching { runtime.calendarEvents(now) }.getOrDefault(emptyList())
        val sessions = runCatching { runtime.policy.sessions().running }.getOrDefault(emptyList())
        val activations = runCatching { runtime.policy.activations(now, events) }
            .getOrDefault(emptyList())
        // Everything the rules will do between now and this time tomorrow, whether or not it has
        // started. The window is deliberately longer than a day so "tomorrow morning" is on screen
        // late tonight, which is exactly when someone checks whether they can stay up.
        val upcoming = runCatching { runtime.policy.upcoming(now, now + PREVIEW_SECONDS, events) }
            .getOrDefault(emptyList())
            .filter { it.end > now }
        val usage = runCatching { runtime.usage(now) }.getOrNull()
        val spent = usage?.usage.orEmpty()
            .mapValues { (_, consumption) -> consumption.rollups.sumOf { it.seconds } }
            .toList()
            .sortedByDescending { it.second }
        val opens = usage?.launches.orEmpty().mapValues { (_, l) -> l.opens.size }

        _state.update { previous ->
            previous.copy(
                now = now,
                lock = runtime.lock.value,
                sessions = sessions,
                activeProfiles = runtime.activeProfiles.value,
                activations = activations.sortedBy { it.start },
                upcoming = upcoming.sortedWith(compareBy({ it.start }, { it.end })),
                spentSeconds = spent,
                launchCounts = opens,
                configToml = runCatching { runtime.policy.configToml() }.getOrDefault(""),
                weekly = runCatching { runtime.weeklySchedules() }.getOrDefault(emptyList()),
                calendarRules = runCatching { runtime.calendarSchedules() }.getOrDefault(emptyList()),
                profiles = runCatching { Policy.profiles(runtime.policy.configToml()) }
                    .getOrDefault(emptyList()),
                audit = runCatching { runtime.db.audit().recent(AUDIT_SHOWN) }
                    .getOrDefault(emptyList()),
                grants = grantStates(getApplication()),
                restrictedSettings = RestrictedSettings.isLikelyBlocking(getApplication()),
                downtime = runtime.downtime.value,
                clockTamper = runtime.clockTamper.value,
                releasable = runtime.releasable.value,
                passesLeft = runCatching { runtime.passesRemaining(now) }.getOrDefault(0),
                passRefusal = runCatching { runtime.passRefusal(now) }.getOrNull(),
                sync = syncState(now, previous.sync),
                loading = false,
            )
        }
    }

    // --- sync -----------------------------------------------------------------------------------

    /** The invite or reply waiting on the user to say that the two phrases matched. */
    private var pending: String? = null

    private fun syncState(now: Long, previous: SyncState): SyncState {
        val hub = runtime.sync ?: return SyncState(offering = previous.offering)
        return SyncState(
            available = true,
            running = hub.isRunning(),
            deviceId = runCatching { hub.deviceId }.getOrDefault(""),
            fingerprint = runCatching { hub.fingerprint }.getOrDefault(""),
            peers = hub.peers.value,
            nearby = hub.nearby(now).toSet(),
            stillLocked = hub.stillLocked.value,
            complaints = hub.complaints,
            error = hub.lastError.value,
            offering = previous.offering,
        )
    }

    /**
     * Start pairing from this device: show an invite for the other one to read.
     *
     * No phrase yet. It covers both devices keys and this one has not seen the others, so it
     * appears once the reply is entered — which is also the first moment a person could compare
     * two screens.
     */
    fun offerPairing() {
        val hub = runtime.sync ?: return say("Sync is not running on this device.")
        runCatching { hub.sync.invite(runtime.clock.now()) }
            .onSuccess { json ->
                pending = null
                _state.update { it.copy(sync = it.sync.copy(offering = Offer(json, ""))) }
            }
            .onFailure { say(it.message ?: "That invite could not be made.") }
    }

    /**
     * Answer an invite shown by another device.
     *
     * Produces this device reply and the phrase together. Nothing here can check that the other
     * screen shows the same six digits — that is the user job, and it is the whole reason the
     * phrase exists, so [confirmPairing] is deliberately a separate press.
     */
    fun answerPairing(inviteJson: String) {
        val hub = runtime.sync ?: return say("Sync is not running on this device.")
        val invite = inviteJson.trim()
        runCatching { Offer(hub.sync.replyTo(invite), hub.sync.phrase(invite), isReply = true) }
            .onSuccess { offer ->
                pending = invite
                _state.update { it.copy(sync = it.sync.copy(offering = offer)) }
            }
            .onFailure { say("That is not a Curfew invite.") }
    }

    /** Read back the reply from the invited device, so this side can show its phrase too. */
    fun readReply(replyJson: String) {
        val hub = runtime.sync ?: return say("Sync is not running on this device.")
        val reply = replyJson.trim()
        runCatching { hub.sync.phrase(reply) }
            .onSuccess { phrase ->
                pending = reply
                _state.update {
                    it.copy(sync = it.sync.copy(offering = Offer(reply, phrase, isReply = true)))
                }
            }
            .onFailure { say("That is not a Curfew reply.") }
    }

    /** Pair, now that the person has said the two phrases matched. */
    fun confirmPairing() {
        val hub = runtime.sync ?: return
        val invite = pending ?: return say("Read the other device code first.")
        viewModelScope.launch {
            runCatching { hub.sync.accept(invite, runtime.clock.now()) }
                .onSuccess {
                    hub.save()
                    hub.refreshPeers()
                    pending = null
                    _state.update { it.copy(sync = it.sync.copy(offering = null)) }
                    say("Paired.")
                }
                .onFailure { say(it.message ?: "That pairing could not be completed.") }
            refresh()
        }
    }

    fun cancelPairing() {
        pending = null
        _state.update { it.copy(sync = it.sync.copy(offering = null)) }
    }

    /**
     * Remove a device.
     *
     * Immediate, local, and needing nothing from the device being removed, because a phone that has
     * been lost cannot agree to its own removal. It does not end anything that device started here:
     * a lock is a promise whoever asked for it, and forgetting a device is not evidence that the
     * block is over.
     */
    fun revokeDevice(deviceId: String) {
        val hub = runtime.sync ?: return
        viewModelScope.launch {
            runCatching { hub.sync.revoke(deviceId, runtime.clock.now()) }
                .onSuccess {
                    hub.save()
                    hub.refreshPeers()
                    say("That device will be ignored from now on.")
                }
                .onFailure { say(it.message ?: "That device could not be removed.") }
            refresh()
        }
    }

    /** Sync now rather than at the next tick, for someone watching two screens at once. */
    fun syncNow() {
        viewModelScope.launch {
            val pass = runtime.syncPass()
            when {
                pass == null -> say("Sync is not running on this device.")
                pass.adopted.isNotEmpty() ->
                    say("Took up ${pass.adopted.size} block(s) from another device.")
                else -> say("Up to date.")
            }
            refresh()
        }
    }

    /** Exchange through a folder both devices can see, for devices never on one network. */
    fun folderPass(root: String) {
        val hub = runtime.sync ?: return say("Sync is not running on this device.")
        viewModelScope.launch {
            runCatching { hub.sync.folderPass(root.trim()) }
                .onSuccess { pass ->
                    hub.save()
                    say("Took in ${pass.accepted} update(s), left ${pass.written} behind.")
                    runtime.syncPass()
                }
                .onFailure { say(it.message ?: "That folder could not be used.") }
            refresh()
        }
    }

    /** Acknowledge the "your other device says this is over" notice. */
    fun dismissStillLocked() {
        runtime.sync?.forget(state.value.sync.stillLocked)
        _state.update { it.copy(sync = it.sync.copy(stillLocked = emptyList())) }
    }

    // --- the things a user can actually do -------------------------------------------------------

    /**
     * Ask to end a session, handing over whatever the platform managed to prove.
     *
     * A refusal is not an error state: it is the lock working. It is surfaced as [UiState.message]
     * naming exactly what is still missing, so the next screen can ask for that one thing.
     */
    fun endSession(session: Session, satisfied: List<Lock> = emptyList()) {
        viewModelScope.launch {
            try {
                runtime.endSession(session.id, satisfied)
                say("${session.profile} ended.")
            } catch (refused: Refused) {
                _state.update { it.copy(refusal = refused.refusal, refusedSession = session.id) }
            }
            refresh()
        }
    }

    /**
     * End a session the user has just proved they own the device for.
     *
     * The prompt is shown by the caller; this is what happens after it says yes. The proof is
     * recorded first and separately, so a lock that also wants a tag is left one step from open
     * rather than refusing everything and starting again.
     */
    fun endWithCredential(session: Session, satisfied: List<Lock> = emptyList()) {
        viewModelScope.launch {
            runtime.recordCredential(session.id)
            endSession(session, satisfied)
        }
    }

    /**
     * Present a tag to a session, from an NFC read or from the field for typing one in.
     *
     * The payload is handed straight to the core and never kept here: not in the state, not in a
     * message, not in the audit log. A wrong tag and a tag belonging to another lock are answered
     * identically, so nothing about which tags exist can be learned by trying.
     */
    fun scanToken(session: Session, payload: String) {
        viewModelScope.launch {
            try {
                runtime.scanToken(session.id, payload)
                say("${session.profile} ended.")
            } catch (refused: Refused) {
                _state.update { it.copy(refusal = refused.refusal, refusedSession = session.id) }
            }
            refresh()
        }
    }

    /**
     * Give the release another device's lock is waiting on this one for.
     *
     * Takes an id rather than a [Session] because the session being released usually belongs to
     * the other device and is not running here at all. The confirmation lives in the UI: this
     * cannot be undone, and a release given by accident would hand back a lock the user asked for
     * and could not take away again.
     */
    fun releasePeer(id: String) {
        viewModelScope.launch {
            runtime.releasePeer(id)
            say("Released. The other device will act on it within a few seconds.")
            refresh()
        }
    }

    /** Start the delayed release. The instant it lands is the core's to choose, and never moves. */
    fun requestRelease(session: Session) {
        viewModelScope.launch {
            runCatching { runtime.requestRelease(session.id) }
                .onSuccess { say("Release for ${session.profile} lands ${relative(it, runtime.clock.now())}.") }
                .onFailure { say("That session cannot be released early.") }
            refresh()
        }
    }

    /**
     * Spend an emergency pass on a session.
     *
     * The confirmation lives in the UI, not here: by the time this runs the pass is being spent,
     * and it is spent whether or not the session was still there to end. That is deliberate — a
     * ration that only counted successes could be probed for free with a stale id.
     */
    fun spendPass(session: Session) {
        viewModelScope.launch {
            try {
                runtime.spendPass(session.id)
                say("${session.profile} ended with an emergency pass.")
            } catch (e: NoPass) {
                say(describePassRefusal(e.refusal, runtime.clock.now()))
            } catch (refused: Refused) {
                _state.update { it.copy(refusal = refused.refusal, refusedSession = session.id) }
            }
            refresh()
        }
    }

    fun dismissRefusal() = _state.update { it.copy(refusal = null, refusedSession = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /**
     * Dismiss the downtime banner.
     *
     * Only the user can clear it. Curfew clearing its own "I was not running" notice would make the
     * notice worthless, which is the whole reason it exists.
     */
    fun dismissDowntime() {
        viewModelScope.launch {
            runtime.acknowledgeDowntime()
            refresh()
        }
    }

    /** Dismiss the refused-clock-change banner. Same rule as downtime: only the user clears it. */
    fun dismissClockTamper() {
        viewModelScope.launch {
            runtime.acknowledgeClockTamper()
            refresh()
        }
    }

    /** Replace the config, rejecting an invalid one before the working config is touched. */
    fun saveConfig(toml: String) {
        viewModelScope.launch {
            runtime.setConfig(toml)
                .onSuccess { say("Saved.") }
                .onFailure { say(it.message ?: "That config could not be loaded.") }
            refresh()
        }
    }

    // --- editing schedules ------------------------------------------------------------------------
    //
    // Each of these hands one schedule to the core, which validates the whole config before it is
    // written. A refusal is reported in the core's own words and changes nothing, so a form filled
    // in wrongly cannot leave the device unprotected.

    /**
     * Add a profile, or rename one.
     *
     * The edit that has to come first on a fresh install: everything else on this screen asks for a
     * profile by name.
     */
    fun saveProfile(id: String, name: String, description: String = "") {
        viewModelScope.launch {
            runtime.saveProfile(id, name, description)
                .onSuccess { say("Saved.") }
                .onFailure { say(it.message ?: "That profile could not be saved.") }
            refresh()
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            runtime.deleteProfile(id)
                .onSuccess { say("Removed. A session it already started keeps running.") }
                // The core's message names the schedules still pointing at it, which is exactly
                // what the user needs in order to fix it.
                .onFailure { say(it.message ?: "That profile could not be removed.") }
            refresh()
        }
    }

    fun saveWeekly(window: WeeklySchedule) {
        viewModelScope.launch {
            runtime.saveWeekly(window)
                .onSuccess { say("Saved.") }
                .onFailure { say(it.message ?: "That window could not be saved.") }
            refresh()
        }
    }

    fun deleteWeekly(id: String) {
        viewModelScope.launch {
            // Said plainly, because it is the part people get wrong: deleting the rule that
            // started a session does not end the session.
            runtime.deleteWeekly(id)
                .onSuccess { say("Removed. A session it already started keeps running.") }
                .onFailure { say(it.message ?: "That window could not be removed.") }
            refresh()
        }
    }

    fun saveCalendarRule(rule: CalendarSchedule) {
        viewModelScope.launch {
            runtime.saveCalendarRule(rule)
                .onSuccess { say("Saved.") }
                .onFailure { say(it.message ?: "That rule could not be saved.") }
            refresh()
        }
    }

    fun deleteCalendarRule(id: String) {
        viewModelScope.launch {
            runtime.deleteCalendarRule(id)
                .onSuccess { say("Removed. A session it already started keeps running.") }
                .onFailure { say(it.message ?: "That rule could not be removed.") }
            refresh()
        }
    }

    /**
     * Save the app picker's answer for one profile.
     *
     * The edit itself is the core's — see [Policy.setBlockedApps] — and the result goes back
     * through the same `setConfig` path as a hand-typed file, so a picker cannot write a config a
     * person could not have written, and cannot end a running session either.
     */
    fun setBlockedApps(profile: String, packages: List<String>) {
        viewModelScope.launch {
            Policy.setBlockedApps(runtime.policy.configToml(), profile, packages)
                .onSuccess { toml ->
                    runtime.setConfig(toml)
                        .onSuccess { say("Saved.") }
                        .onFailure { say(it.message ?: "That change could not be saved.") }
                }
                .onFailure { say(it.message ?: "That change could not be saved.") }
            refresh()
        }
    }

    /** The packages the picker should open with ticked, for [profile]. */
    fun blockedApps(profile: String): List<String> =
        Policy.blockedApps(runCatching { runtime.policy.configToml() }.getOrDefault(""), profile)

    /**
     * Import a config from a file the user chose.
     *
     * It goes through the same validation as any other config: an invalid file is refused with the
     * core's own message, and the working config is left untouched. Importing does not end a
     * running session, for the same reason editing does not.
     */
    fun importConfig(toml: String) = saveConfig(toml)

    /** Bring sessions into line with the schedules right now, rather than at the next alarm. */
    fun reconcileNow() {
        viewModelScope.launch {
            val now = runtime.clock.now()
            val events = runCatching { runtime.calendarEvents(now) }.getOrDefault(emptyList())
            runCatching { runtime.reconcile(now, events) }
            refresh()
        }
    }

    /** Put one sentence in front of the user. Public so a screen can report a failed file read. */
    fun say(message: String) = _state.update { it.copy(message = message) }

    companion object {
        private const val AUDIT_SHOWN = 200
    }
}

/**
 * How far ahead the schedule tab looks. A day and a half: far enough that tomorrow morning is
 * visible tonight, short enough that the list stays readable without paging.
 */
internal const val PREVIEW_SECONDS = 36L * 3600

/**
 * Everything on screen, as one value.
 *
 * One state object rather than a flow per field, because the screens show things that must agree
 * with each other — a countdown and the lock it belongs to cannot be allowed to come from two
 * different instants.
 */
data class UiState(
    val now: Long = 0,
    val loading: Boolean = true,
    val lock: LockSet = LockSet(),
    val sessions: List<Session> = emptyList(),
    val activeProfiles: List<String> = emptyList(),
    val activations: List<Activation> = emptyList(),
    /**
     * Everything scheduled in the next [PREVIEW_SECONDS], including what is already running.
     * A superset of [activations], so the timeline reads as one list rather than two.
     */
    val upcoming: List<Activation> = emptyList(),
    /** Seconds spent per target key today, largest first. */
    val spentSeconds: List<Pair<String, Int>> = emptyList(),
    val launchCounts: Map<String, Int> = emptyMap(),
    val configToml: String = "",
    /** The weekly windows and calendar rules, as the schedule editor lists them. */
    val weekly: List<WeeklySchedule> = emptyList(),
    val calendarRules: List<CalendarSchedule> = emptyList(),
    val profiles: List<ProfileName> = emptyList(),
    val audit: List<AuditRow> = emptyList(),
    val grants: List<GrantState> = emptyList(),
    /** True while Android is refusing accessibility access because Curfew was sideloaded. */
    val restrictedSettings: Boolean = false,
    /** A stretch Curfew could not account for, until the user has seen it. */
    val downtime: Downtime? = null,
    /** A clock change that was refused, until the user has seen it. */
    val clockTamper: dev.curfew.app.data.ClockTamper? = null,
    /** Sync as the devices screen shows it. Present even when nothing has been paired. */
    val sync: SyncState = SyncState(),
    val message: String? = null,
    /**
     * Sessions whose lock names *this* device as the one that must let them out.
     *
     * Usually the lock is on the other device, so this is normally about a session that is not in
     * [sessions] at all.
     */
    val releasable: List<String> = emptyList(),
    /** Emergency passes that could be spent right now, and why not when there are none. */
    val passesLeft: Int = 0,
    val passRefusal: PassRefusal? = null,
    val refusal: dev.curfew.policy.Refusal? = null,
    val refusedSession: String? = null,
) {
    val isEnforcing: Boolean get() = sessions.isNotEmpty()
}

/**
 * What the devices screen knows.
 *
 * [stillLocked] is the one field here that is not housekeeping: it names sessions another device
 * has ended and this one is still holding, which is the moment a user is most likely to conclude
 * that sync is broken. It is shown, with its reason, rather than hidden.
 */
data class SyncState(
    val available: Boolean = false,
    val running: Boolean = false,
    val deviceId: String = "",
    val fingerprint: String = "",
    val peers: List<dev.curfew.policy.Peer> = emptyList(),
    val nearby: Set<String> = emptySet(),
    val stillLocked: List<String> = emptyList(),
    val complaints: List<String> = emptyList(),
    val error: String? = null,
    /** The invite or reply currently on screen, and the phrase that goes with it. */
    val offering: Offer? = null,
) {
    val active: List<dev.curfew.policy.Peer> get() = peers.filter { it.isActive }
}

/**
 * Something to put in front of the other device: the JSON for the QR code, and the six digits.
 *
 * [phrase] is empty on the first half of the ceremony, when this device has offered an invite and
 * has not yet seen the other device keys. There is nothing to compare until both sides are known,
 * and showing a number that only covers one of them would teach the user to ignore it.
 */
data class Offer(val json: String, val phrase: String, val isReply: Boolean = false)
