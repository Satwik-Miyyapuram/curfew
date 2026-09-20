package dev.curfew.app.enforce

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.curfew.app.ui.InstalledAppsCache

/**
 * Notices that the set of installed apps changed, and drops everything that was derived from it.
 *
 * **Why this exists.** Two caches are built from the package list and neither is rebuilt by anything
 * else. `InstalledAppsCache` holds the app picker's candidates for the life of the process and its
 * `clear()` was called from nowhere, so an app installed after Curfew started did not appear in the
 * picker until the process died. And each `ScreenWatcher` holds the sensitive list resolved when its
 * service connected, so a bank installed afterwards was not yet unreadable by the running service.
 *
 * The second of those is the one that matters. Its window is bounded — the sensitive list is a
 * snapshot, and `ConfigStore` still refuses to *save* a block on a fresh installation while
 * `ScreenWatcher`'s set is behind — but "bounded" is not "closed", and the fix is one receiver for
 * three intents that the platform sends anyway.
 *
 * Registered in the manifest rather than in code, because the interesting moment is the app being
 * installed while Curfew is in the background or not running at all: a receiver registered by a
 * component only fires while that component is alive, which is exactly when it is least needed.
 *
 * `MY_PACKAGE_REPLACED` is deliberately absent — `BootReceiver` already handles it, and a receiver
 * listening for its own package being replaced is running in a process that is about to be killed.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED,
            -> {
                // The picker's list of candidates: a new app must be blockable without a restart,
                // and a removed one must stop being offered.
                InstalledAppsCache.clear()
                // The sensitive list every reader checks, so a bank installed a moment ago is
                // unreadable now rather than at the next service start. This is the one that matters:
                // a briefly stale picker is cosmetic, and a readable payment app is not.
                Watchers.refreshSensitive(context)
                // And the reader's event subscription, which depends on whether any URL rule exists.
                Watchers.reader?.refreshSurface()
            }
        }
    }
}
