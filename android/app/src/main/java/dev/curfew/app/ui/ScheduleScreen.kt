// combinedClickable is how a row carries both "open this" and "remove this" without a second
// control taking up space in every row. It is experimental only in the sense that its signature
// may change; the gesture itself is the platform's oldest one.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.curfew.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.ProfileName
import dev.curfew.policy.WeeklySchedule

/**
 * The plan: one card per profile, and under it every reason that profile turns on.
 *
 * This screen has been two wrong shapes already. First four stacked sections — a timeline,
 * profiles, weekly windows, calendar rules — which asked the user to hold the app's data model
 * in their head before they could answer "what is going to happen to me?". Then one flat list of
 * everything that could start a block, which answered that question but lost the one people asked
 * next: a profile can be started by a schedule *and* by four meetings, and a flat list scattered
 * those five rows down the page with the profile's name repeated on every one of them.
 *
 * So the profile is the card, and the things that start it are rows inside it. The card names what
 * the profile takes away ("2 apps · 1 site · 1h a day"), each calendar rule shows the real
 * events it has caught in the next week, and the sub-line counts both numbers — how many
 * profiles, and how many things can start one.
 *
 * The switches stay, at both levels. Pausing is not deleting — "not this week" is a thing people
 * mean constantly, and an app with nowhere to put it teaches them to delete the window and rebuild
 * it later from memory, usually wrong. The card's switch is the profile's whole plan; a row's is
 * that one reason for it.
 *
 * Tapping a card opens the profile; holding one offers to remove it. The config text itself stays,
 * in Power mode only, because the file is the thing a user backs up and carries between devices and
 * hiding it would make their own document a mystery to them.
 */
