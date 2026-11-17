package dev.curfew.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.EventMatcher
import dev.curfew.policy.Lock
import dev.curfew.policy.ProfileName

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
    "Solve math" to Lock.Challenge(ChallengeKind.MATH),
    "Type it out" to Lock.Challenge(ChallengeKind.TYPING),
    "Restart the device" to Lock.RestartRequired,
)

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

/** The profile a schedule runs. A schedule naming no profile enforces nothing, so one is required. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePicker(profiles: List<ProfileName>, chosen: String, onChoose: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        profiles.forEach { profile ->
            Pill(profile.name, selected = profile.id == chosen, onClick = { onChoose(profile.id) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LockPicker(chosen: List<Lock>, onChange: (List<Lock>) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        OFFERED_LOCKS.forEach { (label, lock) ->
            Pill(label, selected = lock in chosen, onClick = {
                onChange(if (lock in chosen) chosen - lock else chosen + lock)
            })
        }
    }
    SheetNote(
        if (chosen.isEmpty()) {
            "Nothing chosen means you can end it whenever you like."
        } else {
            "Every condition you add is one you will have to satisfy to get out early."
        },
    )
}

/**
 * The calendar rule form.
 *
 * Padding is entered in minutes and stored in seconds: nobody plans a meeting buffer in seconds,
 * and the config keeps seconds because everything else in it does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarDialog(
    existing: CalendarSchedule?,
    profiles: List<ProfileName>,
    now: Long,
    defaultProfile: String? = null,
    onDismiss: () -> Unit,
    onSave: (CalendarSchedule) -> Unit,
    prefill: CalendarEvent? = null,
    availableCalendars: List<String> = emptyList(),
) {
    var profile by remember { mutableStateOf(existing?.profile ?: defaultProfile ?: profiles.firstOrNull()?.id ?: "") }
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

    DSheet(
        title = if (existing == null) "New calendar rule" else "Edit calendar rule",
        sub = describeMatcher(
            EventMatcher(
                title = title.trim().ifBlank { null },
                calendar = calendar.trim().ifBlank { null },
                location = location.trim().ifBlank { null },
                busyOnly = busyOnly,
            ),
        ) + ".",
        onDismiss = onDismiss,
        confirm = "Save",
        confirmEnabled = valid,
        onConfirm = {
            onSave(
                CalendarSchedule(
                    id = existing?.id ?: "c-$now",
                    profile = profile,
                    matcher = EventMatcher(
                        // Blank means "do not care", not "match the empty string": a matcher
                        // holding "" would catch nothing at all.
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
    ) {
        SheetSection("Profile") { ProfilePicker(profiles, profile) { profile = it } }

        SheetSection("Which events") {
            TextField("Title contains", title, placeholder = "*focus*") { title = it }
            Gap(Space.m)
            if (availableCalendars.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    availableCalendars.forEach { calName ->
                        Pill(
                            text = calName,
                            selected = calendar.equals(calName, ignoreCase = true),
                            onClick = {
                                calendar = if (calendar.equals(calName, ignoreCase = true)) "" else calName
                            },
                        )
                    }
                }
                Gap(Space.s)
            }
            TextField("Calendar", calendar, placeholder = "Work") { calendar = it }
            Gap(Space.m)
            TextField("Location", location, placeholder = "Anywhere") { location = it }
            SheetNote(
                "Leave a field empty to ignore it. A star matches anything, so *focus* catches any " +
                    "event with the word focus in it.",
            )
            Gap(Space.l)
            DRow(
                title = "Only events marked busy",
                note = "Ignores the ones you are merely invited to.",
                trailing = { Switch(busyOnly) { busyOnly = it } },
            )
        }

        SheetSection("Padding") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                listOf(0, 5, 10, 15).forEach { mins ->
                    val selected = before == mins.toString() && after == mins.toString()
                    Pill(
                        text = if (mins == 0) "No buffer" else "±${mins}m",
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            before = mins.toString()
                            after = mins.toString()
                        },
                    )
                }
            }
            Gap(Space.m)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                ValueField(
                    "Start early",
                    before,
                    bad = beforeMinutes == null,
                    modifier = Modifier.weight(1f),
                ) { before = it }
                ValueField(
                    "Run late",
                    after,
                    bad = afterMinutes == null,
                    modifier = Modifier.weight(1f),
                ) { after = it }
            }
            SheetNote("Minutes, either side of the event.")
        }

        SheetSection("Locked by") { LockPicker(locks) { locks = it } }
    }
}
