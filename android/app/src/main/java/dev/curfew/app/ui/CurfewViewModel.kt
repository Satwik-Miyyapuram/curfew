package dev.curfew.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.curfew.app.data.AuditRow
import dev.curfew.app.data.CurfewRuntime
import dev.curfew.app.data.Downtime
import dev.curfew.app.curfew
import dev.curfew.policy.Activation
import dev.curfew.policy.Lock
import dev.curfew.policy.Policy
import dev.curfew.policy.ProfileName
import dev.curfew.policy.LockSet
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
                spentSeconds = spent,
                launchCounts = opens,
                configToml = runCatching { runtime.policy.configToml() }.getOrDefault(""),
                profiles = runCatching { Policy.profiles(runtime.policy.configToml()) }
                    .getOrDefault(emptyList()),
                audit = runCatching { runtime.db.audit().recent(AUDIT_SHOWN) }
                    .getOrDefault(emptyList()),
                grants = grantStates(getApplication()),
                downtime = runtime.downtime.value,
                clockTamper = runtime.clockTamper.value,
                loading = false,
            )
        }
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

    /** Start the delayed release. The instant it lands is the core's to choose, and never moves. */
    fun requestRelease(session: Session) {
        viewModelScope.launch {
            runCatching { runtime.requestRelease(session.id) }
                .onSuccess { say("Release for ${session.profile} lands ${relative(it, runtime.clock.now())}.") }
                .onFailure { say("That session cannot be released early.") }
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
    /** Seconds spent per target key today, largest first. */
    val spentSeconds: List<Pair<String, Int>> = emptyList(),
    val launchCounts: Map<String, Int> = emptyMap(),
    val configToml: String = "",
    val profiles: List<ProfileName> = emptyList(),
    val audit: List<AuditRow> = emptyList(),
    val grants: List<GrantState> = emptyList(),
    /** A stretch Curfew could not account for, until the user has seen it. */
    val downtime: Downtime? = null,
    /** A clock change that was refused, until the user has seen it. */
    val clockTamper: dev.curfew.app.data.ClockTamper? = null,
    val message: String? = null,
    val refusal: dev.curfew.policy.Refusal? = null,
    val refusedSession: String? = null,
) {
    val isEnforcing: Boolean get() = sessions.isNotEmpty()
}
