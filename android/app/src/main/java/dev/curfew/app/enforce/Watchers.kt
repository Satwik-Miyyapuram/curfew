package dev.curfew.app.enforce

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import dev.curfew.policy.Observation

/**
 * How Curfew is allowed to learn what is in front, and what that costs.
 *
 * **This is a choice the user makes, because it cannot be made for them.** Android applies
 * `canRetrieveWindowContent` per *service*, not per package. A service that can read any window is
 * treated as one that does, and payment and banking apps refuse to start on that basis alone —
 * Google Pay says the device's accessibility settings could allow another app to see the screen, and
 * the only way to stop it saying that is to not run a service holding the capability at all.
 *
 * So there are two services and the app runs exactly one of them:
 *
 *  - [APP_ONLY] — [ForegroundTracker]. `canRetrieveWindowContent="false"`. Blocks apps from the
 *    window-state event alone, touches no node tree, and no banking app can object to it. What it
 *    gives up: no URL, site or keyword rules, and **no uninstall guard** — the guard reads a window
 *    to find out whether it is looking at Curfew's own removal screen, which is precisely the
 *    capability this mode does not have. Device admin is the remaining way to resist uninstall, and
 *    it is the option that costs banking apps instead; the wizard says so rather than picking.
 *
 *  - [APP_AND_URL] — [UrlReaderService]. Reads a known browser's address bar by view id, and runs the
 *    uninstall guard. What it costs: the banking-app warning above, avoidable only by not holding the
 *    capability. What survives anyway: payment, banking and credential apps are still never read —
 *    see [SensitiveApps] — so the warning is about the capability, not about anything Curfew
 *    actually does with a bank's screen.
 *
 * The two modes share every decision about what an event *means*; only the node access differs. See
 * [ScreenWatcher].
 */
enum class EnforcementMode {
    APP_ONLY,
    APP_AND_URL,
    ;

    /** Whether this mode reads window content at all. False for the mode that cannot. */
    val readsWindowContent: Boolean get() = this == APP_AND_URL

    /** Whether this mode can run the uninstall guard, which needs a window to look at. */
    val guardsUninstall: Boolean get() = readsWindowContent

    /**
     * The accessibility service component this mode runs.
     *
     * The two are mutually exclusive on purpose. Having both enabled is the worst of both worlds: the
     * banking warning is present because one of them holds the capability, and nothing is gained that
     * the other does not already do.
     */
    fun serviceClass(): Class<out AccessibilityService> = when (this) {
        APP_ONLY -> ForegroundTracker::class.java
        APP_AND_URL -> UrlReaderService::class.java
    }

    companion object {
        /**
         * What is running now, from the system's own list of enabled services.
         *
         * Read from `Settings.Secure` rather than from a stored preference, because the preference is
         * a request and this is the fact: a user can turn either service off from Settings at any
         * moment, and what matters for every screen and every decision is which one is on. Null when
         * neither is.
         *
         * [APP_AND_URL] wins when both are somehow on, because it is the one that can do everything —
         * and the wizard's job at that point is to tell the user to turn the other one off.
         */
        fun current(context: Context): EnforcementMode? = when {
            Watchers.isEnabled(context, APP_AND_URL) -> APP_AND_URL
            Watchers.isEnabled(context, APP_ONLY) -> APP_ONLY
            else -> null
        }

        /** The mode a fresh install is offered: the one that costs nothing outside Curfew. */
        val DEFAULT = APP_ONLY

        private const val PREFS = "curfew_enforcement"
        private const val KEY_MODE = "mode"

        /**
         * The mode the user chose, or null when they have never chosen.
         *
         * Kept because the choice has to survive the gap between being made and being granted: the
         * user picks a mode, is sent to Settings, and comes back — and every screen in between has to
         * show the mode they are mid-way through granting rather than the one still running.
         *
         * It is a *request*, never a fact. What is running is [current], read from the system, and the
         * two disagreeing is the ordinary case rather than an error: the user may have turned the
         * service off in Settings, or granted the other one.
         */
        fun stored(context: Context): EnforcementMode? {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, null)
                ?: return null
            return runCatching { valueOf(raw) }.getOrNull()
        }

