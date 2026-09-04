package dev.curfew.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The app's colours.
 *
 * Curfew follows the system's own palette where the platform offers one, because an app that runs
 * on the phone every day should look like it belongs to the phone rather than announcing itself.
 * The fallback is a deliberately quiet blue: this is not an app anyone wants to be excited by.
 */
private val fallbackLight = lightColorScheme(primary = Color(0xFF1B3A5B))
private val fallbackDark = darkColorScheme(primary = Color(0xFF9EC5E8))

@Composable
fun CurfewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> fallbackDark
        else -> fallbackLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}
