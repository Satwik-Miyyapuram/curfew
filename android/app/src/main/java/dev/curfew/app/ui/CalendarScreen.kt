package dev.curfew.app.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.ActivationSource
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule

/**
 * The device's calendar, and what Curfew is going to do about each entry.
 *
 * This screen exists because a calendar rule used to be written blind: a wildcard typed into a text
 * field, against titles the user could not see from inside the app. A rule that matches nothing and
 * a rule that works look identical that way, and the failure is silent — the meeting arrives and
 * nothing is blocked. Here the events are listed as they actually are, each one says whether a rule
 * already catches it, and "Block this" builds the rule from the event rather than from a guess.
 *
 * Curfew reads calendars and never writes them, so nothing on this screen changes the calendar.
 */
@Composable
fun CalendarScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var blocking by remember { mutableStateOf<CalendarEvent?>(null) }

    // Which event ids the rules already catch, and which profile each one runs. Taken from the
    // core's own activations rather than re-implementing matching here, so the badge cannot claim
    // something the enforcement side would disagree with.
    val caught: Map<String, String> = remember(state.upcoming) {
        state.upcoming.mapNotNull { activation ->
            (activation.source as? ActivationSource.Calendar)?.let { it.event to activation.profile }
        }.toMap()
    }

    // Profiles answer to an id in the config and to a name on screen. The badge says the name:
    // an id is an implementation detail the user never chose and, for a renamed profile, is not
    // even recognisable as the thing they picked.
    val names = remember(state.profiles) { state.profiles.associate { it.id to it.name } }

    val shown = remember(state.calendarEvents, query) { search(state.calendarEvents, query) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Your calendar",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }

        // Nothing is claimed before the first refresh has run. Every field below still holds its
        // default until then, and the default for "may Curfew read the calendar" is no — so a cold
        // start spent a moment insisting the permission was missing on a device that had granted
        // it, which reads as the feature being broken rather than as the app still looking.
        if (state.loading) {
            item {
                Text(
                    "Reading your calendar…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LazyColumn
        }

        if (!state.calendarGranted) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Curfew cannot see your calendar yet, so no meeting can start a block.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { requestRuntimePermission(context, Manifest.permission.READ_CALENDAR) }) {
                        Text("Allow calendar access")
                    }
                }
            }
            return@LazyColumn
        }

        item {
            // Filters as it is typed: there is no search button, because the list is small enough
            // that a round trip through a button would only be a way to get it wrong.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search events") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Search your calendar events"
                },
            )
        }

        if (state.calendarEvents.isEmpty()) {
            item {
                Text(
                    "Nothing in the next day and a half. Curfew only reads a narrow window around " +
                        "now, because that is all a rule can act on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (shown.isEmpty()) {
            item {
                Text("No event matches “$query”.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Grouped by day, with the day named once above its events: a flat list of timestamps is
        // the part of every calendar view people misread. Flattened into header-and-event rows
        // ahead of time rather than tracked with a running variable, which would be read during
        // recomposition in an order the list does not promise.
        items(rows(shown, state.now), key = { it.key }) { row ->
            when (row) {
                is CalendarRow.Day -> Text(
                    row.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                )

                is CalendarRow.Event -> EventCard(
                    event = row.event,
                    blockedBy = caught[row.event.id]?.let { names[it] ?: it },
                    canBlock = state.profiles.isNotEmpty(),
                    onBlock = { blocking = row.event },
                )
            }
        }

        if (state.profiles.isEmpty() && !state.loading) {
            item {
                Text(
                    "Add a profile on the Schedule tab first — a calendar rule has to say " +
                        "which one it runs.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    blocking?.let { event ->
        CalendarDialog(
            existing = null,
            prefill = event,
            profiles = state.profiles,
            now = state.now,
            onDismiss = { blocking = null },
            onSave = { rule: CalendarSchedule ->
                blocking = null
                model.saveCalendarRule(rule)
            },
        )
    }
}

/** One event, and the plain answer to "will this block anything?". */
@Composable
private fun EventCard(
    event: CalendarEvent,
    blockedBy: String?,
    canBlock: Boolean,
    onBlock: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (blockedBy != null) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                event.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (event.allDay) "All day" else "${clockTime(event.start)} – ${clockTime(event.end)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val where = listOfNotNull(
                event.calendar.takeIf { it.isNotBlank() },
                event.location.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (where.isNotEmpty()) {
                Text(where, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (blockedBy != null) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Blocks $blockedBy") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                } else {
                    Text("Nothing blocked", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = onBlock,
                    enabled = canBlock,
                    modifier = Modifier.semantics {
                        contentDescription = "Block during ${event.title}"
                    },
                ) { Text(if (blockedBy != null) "Add another rule" else "Block this") }
            }
            if (!event.busy) {
                Text(
                    "Marked free in your calendar.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** A day heading, or one event under it. */
internal sealed interface CalendarRow {
    /** Stable across recomposition, and unique: day labels and event ids never collide. */
    val key: String

    data class Day(val label: String) : CalendarRow {
        override val key: String get() = "day:$label"
    }

    data class Event(val event: CalendarEvent) : CalendarRow {
        override val key: String get() = "event:${event.id}"
    }
}

/**
 * [events] flattened into the rows the list draws, a day heading before each new day.
 *
 * Expects [events] already in start order, which is how the view model hands them over.
 */
internal fun rows(events: List<CalendarEvent>, now: Long): List<CalendarRow> {
    val out = mutableListOf<CalendarRow>()
    var day: String? = null
    for (event in events) {
        val label = dayLabel(event.start, now)
        if (label != day) {
            day = label
            out += CalendarRow.Day(label)
        }
        out += CalendarRow.Event(event)
    }
    return out
}

/**
 * The events matching [query], in order.
 *
 * Matched against title, calendar and location together, because a person searching "work" may
 * mean the calendar named Work or the meeting called Work review, and asking them which is a
 * question the app can answer for itself.
 */
internal fun search(events: List<CalendarEvent>, query: String): List<CalendarEvent> {
    val needle = query.trim()
    if (needle.isEmpty()) return events
    return events.filter { event ->
        listOf(event.title, event.calendar, event.location)
            .any { it.contains(needle, ignoreCase = true) }
    }
}
