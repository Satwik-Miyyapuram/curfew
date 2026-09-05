package dev.curfew.app.ui.widget

import dev.curfew.app.ui.relative

/**
 * The three lines a home screen or a quick settings tile can hold.
 *
 * Kept apart from the views that draw them because this is the part worth testing: a widget is a
 * surface a user glances at without opening anything, so if it says "nothing blocked" while a
 * session is running it is worse than having no widget at all.
 */
data class WidgetFace(val headline: String, val detail: String, val footer: String)

/**
 * What Curfew is doing, in the fewest words that are still true.
 *
 * Deliberately never a control. A widget that could end a session would be a way round the lock
 * from a surface with no room to ask for a screen lock or a typed passage, so this one reports and
 * offers a way into the app, and that is all.
 */
fun widgetFace(profiles: List<String>, nextChange: Long?, streak: Int, now: Long): WidgetFace =
    WidgetFace(
        headline = if (profiles.isEmpty()) "Nothing blocked" else "Blocking",
        detail = when {
            profiles.isNotEmpty() -> profiles.joinToString(", ")
            nextChange != null -> "Next change ${relative(nextChange, now)}"
            else -> "No sessions or schedules due"
        },
        footer = when (streak) {
            0 -> "No days in a row yet"
            1 -> "One day in a row"
            else -> "$streak days in a row"
        },
    )
