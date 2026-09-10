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

/**
 * An app's launcher icon, loaded off the main thread.
 *
 * A list of a hundred and fifty apps is a list of a hundred and fifty drawables, each one read from
 * that app's resources, and doing that while composing freezes the picker on exactly the phones
 * that have the most apps to show. So each row asks for its own icon and draws a gap until it
 * arrives, which is invisible in practice and never blocks a scroll.
 */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var image by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        image = withContext(Dispatchers.IO) { loadIcon(context, packageName) }
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
