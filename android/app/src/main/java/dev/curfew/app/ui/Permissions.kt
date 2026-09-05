package dev.curfew.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import dev.curfew.app.enforce.CurfewAccessibilityService
import dev.curfew.app.enforce.CurfewDeviceAdmin
import dev.curfew.app.enforce.CurfewNotificationListener
import dev.curfew.app.enforce.UsageStatsPoller

/**
 * What Curfew is allowed to do, and what it cannot do without.
 *
 * Every one of these is asked for separately, late, and with a reason attached. A permission wizard
 * that demands everything on first launch teaches the user to tap through without reading, which is
 * how an app that watches what you are doing all day ends up trusted by accident rather than on
 * purpose. Each grant here buys exactly one named capability, and the health screen says plainly
 * what is lost while it is missing.
 */
enum class Grant(
    val title: String,
    /** Why Curfew wants it, in the user's terms. */
    val because: String,
    /** What still works without it, so the user can make a real choice. */
    val cost: String,
    val required: Boolean,
) {
    Accessibility(
        title = "Accessibility service",
        because = "Curfew needs to see which app is in front to replace it when it is blocked.",
        cost = "Nothing is blocked at all: this is the only way to act on an app the moment it opens.",
        required = true,
    ),
    UsageAccess(
        title = "Usage access",
        because = "Time budgets are counted from the system's own usage figures, which survive Curfew being killed.",
        cost = "Budgets and launch limits stop counting; blocks and schedules still work.",
        required = false,
    ),
    Overlay(
        title = "Display over other apps",
        because = "Some launchers refuse to show the block screen from the background without it.",
        cost = "A blocked app may flash into view for a moment before it is replaced.",
        required = false,
    ),
    Notifications(
        title = "Notifications",
        because = "The ongoing notification is how you can always tell that enforcement is running.",
        cost = "Enforcement still runs, but Android may show its own generic notice instead.",
        required = false,
    ),
    ExactAlarms(
        title = "Exact alarms",
        because = "A session that should start at 09:00 has to start at 09:00, not whenever the system next wakes.",
        cost = "Sessions can start up to a few minutes late.",
        required = false,
    ),
    Calendar(
        title = "Calendar",
        because = "Calendar rules read event titles to decide when a profile should run.",
        cost = "Calendar rules never match; weekly schedules are unaffected.",
        required = false,
    ),
    NotificationAccess(
        title = "Notification access",
        because = "Silencing an app's notifications is the one thing Android will not let Curfew do without it.",
        cost = "Rules that mute notifications do nothing; everything else is unaffected. Granting it lets Curfew see every notification on the device, so leave it off unless you use a mute rule.",
        required = false,
    ),
    BatteryUnrestricted(
        title = "Unrestricted battery use",
        because = "Aggressive battery savers kill background services, and a blocker that is killed blocks nothing.",
        cost = "Curfew may be stopped by the system and stop enforcing without warning.",
        required = false,
    ),

    /**
     * Last on purpose, and genuinely optional.
     *
     * This is the scariest dialog in the wizard and it has a real cost outside Curfew: a great many
     * banking and payment apps refuse to run on a device with any active device admin, and one that
     * cost somebody their bank app would have taken more than it gave. The accessibility guard
     * covers the same ground without that cost, so this is now the stronger-but-pricier option
     * rather than the recommended one.
     */
    UninstallProtection(
        title = "Uninstall protection (device admin)",
        because = "Android will not uninstall an app that is an active device admin, which closes the last easy way out of a running lock. Curfew already holds its own settings and uninstall pages shut through the accessibility service; this is the stricter version of the same idea.",
        cost = "Nothing inside Curfew changes: every block, schedule and lock works the same either way. The cost is outside it — many banking and payment apps refuse to run while any device admin is active, so leave this off if you use one. Curfew asks for no admin powers beyond being active: it cannot erase, lock or unlock the device, or touch any password, and you can turn it off whenever no lock is running.",
        required = false,
    ),
    ;

    fun isGranted(context: Context): Boolean = when (this) {
        Accessibility -> isAccessibilityEnabled(context)
        UsageAccess -> hasUsageAccess(context)
        Overlay -> Settings.canDrawOverlays(context)
        Notifications ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ExactAlarms ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Calendar ->
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        NotificationAccess -> CurfewNotificationListener.isEnabled(context)
        BatteryUnrestricted ->
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        UninstallProtection -> CurfewDeviceAdmin.isActive(context)
    }

    /**
     * Where to send the user to grant it, or null when it is an ordinary runtime permission the
     * caller should request through the permission launcher instead.
     */
    fun settingsIntent(context: Context): Intent? = when (this) {
        Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        UsageAccess -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Overlay -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        ExactAlarms ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            } else {
                null
            }
        // Asking to be exempted from battery optimisation with ACTION_REQUEST_IGNORE_... is a
        // policy violation on Play, and Curfew is distributed outside it — but the settings screen
        // is the honest route either way: the user should see the list they are changing.
        NotificationAccess -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        BatteryUnrestricted -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        // Android's own add-admin dialog, not a Settings screen: it is the only place the
        // explanation is shown at the moment the user decides.
        UninstallProtection -> CurfewDeviceAdmin.requestIntent(context)
        Notifications, Calendar -> null
    }

    /** The runtime permission to request, for the two that are ordinary runtime permissions. */
    fun runtimePermission(): String? = when (this) {
        Notifications ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                null
            }
        Calendar -> Manifest.permission.READ_CALENDAR
        else -> null
    }

    companion object {
        /**
         * Whether Curfew's accessibility service is switched on.
         *
         * Read from the secure setting rather than from the service instance, because the instance
         * is null both when the service is off and when the process has just started — and those
         * two mean very different things to a user staring at a health screen.
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            val expected = ComponentName(context, CurfewAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            return splitter.any {
                ComponentName.unflattenFromString(it)?.equals(expected) == true
            }
        }

        /** Asked in one place, in [UsageStatsPoller], so the API-level branch exists only once. */
        fun hasUsageAccess(context: Context): Boolean = UsageStatsPoller.hasPermission(context)
    }
}