@Composable
fun ScheduleScreen(
    model: CurfewViewModel,
    onNewProfile: () -> Unit = {},
    onEditProfile: (String) -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val mode by model.mode.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(state.configToml) }
    var editing by remember { mutableStateOf(false) }

    // Which form is open, if any. `Editing(null)` is a new schedule; a value is an edit of that
    // one. Held here rather than in the rows so only one form can be open at a time.
    var weeklyForm by remember { mutableStateOf<Editing<WeeklySchedule>?>(null) }
    var calendarForm by remember { mutableStateOf<Editing<CalendarSchedule>?>(null) }
    var removing by remember { mutableStateOf<Removal?>(null) }
    var removingProfile by remember { mutableStateOf<ProfileName?>(null) }

    // Which profile is picking meetings, if any. The picker is the same sheet the profile screen
    // opens, so an event chosen from either place is the same rule written the same way.
    var picking by remember { mutableStateOf<ProfileName?>(null) }

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

    val running = state.weekly.count { it.enabled } + state.calendarRules.count { it.enabled }
    val fromCalendar = state.calendarRules.count { it.enabled }

    Screen(spacing = 0.dp) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Title("Plan")
                Sub(
                    summarise(
                        profiles = state.profiles.size,
                        triggers = running,
                        fromCalendar = fromCalendar,
                        loading = state.loading,
                    ),
                )
            }
            // The one accent-filled control on the screen, because adding a profile is the one
            // thing a user with nothing set up has to do next.
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(42.dp)
                    .clip(RoundedCornerShape(Dsn.CtlRadius))
                    .background(Palette.Accent)
                    .combinedClickable(onClickLabel = "Add a profile", onClick = onNewProfile),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
        }

        Gap(22.dp)

        if (state.profiles.isEmpty()) {
            DCard {
                Text(
                    "Nothing can start a block yet. Make a profile first \u2014 a profile is the " +
                        "set of things to switch off \u2014 then say when it should run.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Palette.Muted,
                )
            }
        }

        // One card per profile, with everything that can switch it on inside it.
        //
        // The flat list this replaced gave every rule a card of its own, headed by the profile it
        // ran \u2014 so a profile started by three meetings read as three separate blocks all called
        // "Distractions", and the events themselves were only ever described as a matcher. A plan
        // is a small number of profiles, each with a handful of reasons to run; this says that.
        state.profiles.forEachIndexed { index, profile ->
            val tint = Palette.ProfileColours[index % Palette.ProfileColours.size]
            val windows = state.weekly.filter { it.profile == profile.id }
            val calendar = state.calendarRules.filter { it.profile == profile.id }

            DCardFlush {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onEditProfile(profile.id) },
                            onLongClick = { removingProfile = profile },
                            onClickLabel = "Edit ${profile.name}",
                            onLongClickLabel = "Remove ${profile.name}",
                        )
                        .padding(Dsn.CardPad),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Glyph(tint, size = 38.dp) {
                        Text(
                            profile.name.take(1).uppercase(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = tint,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.Text,
                        )
                        Text(
                            model.describeBlocks(profile.id),
                            fontSize = 12.sp,
                            color = Palette.Muted,
                        )
                    }
                    // The master switch: what "not this week" means for a whole profile, rather
                    // than for one of the three rules that happen to start it.
                    val live = windows.any { it.enabled } || calendar.any { it.enabled }
                    if (windows.isNotEmpty() || calendar.isNotEmpty()) {
                        Switch(live) { on ->
                            windows.forEach { model.saveWeekly(it.copy(enabled = on)) }
                            calendar.forEach { model.saveCalendarRule(it.copy(enabled = on)) }
                        }
                    }
                }

                windows.forEach { window ->
                    Rule()
                    TriggerRow(
                        title = describeDays(window.days),
                        note = describeWindow(window).substringAfter(" \u00B7 "),
                        tint = Palette.Live,
                        enabled = window.enabled,
                        onToggle = { model.saveWeekly(window.copy(enabled = it)) },
                        onOpen = { weeklyForm = Editing(window) },
                        onRemove = {
                            removing = Removal(window.id, describeWindow(window), weekly = true)
                        },
                    )
                }

                calendar.forEach { rule ->
                    Rule()
                    val caught = state.calendarEvents.filter { matches(rule, it) }
                    TriggerRow(
                        title = "From your calendar",
                        note = describeMatcher(rule.matcher),
                        tint = Palette.Accent,
                        enabled = rule.enabled,
                        onToggle = { model.saveCalendarRule(rule.copy(enabled = it)) },
                        onOpen = { calendarForm = Editing(rule) },
                        onRemove = {
                            removing = Removal(rule.id, describeMatcher(rule.matcher), weekly = false)
                        },
                        bottomPad = if (caught.isEmpty()) Dsn.CardPad else 8.dp,
                    )
                    // The meetings themselves, named. A rule the user made by tapping an event is
                    // stored as a title match, and printing the match back at them ("Events titled
                    // Tapri Lab Meeting") is the config talking; these are the actual dates it has.
                    caught.take(4).forEach { event ->
                        CaughtEvent(event.title, "${dayLabel(event.start, state.now)} " + clockTime(event.start))
                    }
                    if (caught.size > 4) {
                        CaughtEvent("and ${caught.size - 4} more", "")
                    }
                }

                if (windows.isEmpty() && calendar.isEmpty()) {
                    Rule()
                    TriggerRow(
                        title = "Nothing starts it yet",
                        note = "Pick a meeting, or set a schedule",
                        tint = Palette.Muted,
                        enabled = false,
                        onToggle = {},
                        onOpen = { picking = profile },
                        onRemove = {},
                        showSwitch = false,
                    )
                }

                // Adding a meeting to a profile that already follows some, without going through
                // the profile screen: this is the page those meetings are listed on.
                if (state.calendarGranted && (windows.isNotEmpty() || calendar.isNotEmpty())) {
                    AddEvent { picking = profile }
                }
            }
            Gap(14.dp)
        }

        Gap(4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Add a window", Modifier.weight(1f), enabled = state.profiles.isNotEmpty()) {
                weeklyForm = Editing(null)
            }
            GhostButton(
                "From calendar",
                Modifier.weight(1f),
                enabled = state.profiles.isNotEmpty(),
            ) { calendarForm = Editing(null) }
        }

        Gap(14.dp)
        // The status line the canvas ends on: whether the calendar half of the plan can actually
        // happen. It is the one dependency on this screen that lives outside the app.
        DCard(padding = 16.dp) {
            Text(
                if (state.calendarGranted) {
                    "Your calendar is connected. New events matching a rule block automatically."
                } else {
                    "Your calendar is not connected, so calendar rules will not start anything. " +
                        "Connect it under Settings."
                },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = if (state.calendarGranted) Palette.Muted else Palette.Bad,
            )
        }

        // Power only, and last: the file is for the person who wants the file. In Simple mode the
        // same document is still exportable from Settings, so nothing becomes unreachable.
        if (mode.isPower) {
            Gap(22.dp)
            SectionLabel("curfew.toml")
            Gap(10.dp)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; editing = true },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                minLines = 8,
            )
            Gap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { model.saveConfig(draft); editing = false },
                    enabled = editing && draft != state.configToml,
                ) { Text("Save") }
                TextButton(
                    onClick = { draft = state.configToml; editing = false },
                    enabled = editing,
                ) { Text("Discard") }
                // Import and export are how a config moves between devices before sync exists —
                // and how it stays the user's own document afterwards. Both go through the system
                // file picker, so Curfew needs no storage permission to do it.
                TextButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("Import") }
                TextButton(onClick = { exportFile.launch("curfew.toml") }) { Text("Export") }
            }
            Gap(6.dp)
            Text(
                "Saving does not end a session that is already running. A lock you asked for is " +
                    "not something a settings edit can undo.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Palette.Muted,
            )
        }
    }

    picking?.let { profile ->
        CalendarPickerSheet(
            model = model,
            profileId = profile.id,
            profileName = profile.name,
            onDone = { picking = null },
        )
    }

    removingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { removingProfile = null },
            title = { Text("Remove ${profile.name}?") },
            text = {
                Text(
                    "Everything it blocks goes with it. A schedule still pointing at it has to " +
                        "be removed first, and a session it already started keeps running.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = profile
                    removingProfile = null
                    model.deleteProfile(chosen.id)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removingProfile = null }) { Text("Keep it") }
            },
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
    // same control again, and because what it does *not* do — end a session already running — is
    // the thing people expect it to.
    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this schedule?") },
            text = {
                Text(
                    target.description + "\n\nIt will stop starting sessions. A session it has " +
                        "already started keeps running until its own lock lets it go. If you only " +
                        "want it off for a while, use the switch instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = removing
                    removing = null
                    if (chosen != null) {
                        if (chosen.weekly) model.deleteWeekly(chosen.id)
                        else model.deleteCalendarRule(chosen.id)
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep it") } },
        )
    }
}

