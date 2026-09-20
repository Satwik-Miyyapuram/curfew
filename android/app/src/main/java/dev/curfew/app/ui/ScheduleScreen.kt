// combinedClickable is how a row carries both "open this" and "remove this" without a second
// control taking up space in every row. It is experimental only in the sense that its signature
// may change; the gesture itself is the platform's oldest one.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.curfew.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.CalendarEvent
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.ProfileName
import dev.curfew.policy.WeeklySchedule

/**
 * The plan: one focused card per profile, and under it every reason that profile turns on.
 *
 * Clean, uncluttered layout showing active weekly schedules and calendar event triggers.
 * Power-user config backup & restore is moved to Settings to keep the daily schedule clean.
 */
@Composable
fun ScheduleScreen(
    model: CurfewViewModel,
    onNewProfile: () -> Unit = {},
    onEditProfile: (String) -> Unit = {},
    onPickFromCalendar: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()

    var weeklyForm by remember { mutableStateOf<Editing<WeeklySchedule>?>(null) }
    var calendarForm by remember { mutableStateOf<Editing<CalendarSchedule>?>(null) }
    var removing by remember { mutableStateOf<Removal?>(null) }
    var removingProfile by remember { mutableStateOf<ProfileName?>(null) }

    // Which profile is picking meetings, if any.
    var picking by remember { mutableStateOf<ProfileName?>(null) }

    var expandedProfileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(state.profiles) {
        if (expandedProfileIds.isEmpty() && state.profiles.isNotEmpty()) {
            expandedProfileIds = setOf(state.profiles.first().id)
        }
    }

    val running = state.weekly.count { it.enabled } + state.calendarRules.count { it.enabled }
    val fromCalendar = state.calendarRules.count { it.enabled }

    Screen(spacing = 0.dp) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
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
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(42.dp)
                    .clip(RoundedCornerShape(Dsn.CtlRadius))
                    .background(Palette.Accent)
                    .clickable(onClickLabel = "Add a profile", onClick = onNewProfile),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
        }

        Gap(20.dp)

        if (state.profiles.isEmpty()) {
            DCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No profiles yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                    Gap(6.dp)
                    Text(
                        "A profile is the set of apps and sites to block. Create your first profile to start scheduling focus time.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Palette.Muted,
                        textAlign = TextAlign.Center,
                    )
                    Gap(16.dp)
                    PrimaryButton("Create a profile", onClick = onNewProfile)
                }
            }
        } else {
            state.profiles.forEachIndexed { index, profile ->
                val tint = Palette.ProfileColours[index % Palette.ProfileColours.size]
                val isExpanded = profile.id in expandedProfileIds
                val windows = state.weekly.filter { it.profile == profile.id }
                val calendar = state.calendarRules.filter { it.profile == profile.id }
                val live = windows.any { it.enabled } || calendar.any { it.enabled }

                DCardFlush {
                    // Header row of the profile card (clickable to toggle expanded)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedProfileIds = if (isExpanded) {
                                    expandedProfileIds - profile.id
                                } else {
                                    expandedProfileIds + profile.id
                                }
                            }
                            .padding(Dsn.CardPad),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Glyph(tint, size = 42.dp) {
                            Text(
                                profile.name.take(1).uppercase(),
                                fontSize = 17.sp,
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
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }

                        // Master Switch for all triggers in this profile
                        if (windows.isNotEmpty() || calendar.isNotEmpty()) {
                            Switch(live) { on ->
                                windows.forEach { model.saveWeekly(it.copy(enabled = on)) }
                                calendar.forEach { model.saveCalendarRule(it.copy(enabled = on)) }
                            }
                        }

                        // Caret indicator
                        Text(
                            if (isExpanded) "▾" else "▸",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.Dim,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }

                    if (isExpanded) {
                        // Weekly schedules
                        windows.forEach { window ->
                            Rule()
                            TriggerRow(
                                title = describeDays(window.days),
                                note = describeWindow(window).substringAfter(" · "),
                                tint = Palette.Accent,
                                enabled = window.enabled,
                                onToggle = { model.saveWeekly(window.copy(enabled = it)) },
                                onOpen = { weeklyForm = Editing(window, profile.id) },
                                onRemove = {
                                    removing = Removal(window.id, describeWindow(window), weekly = true)
                                },
                            )
                        }

                        // Calendar schedules
                        calendar.forEach { rule ->
                            Rule()
                            val caught = state.calendarEvents.filter { matches(rule, it) }
                            TriggerRow(
                                title = "From your calendar",
                                note = describeMatcher(rule.matcher),
                                tint = Palette.Accent,
                                enabled = rule.enabled,
                                onToggle = { model.saveCalendarRule(rule.copy(enabled = it)) },
                                onOpen = { calendarForm = Editing(rule, profile.id) },
                                onRemove = {
                                    removing = Removal(rule.id, describeMatcher(rule.matcher), weekly = false)
                                },
                                bottomPad = if (caught.isEmpty()) Dsn.CardPad else 8.dp,
                            )
                            caught.take(3).forEach { event ->
                                CaughtEvent(event.title, "${dayLabel(event.start, state.now)} " + clockTime(event.start))
                            }
                            if (caught.size > 3) {
                                CaughtEvent("and ${caught.size - 3} more", "")
                            }
                        }

                        // Empty state inside profile card
                        if (windows.isEmpty() && calendar.isEmpty()) {
                            Rule()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dsn.CardPad, vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "No automated schedules yet",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Palette.Text,
                                )
                                Gap(4.dp)
                                Text(
                                    "Add a recurring window or sync with your calendar.",
                                    fontSize = 12.sp,
                                    color = Palette.Muted,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        // Clean Action Buttons: Edit Profile and Add Window (no Apps & Sites button)
                        Rule()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dsn.CardPad, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            GhostButton("✏️ Edit Profile", Modifier.weight(1f)) {
                                onEditProfile(profile.id)
                            }
                            GhostButton("+ Window", Modifier.weight(1f)) {
                                weeklyForm = Editing(null, profile.id)
                            }
                        }
                    }
                }
                Gap(14.dp)
            }

            GhostButton("+ Create another profile") {
                onNewProfile()
            }
            Gap(16.dp)
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
        DConfirm(
            title = "Remove ${profile.name}?",
            sub = "Everything it blocks goes with it, including any schedules starting it.",
            body = "A session it has already started keeps running until its own lock lets it go.",
            dismiss = "Keep it",
            confirm = "Remove",
            destructive = true,
            onDismiss = { removingProfile = null },
            onConfirm = {
                val chosen = profile
                removingProfile = null
                model.deleteProfile(chosen.id)
            },
        )
    }

    weeklyForm?.let { form ->
        WeeklyDialog(
            existing = form.value,
            profiles = state.profiles,
            now = state.now,
            defaultProfile = form.targetProfileId ?: form.value?.profile,
            onDismiss = { weeklyForm = null },
            onSave = { window ->
                weeklyForm = null
                model.saveWeekly(window)
            },
        )
    }

    val availableCalendars = remember(state.calendarEvents) {
        state.calendarEvents.map { it.calendar.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }

    calendarForm?.let { form ->
        CalendarDialog(
            existing = form.value,
            profiles = state.profiles,
            now = state.now,
            defaultProfile = form.targetProfileId ?: form.value?.profile,
            availableCalendars = availableCalendars,
            onDismiss = { calendarForm = null },
            onSave = { rule ->
                calendarForm = null
                model.saveCalendarRule(rule)
            },
        )
    }

    removing?.let { target ->
        DConfirm(
            title = "Remove this schedule?",
            sub = target.description,
            body = "It will stop starting sessions. A session it has already started keeps running until its own lock lets it go. If you only want it off for a while, use the switch instead.",
            dismiss = "Keep it",
            confirm = "Remove",
            destructive = true,
            onDismiss = { removing = null },
            onConfirm = {
                val chosen = target
                removing = null
                if (chosen.weekly) model.deleteWeekly(chosen.id)
                else model.deleteCalendarRule(chosen.id)
            },
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
internal data class Editing<T>(val value: T?, val targetProfileId: String? = null)

/** A schedule the user has asked to remove, waiting on the confirmation. */
internal data class Removal(val id: String, val description: String, val weekly: Boolean)
