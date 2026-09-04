package dev.curfew.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
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
import dev.curfew.app.enforce.CurfewNotificationListener

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

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}

/** A grant and whether it is currently held, which is all the health screen needs. */
data class GrantState(val grant: Grant, val granted: Boolean)

fun grantStates(context: Context): List<GrantState> =
    Grant.entries.map { GrantState(it, runCatching { it.isGranted(context) }.getOrDefault(false)) }
