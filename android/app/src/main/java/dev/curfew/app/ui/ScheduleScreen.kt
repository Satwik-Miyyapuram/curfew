package dev.curfew.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.WeeklySchedule

/**
 * What is going to happen, and the rules that decide it.
 *
 * The timeline comes first because the honest question about a scheduling tool is "what is this
 * going to do to me later today?", and that has to be answerable without reading a config. The
 * config itself is below it, as text.
 *
 * Below it are the schedules themselves, as forms: a weekly window is "these days, between these
 * two times", and a calendar rule is "whatever my calendar calls a meeting". Neither should require
 * learning a file format.
 *
 * The TOML editor stays underneath, and is not a fallback for the forms: the config is the thing a
 * user backs up, diffs and carries between devices, and hiding it would make the file a mystery to
 * the person who owns it. Both paths write through the same core validation, so neither can leave
 * the device unprotected.
 */
@Composable
fun ScheduleScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(state.configToml) }
    var editing by remember { mutableStateOf(false) }

    // Which form is open, if any. `Editing(null)` is a new schedule; a value is an edit of that
    // one. Held here rather than in the cards so only one form can be open at a time.
    var weeklyForm by remember { mutableStateOf<Editing<WeeklySchedule>?>(null) }
    var calendarForm by remember { mutableStateOf<Editing<CalendarSchedule>?>(null) }
    var removing by remember { mutableStateOf<Removal?>(null) }

    // Adopt the saved config whenever it changes underneath an untouched editor, so the text does
    // not silently go stale — but never overwrite an edit in progress.
    LaunchedEffect(state.configToml) {
        if (!editing) draft = state.configToml
    }

    val context = LocalContext.current
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            // An unreadable or invalid file changes nothing: the core validates before the working
            // config is touched, and says why in the user's own words when it refuses.
            if (text == null) model.say("That file could not be read.") else model.importConfig(text)
        }
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(state.configToml.toByteArray())
                }
            }.onFailure { model.say("That file could not be written.") }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Marked as headings so a screen reader can jump between the timeline and the rules
        // instead of swiping through every card in between.
        Text(
            "Coming up",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )

        if (state.upcoming.isEmpty()) {
            Text(
                "Nothing is scheduled. Add a profile with a schedule below and it will appear here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Grouped by day so a block tomorrow morning can never be read as one this evening. The
        // list is a preview, not a promise about the past: anything that has already ended is
        // dropped before it reaches here.
        var day: String? = null
        state.upcoming.forEach { activation ->
            val label = dayLabel(activation.start, state.now)
            if (label != day) {
                day = label
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                )
            }
            Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(activation.profile, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${clockTime(activation.start)} – ${clockTime(activation.end)} · " +
                            describeSource(activation.source),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (activation.start > state.now) {
                            "Starts ${relative(activation.start, state.now)}"
                        } else {
                            "Running, ends ${relative(activation.end, state.now)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (activation.locks.isNotEmpty()) {
                        Text(
                            // Said in the future tense for a block that has not begun: the whole
                            // point of a preview is to let someone decide before the lock exists.
                            (if (activation.start > state.now) "Will lock: needs " else "Locked: needs ") +
                                activation.locks.joinToString(", ") { describeLock(it) } + ".",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        Text(
            "Weekly windows",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        if (state.weekly.isEmpty()) {
            Text(
                "No weekly windows. Add one to block a profile at the same time every week.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.weekly.forEach { window ->
            WeeklyCard(
                window = window,
                onEdit = { weeklyForm = Editing(window) },
                onDelete = { removing = Removal(window.id, describeWindow(window), weekly = true) },
            )
        }
        Button(
            onClick = { weeklyForm = Editing(null) },
            enabled = state.profiles.isNotEmpty(),
        ) { Text("Add a window") }

        Text(
            "From your calendar",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        if (state.calendarRules.isEmpty()) {
            Text(
                "No calendar rules. Add one to block a profile for as long as a meeting lasts.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.calendarRules.forEach { rule ->
            CalendarRuleCard(
                rule = rule,
                onEdit = { calendarForm = Editing(rule) },
                onDelete = {
                    removing = Removal(rule.id, describeMatcher(rule.matcher), weekly = false)
                },
            )
        }
        Button(
            onClick = { calendarForm = Editing(null) },
            enabled = state.profiles.isNotEmpty(),
        ) { Text("Add a calendar rule") }
        // A schedule has to name a profile that exists, so the buttons above are dead until one
        // does. Said plainly rather than left as a greyed-out button with no explanation.
        if (state.profiles.isEmpty()) {
            Text(
                "Add a profile in the rules below first \u2014 a schedule has to say which one it runs.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            "Rules",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; editing = true },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            label = { Text("curfew.toml") },
            minLines = 8,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { model.saveConfig(draft); editing = false },
                enabled = editing && draft != state.configToml,
            ) {
                Text("Save")
            }
            TextButton(
                onClick = { draft = state.configToml; editing = false },
                enabled = editing,
            ) {
                Text("Discard")
            }
            // Import and export are how a config moves between devices before sync exists — and
            // how it stays the user's own document afterwards. Both go through the system file
            // picker, so Curfew needs no storage permission to do it.
            TextButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("Import") }
            TextButton(onClick = { exportFile.launch("curfew.toml") }) { Text("Export") }
        }
        Text(
            "Saving does not end a session that is already running. A lock you asked for is not " +
                "something a settings edit can undo.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    weeklyForm?.let { form ->
        WeeklyDialog(
            existing = form.value,
            profiles = state.profiles,
            now = state.now,
            onDismiss = { weeklyForm = null },
            onSave = { window ->
                weeklyForm = null
                model.saveWeekly(window)
            },
        )
    }

    calendarForm?.let { form ->
        CalendarDialog(
            existing = form.value,
            profiles = state.profiles,
            now = state.now,
            onDismiss = { calendarForm = null },
            onSave = { rule ->
                calendarForm = null
                model.saveCalendarRule(rule)
            },
        )
    }

    // Removal is confirmed because it is the one edit here that cannot be undone by pressing the
    // same button again, and because what it does *not* do \u2014 end a session already running
    // \u2014 is the thing people expect it to.
    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this schedule?") },
            text = {
                Text(
                    target.description + "\n\nIt will stop starting sessions. A session it has " +
                        "already started keeps running until its own lock lets it go.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = target
                    removing = null
                    if (chosen.weekly) model.deleteWeekly(chosen.id)
                    else model.deleteCalendarRule(chosen.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep it") } },
        )
    }
}

/**
 * A form that is open, over a schedule being edited or nothing for a new one.
 *
 * A wrapper rather than a bare nullable because null already means "no form open", and the two
 * cases have to be told apart: a new window and an edit of an existing one are different saves.
 */
internal data class Editing<T>(val value: T?)

/** A schedule the user has asked to remove, waiting on the confirmation. */
internal data class Removal(val id: String, val description: String, val weekly: Boolean)