        /** Record the user's choice. See [stored] for why this is not the source of truth. */
        fun remember(context: Context, mode: EnforcementMode) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name)
                .apply()
        }
    }
}

/**
 * Which of Curfew's two accessibility services are on, and what the URL reader is currently doing.
 *
 * A small object rather than fields on one of the services, because the two are alternatives and
 * several callers need to ask about both: the health screen, the permission wizard, the block screen
 * (which needs to know which browser to open a new tab in) and the enforcement service (which needs
 * to know whether the fallback poller is required).
 */
object Watchers {

    /** The running URL reader, for the one global action an enforcer needs to perform. */
    @Volatile
    var reader: UrlReaderService? = null

    /**
     * The browser the user is in, for the block screen's "open a new tab".
     *
     * Advisory only: it is a hint about which app to hand an `ACTION_VIEW` to, and the block screen
     * lets the system choose when it is null. Cleared when the user leaves a browser as well as when
     * the reader stops, so it cannot point at a browser that has not been in front for hours.
     */
    @Volatile
    var activeBrowserPackage: String? = null

    /**
     * The apps no reader may look at, as of the last time anything asked.
     *
     * Held here rather than re-resolved per event, because the check runs on the first line of every
     * accessibility event and a `PackageManager` walk there would be a walk per repaint. It is
     * refreshed at the moments the answer can have changed — when a service connects, and when a
     * package is installed or removed — rather than on a timer, because those are the only moments it
     * *does* change.
     *
     * The initial value is the curated list, which is a constant and therefore already correct: the
     * two dynamic sources arrive with the first [refreshSensitive].
     */
    @Volatile
    private var sensitive: Set<String> = SensitiveApps.CURATED

    /** The resolved sensitive list. A set lookup, for the hot path. */
    fun sensitive(): Set<String> = sensitive

    /**
     * Re-resolve the sensitive list from the package manager.
     *
     * Called when a service connects and by [PackageChangeReceiver]: a bank installed while Curfew is
     * running becomes unreadable then, rather than at the next service start.
     */
    fun refreshSensitive(context: Context) {
        sensitive = SensitiveApps.resolve(context)
    }

