package dev.curfew.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * The app's colours, and the reasons there are so few of them.
 *
 * Curfew used to follow the system palette. That was the wrong instinct: a dynamic accent means
 * the colour that says *a block is running* is whatever the user's wallpaper happened to be that
 * week, and on a mint-green phone the difference between "locked" and "free" stopped being
 * visible at a glance. So the palette is fixed, and every colour in it has exactly one job:
 *
 *  - [ground] and its surfaces are the page.
 *  - [accent] means *you can touch this*. Buttons, links, the selected tab. Nothing else.
 *  - [live] means *a block is running right now*. It appears when one is, and never otherwise.
 *  - [ok] and [bad] are for state that has already happened: within budget, permission missing.
 *  - [warn] is *degraded, or needs attention*: a layer that is up but not doing its whole job, a
 *    calendar rule that has never matched anything.
 *  - [cal] is *derived from the calendar*: an event's band, an event's arc.
 *
 * The [live] rule is the one that keeps being broken, so it is worth spelling out what it rules
 * out. A **planning** screen is not live: the timer dial while you are choosing a length, a row
 * that describes a way to start a profile, a weekly window sitting in a list. A **neutral status**
 * is not live: sync listening or not listening, a schedule that is switched off. And a **warning**
 * is not live either — a caution about something that has not happened yet is [warn] or [bad],
 * because orange would say a block is running and that is the opposite of the message. If a screen
 * has no running block on it, it should have no orange on it.
 *
 * The reason this matters more than taste: [live] is the only signal in the app that crosses the
 * screen, the notification shade and the home-screen tile, and it is the one a person learns to
 * read without thinking. Spend it on a second meaning and it stops meaning anything.
 *
 * ## Two palettes, and why they are the same palette
 *
 * `docs/design.md` §3.2 fixes the light values exactly — neutral greys, near-black ink, one indigo
 * accent — and that is the default, so [Paper] is what a fresh install gets. [Dusk] is the same palette with the
 * ground and the ink swapped, because this is an app people open at night to be told no and a sheet
 * of white paper at 23:00 is its own small hostility.
 *
 * §3.2 also says *"Do not make dark mode a separate visual language."* So every relationship is
 * preserved: the ground stays a neutral grey, the ink stays a single near-white, borders are
 * still drawn in the ink colour, and the accents keep their exact jobs. Only two things change —
 * the ground goes dark and the text goes light — so a screenshot of one theme is recognisably the
 * other.
 *
 * ## Why a composition local and not an `object` of `val`s
 *
 * There are several hundred `Palette.X` references across the screens, and a theme that could only
 * be chosen by editing every one of them would not be a theme. Reading through a composition local
 * lets [CurfewTheme] swap the whole palette in one place and have every screen follow, including
 * the ones written after today.
 */

/**
 * The light theme: warm paper, near-black ink, and fills as bright as [Dusk]'s.
 * `docs/design.md` §3.2, and the default.
 *
 * Fills used to be dark (a mustard accent, a forest green) because white was drawn on them, and a
 * dark fill is the only kind white reads on. That made the light theme the dull one. Ink on a fill
 * is now [Brutal.onFill], near-black, so the fills can be loud; the accent drawn *as text* on white
 * uses [Brutal.link], the deep shade that still reads there.
 */
val Paper = Brutal(
    ground = Color(0xFFF4F1E8),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFEAE6DA),
    selected = Color(0xFFDCD7C8),
    line = Color(0xFF18181B),
    text = Color(0xFF09090B),
    muted = Color(0xFF52525B),
    dim = Color(0xFF71717A),
    accent = Color(0xFFFFD60A),
    link = Color(0xFF8A6A00),
    onFill = Color(0xFF09090B),
    live = Color(0xFFFF6A2B),
    ok = Color(0xFF16A34A),
    warn = Color(0xFFFFAA00),
    bad = Color(0xFFF0283F),
    cal = Color(0xFF12B5D3),
)

/**
 * The dark theme, for AMOLED: ground and cards are both pure black, so a card is grouping and
 * spacing rather than a grey slab. Only controls get a tone, a near-black one, so what can be tapped
 * still reads as a block without the screen turning grey.
 */
val Dusk = Brutal(
    ground = Color(0xFF000000),
    surface = Color(0xFF000000),
    raised = Color(0xFF141416),
    selected = Color(0xFF232327),
    line = Color(0xFF333333),
    text = Color(0xFFFAFAFA),
    muted = Color(0xFFA3A3A3),
    dim = Color(0xFF8A8A8A),
    accent = Color(0xFFFFD60A),
    link = Color(0xFFFFD60A),
    onFill = Color(0xFF000000),
    live = Color(0xFFFF8A4D),
    ok = Color(0xFF4FC183),
    warn = Color(0xFFE8B33A),
    bad = Color(0xFFFF667E),
    cal = Color(0xFF3BB8CE),
)

