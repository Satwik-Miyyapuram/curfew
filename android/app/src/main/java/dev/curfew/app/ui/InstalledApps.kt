package dev.curfew.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An installed app as a picker shows it. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * The apps a person could plausibly want to block.
 *
 * Only apps with a launcher entry are listed. The full package list would include a hundred system
 * components a user has never heard of, and a picker they have to scroll past is a picker they do
 * not use — which ends with rules going unwritten.
 *
 * Curfew's own package is excluded: blocking the blocker is a way to lock yourself out of the only
 * screen that can end a session.
 */
fun installedApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { resolved ->
            val activity = resolved.activityInfo ?: return@mapNotNull null
            InstalledApp(
                packageName = activity.packageName,
                label = resolved.loadLabel(pm).toString(),
            )
        }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** The label for a package, falling back to the package name when it is not installed. */
fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)
