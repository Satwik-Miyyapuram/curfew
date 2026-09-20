package dev.curfew.app.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule

/**
 * The device's calendar, and what Curfew is going to do about each entry.
 *
 * Provides calendar filtering ("Cal select"), whole-calendar blocking, and polished event cards.
 */
@Composable
fun CalendarScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pick?>(null) }
    var managingEvent by remember { mutableStateOf<CalendarEvent?>(null) }

    val names = remember(state.profiles) { state.profiles.associate { it.id to it.name } }
    val availableCalendars = remember(state.calendarEvents) {
        state.calendarEvents
            .map { it.calendar.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    CalendarList(
        state = state,
        context = context,
        heading = "Pick from your calendar",
        query = query,
        caught = state.eventRules,
        names = names,
        onQuery = { query = it },
        onPick = { event ->
            val matching = state.eventRules[event.id].orEmpty()
                .mapNotNull { rule -> state.calendarRules.find { it.id == rule.schedule } }
            if (matching.isNotEmpty()) {
                managingEvent = event
            } else {
                editing = Pick(event, null)
            }
        },
        onToggleWholeCalendar = { calName ->
            val existing = state.calendarRules.find {
                it.matcher.calendar.equals(calName, ignoreCase = true) &&
                    it.matcher.title.isNullOrBlank()
            }
            editing = Pick(
                event = CalendarEvent(
                    id = "whole:$calName",
                    title = "",
                    calendar = calName,
                    location = "",
                    start = state.now,
                    end = state.now + 3600,
                    allDay = true,
                    busy = true,
                ),
                rule = existing,
            )
        },
    )

    managingEvent?.let { event ->
        val matching = state.eventRules[event.id].orEmpty()
            .mapNotNull { rule -> state.calendarRules.find { it.id == rule.schedule } }
        if (matching.isEmpty()) {
            managingEvent = null
        } else {
            EventSchedulesSheet(
                event = event,
                matchingRules = matching,
                names = names,
                onDismiss = { managingEvent = null },
                onToggleRule = { rule, on ->
                    model.saveCalendarRule(rule.copy(enabled = on))
                },
                onEditRule = { rule ->
                    editing = Pick(event, rule)
                    managingEvent = null
                },
                onRemoveRule = { rule ->
                    model.deleteCalendarRule(rule.id)
                },
                onAddRuleForEvent = {
                    editing = Pick(event, null)
                    managingEvent = null
                },
            )
        }
    }

    editing?.let { pick ->
        CalendarDialog(
            existing = pick.rule,
            prefill = pick.event,
            profiles = state.profiles,
            now = state.now,
            availableCalendars = availableCalendars,
            onDismiss = { editing = null },
            onSave = { rule: CalendarSchedule ->
                editing = null
                model.saveCalendarRule(rule)
            },
        )
    }
}

/** An event the user tapped, and the rule already covering it, when there is one. */
private data class Pick(val event: CalendarEvent, val rule: CalendarSchedule?)

/**
 * The list of events itself, with its search field and calendar filter pills.
 */
@Composable
internal fun CalendarList(
    state: UiState,
    context: android.content.Context,
    heading: String,
    query: String,
    shown: List<CalendarEvent> = emptyList(),
    caught: Map<String, List<EventRule>>,
    names: Map<String, String>,
    onQuery: (String) -> Unit,
    onPick: (CalendarEvent) -> Unit,
    targetProfileId: String? = null,
    targetProfileName: String? = null,
    onToggleWholeCalendar: ((String) -> Unit)? = null,
) {
    val availableCalendars = remember(state.calendarEvents) {
        state.calendarEvents
            .map { it.calendar.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
    var selectedCalendar by remember { mutableStateOf<String?>(null) }

    val filteredByCalendar = remember(state.calendarEvents, selectedCalendar) {
        if (selectedCalendar == null) state.calendarEvents
        else state.calendarEvents.filter { it.calendar.equals(selectedCalendar, ignoreCase = true) }
    }
    val filteredEvents = remember(filteredByCalendar, query) { search(filteredByCalendar, query) }

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

        // Search row with clear button
        item {
            SearchRow(
                query = query,
                kept = filteredEvents.size,
                total = state.calendarEvents.size,
                onChange = onQuery,
            )
        }

        // Calendar filter pills ("Cal Select")
        if (availableCalendars.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill(
                        text = "All (${state.calendarEvents.size})",
                        selected = selectedCalendar == null,
                        onClick = { selectedCalendar = null },
                    )
                    availableCalendars.forEach { cal ->
                        val count = state.calendarEvents.count { it.calendar.equals(cal, ignoreCase = true) }
                        Pill(
                            text = "$cal ($count)",
                            selected = selectedCalendar == cal,
                            onClick = { selectedCalendar = if (selectedCalendar == cal) null else cal },
                        )
                    }
                }
            }
        }

        // Whole calendar block card
        if (selectedCalendar != null) {
            val cal = selectedCalendar!!
            val coversCalendar = state.calendarRules.any {
                it.matcher.calendar.equals(cal, ignoreCase = true) &&
                    it.matcher.title.isNullOrBlank() &&
                    (targetProfileId == null || it.profile == targetProfileId)
            }
            item {
                DCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Palette.Accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("📅", fontSize = 16.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "All “$cal” events",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Palette.Text,
                            )
                            Text(
                                if (coversCalendar) "Active · Blocks during every event"
                                else "Block during every event on this calendar",
                                fontSize = 12.sp,
                                color = if (coversCalendar) Palette.Ok else Palette.Muted,
                            )
                        }
                        if (onToggleWholeCalendar != null) {
                            Switch(coversCalendar) { onToggleWholeCalendar(cal) }
                        }
                    }
                }
            }
        }

        if (state.calendarEvents.isEmpty()) {
            item {
                val failure = state.calendarError
                Text(
                    if (failure != null) {
                        "Your calendar could not be read just now, so this is not a complete list.\n\n" +
                            failure
                    } else {
                        "Nothing in the next day and a half. Curfew only reads a narrow window " +
                            "around now, because that is all a rule can act on."
                    },
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = if (failure != null) Palette.Bad else Palette.Muted,
                )
            }
        } else if (filteredEvents.isEmpty()) {
            item {
                Text(
                    if (selectedCalendar != null) "No events on “$selectedCalendar” match “$query”."
                    else "No event matches “$query”.",
                    fontSize = 14.sp,
                    color = Palette.Muted,
                )
            }
        }

        // Grouped by day
        items(rows(filteredEvents, state.now), key = { it.key }) { row ->
            when (row) {
                is CalendarRow.Day -> Column {
                    Gap(6.dp)
                    Box(Modifier.semantics { heading() }) { SectionLabel(row.label) }
                }

                is CalendarRow.Event -> {
                    val isTargetBlocked = targetProfileId != null &&
                        caught[row.event.id].orEmpty().any { it.profile == targetProfileId }
                    EventCard(
                        event = row.event,
                        highlight = query.trim(),
                        blockedBy = caught[row.event.id].orEmpty()
                            .map { names[it.profile] ?: it.profile }
                            .distinct(),
                        canBlock = state.profiles.isNotEmpty(),
                        targetProfileName = targetProfileName,
                        isBlockedForTarget = isTargetBlocked,
                        onBlock = { onPick(row.event) },
                    )
                }
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

        item { Gap(Dsn.BottomRoom) }
    }
}

