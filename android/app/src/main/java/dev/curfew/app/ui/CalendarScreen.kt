package dev.curfew.app.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The search box highlights what it matched inside each title, and counts how many of the events it
 * kept. That is the whole point of typing here: the user is not looking for one meeting, they are
 * checking what a word like "lect" would catch if they made it a rule.
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

    CalendarList(
        state = state,
        context = context,
        heading = "Pick from your calendar",
        query = query,
        shown = shown,
        caught = caught,
        names = names,
        onQuery = { query = it },
        onPick = { blocking = it },
    )

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

/**
 * The list of events itself, with its search field — the part both the Events tab and the profile
 * screen's own picker need.
 *
 * It was only ever a tab before, which meant setting a profile up meant leaving the profile: pick
 * the events somewhere else, against a profile chosen from a dropdown, then come back. Everything
 * here takes its heading and its "what happens when you tap one" from the caller, so the same list
 * can be the tab and can be the sheet that opens inside a profile.
 */
@Composable
internal fun CalendarList(
    state: UiState,
    context: android.content.Context,
    heading: String,
    query: String,
    shown: List<CalendarEvent>,
    caught: Map<String, String>,
    names: Map<String, String>,
    onQuery: (String) -> Unit,
    onPick: (CalendarEvent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Dsn.Gutter),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Gap(14.dp)
                Title(heading, size = 24)
            }
        }

        // Nothing is claimed before the first refresh has run. Every field below still holds its
        // default until then, and the default for "may Curfew read the calendar" is no — so a cold
        // start spent a moment insisting the permission was missing on a device that had granted
        // it, which reads as the feature being broken rather than as the app still looking.
        if (state.loading) {
            item { Text("Reading your calendar…", fontSize = 14.sp, color = Palette.Muted) }
            return@LazyColumn
        }

        if (!state.calendarGranted) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Curfew cannot see your calendar yet, so no meeting can start a block.",
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = Palette.Muted,
                    )
                    PrimaryButton("Allow calendar access") {
                        requestRuntimePermission(context, Manifest.permission.READ_CALENDAR)
                    }
                }
            }
            return@LazyColumn
        }

        item {
            // Filters as it is typed: there is no search button, because the list is small enough
            // that a round trip through a button would only be a way to get it wrong.
            SearchRow(
                query = query,
                kept = shown.size,
                total = state.calendarEvents.size,
                onChange = onQuery,
            )
        }

        if (state.calendarEvents.isEmpty()) {
            item {
                Text(
                    "Nothing in the next day and a half. Curfew only reads a narrow window around " +
                        "now, because that is all a rule can act on.",
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = Palette.Muted,
                )
            }
        } else if (shown.isEmpty()) {
            item {
                Text("No event matches “$query”.", fontSize = 14.sp, color = Palette.Muted)
            }
        }

        // Grouped by day, with the day named once above its events: a flat list of timestamps is
        // the part of every calendar view people misread. Flattened into header-and-event rows
        // ahead of time rather than tracked with a running variable, which would be read during
        // recomposition in an order the list does not promise.
        items(rows(shown, state.now), key = { it.key }) { row ->
            when (row) {
                is CalendarRow.Day -> Column {
                    Gap(6.dp)
                    Box(Modifier.semantics { heading() }) { SectionLabel(row.label) }
                }

                is CalendarRow.Event -> EventCard(
                    event = row.event,
                    highlight = query.trim(),
                    blockedBy = caught[row.event.id]?.let { names[it] ?: it },
                    canBlock = state.profiles.isNotEmpty(),
                    onBlock = { onPick(row.event) },
                )
            }
        }

        if (state.profiles.isEmpty()) {
            item {
                Text(
                    "Add a profile on the Plan tab first — a calendar rule has to say which one " +
                        "it runs.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Palette.Dim,
                )
            }
        }

        item { Gap(8.dp) }
    }
}

/** The search field, with the count of what it kept where a submit button would otherwise be. */
@Composable
private fun SearchRow(query: String, kept: Int, total: Int, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(Dsn.CtlRadius))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("⌕", fontSize = 17.sp, color = Palette.Muted)
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search your calendar events" },
            )
            if (query.isEmpty()) {
                Text("Search your events", fontSize = 15.sp, color = Palette.Dim)
            }
        }
        if (query.isNotBlank()) {
            Text("$kept of $total", fontSize = 12.sp, color = Palette.Dim)
        }
    }
}

