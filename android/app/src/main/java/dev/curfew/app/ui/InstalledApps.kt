package dev.curfew.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An installed app as a picker shows it. */
data class InstalledApp(val packageName: String, val label: String)

/** In-memory cache for installed apps list and loaded icons to ensure instant UI rendering. */
object InstalledAppsCache {
    @Volatile
    var cached: List<InstalledApp>? = null

    private val iconCache = android.util.LruCache<String, ImageBitmap>(300)
    private val labelCache = android.util.LruCache<String, String>(500)

    fun getIcon(packageName: String): ImageBitmap? = iconCache.get(packageName)

    fun putIcon(packageName: String, bitmap: ImageBitmap) {
        iconCache.put(packageName, bitmap)
    }

    fun getLabel(packageName: String): String? = labelCache.get(packageName)

    fun putLabel(packageName: String, label: String) {
        labelCache.put(packageName, label)
    }

    fun clear() {
        cached = null
        iconCache.evictAll()
        labelCache.evictAll()
    }
}

/**
 * The apps a person could plausibly want to block.
 *
 * Fast, cached retrieval. Queries launchable activities in a single batch, and only supplements with
 * user-installed non-launcher apps via in-memory flag checks (avoiding slow per-package binder calls).
 */
fun installedApps(context: Context, forceRefresh: Boolean = false): List<InstalledApp> {
    if (!forceRefresh) {
        InstalledAppsCache.cached?.let { return it }
    }

    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launcherApps = pm.queryIntentActivities(launcherIntent, 0)
        .mapNotNull { resolved ->
            val activity = resolved.activityInfo ?: return@mapNotNull null
            val label = resolved.loadLabel(pm).toString()
            InstalledAppsCache.putLabel(activity.packageName, label)
            InstalledApp(
                packageName = activity.packageName,
                label = label,
            )
        }

    val launcherPkgs = launcherApps.map { it.packageName }.toSet()

    val installedPackages = runCatching {
        pm.getInstalledApplications(0)
    }.getOrDefault(emptyList())

    // Only include user-installed or updated non-system apps not already in the launcher set.
    val additionalApps = installedPackages.mapNotNull { appInfo ->
        if (appInfo.packageName in launcherPkgs) return@mapNotNull null
        val isUserApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isUserApp) {
            val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(appInfo.packageName)
            InstalledAppsCache.putLabel(appInfo.packageName, label)
            InstalledApp(
                packageName = appInfo.packageName,
                label = label,
            )
        } else {
            null
        }
    }

    val result = (launcherApps + additionalApps)
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }

    InstalledAppsCache.cached = result
    return result
}

/** The label for a package, served instantly from memory cache when available. */
fun appLabel(context: Context, packageName: String): String {
    InstalledAppsCache.getLabel(packageName)?.let { return it }
    InstalledAppsCache.cached?.firstOrNull { it.packageName == packageName }?.let {
        InstalledAppsCache.putLabel(packageName, it.label)
        return it.label
    }
    val resolved = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
    InstalledAppsCache.putLabel(packageName, resolved)
    return resolved
}

/**
 * An app's launcher icon, served instantly from memory cache when available.
 */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var image by remember(packageName) { mutableStateOf(InstalledAppsCache.getIcon(packageName)) }
    if (image == null) {
        LaunchedEffect(packageName) {
            val loaded = withContext(Dispatchers.IO) { loadIcon(context, packageName) }
            if (loaded != null) {
                InstalledAppsCache.putIcon(packageName, loaded)
                image = loaded
            }
        }
    }
    val bitmap = image
    if (bitmap == null) {
        Spacer(modifier = modifier)
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    }
}

/**
 * [packageName]'s icon as a bitmap, or null when the app is gone or refuses to draw.
 *
 * The icon is asked for at this screen's own density rather than taken as `getApplicationIcon`
 * hands it over. That call resolves against the density Curfew's own resources were loaded at, and
 * for an app whose icon is a plain bitmap that can mean a 48-pixel mdpi drawable — which then gets
 * stretched three times over to fill the row, and arrives looking like it was faxed. Asking the
 * owning app's resources for the densest version it ships fixes exactly those icons and changes
 * nothing for the adaptive ones, which are vectors and scale either way.
 */
private fun loadIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(packageName, 0)
    val dense = runCatching {
        val resources = pm.getResourcesForApplication(info)
        val id = if (info.icon != 0) info.icon else info.logo
        if (id == 0) null else resources.getDrawableForDensity(id, ICON_DENSITY, null)
    }.getOrNull()
    (dense ?: pm.getApplicationIcon(info))
        .toBitmap(width = ICON_PIXELS, height = ICON_PIXELS)
        .asImageBitmap()
}.getOrNull()

/**
 * Comfortably above the size the rows draw at on the densest phones: 36dp at 4x is 144 pixels, and
 * a bitmap scaled down reads cleanly while one scaled up does not.
 */
private const val ICON_PIXELS = 192

/** The densest bucket Android defines, so the best artwork an app ships is the one we get. */
private const val ICON_DENSITY = android.util.DisplayMetrics.DENSITY_XXXHIGH
