package dev.curfew.app.enforce

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.curfew.app.curfew
import dev.curfew.policy.Observation
import kotlinx.coroutines.launch

/**
 * The lightweight detector: which app is in front, and nothing else.
 *
 * `canRetrieveWindowContent="false"` in `res/xml/foreground_tracker_config.xml`, and that single bit
 * is the whole point of this class existing separately from [UrlReaderService]. Android applies the
 * privilege per *service*: a blocker whose only enabled service cannot read a window is a blocker
 * payment and banking apps have no reason to refuse. This is the mode that costs nothing outside
 * Curfew.
 *
 * **It cannot read a screen, and that is a capability rather than a promise.** There is no
 * `rootInActiveWindow` call in this file, no node walk, and no view-id lookup anywhere it can reach —
 * `ScreenWatcher` asks its [ScreenWatcher.ObservationSource] for those and this source answers
 * `canReadWindows = false`, so the code that would use a node tree is never entered. The uninstall
 * guard is the visible casualty: it needs a window to read, so this mode does not guard uninstall at
 * all. The wizard says so and points at device admin, which is the option that costs banking apps
 * instead. See [EnforcementMode].
 *
 * URL, site and keyword rules are refused at config load in this mode rather than silently ignored,
 * because a rule the user wrote that quietly does nothing is the failure this whole audit round was
 * about.
 */
class ForegroundTracker : AccessibilityService() {

    private var watcher: ScreenWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Resolved before the watcher exists, so the first event already sees the real list rather
        // than the curated constant the shared value starts from.
        Watchers.refreshSensitive(this)
        watcher = ScreenWatcher(
            context = this,
            source = NoWindowAccess,
            launch = ::emit,
        )
        // Enforcement runs in the foreground service, not here: an accessibility service can be
        // switched off from Settings at any time, and the session it was enforcing must not stop when
        // it does.
        EnforcementService.start(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        watcher?.onEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        watcher = null
        super.onDestroy()
    }

    private fun emit(observation: Observation) {
        val runtime = curfew
        runtime.scope.launch {
            runCatching {
                val now = runtime.clock.now()
                EnforcementService.enforcer(applicationContext).onObservation(observation, now)
            }.onFailure { android.util.Log.w(TAG, "observation failed", it) }
        }
    }

    /**
     * The source for a service that may not read windows.
     *
     * Both answering methods are unreachable in practice — `ScreenWatcher` checks [canReadWindows]
     * before the guard and before any browser probe — and are written to say so rather than to throw.
     * A throw here would be a crash in the one process that must stay resident, for a case that
     * cannot happen; a `false`/`null` degrades to "nothing was read", which is what this mode means.
     */
    private object NoWindowAccess : ScreenWatcher.ObservationSource {
        override fun readUrl(packageName: String): String? = null

        override fun windowMentionsCurfew(packageName: String): Boolean = false

        override val canReadWindows: Boolean = false

        override fun onGuardFired() = Unit
    }

    companion object {
        private const val TAG = "Curfew"

        /** Whether this detector is switched on, for the wizard and the health screen. */
        fun isEnabled(context: Context): Boolean =
            Watchers.isEnabled(context, EnforcementMode.APP_ONLY)

        fun settingsIntent(): Intent =
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