/**
 * One palette, as a value.
 *
 * A class rather than a bare object so that two of them can exist and the theme can choose. The
 * *names* are the contract: `Palette.ground` is the page, `Palette.text` is what is written on it,
 * and every screen refers to those rather than to a hex code.
 */
class Brutal(
    /** The page the whole app sits on. */
    val ground: Color,
    /** A card: the main raised surface. */
    val surface: Color,
    /** A secondary surface inside a card — a field, a well, an unselected chip. */
    val raised: Color,
    /** A selected or pressed surface. One step further from the ground than [raised]. */
    val selected: Color,
    /** Borders, rules and the drawn edge of every control. */
    val line: Color,
    /** Primary text. */
    val text: Color,
    /** Supporting text: the sentence under a title. */
    val muted: Color,
    /** Hints and low-priority metadata. */
    val dim: Color,
    /** Primary action and navigation. */
    val accent: Color,
    /** The accent as text, an icon or a cursor on the page; deeper than [accent] where that would not read. */
    val link: Color,
    /** Text and icons drawn on any solid colour fill: the accent, a status colour, a tinted tag. */
    val onFill: Color,
    /** A block is running. Never used for anything else. */
    val live: Color,
    /** Live or successful. */
    val ok: Color,
    /** Degraded, or needs attention. */
    val warn: Color,
    /** Refused, dangerous, or destructive. */
    val bad: Color,
    /** Derived from the calendar. */
    val cal: Color,
) {
    /** Colours a profile may be given. Named nowhere in the UI — a profile shows a dot, not a word. */
    val profileColours: List<Color> = listOf(accent, live, ok, ProfilePink, bad, cal)
}

/** The one profile colour that is not also a status colour; it reads on both grounds. */
private val ProfilePink = Color(0xFFE17BA8)

private val localPalette = staticCompositionLocalOf { Paper }


/** The two palettes, for the appearance setting on the Settings screen. */
val LightPalette: Brutal get() = Paper
val DarkPalette: Brutal get() = Dusk

private fun schemeOf(p: Brutal, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.ground,
        // Muted, not [p.live]. `secondary` is Material's general-purpose accent role, and pointing it
        // at the colour meaning *a block is running* broke the palette's one rule the moment any
        // Material component read it — which, via `secondaryContainer` below, one already did.
        secondary = p.muted,
        onSecondary = p.ground,
        // Set rather than left to the baseline, and that matters more than it looks. `FilterChip` —
        // the only Material component here that draws a selected state — takes its selected fill from
        // `secondaryContainer`. Leaving a role unset does not mean "no colour": Material falls back to
        // its own baseline palette, which is a lavender that appears nowhere else in this app.
        secondaryContainer = p.raised,
        onSecondaryContainer = p.text,
        tertiary = p.ok,
        onTertiary = p.ground,
        background = p.ground,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.raised,
        onSurfaceVariant = p.muted,
        outline = p.line,
        outlineVariant = p.line,
        error = p.bad,
        onError = p.ground,
        surfaceContainer = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainerHigh = p.raised,
        surfaceContainerHighest = p.selected,
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.surface,
        secondary = p.muted,
        onSecondary = p.surface,
        secondaryContainer = p.raised,
        onSecondaryContainer = p.text,
        tertiary = p.ok,
        onTertiary = p.surface,
        background = p.ground,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.raised,
        onSurfaceVariant = p.muted,
        outline = p.line,
        outlineVariant = p.line,
        error = p.bad,
        onError = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainerHigh = p.raised,
        surfaceContainerHighest = p.selected,
    )
}

/**
 * Material's slots, filled from [Type] so a Material component that picks its own style (a dialog
 * title, a button, a text field) still lands on the ramp rather than on Material's defaults.
 *
 * The default body is [Type.body] at 14.5sp rather than Material's 14 because §8 sets a **14px
 * floor**, and a floor that no default body style reaches is a floor the next screen written will
 * break.
 */
private val type = Typography(
    displayLarge = Type.mega,
    displayMedium = Type.display,
    displaySmall = Type.display,
    headlineLarge = Type.h1,
    headlineMedium = Type.h2,
    headlineSmall = Type.h3,
    titleLarge = Type.h2,
    titleMedium = Type.h3,
    titleSmall = Type.body.copy(fontWeight = FontWeight.Bold),
    bodyLarge = Type.body,
    bodyMedium = Type.body,
    bodySmall = Type.meta,
    labelLarge = Type.button,
    labelMedium = Type.meta.copy(fontWeight = FontWeight.Bold),
    labelSmall = Type.label,
)

