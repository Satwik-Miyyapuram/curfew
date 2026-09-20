package dev.curfew.app.enforce

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.curfew.app.curfew
import dev.curfew.policy.Observation
import kotlinx.coroutines.launch

/**
 * How Curfew knows which app is in front.
 *
 * The accessibility API is the only way on modern Android to learn that the foreground app changed
 * *at the moment it changes*, which is what makes a block feel like a closed door rather than a
 * delayed complaint. `UsageStatsPoller` is the fallback for users who will not grant it, and it is
 * noticeably worse — see the health screen, which says so honestly.
 *
 * The service reads a package name and nothing else: window content retrieval is off in the
 * configuration, so it is not capable of reading the screen even if it wanted to.
 */
class CurfewAccessibilityService : AccessibilityService() {

    private var lastBrowserPackage: String? = null
    private var lastBrowserUrl: String? = null
    private var lastUrlCheckTime: Long = 0L
    private var lastNonBrowserPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // Curfew's own screens, the system UI and the launcher are never blocked: blocking them is
        // how a blocker makes a phone unusable, which the design forbids outright.
        if (packageName == packageName() || packageName == "com.android.systemui") return

        // Before anything else: is this the screen that would remove Curfew mid-lock? Cheap to
        // ask, and it has to be asked here because this is the only moment the window is known.
        if (guard(packageName)) return

        val isBrowser = BrowserUrlExtractor.isBrowser(packageName)

        // For non-browsers, we only care about window state changes or foreground package switches
        if (!isBrowser && eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && packageName == lastNonBrowserPackage) {
            return
        }

        val runtime = curfew

        if (isBrowser) {
            lastNonBrowserPackage = null
            activeBrowserPackage = packageName
            // Throttle content-changed URL extraction to avoid hammering accessibility node tree
            val uptime = android.os.SystemClock.elapsedRealtime()
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                uptime - lastUrlCheckTime < 250L
            ) {
                return
            }
            lastUrlCheckTime = uptime

            val root = rootInActiveWindow
            val urlString = BrowserUrlExtractor.extractUrl(root, packageName)

            if (urlString != null) {
                if (packageName == lastBrowserPackage && urlString == lastBrowserUrl &&
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                ) {
                    return
                }
                lastBrowserPackage = packageName
                lastBrowserUrl = urlString

                val parsedUrl = dev.curfew.policy.Url.parse(urlString)
                runtime.scope.launch {
                    val enforcer = EnforcementService.enforcer(applicationContext)
                    val now = runtime.clock.now()
                    if (runtime.decide(Observation.App(packageName), now) is dev.curfew.policy.Decision.Block) {
                        enforcer.onObservation(Observation.App(packageName), now)
                    } else {
                        val decision = runtime.decide(Observation.Web(parsedUrl), now)
                        if (decision is dev.curfew.policy.Decision.Block) {
                            // Send Back action to the browser so the tab navigates away from the blocked URL
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        enforcer.onObservation(Observation.Web(parsedUrl), now)
                    }
                }
                return
            } else {
                // In a browser, but no address bar URL active (e.g. New Tab, typing, or page closed)
                if (lastBrowserUrl != null) {
                    lastBrowserUrl = null
                    runtime.scope.launch {
                        val enforcer = EnforcementService.enforcer(applicationContext)
                        val now = runtime.clock.now()
                        enforcer.onObservation(Observation.App(packageName), now)
                    }
                }
            }
        }

        // If not a browser, emit app observation on window state change or package change
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || packageName != lastNonBrowserPackage) {
            lastNonBrowserPackage = packageName
            lastBrowserPackage = null
            lastBrowserUrl = null
            runtime.scope.launch {
                EnforcementService.enforcer(applicationContext)
                    .onObservation(Observation.App(packageName), runtime.clock.now())
            }
        }
    }

    /**
     * Hold Curfew's own Settings page and uninstall dialog shut while a lock runs.
     *
     * Returns true when it acted, so the caller does not also treat the screen as an app to decide
     * about. The screen is read only in this narrow case — a lock running, on one of a short list
     * of packages that can remove an app — and only to answer one question: does this window
     * mention Curfew. Nothing is stored and nothing else is looked at; see [UninstallGuard].
     */
    private fun guard(packageName: String): Boolean {
        if (!UninstallGuard.isLockHeld(this)) return false
        if (!UninstallGuard.watches(packageName)) return false
        val mentions = runCatching {
            UninstallGuard.mentions(rootInActiveWindow, appLabel(), packageName())
        }.getOrDefault(false)
        if (!UninstallGuard.shouldIntervene(packageName, lockHeld = true, mentionsCurfew = mentions)) {
            return false
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Said out loud, because a Back press that arrives with no explanation reads as the phone
        // being broken rather than as the lock the user asked for.
        android.widget.Toast.makeText(this, UninstallGuard.EXPLANATION, android.widget.Toast.LENGTH_LONG)
            .show()
        return true
    }

    private fun appLabel(): String =
        runCatching { applicationInfo.loadLabel(packageManager).toString() }.getOrDefault("Curfew")

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Enforcement runs in the foreground service, not here: an accessibility service can be
        // switched off from Settings at any time, and the session it was enforcing must not stop
        // when it does.
        EnforcementService.start(applicationContext)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun packageName(): String = applicationContext.packageName

    companion object {
        @Volatile
        var instance: CurfewAccessibilityService? = null

        @Volatile
        var activeBrowserPackage: String? = null

        /** Whether the user has granted the service, for the permission wizard and health screen. */
        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.split(':').any {
                it.substringBefore('/') == context.packageName
            }
        }

        fun settingsIntent(): Intent =
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
