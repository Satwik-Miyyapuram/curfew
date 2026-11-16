package dev.curfew.app.enforce

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.curfew.app.curfew
import dev.curfew.policy.Observation
import kotlinx.coroutines.launch

/**
 * The detector that can read a browser's address bar, and the one that costs something.
 *
 * `canRetrieveWindowContent="true"` in `res/xml/url_reader_config.xml`, and that bit is why this is a
 * separate declaration rather than a flag on one class. Android applies the privilege per
 * *service*, so a device running this one is a device where payment and banking apps are entitled to
 * refuse — Google Pay says the accessibility settings could let another app see the screen, which is
 * true, and no `packageNames` filter changes it, because the check is on the capability and not on
 * behaviour.
 *
 * **What is actually read, exactly.** One thing, and nothing else:
 *
 *  - The address bar of a package on [BrowserUrlExtractor]'s curated list, by view id. Nothing else
 *     in the browser is looked at.
 *
 * And two things are never read, structurally rather than by review: a package in
 * [ScreenWatcher]'s never-block list, and a package on the sensitive list — payment, banking, wallet
 * and credential apps — which `ScreenWatcher` short-circuits before any node
 * access. So while this service does make banks warn, it never reads a bank, and the health screen
 * says exactly that rather than the flat claim that Curfew reads screens.
 *
 * **The only accessibility service.** There were two, on the argument that a second declaration with
 * `canRetrieveWindowContent="false"` would be one no banking app could object to, at the price of URL
 * rules. That was tested and the payoff turned out to be imaginary: banks
 * refuse to run with the capability-free service enabled just the same, because what they detect is
 * an accessibility service being *active*, not what it is permitted to read. So the second service
 * was removed and this one does everything. See [EnforcementMode].
 */
class UrlReaderService : AccessibilityService() {

    private var watcher: ScreenWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Watchers.reader = this
        // Resolved before the watcher exists, so the first event already sees the real list rather
        // than the curated constant the shared value starts from.
        Watchers.refreshSensitive(this)
        watcher = ScreenWatcher(
            context = this,
            source = BrowserAndWindowAccess(),
            launch = ::emit,
        )
        refreshSurface()
        EnforcementService.start(applicationContext)
    }

    /**
     * Right-size the event subscription to the rules that exist.
     *
     * Called when the service connects and from the enforcement tick, so a user who writes their first
     * site rule waits at most one tick to have it enforced, and a user who has none stops paying for
     * the content-event firehose. See [ServiceSurface] for why this can only ever narrow what the XML
     * already asked for, and why a failure here leaves the service with the more capable subscription
     * rather than a broken one.
     *
     * The failure is logged rather than retried in a loop: if the platform will not accept the
     * change, retrying it every second would be a wake-up per second to achieve nothing.
     */
    fun refreshSurface() {
        val runtime = runCatching { curfew }.getOrNull() ?: return
        // Default true on a policy that will not answer: the more capable subscription is the safe
        // direction, and the rules are still there when it can be read.
        val needed = runCatching { runtime.policy.needsUrlReading() }.getOrDefault(true)
        if (!ServiceSurface.apply(this, webRulesExist = needed)) {
            android.util.Log.w(
                TAG,
                "could not right-size the accessibility subscription; the config's own subscription " +
                    "stands, which reads more than this config needs",
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        watcher?.onEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        // Both of these before `super`, because the block screen reads the browser from here and a
        // stale package name would send its "open a new tab" at a browser the user left hours ago.
        Watchers.reader = null
        Watchers.activeBrowserPackage = null
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
     * The source that may read a window, and the only one that may.
     *
     * The one read is the whole of it, and it is bounded: [readUrl] asks for one named view id in a
     * package already known to be a browser.
     */
    private inner class BrowserAndWindowAccess : ScreenWatcher.ObservationSource {
        override fun readUrl(packageName: String): String? {
            // The id list, not the package name: `extractUrl` cannot fabricate an id, so a package
            // that is not on the curated list cannot reach the node lookup at all.
            val ids = BrowserUrlExtractor.urlBarIds(packageName) ?: return null
            return runCatching {
                BrowserUrlExtractor.extractUrl(rootInActiveWindow, ids)
            }.getOrNull()
        }

        override val canReadWindows: Boolean = true
    }

    companion object {
        private const val TAG = "Curfew"

        /** Whether this detector is switched on, for the wizard and the health screen. */
        fun isEnabled(context: Context): Boolean =
            Watchers.isEnabled(context, EnforcementMode.PRECISE)

        fun settingsIntent(): Intent =
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