/** The search field with clean clear button. */
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
                textStyle = TextStyle(fontSize = 14.5.sp, color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search your calendar events" },
            )
            if (query.isEmpty()) {
                Text("Search events by title or location", fontSize = 14.sp, color = Palette.Dim)
            }
        }
        if (query.isNotBlank()) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Palette.Raised)
                    .clickable { onChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", fontSize = 11.sp, color = Palette.Muted)
            }
        } else if (total > 0) {
            Text("$kept of $total", fontSize = 12.sp, color = Palette.Dim)
        }
    }
}

/** One event with time duration, calendar badge, and unambiguous block status. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventCard(
    event: CalendarEvent,
    highlight: String,
    blockedBy: List<String>,
    canBlock: Boolean,
    onBlock: () -> Unit,
    targetProfileName: String? = null,
    isBlockedForTarget: Boolean = false,
) {
    val tinted = if (targetProfileName != null) isBlockedForTarget else blockedBy.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CardRadius))
            .background(if (tinted) Palette.Accent.copy(alpha = 0.07f) else Palette.Surface)
            .border(
                1.dp,
                if (tinted) Palette.Accent.copy(alpha = 0.45f) else Palette.Line,
                RoundedCornerShape(Dsn.CardRadius),
            )
            .clickable(enabled = canBlock, onClick = onBlock)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    marked(event.title.ifBlank { "(untitled)" }, highlight),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                    color = Palette.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Gap(4.dp)
                // Calendar name & location
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (event.calendar.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Palette.Accent),
                            )
                            Text(event.calendar, fontSize = 12.sp, color = Palette.Dim)
                        }
                    }
                    if (event.location.isNotBlank()) {
                        Text(
                            "📍 " + event.location,
                            fontSize = 12.sp,
                            color = Palette.Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!event.busy) {
                        Text("(free)", fontSize = 11.5.sp, color = Palette.Dim)
                    }
                }
            }

            // Time & Duration column
            Column(horizontalAlignment = Alignment.End) {
                if (event.allDay) {
                    Text("All day", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.Muted)
                } else {
                    Text(clockTime(event.start), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
                    val diff = (event.end - event.start).toInt().coerceAtLeast(0)
                    if (diff > 0) {
                        Text(duration(diff), fontSize = 11.5.sp, color = Palette.Dim)
                    }
                }
            }
        }

        Gap(12.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (targetProfileName != null) {
                // Profile sheet view: clear status and untoggle
                if (isBlockedForTarget) {
                    Pill("✓ Blocks $targetProfileName", tint = Palette.Accent)
                    Spacer(Modifier.weight(1f))
                    Text("Tap to remove", fontSize = 12.sp, color = Palette.Dim)
                } else {
                    Text("Not blocked", fontSize = 12.5.sp, color = Palette.Dim, modifier = Modifier.weight(1f))
                    Pill("+ Block", tint = Palette.Accent)
                }
            } else {
                // General Events tab view
                if (blockedBy.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        blockedBy.forEach { profile -> Pill("Blocks $profile", tint = Palette.Accent) }
                    }
                    Text(
                        if (blockedBy.size > 1) "Manage (${blockedBy.size}) ›" else "Manage ›",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Accent,
                    )
                } else {
                    Text("Nothing blocked", fontSize = 12.5.sp, color = Palette.Dim, modifier = Modifier.weight(1f))
                    Text(
                        "Block this",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canBlock) Palette.Accent else Palette.Dim,
                    )
                }
            }
        }
    }
}

/**
 * [title] with whatever the search matched drawn on the accent.
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

    val names = remember(state.profiles) { state.profiles.associate { it.id to it.name } }

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
                    caught = state.eventRules,
                    names = names,
                    onQuery = { query = it },
                    targetProfileId = profileId,
                    targetProfileName = profileName,
                    onToggleWholeCalendar = { calName ->
                        val existing = state.calendarRules.find {
                            it.profile == profileId &&
                                it.matcher.calendar.equals(calName, ignoreCase = true) &&
                                it.matcher.title.isNullOrBlank()
                        }
                        if (existing == null) {
                            model.saveCalendarRule(
                                CalendarSchedule(
                                    id = "c-" + System.currentTimeMillis(),
                                    profile = profileId,
                                    matcher = dev.curfew.policy.EventMatcher(calendar = calName),
                                    locks = listOf(dev.curfew.policy.Lock.Confirm),
                                ),
                            )
                        } else {
                            model.deleteCalendarRule(existing.id)
                        }
                    },
                    onPick = { event ->
                        val mine = state.eventRules[event.id].orEmpty()
                            .filter { it.profile == profileId }
                            .mapNotNull { r -> state.calendarRules.find { it.id == r.schedule } }
                        if (mine.isEmpty()) {
                            model.saveCalendarRule(
                                CalendarSchedule(
                                    id = "c-" + System.currentTimeMillis(),
                                    profile = profileId,
                                    matcher = dev.curfew.policy.EventMatcher(title = event.title),
                                    locks = listOf(dev.curfew.policy.Lock.Confirm),
                                ),
                            )
                        } else {
                            mine.forEach { model.deleteCalendarRule(it.id) }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Bottom sheet displaying all schedules currently blocking a specific calendar event.
 * Allows toggling rules on/off, changing rule buffers, removing rules, and adding new rules.
 */
