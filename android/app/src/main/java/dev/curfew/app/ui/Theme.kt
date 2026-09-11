package dev.curfew.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's colours, and the reasons there are so few of them.
 *
 * Curfew used to follow the system palette. That was the wrong instinct: a dynamic accent means
 * the colour that says *a block is running* is whatever the user's wallpaper happened to be that
 * week, and on a mint-green phone the difference between "locked" and "free" stopped being
 * visible at a glance. So the palette is fixed, and every colour in it has exactly one job:
 *
 *  - [Ink] and its surfaces are the ground. Dark, low-contrast between layers, nothing shiny.
 *  - [Accent] means *you can touch this*. Buttons, links, the selected tab. Nothing else.
 *  - [Live] means *a block is running right now*. It appears when one is, and never otherwise.
 *  - [Ok] and [Bad] are for state that has already happened: within budget, permission missing.
 *
 * The [Live] rule is the one that keeps being broken, so it is worth spelling out what it rules out.
 * A **planning** screen is not live: the timer dial while you are choosing a length, a row that
 * describes a way to start a profile, a weekly window sitting in a list. A **neutral status** is not
 * live: sync listening or not listening, a schedule that is switched off. And a **warning** is not
 * live either — a caution about something that has not happened yet is [Bad], because amber would say
 * a block is running and that is the opposite of the message. If a screen has no running block on it,
 * it should have no amber on it.
 *
 * The reason this matters more than taste: amber is the only signal in the app that crosses the
 * screen, the notification shade and the home-screen tile, and it is the one a person learns to read
 * without thinking. Spend it on a second meaning and it stops meaning anything.
 *
 * It is dark in both system themes, deliberately. This is an app people open at night to be told
 * no, and a white page at 23:00 is its own small hostility.
 */
object Palette {
    val Ink = Color(0xFF0D1016)
    val Surface = Color(0xFF161B23)
    val Raised = Color(0xFF1D242E)
    val Line = Color(0xFF2A3341)

    val Text = Color(0xFFEDF1F7)
    val Muted = Color(0xFF8D9AAC)
    val Dim = Color(0xFF5D6879)

    val Accent = Color(0xFF7C9CF5)
    val Live = Color(0xFFF2A65A)
    val Ok = Color(0xFF5FD3A6)
    val Bad = Color(0xFFF27272)

    /** Colours a profile may be given. Named nowhere in the UI — a profile shows a dot, not a word. */
    val ProfileColours = listOf(Accent, Live, Ok, Color(0xFFE17BA8), Bad, Muted)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Ink,
    // Muted, not [Palette.Live]. `secondary` is Material's general-purpose accent role, and pointing
    // it at the colour meaning *a block is running* broke the palette's one rule the moment any
    // Material component read it — which, via `secondaryContainer` below, one already did.
    secondary = Palette.Muted,
    onSecondary = Palette.Ink,
    // Set rather than left to the baseline, and that matters more than it looks. `FilterChip` — the
    // only Material component here that draws a selected state — takes its selected fill from
    // `secondaryContainer`. Leaving a role unset does not mean "no colour": Material falls back to
    // its own baseline palette, which is a lavender that appears nowhere else in this app. So the one
    // chip on the app-picker screen was wearing a colour from a different design system.
    //
    // A translucent accent, matching how selection is drawn everywhere else here (`Pill`, the profile
    // tabs, the block screen's tint): an accent wash rather than a solid fill, so the label stays
    // readable on the dark ground.
    secondaryContainer = Palette.Accent.copy(alpha = 0.20f),
    onSecondaryContainer = Palette.Text,
    tertiary = Palette.Ok,
    onTertiary = Palette.Ink,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    error = Palette.Bad,
    onError = Palette.Ink,
    // The containers Material draws dialogs and sheets on. Translucent on purpose: a dialog is a
    // pane laid over the page, the same material as the nav bar, and an opaque slab in the middle
    // of a screen is what made the app read as flat cards on a flat ground.
    surfaceContainer = Palette.Raised.copy(alpha = 0.93f),
    surfaceContainerLow = Palette.Surface.copy(alpha = 0.93f),
    surfaceContainerHigh = Palette.Raised.copy(alpha = 0.94f),
    surfaceContainerHighest = Palette.Line.copy(alpha = 0.94f),
)

/**
 * The type ramp.
 *
 * Tighter tracking on the big sizes and a heavier body weight than Material's default, because
 * almost every string in this app is a sentence about what is about to happen to the user's
 * phone, and sentences want to read like prose rather than like a settings list.
 */
private val type = Typography().let { base ->
    fun TextStyle.tight(weight: FontWeight, letter: Double) =
        copy(fontWeight = weight, letterSpacing = letter.sp, fontFamily = FontFamily.Default)
    base.copy(
        headlineLarge = base.headlineLarge.tight(FontWeight.Bold, -0.6),
        headlineMedium = base.headlineMedium.tight(FontWeight.Bold, -0.5),
        headlineSmall = base.headlineSmall.tight(FontWeight.Bold, -0.4),
        titleLarge = base.titleLarge.tight(FontWeight.SemiBold, -0.2),
        titleMedium = base.titleMedium.tight(FontWeight.SemiBold, 0.0),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        labelLarge = base.labelLarge.tight(FontWeight.SemiBold, 0.1),
        labelSmall = base.labelSmall.tight(FontWeight.Bold, 1.4),
    )
}

/** Cards are round, controls are less round, and nothing is square. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CurfewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}
