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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Curfew's own screens, the system UI and the launcher are never blocked: blocking them is
        // how a blocker makes a phone unusable, which the design forbids outright.
        if (packageName == packageName()) return

        val runtime = curfew
        runtime.scope.launch {
            EnforcementService.enforcer(applicationContext)
                .onObservation(Observation.App(packageName), runtime.clock.now())
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Enforcement runs in the foreground service, not here: an accessibility service can be
        // switched off from Settings at any time, and the session it was enforcing must not stop
        // when it does.
        EnforcementService.start(applicationContext)
    }

    private fun packageName(): String = applicationContext.packageName

    companion object {
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