    /**
     * Whether the service for [mode] is switched on.
     *
     * From the secure setting rather than from the service instance, because the instance is null
     * both when the service is off and when the process has just started — and those two mean very
     * different things to a user staring at a health screen.
     *
     * Compared as flattened `package/class` strings, which is the form the setting uses, rather than
     * by constructing a `ComponentName` from `Class`: that spelling depends on the package being able
     * to resolve its own class name, which is exactly the kind of thing that differs between a debug
     * build and an installed one.
     */
    fun isEnabled(context: Context, mode: EnforcementMode): Boolean {
        val expected = serviceName(context, mode)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** Whether either detector is on, which is what decides if the fallback poller is needed. */
    fun anyEnabled(context: Context): Boolean =
        isEnabled(context, EnforcementMode.APP_ONLY) || isEnabled(context, EnforcementMode.APP_AND_URL)

    /** The `package/class` spelling of the service one mode uses. */
    fun serviceName(context: Context, mode: EnforcementMode): String =
        ComponentName(context, mode.serviceClass()).flattenToString()

    /**
     * The accessibility page for the service one mode uses.
     *
     * Android 11 and later can open the detail page for one service, which is where the toggle is.
     * Before that there is only the list, so the caller falls back to it — and the detail page is
     * also where the user turns the *other* service off, which is why the wizard links here.
     *
     * The action and the extra are spelled out as literals because `Settings` carries no public
     * constants for them: they exist only as `@SystemApi` strings, so the documented values are the
     * only way to reach the page. Both uses are behind the API check above, because the page does not
     * exist before 30 and an unguarded intent would silently open nothing.
     */
    fun settingsIntent(context: Context, mode: EnforcementMode): android.content.Intent {
        val list = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return list
        return android.content.Intent(ACTION_ACCESSIBILITY_DETAILS)
            .putExtra(EXTRA_ACCESSIBILITY_COMPONENT, serviceName(context, mode))
    }

    private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val EXTRA_ACCESSIBILITY_COMPONENT =
        "android.provider.extra.ACCESSIBILITY_COMPONENT_NAME"
}

/**
 * What one accessibility service does with an event, once the exemptions have been applied.
 *
 * The two modes differ in exactly one thing — whether a node tree may be read — and that difference
 * is [ObservationSource]. Everything else is shared, and shared *here* rather than copied into the
 * two services, because the version of this that lived in one long `onAccessibilityEvent` had three
 * exit styles and emitted two observations for one event.
 */
class ScreenWatcher(
    private val context: Context,
    /**
     * What this watcher is allowed to ask the screen for. The mode is not passed separately: the
     * source *is* the difference between the modes, and keeping both would be two things to keep in
     * step.
     */
    private val source: ObservationSource,
    /** Called with each observation, once. The caller decides where it goes. */
    private val launch: (Observation) -> Unit,
) {
    /**
     * What the source of observations can read.
     *
     * The uninstall guard belongs to the *source* rather than to the watcher because it needs
     * `rootInActiveWindow`, and only one of the two services has it. That is the honest shape of the
     * trade-off in [EnforcementMode]: a mode without window content cannot watch for its own removal
     * screen, and a guard that silently never fires would be worse than one that is absent and said
     * to be absent.
     */
    interface ObservationSource {
        /** The browser URL bar text for [packageName], or null if there is none to read. */
        fun readUrl(packageName: String): String?

        /** Whether a window belonging to [packageName] mentions Curfew. Not called unless [canReadWindows]. */
        fun windowMentionsCurfew(packageName: String): Boolean

        /** Whether this source can answer [windowMentionsCurfew] at all. */
        val canReadWindows: Boolean

        /** Do whatever a fired guard does: press Back, and explain why. */
        fun onGuardFired()
    }

    private var lastBrowserPackage: String? = null
    private var lastBrowserUrl: String? = null
    private var lastUrlCheckTime: Long = 0L
    private var lastNonBrowserPackage: String? = null

    /**
     * The identity of the last thing emitted, so the same foreground does not produce a decision per
     * repaint. Separate from the fields above because what matters is what was *emitted*, not what
     * was last seen.
     */
    private var lastEmittedKey: String? = null

    /**
     * The packages Curfew never blocks: its own screens, the system UI and the launcher.
     *
     * Blocking any of them is how a blocker makes a phone unusable, which the design forbids outright.
     */
    private val neverBlocked: Set<String> =
        setOf(context.packageName, "com.android.systemui") + DEFAULT_LAUNCHERS

    /**
     * The sensitive list, read through [Watchers] rather than copied.
     *
     * A copy taken here goes stale the moment a package is installed, because only the holder of the
     * copy knows to refresh it — and the thing that *does* know, [PackageChangeReceiver], has no
     * reference to this watcher. So the shared value is read at each event instead: one volatile read
     * and a set lookup, and a bank installed a minute ago is unreadable without a service restart.
     */
    private val sensitive: Set<String> get() = Watchers.sensitive()

    fun onEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        // 1. The uninstall guard, before the exemptions — the screen that removes Curfew can be a
        //    systemui window on several vendors, and the guard is safe there: it acts only while a
        //    lock is held, only on a watched package, and only when the window mentions Curfew.
        if (guard(packageName)) return

        // 2. Sensitive apps, after the guard (a bank never uninstalls Curfew, so deferring costs
        //    nothing) and before everything else. They stay blockable as apps, from the package name
        //    alone, and are never read: no browser probe, no node walk, nothing.
        if (packageName in sensitive) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                emit(Observation.App(packageName))
            }
            return
        }

        // 3. The never-block list, now that the guard has had its look.
        if (packageName in neverBlocked) return

        val observation = classify(packageName, eventType) ?: return
        emit(observation)
    }

    /** Forget the browser and the last emission: the user has gone somewhere that is not a page. */
    fun leaveBrowser() {
        Watchers.activeBrowserPackage = null
        lastBrowserPackage = null
        lastBrowserUrl = null
    }

    /** Drop the suppressed-identity memory, so the next event is judged on its own. */
    fun forget() {
        lastEmittedKey = null
    }

    /**
     * What the user is looking at, from one event. Null when there is nothing worth saying.
     *
     * Exactly one [Observation] comes out of this.
     */
    private fun classify(packageName: String, eventType: Int): Observation? {
        val urlBarIds = BrowserUrlExtractor.urlBarIds(packageName)
        val canRead = urlBarIds != null && source.canReadWindows

        if (!canRead) {
            // Either a plain app, or a browser in a mode that may not read one — which from here on
            // is the same thing: what is in front is an app, and that is what gets judged.
            leaveBrowser()
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                packageName == lastNonBrowserPackage
            ) {
                // A repaint of what is already in front says nothing new. Window-state changes
                // always do, and so does a different package.
                return null
            }
            lastNonBrowserPackage = packageName
            return Observation.App(packageName)
        }

        // A known browser, and this source may read its address bar.
        lastNonBrowserPackage = null
        Watchers.activeBrowserPackage = packageName

        // An address bar repaint is not a navigation, and reading it is a binder call into the
        // browser. `notificationTimeout` in the service config is the same figure, so the framework
        // coalesces the burst as well and this is the belt to that braces.
        val uptime = android.os.SystemClock.elapsedRealtime()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            uptime - lastUrlCheckTime < URL_RECHECK_MILLIS
        ) {
            return null
        }
        lastUrlCheckTime = uptime

        val urlString = source.readUrl(packageName)
        if (urlString == null) {
            // A browser with no address bar URL: a new tab, or the user typing. The browser as an
            // *app* is still what is in front, so say that — but only when the last thing said was a
            // URL, so this is a transition and not a repetition.
            if (lastBrowserUrl == null) return null
            lastBrowserUrl = null
            lastBrowserPackage = packageName
            return Observation.App(packageName)
        }

        if (packageName == lastBrowserPackage && urlString == lastBrowserUrl &&
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return null
        }
        lastBrowserPackage = packageName
        lastBrowserUrl = urlString
        return Observation.Web(dev.curfew.policy.Url.parse(urlString))
    }

    /**
     * Hold Curfew's own Settings page and uninstall dialog shut while a lock runs.
     *
     * Returns true when it acted, so the caller does not also treat the screen as an app to decide
     * about. Available only where window content is; see [ObservationSource].
     */
    private fun guard(packageName: String): Boolean {
        if (!source.canReadWindows) return false
        if (!UninstallGuard.isLockHeld(context)) return false
        if (!UninstallGuard.watches(packageName)) return false
        val mentions = source.windowMentionsCurfew(packageName)
        if (!UninstallGuard.shouldIntervene(packageName, lockHeld = true, mentionsCurfew = mentions)) {
            return false
        }
        source.onGuardFired()
        return true
    }

    /**
     * Hand one observation on, once.
     *
     * The decision is made inside `Enforcer.onObservation` and nowhere else: this class decides what
     * the user is looking at, and the enforcer decides what to do about it. The version of this that
     * also asked the policy — once for the browser as an app, once for the URL, and a third time
     * inside the enforcer — made one address-bar repaint cost three full evaluations, each of which
     * read 25 hours of history out of an encrypted database.
     */
    private fun emit(observation: Observation) {
        val key = identity(observation) ?: return
        // A notification is not a foreground state and must never set one; it is also never a
        // repetition, because two notifications from the same app are two notifications. So it goes
        // through without touching the gate.
        if (observation !is Observation.Notification) {
            if (key == lastEmittedKey) return
            lastEmittedKey = key
        }
        launch(observation)
    }

    /** How one observation is told from another, for suppression. */
    private fun identity(observation: Observation): String? = when (observation) {
        is Observation.App -> "app:${observation.`package`}"
        is Observation.Web -> "web:${observation.url.raw}"
        is Observation.Notification -> "notif:${observation.`package`}"
        else -> null
    }

    companion object {
        /**
         * How long an address bar is trusted before it is read again.
         *
         * Matches `android:notificationTimeout` in the reader's service config, so the framework
         * coalesces the burst and this is the belt to that braces.
         */
        const val URL_RECHECK_MILLIS = 250L

        /**
         * Home screens that are never blocked.
         *
         * Enumerated rather than discovered, because discovering them means asking the
         * `PackageManager` for the current home app. These are the launchers a Curfew user is
         * actually running; anything else falls through to being an app like any other, which is the
         * failure that costs least.
         */
        private val DEFAULT_LAUNCHERS = setOf(
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.huawei.android.launcher",
            "com.teslacoilsw.launcher",
        )
    }
}
