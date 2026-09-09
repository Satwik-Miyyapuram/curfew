package dev.curfew.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.EventMatcher
import dev.curfew.policy.Lock
import dev.curfew.policy.ProfileName
import dev.curfew.policy.WeeklySchedule

/**
 * Making a schedule without writing a config file.
 *
 * The TOML editor stays — it is the document a user backs up, diffs and carries between devices —
 * but nobody should have to learn a file format to say "no Instagram after eleven". These forms
 * cover the shapes people actually ask for; anything they cannot express is still one screen away
 * in the text, and both write through the same core validation.
 *
 * Nothing here decides whether a schedule is legal. The form builds a value, the core accepts or
 * refuses the whole config, and a refusal is shown in the core's own words.
 */

/** Monday first, because the core numbers days that way and a UI that reordered them would lie. */
internal val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * The locks a form offers.
 *
 * Not every lock the core knows: a tag lock names a tag that has to exist in the config first, and
 * a peer lock names a device that has to be paired, so both are offered where those things live
 * rather than in a list that could produce a lock nothing can open.
 */
internal val OFFERED_LOCKS: List<Pair<String, Lock>> = listOf(
    "Runs to the end" to Lock.Timer,
    "Ask before ending" to Lock.Confirm,
    "Screen lock" to Lock.DeviceCredential,
    "Restart the device" to Lock.RestartRequired,
)

/**
 * Minutes after midnight, as `HH:mm`.
 *
 * Always 24-hour and always two digits, because this string is also what gets typed back in: a
 * field that renders "9:05 PM" and then refuses to parse it is worse than one that never localised.
 */
internal fun minutesToHhMm(minutes: Int): String =
    "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

/**
 * `HH:mm` back to minutes after midnight, or null if it is not a time.
 *
 * Lenient about the shapes people type — `9:5`, `09:05`, a stray space — and strict about the
 * result: 24:00 is not a time, and neither is 9:60.
 */
internal fun hhMmToMinutes(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val hours = parts[0].trim().toIntOrNull() ?: return null
    val minutes = parts[1].trim().toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return hours * 60 + minutes
}

/** The days a window runs, said the way the list itself would be read aloud. */
internal fun describeDays(days: List<Int>): String = when {
    days.isEmpty() -> "Every day"
    days.sorted() == listOf(0, 1, 2, 3, 4) -> "Weekdays"
    days.sorted() == listOf(5, 6) -> "Weekends"
    else -> days.sorted().joinToString(", ") { DAY_NAMES.getOrElse(it) { "?" } }
}

/**
 * A window as one line: when it runs, and whether it crosses midnight.
 *
 * The crossing is spelled out rather than left to the reader, because "23:00 - 07:00" is the one
 * range a person routinely reads as an empty window.
 */
internal fun describeWindow(window: WeeklySchedule): String {
    val span = minutesToHhMm(window.startMinute) + " - " + minutesToHhMm(window.endMinute)
    val overnight = if (window.endMinute <= window.startMinute) " (overnight)" else ""
    return describeDays(window.days) + " · " + span + overnight
}

/** A calendar rule as one line: which events it catches. */
internal fun describeMatcher(matcher: EventMatcher): String {
    val parts = buildList {
        matcher.calendar?.takeIf { it.isNotBlank() }?.let { add("on the $it calendar") }
        matcher.title?.takeIf { it.isNotBlank() }?.let { add("titled $it") }
        matcher.location?.takeIf { it.isNotBlank() }?.let { add("at $it") }
        if (matcher.busyOnly) add("marked busy")
    }
    return if (parts.isEmpty()) "Every event" else "Events " + parts.joinToString(", ")
}

/** Padding, said as the sentence the form is trying to make, or nothing when there is none. */
internal fun describePadding(beforeSeconds: Int, afterSeconds: Int): String? {
    val before = beforeSeconds / 60
    val after = afterSeconds / 60
    return when {
        before == 0 && after == 0 -> null
        after == 0 -> "Starts $before min early"
        before == 0 -> "Runs $after min late"
        else -> "Starts $before min early, runs $after min late"
    }
}

