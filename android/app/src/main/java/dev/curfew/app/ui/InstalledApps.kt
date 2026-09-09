package dev.curfew.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { loadIcon(context, packageName) }
    }
    val image = icon
    if (image == null) {
        Spacer(modifier = modifier)
    } else {
        Image(bitmap = image, contentDescription = null, modifier = modifier)
    }
}

/** [packageName]'s icon as a bitmap, or null when the app is gone or refuses to draw. */
private fun loadIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(packageName)
        .toBitmap(width = ICON_PIXELS, height = ICON_PIXELS)
        .asImageBitmap()
}.getOrNull()

/** Big enough for the size the rows draw at on a dense screen, and no bigger. */
private const val ICON_PIXELS = 128
