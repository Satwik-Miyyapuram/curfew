package dev.curfew.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The appearance the user chose, and where it is remembered.
 *
 * `docs/design.md` section 5.11 lists Appearance as a settings section with a theme and an accent in
 * it, and a setting with no storage is not a setting. `CurfewTheme` shipped with
 * `dark = isSystemInDarkTheme()` and nothing else, so the palette the design document treats as the
 * default - warm paper - was reachable only by changing a system setting.
 *
 * ## Why SharedPreferences and not the plan file or the database
 *
 * - **Not the plan file.** Section 2 invariant 7 is that the plan file is the source of truth *for
 *   what is enforced*. A theme is not enforced, is not readable by the core, and writing a colour
 *   into a document the service parses would mean a malformed colour could stop a block loading.
 * - **Not the encrypted database.** That holds what the user *did* - usage, launches, the audit
 *   trail - and is encrypted because that history is the sensitive thing. A theme is not sensitive,
 *   and tying rendering to a database that may be mid-migration is a way to paint nothing at all.
 * - **Preferences**, which is exactly what they are for: a handful of small, non-sensitive values
 *   needed before the first frame.
 *
 * The values are read once in [attach], from the activity, before the first composition, so the very
 * first frame is already the chosen palette. A value that arrived a frame late would be a visible
 * flash of the wrong theme on every launch.
 */
object Appearance {

    private const val FILE = "curfew-appearance"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"

    /** `system`, `light`, `dark` - the three answers section 5.11's Theme setting can have. */
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private var prefs: SharedPreferences? = null

    private var themeChoice by mutableStateOf(SYSTEM)
    private var accentChoice by mutableStateOf(Accent.Brand.id)

    /** The chosen theme, as one of [SYSTEM], [LIGHT] or [DARK]. Read by [CurfewTheme]. */
    val theme: String get() = themeChoice

    /** The chosen accent id. Read by [CurfewTheme]. */
    val accent: String get() = accentChoice

    /** Read the remembered values. Called once, from the activity, before the first composition. */
    fun attach(context: Context) {
        if (prefs != null) return
        val opened = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = opened
        themeChoice = opened.getString(KEY_THEME, SYSTEM) ?: SYSTEM
        accentChoice = opened.getString(KEY_ACCENT, Accent.Brand.id) ?: Accent.Brand.id
    }

    fun setTheme(context: Context, choice: String) {
        themeChoice = choice
        prefs(context).edit().putString(KEY_THEME, choice).apply()
    }

    fun setAccent(context: Context, id: String) {
        accentChoice = id
        prefs(context).edit().putString(KEY_ACCENT, id).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .also { prefs = it }

    /**
     * Which palette the settings ask for, given what the system says.
     *
     * **Called from inside `CurfewTheme`'s body, never as a default argument.** It was a default
     * argument once, which is evaluated by the *caller* and therefore outside the scope that observes
     * this object's state: the preference changed and nothing recomposed, so the Theme and Accent
     * controls wrote a value the app never looked at again. Kept as a named function so the rule
     * lives in one place, with the trap written down beside it.
     */
    @Composable
    fun darkFor(systemDark: Boolean): Boolean =
        when (theme) {
            LIGHT -> false
            DARK -> true
            else -> systemDark
        }
}

/**
 * The accents a user may pick, per section 5.11.
 *
 * Each is a single hue **for the primary action and navigation only**. Section 3.2 keeps run, ok,
 * warn, bad and cal fixed, and that is the point rather than a limitation: the accent is the one
 * colour that means *you can touch this*, so it is the only one a user can safely change.
 * Recolouring the orange that means *a block is running* would break the one signal this app spends
 * everywhere.
 *
 * Every entry carries a light and a dark value, because #4D5DFF on white paper and #4D5DFF on a
 * near-black ground are not the same colour to a reader. The dark variants are lifted for contrast,
 * the same thing Dusk does with every other token.
 */
enum class Accent(val id: String, val label: String, val light: Color, val dark: Color) {
    Brand("brand", "Indigo", Color(0xFF4D5DFF), Color(0xFF8B95FF)),
    // Blue-black, not the text colour: an accent that matches the text cannot mark anything.
    Ink("ink", "Ink", Color(0xFF233A6B), Color(0xFFAFC2EE)),
    Teal("teal", "Teal", Color(0xFF0B6E80), Color(0xFF3BB8CE)),
    Rose("rose", "Rose", Color(0xFFC21F49), Color(0xFFFF667E)),
    ;

    companion object {
        fun of(id: String): Accent = entries.firstOrNull { it.id == id } ?: Brand
    }
}