package dev.curfew.app.enforce

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import dev.curfew.policy.Observation

/**
 * How Curfew learns what is in front.
 *
 * **There was a choice here, and it was removed because it bought nothing.** Android applies
 * `canRetrieveWindowContent` per *service*, not per package, so a service that can read any window
 * is treated as one that does — and the reasoning was that a second service holding
 * `canRetrieveWindowContent="false"` would be one no payment or banking app could object to, while
 * giving up URL rules. A fresh install was offered that one.
 *
 * The argument was sound and the payoff was imaginary. Banking and payment apps refuse to run with
 * the capability-free service enabled just the same, because what they detect is an accessibility
 * service being *active*, not what it is permitted to read. So the second declaration bought no
 * compatibility at all and cost a choice the user had to reason about — and, worse, cost the
 * every site rule for anyone who took the offer.
 *
 * What is left is the single service that can do everything, and there is no longer a mode with
 * capability missing from it. See [UrlReaderService], and `ScreenWatcher` for the fact that payment,
 * banking and credential apps are still never read.
 */
enum class EnforcementMode {
    /**
     * The only mode. Kept as a named value rather than deleted, because `ConfigStore` filters the
     * plan by what the running service can enforce and that filter has to be explicit rather than
     * implied by there being no other option.
     */
    PRECISE,
    ;

    /** Always true now: the one service reads windows, which is what makes site rules work. */
    val readsWindowContent: Boolean get() = true

    /** The accessibility service component this mode runs. */
    fun serviceClass(): Class<out AccessibilityService> = UrlReaderService::class.java

