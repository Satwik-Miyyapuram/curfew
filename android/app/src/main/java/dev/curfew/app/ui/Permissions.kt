package dev.curfew.app.ui

import android.Manifest
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import dev.curfew.app.R
import android.content.ContextWrapper
import android.app.Activity
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
    /**
     * A string resource rather than the text.
     *
     * This is the table a translator most needs — it is the one that explains what Curfew is asking
     * for and what is lost without it — and its copy is long enough that a retyped duplicate would
     * eventually differ from the original by a word. That would be a permission table quietly
     * misinforming somebody about their own device, which is why the text moved by machine and the
     * call sites resolve it here rather than carrying a copy.
     */
    @StringRes val title: Int,
    /** Why Curfew wants it, in the user's terms. */
    @StringRes val because: Int,
    /** What still works without it, so the user can make a real choice. */
    @StringRes val cost: Int,
    val required: Boolean,
) {
    Accessibility(
        title = R.string.perm_accessibility_title,
        because = R.string.perm_accessibility_because,
        cost = R.string.perm_accessibility_cost,
        required = true,
    ),
    UsageAccess(
        title = R.string.perm_usage_access_title,
        because = R.string.perm_usage_access_because,
        cost = R.string.perm_usage_access_cost,
        required = false,
    ),
    Overlay(
        title = R.string.perm_overlay_title,
        because = R.string.perm_overlay_because,
        cost = R.string.perm_overlay_cost,
        required = false,
    ),
    Notifications(
        title = R.string.perm_notifications_title,
        because = R.string.perm_notifications_because,
        cost = R.string.perm_notifications_cost,
        required = false,
    ),
    ExactAlarms(
        title = R.string.perm_exact_alarms_title,
        because = R.string.perm_exact_alarms_because,
        cost = R.string.perm_exact_alarms_cost,
        required = false,
    ),
    Calendar(
        title = R.string.perm_calendar_title,
        because = R.string.perm_calendar_because,
        cost = R.string.perm_calendar_cost,
        required = false,
    ),
    NotificationAccess(
        title = R.string.perm_notification_access_title,
        because = R.string.perm_notification_access_because,
        cost = R.string.perm_notification_access_cost,
        required = false,
    ),
    BatteryUnrestricted(
        title = R.string.perm_battery_unrestricted_title,
        because = R.string.perm_battery_unrestricted_because,
        cost = R.string.perm_battery_unrestricted_cost,
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
        title = R.string.perm_uninstall_protection_title,
        because = R.string.perm_uninstall_protection_because,
        cost = R.string.perm_uninstall_protection_cost,
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
        // The details page for Curfew's own service where Android has one (12+), so the user lands
        // on the switch rather than on a list of every accessibility service they have ever
        // installed. The list is the fallback, not the destination.
        Accessibility ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Spelled out rather than taken from `Settings`: the constants for this page are
                // not in the SDK this app compiles against, and the strings are the platform's
                // public, stable names for it.
                Intent(ACTION_ACCESSIBILITY_DETAILS).putExtra(
                    EXTRA_ACCESSIBILITY_COMPONENT,
                    ComponentName(context, CurfewAccessibilityService::class.java).flattenToString(),
                )
            } else {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
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
        NotificationAccess ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, CurfewNotificationListener::class.java)
                        .flattenToString(),
                )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
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

/**
 * Ask for a runtime permission, without going through the activity result registry.
 *
 * [MainActivity] is a `FragmentActivity`, which androidx.biometric requires. `FragmentActivity`
 * makes `requestPermissions` final and rejects any request code that does not fit in sixteen bits,
 * while the result registry generates codes far above that — so `ActivityResultContracts
 * .RequestPermission` throws `IllegalArgumentException: Can only use lower 16 bits for requestCode`
 * the moment the user taps Grant, and the app dies on the screen whose whole job is to fix
 * permissions. A fixed small code is accepted by both.
 *
 * Nothing reads the result: every screen that asks re-reads the real grant state on its next
 * refresh, which is the only answer that cannot go stale.
 */
fun requestRuntimePermission(context: Context, permission: String) {
    val activity = context.findActivity() ?: return
    ActivityCompat.requestPermissions(activity, arrayOf(permission), PERMISSION_REQUEST_CODE)
}

/**
 * Take the user to the page where a permission is granted — runtime dialog, or the system settings page.
 *
 * **One function because there were three, and all three behaved differently.** The "Fix" button on
 * Health, the same button on Settings, and "Turn it on" on the Timer each resolved a grant to a page and
 * launched it, and each had drifted into its own failure handling:
 *
 * | Site | On a device whose OEM build lacks the page |
 * | :--- | :--- |
 * | Health | `runCatching` with an App-info fallback — **correct** |
 * | Timer | `runCatching`, failing silently — the tap does nothing and says nothing |
 * | Settings | **no `runCatching` at all** — `ActivityNotFoundException` kills the screen |
 *
 * That third one is `UX_INTERACTION_REVIEW.md` **F-33** (P1), and it is the worst place in the app for
 * it: the Settings screen is where a user goes *because* something is not working, and the failure mode
 * was for the app to close. The comment on Health's copy already said this — *"an
 * ActivityNotFoundException here would kill the one screen whose job is to fix permissions"* — and the
 * fix was applied to the copy in front of it rather than to the behaviour.
 *
 * `App info` is the fallback because it resolves on every build: every app has an app-info page, and from
 * there the permission is two taps away. Landing the user somewhere useful beats a crash and beats
 * silence.
 */
fun openGrantPage(context: Context, grant: Grant) {
    val permission = grant.runtimePermission()
    if (permission != null) {
        requestRuntimePermission(context, permission)
        return
    }
    val settings = grant.settingsIntent(context)
    val opened = settings != null && runCatching {
        context.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    // Nothing to open, or the page this OEM calls it by does not exist. App info always does.
    if (!opened) {
        runCatching {
            context.startActivity(
                RestrictedSettings.appInfoIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Android's accessibility page for one service, and the extra naming that service. API 31+. */
private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
private const val EXTRA_ACCESSIBILITY_COMPONENT = "android.provider.extra.ACCESSIBILITY_COMPONENT_NAME"

/** The one code Curfew asks with. Small enough for `FragmentActivity`, and never read back. */
private const val PERMISSION_REQUEST_CODE = 0x0C0F

/** The activity behind a composable's context, through however many `ContextWrapper`s. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