@Composable
internal fun EventSchedulesSheet(
    event: CalendarEvent,
    matchingRules: List<CalendarSchedule>,
    names: Map<String, String>,
    onDismiss: () -> Unit,
    onToggleRule: (CalendarSchedule, Boolean) -> Unit,
    onEditRule: (CalendarSchedule) -> Unit,
    onRemoveRule: (CalendarSchedule) -> Unit,
    onAddRuleForEvent: () -> Unit,
) {
    val timeLabel = if (event.allDay) {
        "All day • ${event.calendar}"
    } else {
        "${clockTime(event.start)} – ${clockTime(event.end)} • ${event.calendar}"
    }

    DSheet(
        title = event.title.ifBlank { "Event Rules" },
        sub = timeLabel,
        onDismiss = onDismiss,
        confirm = "Done",
        confirmEnabled = true,
        onConfirm = onDismiss,
    ) {
        Gap(14.dp)
        Text(
            "Profiles blocking this event:",
            fontSize = 14.sp,
            color = Palette.Muted,
        )
        Gap(10.dp)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            matchingRules.forEach { rule ->
                val profileName = names[rule.profile] ?: rule.profile
                val padBefore = rule.padBeforeSeconds / 60
                val padAfter = rule.padAfterSeconds / 60
                val bufferText = when {
                    padBefore > 0 && padAfter > 0 -> "±${padBefore}m buffer"
                    padBefore > 0 -> "-${padBefore}m buffer"
                    padAfter > 0 -> "+${padAfter}m buffer"
                    else -> "Matches event time"
                }

                DCardFlush {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dsn.CardPad, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                profileName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Palette.Text,
                            )
                            Text(
                                bufferText,
                                fontSize = 13.sp,
                                color = Palette.Muted,
                            )
                        }
                        Switch(
                            on = rule.enabled,
                            onChange = { onToggleRule(rule, it) },
                        )
                    }
                    Rule()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dsn.CardPad, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GhostButton(
                            text = "Edit rule",
                            modifier = Modifier.weight(1f),
                            onClick = { onEditRule(rule) },
                        )
                        GhostButton(
                            text = "Remove",
                            modifier = Modifier.weight(1f),
                            colour = Palette.Bad,
                            onClick = { onRemoveRule(rule) },
                        )
                    }
                }
            }

            Gap(4.dp)
            GhostButton(
                text = "+ Block another profile",
                onClick = onAddRuleForEvent,
            )
        }
    }
}