    companion object {
        /**
         * What is running now, from the system's own list of enabled services.
         *
         * Read from `Settings.Secure` rather than from a stored preference, because the preference is
         * a request and this is the fact: the service can be turned off from Settings at any moment,
         * and what matters for every screen and every decision is whether it is on. Null when it is
         * not.
         */
        fun current(context: Context): EnforcementMode? =
            if (Watchers.isEnabled(context, PRECISE)) PRECISE else null

        /** The mode a fresh install is offered. There is one. */
        val DEFAULT = PRECISE

        private const val PREFS = "curfew_enforcement"
        private const val KEY_MODE = "mode"
        private const val KEY_A11Y = "accessibility_wanted"

        /**
         * The mode the user chose, or null when they have never chosen.
         *
         * Kept because the choice has to survive the gap between being made and being granted: the
         * user picks a mode, is sent to Settings, and comes back — and every screen in between has to
         * show the mode they are mid-way through granting rather than the one still running.
         *
         * It is a *request*, never a fact. What is running is [Detector.current], read from the system,
         * and the two disagreeing is the ordinary case rather than an error: the user may have turned
         * the service off in Settings, or granted the other one.
         */
        fun stored(context: Context): EnforcementMode? {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, null)
                ?: return null
            // APP_ONLY and APP_AND_URL were removed with the window-less tracker. Anyone who chose
            // either asked for precise detection, which the URL reader now provides.
            if (raw == "APP_ONLY" || raw == "APP_AND_URL") return PRECISE
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
 * What is actually watching for the foreground app — the question every screen really wants answered.
 *
 * **This exists because "which mode did you pick" and "what is running" were being conflated, and
 * neither one is a detector.** There are two accessibility services and a poller, and the poller is
 * not a mode: it is what runs when no accessibility service is granted. Three states, then, and the
 * app only ever talked about the first two:
 *
 *  - [Reader] — the accessibility service that reads browser address bars. Precise, blocks sites, warns banks.
 *  - [Poller] — `UsageStatsManager`, needing only `PACKAGE_USAGE_STATS`. **This is app blocking with
 *    no accessibility at all**, and it is what `docs/ARCHITECTURE.md` has always claimed exists
 *    ("Losing one permission weakens blocking; it never disables the app") while the interface said
 *    the opposite and marked the accessibility grant *required*.
 *
 * The honest cost of [Poller], which every screen must say when it is in use: it samples, so there is a
 * lag of up to a poll interval before a block appears; it cannot re-assert a block against a user who
 * keeps returning to the app; and it cannot block a site, because a URL is not something usage stats
 * record. It is a real detector and a weaker one, and calling it a fallback is not the same as calling
 * it useless.
 */
enum class Detector {
    Reader,
    Poller,
    ;

    /** Whether this detector reads window content. Only the reader does. */
    val readsWindowContent: Boolean get() = this == Reader

    /** Whether this detector can enforce a url, keyword or domain rule. */
    val blocksSites: Boolean get() = this == Reader

    /** Whether a block appears the moment the app opens, rather than within a poll interval. */
    val isPrecise: Boolean get() = this != Poller

    /** Whether payment and banking apps are entitled to refuse to run beside it. */
    val warnsBanks: Boolean get() = this == Reader

    /**
     * The accessibility service behind this detector, or null for the poller.
     *
     * Null is the point rather than an inconvenience: a caller that wants to open a Settings page has
     * to handle "there is no page for this" instead of being handed one that does not exist.
     */
    fun serviceClass(): Class<out AccessibilityService>? = when (this) {
        Reader -> UrlReaderService::class.java
        Poller -> null
    }

    companion object {
        /**
         * What is watching right now, preferring the one the user asked for.
         *
         * Read from the system's own enabled-service list rather than from the preference, because the
         * preference is a request and this is the fact: either service can be turned off from Settings
         * at any moment. Falls back to [Poller] — which is to say, to no accessibility service at all,
         * since the poller needs no component to be enabled — rather than returning null, because
         * something *is* always watching: `EnforcementService` runs either way.
         */
        fun current(context: Context): Detector =
            if (Watchers.isEnabled(context, EnforcementMode.PRECISE)) Reader else Poller

        /** Whether any accessibility detector is running, as opposed to the poller. */
        fun accessibilityRunning(context: Context): Boolean =
            Watchers.anyEnabled(context)
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

    private const val PREFS = "curfew_enforcement"
    private const val KEY_A11Y = "accessibility_wanted"

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
     * Whether the user wants an accessibility service at all.
     *
     * **The unified switch.** There are two services because Android applies the window-content
     * privilege per *service*, but there is one decision — do you want precise blocking, or would you
     * rather not grant accessibility at all. That decision lives here; which service it maps to, and
     * what happens when it is off, is [EnforcementMode]'s and [Detector]'s business.
     *
     * Defaults to true: a user who has never chosen has also never been told there is a choice, and
     * the precise detector is the one the app is designed around.
     *
     * A *request*, like [EnforcementMode.stored] and for the same reason — we cannot enable or disable
     * an accessibility service ourselves. `CHANGE_COMPONENT_ENABLED_STATE` is a system permission, and
     * a component disabled by us would vanish from the list the user needs to reach later. What is
     * actually running is [Detector.current], read from the system.
     */
    fun wantsAccessibility(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_A11Y, true)

    /** Record the choice. See [wantsAccessibility] for why this is not the source of truth. */
    fun rememberAccessibilityWanted(context: Context, wanted: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_A11Y, wanted)
            .apply()
    }

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

    /**
     * Switch the reader off from inside, so a payment app stops refusing to run beside it.
     *
     * `disableSelf` is the one change to the enabled list an app may make without a system
     * permission, and only the running service can make it. Refused while a lock is held, so the
     * one-tap way out is never easier than the deliberate one through Android's accessibility list. False when nothing was turned off: the
     * reader is not running, or a lock holds it, and the caller sends the user to Settings instead.
     */
    fun turnOffReader(context: Context): Boolean {
        if (LockFlag.isHeld(context)) return false
        val running = reader ?: return false
        running.disableSelf()
        return true
    }

    /** Whether either detector is on, which is what decides if the fallback poller is needed. */
    fun anyEnabled(context: Context): Boolean =
        isEnabled(context, EnforcementMode.PRECISE)

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
    /** What the source of observations can read. */
    interface ObservationSource {
        /** The browser URL bar text for [packageName], or null if there is none to read. */
        fun readUrl(packageName: String): String?

        /** Whether this source can read a window at all. */
        val canReadWindows: Boolean
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
     * The packages Curfew never blocks: its own screens and [Enforcer.ESCAPE_ROUTES].
     *
     * Blocking any of them is how a blocker makes a phone unusable, which the design forbids outright.
     */
    private val neverBlocked: Set<String> =
        setOf(context.packageName) + Enforcer.ESCAPE_ROUTES

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

        // 1. Sensitive apps, before everything else. They stay blockable as apps, from the package
        //    name alone, and are never read: no browser probe, no node walk, nothing.
        if (packageName in sensitive) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                emit(Observation.App(packageName))
            }
            return
        }

        // 2. The never-block list. Settings and the uninstaller are on it, so a plain uninstall
        //    is always the way out of any lock: Curfew never stands in front of its own removal.
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
        val DEFAULT_LAUNCHERS = setOf(
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