/**
 * Nothing is round.
 *
 * `docs/design.md` §3.4: *"Default radius: 0. Circular controls: true circles only."* Every role is
 * a zero radius, because the design's language is a stack of rectangles with drawn edges, and a
 * single 9dp corner anywhere in it reads as a mistake rather than as a softness. The one circular
 * thing in the app is the dial, and it is drawn on a `Canvas` rather than shaped with a
 * `RoundedCornerShape`, so it is unaffected by this.
 */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

/**
 * The theme the whole app is drawn in.
 *
 * Two things are resolved here and nothing else.
 *
 * **Which palette.** [Appearance.themeChoice] wins when the user has chosen one, and the system
 * decides when they have not — §5.11 lists a Theme setting, and the document's own default is the
 * light paper theme, so a fresh install gets paper and a user who wants dusk gets dusk.
 *
 * **Which accent.** §5.11's Accent setting, applied to [Brutal.accent] alone and to neither of the
 * semantic colours. Every other token is copied from the chosen palette unchanged, so picking Teal
 * changes the buttons and the selected tab and nothing that means a state.
 */
@Composable
fun CurfewTheme(
    content: @Composable () -> Unit,
) {
    // **Every one of these reads happens in the composable body, and that is load-bearing.**
    //
    // This used to take `dark: Boolean = Appearance.resolveDark(isSystemInDarkTheme())` as a
    // *default argument*, and a default argument is evaluated by the caller — outside the scope that
    // observes this function. The value was computed correctly once and then never again: the theme
    // and accent controls in Settings wrote the new preference, the preference changed, and nothing
    // recomposed, so the buttons appeared to do nothing at all. The state was always right; it was
    // simply not being watched.
    //
    // Reading the system setting and the preference here, in the body, is what subscribes to both.
    val systemDark = isSystemInDarkTheme()
    val choice = Appearance.theme
    val dark = when (choice) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        else -> systemDark
    }
    val base = if (dark) Dusk else Paper
    val accent = Accent.of(Appearance.accent)
    // Built member by member rather than with a `copy`. A member-wise construction is unambiguous,
    // and the one field that changes is the one named here: section 5.11's Accent setting, applied
    // to the accent alone and to neither of the semantic colours.
    val palette = Brutal(
        ground = base.ground,
        surface = base.surface,
        raised = base.raised,
        selected = base.selected,
        line = base.line,
        text = base.text,
        muted = base.muted,
        dim = base.dim,
        accent = if (dark) accent.dark else accent.light,
        link = if (dark) accent.dark else accent.link,
        onFill = base.onFill,
        live = base.live,
        ok = base.ok,
        warn = base.warn,
        bad = base.bad,
        cal = base.cal,
    )
    // **The one writer, and it must be a `SideEffect`.**
    //
    // Writing this directly in the body is a state write *during composition* — the exact pattern
    // `docs/design.md` section 7 rule 10 forbids — and Compose does not merely warn about it: it
    // discards the composition. The observable result was an app that read the stored preference
    // correctly, resolved the right palette, and then painted the old one for ever, because every
    // attempt to publish the new palette was thrown away before it took effect. `SideEffect` runs
    // after the composition is committed, so the write lands and the readers that subscribed to it
    // recompose once.
    SideEffect { Palette.adopt(palette) }
    // The status and navigation bar icons follow the app's theme, not the system's. Edge-to-edge
    // picks them from the system setting, so Paper on a phone set to dark drew white icons on a
    // near-white ground, and the clock and battery vanished.
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(localPalette provides palette) {
        MaterialTheme(
            colorScheme = schemeOf(palette, dark),
            typography = type,
            shapes = shapes,
            content = {
                // **A default text colour, for the `Text` calls that name no colour of their own.**
                //
                // Most of this app's text says `color = Palette.text` explicitly, but not all of it:
                // an early audit of this file found `Text(name, style = TextStyle(fontSize = 20.sp,
                // fontWeight = FontWeight.Black))` with no colour at all, and there are more. A
                // `Text` with no colour falls back to Compose's own default — a near-black — rather
                // than to this palette.
                //
                // That is invisible in the light theme, because near-black on paper is what the
                // light theme wants anyway, which is exactly why it went unnoticed: it looked
                // correct for as long as the app only had one theme. The moment a dark theme could
                // be chosen, every one of those strings became black on a near-black card. That is
                // the "text shows black on the black background" report, and it was never one
                // component — it was every bare `Text` in the app at once.
                //
                // `LocalContentColor` is the switch Material's own `Text` reads for exactly this,
                // so setting it here fixes all of them without touching a single call site, and
                // without overriding any `Text` that does name a colour.
                CompositionLocalProvider(LocalContentColor provides palette.text) {
                    content()
                }
            },
        )
    }
}