@Composable
internal fun WeeklyCard(
    window: WeeklySchedule,
    profile: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(profile, style = MaterialTheme.typography.titleMedium)
            Text(describeWindow(window), style = MaterialTheme.typography.bodyMedium)
            if (window.locks.isNotEmpty()) {
                Text(
                    "Locked: needs " + window.locks.joinToString(", ") { describeLock(it) } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics {
                        contentDescription = "Edit the window for ${window.profile}"
                    },
                ) { Text("Edit") }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics {
                        contentDescription = "Remove the window for ${window.profile}"
                    },
                ) { Text("Remove") }
            }
        }
    }
}

@Composable
internal fun CalendarRuleCard(
    rule: CalendarSchedule,
    profile: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(profile, style = MaterialTheme.typography.titleMedium)
            Text(describeMatcher(rule.matcher), style = MaterialTheme.typography.bodyMedium)
            describePadding(rule.padBeforeSeconds, rule.padAfterSeconds)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (rule.locks.isNotEmpty()) {
                Text(
                    "Locked: needs " + rule.locks.joinToString(", ") { describeLock(it) } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics {
                        contentDescription = "Edit the calendar rule for ${rule.profile}"
                    },
                ) { Text("Edit") }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics {
                        contentDescription = "Remove the calendar rule for ${rule.profile}"
                    },
                ) { Text("Remove") }
            }
        }
    }
}

/** The profile a schedule runs. A schedule naming no profile enforces nothing, so one is required. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePicker(profiles: List<ProfileName>, chosen: String, onChoose: (String) -> Unit) {
    Text("Profile", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            FilterChip(
                selected = profile.id == chosen,
                onClick = { onChoose(profile.id) },
                label = { Text(profile.name) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LockPicker(chosen: List<Lock>, onChange: (List<Lock>) -> Unit) {
    Text("Locked by", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OFFERED_LOCKS.forEach { (label, lock) ->
            FilterChip(
                selected = lock in chosen,
                onClick = { onChange(if (lock in chosen) chosen - lock else chosen + lock) },
                label = { Text(label) },
            )
        }
    }
    Text(
        "With nothing chosen the session can be ended whenever you like. Every condition you add " +
            "is one you will have to satisfy to get out early.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The weekly window form, used for both a new window and an edit.
 *
 * [existing] being null is the only difference: a new window gets an id derived from the clock, an
 * edit keeps the id it had, which is what makes saving it an edit rather than a second window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeeklyDialog(
    existing: WeeklySchedule?,
    profiles: List<ProfileName>,
    now: Long,
    onDismiss: () -> Unit,
    onSave: (WeeklySchedule) -> Unit,
) {
    var profile by remember { mutableStateOf(existing?.profile ?: profiles.firstOrNull()?.id ?: "") }
    var days by remember { mutableStateOf(existing?.days ?: emptyList()) }
    var start by remember { mutableStateOf(minutesToHhMm(existing?.startMinute ?: 9 * 60)) }
    var end by remember { mutableStateOf(minutesToHhMm(existing?.endMinute ?: 17 * 60)) }
    var locks by remember { mutableStateOf(existing?.locks ?: emptyList()) }

    val startMinutes = hhMmToMinutes(start)
    val endMinutes = hhMmToMinutes(end)
    val valid = profile.isNotBlank() &&
        startMinutes != null &&
        endMinutes != null &&
        startMinutes != endMinutes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New window" else "Edit window") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfilePicker(profiles, profile) { profile = it }

                Text("Days", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DAY_NAMES.forEachIndexed { index, name ->
                        FilterChip(
                            selected = index in days,
                            onClick = { days = if (index in days) days - index else days + index },
                            label = { Text(name) },
                        )
                    }
                }
                Text(
                    if (days.isEmpty()) "No day chosen means every day." else describeDays(days),
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it },
                        label = { Text("From") },
                        isError = startMinutes == null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it },
                        label = { Text("Until") },
                        isError = endMinutes == null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                // The overnight case is the one people mean most often and read wrongly most
                // often, so it is confirmed back to them rather than left implied by the numbers.
                if (startMinutes != null && endMinutes != null && endMinutes <= startMinutes) {
                    Text(
                        if (startMinutes == endMinutes) {
                            "A window cannot start and end at the same minute."
                        } else {
                            "This window runs overnight, into the next morning."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                LockPicker(locks) { locks = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        WeeklySchedule(
                            id = existing?.id ?: "w-$now",
                            profile = profile,
                            days = days.sorted(),
                            startMinute = startMinutes ?: 0,
                            endMinute = endMinutes ?: 0,
                            locks = locks,
                            // Editing a paused window must not quietly switch it back on.
                            enabled = existing?.enabled ?: true,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The calendar rule form.
 *
 * Padding is entered in minutes and stored in seconds: nobody plans a meeting buffer in seconds,
 * and the config keeps seconds because everything else in it does.
 */