/** One event, and the plain answer to "will this block anything?". */
@Composable
private fun EventCard(
    event: CalendarEvent,
    highlight: String,
    blockedBy: String?,
    canBlock: Boolean,
    onBlock: () -> Unit,
) {
    // A caught event is outlined and faintly filled in the accent rather than given a different
    // background colour: the card must still read as the same kind of thing as the ones around it,
    // with one of them marked.
    val tinted = blockedBy != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CardRadius))
            .background(if (tinted) Palette.Accent.copy(alpha = 0.07f) else Palette.Surface)
            .border(
                1.dp,
                if (tinted) Palette.Accent.copy(alpha = 0.5f) else Palette.Line,
                RoundedCornerShape(Dsn.CardRadius),
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                marked(event.title.ifBlank { "(untitled)" }, highlight),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
                color = Palette.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (event.allDay) "All day" else clockTime(event.start),
                fontSize = 13.sp,
                color = Palette.Muted,
                maxLines = 1,
            )
        }

        val where = listOfNotNull(
            event.calendar.takeIf { it.isNotBlank() },
            event.location.takeIf { it.isNotBlank() } ?: "no location",
        ).joinToString(" · ")
        Text(where, fontSize = 12.sp, color = Palette.Dim, modifier = Modifier.padding(top = 5.dp))

        if (!event.busy) {
            Text(
                "Marked free in your calendar",
                fontSize = 12.sp,
                color = Palette.Dim,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        Gap(12.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (blockedBy != null) {
                Pill("Blocks $blockedBy", tint = Palette.Accent)
            } else {
                Text("Nothing blocked", fontSize = 13.sp, color = Palette.Dim)
            }
            Text(
                if (blockedBy != null) "Change" else "Block this",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (canBlock) Palette.Accent else Palette.Dim,
                modifier = Modifier
                    .clickable(enabled = canBlock, onClick = onBlock)
                    .semantics { contentDescription = "Block during ${event.title}" },
            )
        }
    }
}

/**
 * [title] with whatever the search matched drawn on the accent.
 *
 * The highlight is the answer to the question the search box is really being asked: not "where is
 * this meeting" but "what would this word catch". Seeing `lect` light up inside *Lecture* and
 * inside *collect* is how a user finds out their rule is wider than they meant.
 */
private fun marked(title: String, needle: String): AnnotatedString {
    if (needle.isBlank()) return AnnotatedString(title)
    val at = title.indexOf(needle, ignoreCase = true)
    if (at < 0) return AnnotatedString(title)
    return buildAnnotatedString {
        append(title.substring(0, at))
        withStyle(SpanStyle(background = Palette.Accent.copy(alpha = 0.3f))) {
            append(title.substring(at, at + needle.length))
        }
        append(title.substring(at + needle.length))
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

/**
 * The same calendar, opened from inside a profile.
 *
 * A profile is the place where a person decides what a block *is*, so it is also where they expect
 * to say "and during these meetings". Sending them to another tab to do it — and to pick, from a
 * dropdown, the profile they were already looking at — is the kind of detour that gets a feature
 * abandoned halfway. Tapping an event here writes the rule against this profile straight away and
 * closes the sheet: the profile is not a question that needs asking twice.
 */
@Composable
fun CalendarPickerSheet(
    model: CurfewViewModel,
    profileId: String,
    profileName: String,
    onDone: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val caught: Map<String, String> = remember(state.upcoming) {
        state.upcoming.mapNotNull { activation ->
            (activation.source as? ActivationSource.Calendar)?.let { it.event to activation.profile }
        }.toMap()
    }
    val names = remember(state.profiles) { state.profiles.associate { it.id to it.name } }
    val shown = remember(state.calendarEvents, query) { search(state.calendarEvents, query) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Ink),
        ) {
            Box(Modifier.padding(horizontal = Dsn.Gutter)) {
                BackRow("Events for $profileName", onDone)
            }
            Box(Modifier.weight(1f)) {
                CalendarList(
                    state = state,
                    context = context,
                    heading = "Which meetings should start it?",
                    query = query,
                    shown = shown,
                    caught = caught,
                    names = names,
                    onQuery = { query = it },
                    onPick = { event ->
                        // The event as it stands, against this profile: the same rule the Events
                        // tab would write, minus the two questions the caller has already answered.
                        model.saveCalendarRule(
                            CalendarSchedule(
                                id = "c-${state.now}",
                                profile = profileId,
                                matcher = dev.curfew.policy.EventMatcher(title = event.title),
                                locks = listOf(dev.curfew.policy.Lock.Confirm),
                            ),
                        )
                        onDone()
                    },
                )
            }
        }
    }
}