/** A grant and whether it is currently held, which is all the health screen needs. */
data class GrantState(val grant: Grant, val granted: Boolean)

fun grantStates(context: Context): List<GrantState> =
    Grant.entries.map { GrantState(it, runCatching { it.isGranted(context) }.getOrDefault(false)) }

/**
 * Android 13's "Restricted setting", which is the wall every sideloaded install hits.
 *
 * An app installed outside a store session cannot be given Accessibility or notification-listener
 * access from the usual screens: the toggle is there but greyed out, and the way through is not in
 * that screen at all — it is App info → ⋮ → Allow restricted settings. Since Curfew is distributed
 * as an APK and through F-Droid, this is the ordinary case, not an edge case, and an unexplained
 * greyed-out toggle is exactly where a person gives up on a blocker.
 *
 * The restricted-settings dialog cannot be launched by an app, so the best that can be done is to
 * recognise the situation, say the menu path in words, and open App info at the right place.
 */
object RestrictedSettings {

    /**
     * Whether the user is probably looking at a greyed-out toggle right now.
     *
     * There is no API that answers this, so it is inferred: recent enough Android, installed with no
     * installing package (a plain sideload), and the accessibility service still not enabled. It can
     * be wrong in the harmless direction — showing the instructions to someone who did not need them
     * — and it stops showing the moment the service is on.
     */
    fun isLikelyBlocking(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (Grant.isAccessibilityEnabled(context)) return false
        return installedOutsideAStore(context)
    }

    private fun installedOutsideAStore(context: Context): Boolean = runCatching {
        // The version check is repeated here rather than relied on from the caller, because that is
        // what lint can see, and a silently wrong API level on an old device is worse than a
        // duplicated condition.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager
                .getInstallSourceInfo(context.packageName)
                .installingPackageName == null
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName) == null
        }
    }.getOrDefault(false)

    /** App info for Curfew, which is where the ⋮ menu with "Allow restricted settings" lives. */
    fun appInfoIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    const val INSTRUCTIONS: String =
        "Android blocks accessibility access for apps installed outside an app store, and shows " +
            "the switch greyed out without saying why. To allow it: open App info, tap the three " +
            "dots in the top corner, choose \"Allow restricted settings\", then come back and turn " +
            "the accessibility service on."
}