/**
 * One thing that can start a block.
 *
 * A paused row is dimmed rather than hidden or moved to the bottom: it is still part of the plan,
 * and a user who paused something last week has to be able to find it in the place they left it.
 */
@Composable
private fun TriggerRow(
    title: String,
    note: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    showSwitch: Boolean = true,
    bottomPad: androidx.compose.ui.unit.Dp = Dsn.CardPad,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onRemove,
                onClickLabel = "Edit this trigger",
                onLongClickLabel = "Remove this trigger",
            )
            .padding(start = Dsn.CardPad, end = Dsn.CardPad, top = Dsn.CardPad, bottom = bottomPad)
            .alpha(if (enabled || !showSwitch) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val glyphTint = if (enabled) tint else Palette.Muted
        Glyph(glyphTint, size = 38.dp) {
            Text(
                title.take(1).uppercase(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = glyphTint,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text(
                if (enabled || !showSwitch) note else "$note · paused",
                fontSize = 12.5.sp,
                color = Palette.Muted,
            )
        }
        if (showSwitch) Switch(enabled, onToggle)
    }
}

/** One meeting a calendar rule has caught, indented under the rule that caught it. */
@Composable
private fun CaughtEvent(title: String, whenIt: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 69.dp, end = Dsn.CardPad, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(11.dp))
                .background(Palette.Raised)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (whenIt.isNotEmpty()) {
                    Text(whenIt, fontSize = 11.5.sp, color = Palette.Dim)
                }
            }
        }
    }
}

/** The way to add another meeting to a profile without leaving the page that lists them. */
@Composable
private fun AddEvent(onClick: () -> Unit) {
    Text(
        "+  Add an event",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Palette.Accent,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onClickLabel = "Add an event")
            .padding(start = 69.dp, end = Dsn.CardPad, top = 2.dp, bottom = 16.dp),
    )
}

/**
 * Whether a rule would catch an event, for listing purposes only.
 *
 * Deliberately the loose half of the core's matcher \u2014 title, calendar and busy \u2014 because this
 * is a label under a row, not the decision to block: the core does that, on the same data, and
 * anything shown here that it would not catch is a wrong caption rather than a wrong block.
 */
private fun matches(rule: CalendarSchedule, event: CalendarEvent): Boolean {
    val m = rule.matcher
    val title = m.title
    if (!title.isNullOrBlank() && !event.title.contains(title.trim('*'), ignoreCase = true)) {
        return false
    }
    val calendar = m.calendar
    if (!calendar.isNullOrBlank() && !event.calendar.equals(calendar, ignoreCase = true)) {
        return false
    }
    if (m.busyOnly && !event.busy) return false
    return true
}

/** The subtitle under "Plan": what the list below adds up to, in one sentence. */
/**
 * The line under the title, counted the way the page is now laid out.
 *
 * It used to count blocks, which was the flat list talking: the page showed one card per rule, so
 * "four blocks" matched what was on screen. The page shows profiles now, each with its reasons
 * nested inside, so the honest summary is both numbers \u2014 how many profiles, and how many
 * things can start one.
 */
private fun summarise(profiles: Int, triggers: Int, fromCalendar: Int, loading: Boolean): String {
    if (loading) return "Reading your plan\u2026"
    if (profiles == 0) return "Nothing set up yet."
    val one = profiles == 1
    val who = if (one) "One profile." else "$profiles profiles."
    // "them" for one profile reads as a typo, which is what it was.
    val it = if (one) "it" else "them"
    if (triggers == 0) return "$who Nothing starts $it on its own yet."
    val what = if (triggers == 1) {
        "One thing can start $it."
    } else {
        "$triggers things can start $it."
    }
    val calendar = when {
        fromCalendar == 0 -> ""
        fromCalendar == triggers -> " All from your calendar."
        fromCalendar == 1 -> " One from your calendar."
        else -> " $fromCalendar from your calendar."
    }
    return "$who $what$calendar"
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
