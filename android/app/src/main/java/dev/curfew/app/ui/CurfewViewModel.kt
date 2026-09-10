package dev.curfew.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.curfew.app.data.AuditRow
import dev.curfew.app.data.CurfewRuntime
import dev.curfew.app.data.CalendarReader
import dev.curfew.app.data.Downtime
import dev.curfew.app.curfew
import dev.curfew.policy.ActivationSource
import dev.curfew.policy.Action
import dev.curfew.policy.Activation
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.Lock
import dev.curfew.policy.WeeklySchedule
import dev.curfew.policy.Policy
import dev.curfew.policy.Rule
import dev.curfew.policy.Target
import dev.curfew.policy.ProfileName
import dev.curfew.policy.LockSet
import dev.curfew.policy.NoPass
import dev.curfew.policy.PassRefusal
import dev.curfew.policy.Refused
import dev.curfew.policy.Session
import dev.curfew.policy.SessionSource
import dev.curfew.policy.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
            refresh()
            // Two cadences, because they cost two different things.
            //
            // A second is the coarsest tick that still lets a countdown read like a countdown, and
            // what it needs — the clock, the lock, the running sessions — is already in memory.
            // Everything else costs a calendar-provider query, a TOML parse or two, a database
            // read and a sweep of the permission states; doing all of that every second made the
            // whole refresh take longer than the interval it ran on, which is why a change only
            // appeared after leaving the app and coming back. It runs on its own slower beat, and
            // — this is the part that matters — immediately after every write, so nothing a user
            // does waits for a beat.
            //
            // Off screen, none of that is worth a beat at all: nothing is being read, and the
            // service that does the actual blocking does not run from here. So the loop slows to
            // the slow beat itself and does the full refresh on it — one pass every five seconds
            // instead of five, and the state is already correct by the time a screen comes back.
            var tick = 0
            while (true) {
                if (foreground) {
                    delay(1_000)
                    if (tick++ % SLOW_TICKS == 0) refresh() else refreshFast()
                } else {
                    delay(SLOW_TICKS * 1_000L)
                    tick = 0
                    refresh()
                }
            }
        }
    }

    /**
     * The clock, and the three things that move with it.
     *
     * Everything read here is already in memory: no file, no database, no content provider, no
     * parse. It is safe to run every second and it is what makes a countdown a countdown.
     */
    /**
     * Whether a Curfew screen is actually in front of someone.
     *
     * The cadence below is paid for by whoever is looking at it, so it follows the looking.
     */
    private var foreground = true

    /** Called by the UI as it resumes and pauses; see [foreground]. */
    fun onForeground(visible: Boolean) {
        foreground = visible
        if (visible) viewModelScope.launch { refresh() }
    }

    private fun refreshFast() {
        val now = runtime.clock.now()
        // A timer that reaches zero has to end the session itself. It used to sit at zero until
        // the enforcement service's next poll noticed — up to half a minute, and longer if the
        // service was dozing — which read as "I have to press End now or it never ends". The
        // countdown and the end are the same event, so they happen on the same tick.
        if (_state.value.sessions.any { s -> s.lock.endsAt?.let { it <= now } == true }) {
            reconcileNow()
        }
        _state.update {
            it.copy(
                now = now,
                lock = runtime.lock.value,
                activeProfiles = runtime.activeProfiles.value,
                downtime = runtime.downtime.value,
                clockTamper = runtime.clockTamper.value,
                releasable = runtime.releasable.value,
            )
        }
    }

    /**
     * Re-read everything the screens show.
     *
     * Off the main thread, every bit of it. `viewModelScope` is `Dispatchers.Main.immediate`, and
     * everything called below blocks: the config reads hit the filesystem, the audit read hits the
     * database, and `nearby` crosses into the sync node, where it waits on the same lock the
     * discovery thread holds while it is talking to the network. On a phone that showed up as a
     * ten-second freeze and an ANR the first time a real device had a peer to look for. None of it
     * touches a view; `_state` is a `MutableStateFlow`, which is safe to update from any thread.
     */
    suspend fun refresh() {
        withContext(Dispatchers.Default) {
            // Sessions come first: a write that has just landed is the reason this was called.
            val now = runtime.clock.now()
            // The browsing window, not the enforcement one: this list is also what the Events
            // screen shows, and a calendar that stopped at tomorrow looked to the user like a
            // calendar that had lost most of their year.
            val events = runCatching { runtime.calendarEvents(now, CalendarReader.BROWSE_SECONDS) }
                .getOrDefault(emptyList())
            val sessions = runCatching { runtime.policy.sessions().running }.getOrDefault(emptyList())
            val activations = runCatching { runtime.policy.activations(now, events) }
                .getOrDefault(emptyList())
            // Everything the rules will do between now and this time tomorrow, whether or not it has
            // started. The window is deliberately longer than a day so "tomorrow morning" is on screen
            // late tonight, which is exactly when someone checks whether they can stay up.
            val upcoming = runCatching { runtime.policy.upcoming(now, now + PREVIEW_SECONDS, events) }
                .getOrDefault(emptyList())
                .filter { it.end > now }
            // Which rules catch which of those events, over the whole browsed window rather than
            // the preview one. The Events screen lists two months, and asking the timeline — which
            // only looks a day and a half ahead — meant every event past tomorrow said "nothing
            // blocked" while a rule was sitting there catching it.
            val eventRules = runCatching {
                runtime.policy.upcoming(now, now + CalendarReader.BROWSE_SECONDS, events)
            }
                .getOrDefault(emptyList())
                .mapNotNull { activation ->
                    (activation.source as? ActivationSource.Calendar)?.let {
                        it.event to EventRule(schedule = it.schedule, profile = activation.profile)
                    }
                }
                // Grouped, not collapsed into one entry per event: an event can be caught by
                // several rules, and the card is supposed to name all of them.
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, rules) -> rules.distinct() }
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
                    // Anything that has not finished yet, soonest first. Events already over are
                    // dropped rather than greyed out: the list exists to be pointed at, and a
                    // finished meeting is not something a new rule can usefully be built from.
                    calendarEvents = events.filter { it.end > now }.sortedBy { it.start },
                    eventRules = eventRules,
                    calendarGranted = CalendarReader(getApplication()).hasPermission(),
                    profiles = runCatching { Policy.profiles(runtime.policy.configToml()) }
                        .getOrDefault(emptyList()),
                    audit = runCatching { runtime.db.audit().recent(AUDIT_SHOWN) }
                        .getOrDefault(emptyList()),
                    stats = runCatching { runtime.stats(now = now) }.getOrDefault(Stats()),
                    screenTime = runCatching { runtime.screenTimeComparison(now) }.getOrNull(),
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
    }

    // --- sync -----------------------------------------------------------------------------------

    /** The invite or reply waiting on the user to say that the two phrases matched. */
    private var pending: String? = null

    private fun syncState(now: Long, previous: SyncState): SyncState {
        val hub = runtime.sync ?: return SyncState(offering = previous.offering)
        // Every field is read from memory. Nothing here may cross into the sync node: `isRunning`
        // and `nearby` used to, and both take a lock the core holds for as long as a pass takes —
        // fifty-two seconds, measured. This function runs inside the once-a-second refresh, so
        // that lock was the reason a saved profile did not appear, a granted permission went on
        // being denied, and an ended session went on looking live. The sync loop observes those
        // two on its own thread now and leaves the answers in flows.
        return SyncState(
            available = true,
            running = hub.running.value,
            deviceId = runCatching { hub.deviceId }.getOrDefault(""),
            fingerprint = runCatching { hub.fingerprint }.getOrDefault(""),
            peers = hub.peers.value,
            nearby = hub.nearby.value,
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
    /**
     * Block right now, for a while, because the user said so.
     *
     * The one path into a session that no schedule and no calendar knows about. Curfew was built
     * around things that arrive on their own -- a lecture, a weeknight -- and that left out the
     * commonest intention of all: *not for the next ninety minutes*. A timer is a manual session
     * with an end time and, if the user asked for one, the same lock a scheduled block would get.
     *
     * The lock is the user's choice at the moment they start it and not a setting they have to
     * find first, because the honest answer to "how hard should this be to undo" changes between
     * a study hour and a night off.
     */
    fun startTimer(profile: String, seconds: Int, locks: List<Lock> = emptyList()) {
        viewModelScope.launch {
            val now = runtime.clock.now()
            val session = Session(
                // Distinct from a reconciled session's id, which is minted from the activation, so
                // a timer and a schedule for the same profile can never collide.
                id = "timer-$now",
                profile = profile,
                source = SessionSource.Manual,
                startedAt = now,
                lock = LockSet(conditions = locks, endsAt = now + seconds),
            )
            runCatching { runtime.startSession(session) }
                .onFailure { say(it.message ?: "That could not be started.") }
            refresh()
        }
    }

    fun saveProfile(id: String, name: String, description: String = "") {
        viewModelScope.launch {
            runtime.saveProfile(id, name, description)
                .onFailure { say(it.message ?: "That profile could not be saved.") }
            refresh()
        }
    }

    /**
     * Create or rename a profile and give it a window, in that order, in one coroutine.
     *
     * Two separate calls raced: each launches its own coroutine, and a window naming a profile the
     * core has not seen yet is refused. Doing both here means the window is only ever written after
     * the profile it names exists, and one failure message is shown instead of two.
     */
    fun saveProfileWithWindow(id: String, name: String, window: WeeklySchedule) {
        viewModelScope.launch {
            runtime.saveProfile(id, name)
                .mapCatching { runtime.saveWeekly(window).getOrThrow() }
                .onFailure { say(it.message ?: "That window could not be saved.") }
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
     * Block something the app picker cannot express — a site, a url, a word, a window title.
     *
     * Saying it twice is one rule rather than two: the core keys a rule on what it points at, so
     * the second statement replaces the first instead of leaving two arguing with each other.
     */
    fun saveRule(profile: String, rule: Rule) {
        viewModelScope.launch {
            runtime.saveRule(profile, rule)
                .onFailure { say(it.message ?: "That could not be blocked.") }
            refresh()
        }
    }

    fun deleteRule(profile: String, target: Target) {
        viewModelScope.launch {
            runtime.deleteRule(profile, target)
                .onSuccess { say("Removed. A session it already started keeps running.") }
                .onFailure { say(it.message ?: "That could not be unblocked.") }
            refresh()
        }
    }

    /**
     * What [profile] blocks apart from apps, for the list under the picker.
     *
     * Apps are left out because the picker above already shows them, ticked; showing each one a
     * second time as a row would read as two separate blocks on the same app.
     */
    /**
     * Write the statistics to a file the user picked, as CSV or as JSON.
     *
     * Only ever the summary: the audit trail it was built from stays on the device, because a
     * per-session log leaving the phone is the thing this app promises does not happen. Both
     * formats are produced by the core, so the file a phone writes and the file a PC writes match.
     */
    fun exportStats(uri: Uri, asCsv: Boolean) {
        viewModelScope.launch {
            val text = runCatching {
                if (asCsv) runtime.statsCsv() else Json.encodeToString(Stats.serializer(), state.value.stats)
            }.getOrNull()
            if (text == null) {
                say("Those figures could not be prepared.")
                return@launch
            }
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray())
                }
            }
                .onFailure { say("That file could not be written.") }
        }
    }

    fun rulesBeyondApps(profile: String): List<Rule> =
        runCatching { runtime.rulesBeyondApps(profile) }.getOrDefault(emptyList())

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
                        .onFailure { say(it.message ?: "That change could not be saved.") }
                }
                .onFailure { say(it.message ?: "That change could not be saved.") }
            refresh()
        }
    }

    /**
     * Copy everything one profile blocks onto another.
     *
     * Profiles overlap heavily in practice — "Deep work" and "Evening" block most of the same
     * dozen apps and the same handful of sites — and ticking that dozen a second time by hand is
     * where people give up and keep one profile that is wrong for both. This adds; it never
     * removes, so a copy can never quietly unblock something the target profile already had.
     */
    fun copyBlocksFrom(source: String, target: String) {
        viewModelScope.launch {
            val apps = (blockedApps(target) + blockedApps(source)).distinct()
            Policy.setBlockedApps(runtime.policy.configToml(), target, apps)
                .onSuccess { toml ->
                    runtime.setConfig(toml)
                        .onFailure { say(it.message ?: "That change could not be saved.") }
                }
                .onFailure { say(it.message ?: "That change could not be saved.") }
            // Everything that is not an app package — domains, urls, keywords — one at a time,
            // because those go through the ordinary rule path rather than the picker's.
            val had = rulesBeyondApps(target).map { it.target }.toSet()
            rulesBeyondApps(source).filterNot { had.contains(it.target) }.forEach { rule ->
                runtime.saveRule(target, rule)
                    .onFailure { say(it.message ?: "That could not be blocked.") }
            }
            refresh()
        }
    }

    /** The packages the picker should open with ticked, for [profile]. */
    /**
     * The daily budget this profile rations its apps by, in seconds, or null if it has none.
     *
     * Deliberately not read through [rulesBeyondApps]: a budget is written onto the app rules
     * themselves, which that helper filters out, so the profile screen was asking the one list
     * that could never answer and showed a budget it had just saved as unset.
     */
    fun budgetSeconds(profile: String): Int? =
        runCatching { runtime.rules(profile) }.getOrDefault(emptyList())
            .firstNotNullOfOrNull { (it.action as? Action.Budget)?.seconds }

    /**
     * One line naming what a profile takes away: "2 apps · 1 site · 1h a day".
     *
     * The Plan page shows a profile once, with its reasons nested under it, so this is the only
     * place the contents get counted — a card that named neither would be a title and a switch.
     */
    fun describeBlocks(profile: String): String {
        val apps = blockedApps(profile).size
        val sites = rulesBeyondApps(profile)
            .count { it.target is Target.Domain || it.target is Target.Url }
        val budget = budgetSeconds(profile)
        val parts = buildList {
            if (apps > 0) add("$apps app" + if (apps == 1) "" else "s")
            if (sites > 0) add("$sites site" + if (sites == 1) "" else "s")
            if (budget != null) add(spellDuration(budget / 60) + " a day")
        }
        return if (parts.isEmpty()) "Nothing yet" else parts.joinToString(" · ")
    }

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

        /** One-second ticks between two full refreshes. Every write forces one regardless. */
        private const val SLOW_TICKS = 5
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
/** One calendar rule catching one event: the rule's id, and the profile it runs. */
data class EventRule(val schedule: String, val profile: String)

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
    /**
     * The device's own calendar entries for the window Curfew reads, soonest first.
     *
     * On screen so that a calendar rule can be made by pointing at a real meeting instead of
     * guessing at a wildcard: a rule written blind against a title that does not exist is a rule
     * that silently never fires, and the user has no way to tell that from a rule that works.
     */
    val calendarEvents: List<CalendarEvent> = emptyList(),
    /**
     * The calendar rules that catch each event, by event id.
     *
     * Worked out by the policy core rather than by matching titles again on this side: the badge on
     * an event says what will actually happen, and a second implementation of matching would
     * eventually say something else.
     */
    val eventRules: Map<String, List<EventRule>> = emptyMap(),
    /** False when Curfew has no calendar permission, which makes [calendarEvents] meaningless. */
    val calendarGranted: Boolean = false,
    val profiles: List<ProfileName> = emptyList(),
    val audit: List<AuditRow> = emptyList(),
    /** Days blocked, streaks and totals over the last fortnight. */
    val stats: Stats = Stats(),
    /**
     * Screen time before Curfew against screen time now, when both are known.
     *
     * Null until usage access has been granted and there is at least one whole day on each side of
     * the comparison. The screens show nothing rather than a comparison with a zero in it.
     */
    val screenTime: dev.curfew.app.data.ScreenTimeComparison? = null,
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