@Composable
internal fun CalendarDialog(
    existing: CalendarSchedule?,
    profiles: List<ProfileName>,
    now: Long,
    onDismiss: () -> Unit,
    onSave: (CalendarSchedule) -> Unit,
    prefill: CalendarEvent? = null,
) {
    var profile by remember { mutableStateOf(existing?.profile ?: profiles.firstOrNull()?.id ?: "") }
    // An event picked from the calendar fills the matcher in from what that event actually says.
    // Its exact title, not a wildcard around it: the user pointed at one meeting, and widening
    // that into a pattern behind their back would block things they never chose.
    var title by remember {
        mutableStateOf(existing?.matcher?.title ?: prefill?.title.orEmpty())
    }
    var calendar by remember {
        mutableStateOf(existing?.matcher?.calendar ?: prefill?.calendar.orEmpty())
    }
    var location by remember { mutableStateOf(existing?.matcher?.location.orEmpty()) }
    var busyOnly by remember { mutableStateOf(existing?.matcher?.busyOnly ?: false) }
    var before by remember { mutableStateOf(((existing?.padBeforeSeconds ?: 0) / 60).toString()) }
    var after by remember { mutableStateOf(((existing?.padAfterSeconds ?: 0) / 60).toString()) }
    var locks by remember { mutableStateOf(existing?.locks ?: emptyList()) }

    val beforeMinutes = before.trim().ifEmpty { "0" }.toIntOrNull()
    val afterMinutes = after.trim().ifEmpty { "0" }.toIntOrNull()
    val valid = profile.isNotBlank() &&
        beforeMinutes != null && beforeMinutes >= 0 &&
        afterMinutes != null && afterMinutes >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New calendar rule" else "Edit calendar rule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfilePicker(profiles, profile) { profile = it }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title contains") },
                    placeholder = { Text("*focus*") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = calendar,
                    onValueChange = { calendar = it },
                    label = { Text("Calendar") },
                    placeholder = { Text("Work") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Leave a field empty to ignore it. A star matches anything, so *focus* catches " +
                        "any event with the word focus in it. A rule with nothing filled in catches " +
                        "every event on every calendar.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Only events marked busy", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = busyOnly, onCheckedChange = { busyOnly = it })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = before,
                        onValueChange = { before = it },
                        label = { Text("Start early (min)") },
                        isError = beforeMinutes == null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = after,
                        onValueChange = { after = it },
                        label = { Text("Run late (min)") },
                        isError = afterMinutes == null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                LockPicker(locks) { locks = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        CalendarSchedule(
                            id = existing?.id ?: "c-$now",
                            profile = profile,
                            matcher = EventMatcher(
                                // Blank means "do not care", not "match the empty string": a
                                // matcher holding "" would catch nothing at all.
                                title = title.trim().ifBlank { null },
                                calendar = calendar.trim().ifBlank { null },
                                location = location.trim().ifBlank { null },
                                busyOnly = busyOnly,
                            ),
                            padBeforeSeconds = (beforeMinutes ?: 0) * 60,
                            padAfterSeconds = (afterMinutes ?: 0) * 60,
                            locks = locks,
                            // Editing a paused rule must not quietly switch it back on.
                            enabled = existing?.enabled ?: true,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
