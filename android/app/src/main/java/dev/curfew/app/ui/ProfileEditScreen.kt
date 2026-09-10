package dev.curfew.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.Action
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import dev.curfew.policy.WeeklySchedule

private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")

/** Starting points, as the canvas spells them: an emoji and a word, not a template menu. */
private val PRESETS = listOf(
    "📚" to "Study",
    "🌙" to "Sleep",
    "📱" to "Socials diet",
    "🏠" to "Weekend",
)

/**
 * Making a profile, and changing one.
 *
 * The same screen for both, because "new" and "edit" differ only in whether the name box starts
 * empty — and a create flow that looks nothing like the edit flow teaches the user the app twice.
 *
 * Two rules run through it. **No id ever reaches the screen.** A profile answers to a slug in the
 * config and to a name everywhere a person can see, and the previous version leaked the slug into
 * headings, chips and confirmations, which is how a tool starts feeling like someone else's
 * database. And **every fixed number is a control**: the hour a block starts, the minute it ends,
 * the days it runs. Those were constants in a file, which meant "21:00 on weeknights" was a
 * decision the app had made on the user's behalf and would not discuss.
 */
@Composable
fun ProfileEditScreen(model: CurfewViewModel, id: String?, onDone: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()

    // Matched on the minted id as well as the routed one, so the screen stops calling itself
    // "New profile" the moment the first save lands and starts editing what it just created.
    var name by remember(id) { mutableStateOf("") }
    // Which of the triggers has opened its own picker, so choosing what a profile blocks never
    // means leaving the profile.
    var picking by remember { mutableStateOf<String?>(null) }
    val existing = state.profiles.firstOrNull { it.id == (id ?: slug(name)) }

    LaunchedEffect(existing?.id) {
        if (name.isBlank()) existing?.let { name = it.name }
    }

    // The id is minted from the name the first time and never shown or changed afterwards:
    // renaming a profile must not orphan the schedules pointing at it.
    val profileId = id ?: slug(name)
    val appCount = remember(profileId, state.configToml) { model.blockedApps(profileId).size }
    val siteCount = remember(profileId, state.configToml) { model.rulesBeyondApps(profileId).size }
    val windows = state.weekly.filter { it.profile == profileId }
    val tint = Palette.ProfileColours[
        state.profiles.indexOfFirst { it.id == profileId }
            .coerceAtLeast(0) % Palette.ProfileColours.size,
    ]

    Screen(spacing = 0.dp) {
        BackRow(if (existing == null) "New profile" else existing.name, onDone)

        if (existing == null) {
            Gap(10.dp)
            Title("What should we call this one?", size = 26)
            Gap(6.dp)
            Sub("A name you would say out loud. You can change it whenever.")
        }

        Gap(20.dp)
        NameField(name, tint) { name = it }

        if (existing == null) {
            Gap(22.dp)
            SectionLabel("Or start from one of these")
            Gap(10.dp)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESETS.forEach { (emoji, value) ->
                    Pill("$emoji $value", selected = name == value, onClick = { name = value })
                }
            }
        }

        Gap(22.dp)
        Title("When should it run?", size = 20)
        Gap(6.dp)
        Sub(
            "Pick as many as you like. They stack \u2014 a calendar rule and a nightly schedule " +
                "can both switch the same profile on.",
        )
        Gap(12.dp)

        // Read back from the config rather than from a wizard's own memory: a trigger is chosen
        // because something in curfew.toml says so, which is the only version of "chosen" that
        // survives leaving the screen.
        val budgeted = remember(profileId, state.configToml) {
            model.rulesBeyondApps(profileId).any { it.action is Action.Budget }
        }
        val fromCalendar = state.calendarRules.any { it.profile == profileId }
        val triggers = listOf(
            Trigger(
                glyph = "\uD83D\uDD01",
                tint = Palette.Accent,
                title = "A repeating schedule",
                example = "Weeknights 21:00 to midnight, every Mon\u2013Fri.",
                chosen = windows.isNotEmpty(),
            ),
            Trigger(
                glyph = "\u23F1",
                tint = Palette.Live,
                title = "A timer I start myself",
                example = "Tap once, block for 90 minutes. Nothing scheduled.",
                // Always true: a timer needs no setting up, it is the Now tab's button.
                chosen = true,
            ),
            Trigger(
                glyph = "\uD83D\uDCC5",
                tint = Palette.Ok,
                title = "Anything in my calendar",
                example = "Events whose title contains \u2018lecture\u2019.",
                chosen = fromCalendar,
            ),
            Trigger(
                glyph = "\u23F3",
                tint = Palette.Bad,
                title = "A daily budget",
                example = "Half an hour of socials a day, then they close.",
                chosen = budgeted,
            ),
            Trigger(
                glyph = "\u221E",
                tint = Palette.Muted,
                title = "Always on",
                example = "Never unblocked, unless you spend a pass.",
                chosen = windows.any { it.days.size == 7 },
            ),
        )

        DCardFlush {
            triggers.forEachIndexed { index, trigger ->
                if (index > 0) Rule()
                TriggerRow(trigger) {
                    when (trigger.title) {
                        "A repeating schedule" -> {
                            if (name.isBlank()) {
                                model.say("Give it a name first.")
                            } else {
                                model.saveProfileWithWindow(
                                    profileId,
                                    name.trim(),
                                    WeeklySchedule(
                                        // Minted from the clock so two windows added in the same
                                        // session cannot collide, and never shown.
                                        id = "w-${state.now}",
                                        profile = profileId,
                                        // A weeknight evening: the commonest thing anyone sets up,
                                        // and every part of it is a control on the card below.
                                        // Monday is 0 and Sunday is 6, the way the core counts
                                        // days from Monday. Numbering these from 1 made every
                                        // Sunday window invalid.
                                        days = listOf(0, 1, 2, 3, 4),
                                        startMinute = 21 * 60,
                                        // Midnight at the far end is 0, not 1440: the core reads
                                        // an end at or before the start as "the next day", and
                                        // refuses any minute outside the day itself.
                                        endMinute = 0,
                                        locks = listOf(Lock.Confirm),
                                    ),
                                )
                            }
                        }
                        "A timer I start myself" -> {
                            if (name.isBlank()) {
                                model.say("Give it a name first.")
                            } else {
                                model.saveProfile(profileId, name.trim())
                                picking = "apps"
                            }
                        }
                        "Anything in my calendar" -> {
                            // The events open here rather than on their own tab. Being told to go
                            // somewhere else, find the same list, and remember which profile you
                            // were half way through building is how a profile gets abandoned.
                            if (name.isBlank()) {
                                model.say("Give it a name first.")
                            } else {
                                model.saveProfile(profileId, name.trim())
                                picking = "calendar"
                            }
                        }
                        "A daily budget" -> {
                            if (name.isBlank()) {
                                model.say("Give it a name first.")
                            } else {
                                model.saveProfile(profileId, name.trim())
                                picking = "budget"
                            }
                        }
                        else ->
                            model.say(
                                "Add a window covering the whole week to leave it always on.",
                            )
                    }
                }
            }
        }

        Gap(16.dp)
        SectionLabel("What it blocks")
        Gap(8.dp)
        // The other half of a profile, and the half that used to live on a tab of its own. Apps
        // and sites belong to a profile, so the place to set them is inside the profile — the tab
        // asked which profile you meant when you had just come from it.
        DCardFlush {
            TriggerRow(
                Trigger(
                    glyph = "■",
                    tint = Palette.Accent,
                    title = blocksLine(appCount, siteCount),
                    example = "Apps and websites, kept apart in two lists.",
                    chosen = appCount + siteCount > 0,
                ),
            ) {
                if (name.isBlank()) {
                    model.say("Give it a name first.")
                } else {
                    model.saveProfile(profileId, name.trim())
                    picking = "apps"
                }
            }
        }

        Gap(10.dp)
        Text(
            "${chosenWord(triggers.count { it.chosen })} Whichever starts first wins, and the " +
                "block ends when the last one is done.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Palette.Dim,
        )

        windows.forEach { window ->
            Gap(12.dp)
            WindowCard(
                window = window,
                onChange = { model.saveWeekly(it) },
                onRemove = { model.deleteWeekly(window.id) },
            )
        }

        Gap(18.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (existing != null) {
                GhostButton(
                    text = "Delete",
                    modifier = Modifier.weight(1f),
                    colour = Palette.Bad,
                ) {
                    model.deleteProfile(existing.id)
                    onDone()
                }
            }
            PrimaryButton(
                text = "Save",
                modifier = Modifier.weight(1f),
            ) {
                if (name.isBlank()) {
                    model.say("Give it a name first.")
                    return@PrimaryButton
                }
                model.saveProfile(profileId, name.trim())
                onDone()
            }
        }
    }

    when (picking) {
        "calendar" -> CalendarPickerSheet(
            model = model,
            profileId = profileId,
            profileName = name.trim(),
            onDone = { picking = null },
        )

        "apps" -> AppPickerSheet(
            model = model,
            profileId = profileId,
            profileName = name.trim(),
            onDone = { picking = null },
        )

        "budget" -> BudgetSheet(
            model = model,
            profileId = profileId,
            onDone = { picking = null },
        )
    }

    // Whatever the core said. This screen used to swallow it, which is how "Add a window" could
    // fail silently and leave a profile with no schedule and no explanation.
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissMessage,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissMessage) { Text("OK") } },
        )
    }
}

/**
 * The name, as the largest editable thing on the screen.
 *
 * Drawn rather than themed because a Material text field brings a floating label, a filled
 * container and its own idea of a focus colour, none of which belong on a card whose whole point
 * is that it looks like the profile it is about to become.
 */
@Composable
private fun NameField(
    value: String,
    tint: androidx.compose.ui.graphics.Color,
    onChange: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(Dsn.CardRadius))
            .background(Palette.Surface)
            .border(
                1.dp,
                if (value.isBlank()) Palette.Line else tint,
                RoundedCornerShape(Dsn.CardRadius),
            )
            .padding(horizontal = Dsn.CardPad),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text("Name it", fontSize = 19.sp, color = Palette.Dim)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Text,
            ),
            cursorBrush = SolidColor(tint),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Mon–Fri 21:00 → midnight", the way the pill on the canvas says it. */
private fun summarise(window: WeeklySchedule): String {
    val days = when {
        window.days == listOf(0, 1, 2, 3, 4) -> "Mon–Fri"
        window.days == listOf(5, 6) -> "Weekend"
        window.days.size == 7 -> "Every day"
        else -> window.days.joinToString("") { DAYS.getOrElse(it) { "?" } }
    }
    return "$days ${clock(window.startMinute)} → ${clock(window.endMinute)}"
}

/**
 * One weekly window, with every number in it as a control.
 *
 * Times move in fifteen-minute steps because that is the granularity anyone actually means when
 * they say when they want to stop — minute-precision here would be a spinner nobody can hit and a
 * decision nobody wanted to make.
 */
@Composable
private fun WindowCard(
    window: WeeklySchedule,
    onChange: (WeeklySchedule) -> Unit,
    onRemove: () -> Unit,
) {
    DCard {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAYS.forEachIndexed { index, letter ->
                // Monday is 1 in the config, and Sunday is 7 — the ISO numbering the core uses,
                // kept out of the user's way.
                val day = index
                val on = day in window.days
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (on) Palette.Accent else Palette.Raised)
                        .clickable {
                            val days = if (on) window.days - day else window.days + day
                            onChange(window.copy(days = days.sorted()))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (on) Palette.Ink else Palette.Muted,
                    )
                }
            }
        }

        Gap(14.dp)
        Rule()
        Gap(14.dp)

        MinuteRow("Starts", window.startMinute) { onChange(window.copy(startMinute = it)) }
        Gap(10.dp)
        MinuteRow("Ends", window.endMinute) { onChange(window.copy(endMinute = it)) }

        Gap(14.dp)
        Rule()
        Gap(14.dp)

        SectionLabel("How hard is it to get out")
        Gap(9.dp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            strengths().forEach { (label, lock) ->
                Pill(
                    label,
                    selected = window.locks.firstOrNull() == lock,
                    onClick = { onChange(window.copy(locks = listOfNotNull(lock))) },
                )
            }
        }

        Gap(14.dp)
        Text(
            "Remove this window",
            fontSize = 13.sp,
            color = Palette.Bad,
            modifier = Modifier.clickable(onClick = onRemove),
        )
    }
}

/** The four answers to "how hard should this be to escape", in order of how hard they are. */
private fun strengths(): List<Pair<String, Lock?>> = listOf(
    "I can stop it" to null,
    "Ask me first" to Lock.Confirm,
    "Fingerprint" to Lock.DeviceCredential,
    "Type it out" to Lock.Challenge(ChallengeKind.TYPING),
)

@Composable
private fun MinuteRow(label: String, minute: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = Palette.Text, modifier = Modifier.weight(1f))
        // The stepper from the canvas: a raised tray holding minus, the value, and an accented
        // plus. The tray is what makes the two glyphs read as one control rather than two buttons.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(Palette.Raised)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Nudge("−", "fifteen minutes earlier", accent = false) {
                onChange(((minute - 15) + 24 * 60) % (24 * 60))
            }
            Text(
                clock(minute),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Text,
                modifier = Modifier.width(62.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Nudge("+", "fifteen minutes later", accent = true) {
                onChange((minute + 15) % (24 * 60))
            }
        }
    }
}

/**
 * Minutes past midnight as a clock face.
 *
 * Zero is spelled "midnight" rather than "00:00" because in this app it is almost always the far
 * end of an evening window rather than the start of one, and a window that ends at or before it
 * starts is the core's own way of saying "and on into tomorrow".
 */
private fun clock(minute: Int): String {
    val m = ((minute % (24 * 60)) + 24 * 60) % (24 * 60)
    if (m == 0) return "midnight"
    return "%02d:%02d".format(m / 60, m % 60)
}

private fun slug(name: String): String =
    name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "profile" }

@Composable
private fun Nudge(glyph: String, description: String, accent: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (accent) Palette.Accent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) Palette.Ink else Palette.Muted,
        )
    }
}

/** One thing that can switch a profile on, as the canvas lists them. */
private data class Trigger(
    val glyph: String,
    val tint: androidx.compose.ui.graphics.Color,
    val title: String,
    val example: String,
    val chosen: Boolean,
)

/**
 * A trigger, with a tick when it is already set up and a chevron when it is not.
 *
 * The example line under each title is doing the real work: "a calendar rule" means nothing until
 * it is spelled as an event whose title contains a word, and a list of five abstractions is how a
 * setup screen gets skipped.
 */
@Composable
private fun TriggerRow(trigger: Trigger, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(trigger.tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(trigger.glyph, fontSize = 17.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                trigger.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Text,
            )
            Text(
                trigger.example,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Palette.Muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            if (trigger.chosen) "\u2713" else "\u203A",
            fontSize = if (trigger.chosen) 15.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (trigger.chosen) Palette.Ok else Palette.Dim,
        )
    }
}

/** "Two chosen." \u2014 the count as a word, because a digit here reads like a setting. */
private fun chosenWord(count: Int): String {
    val word = when (count) {
        0 -> "Nothing"
        1 -> "One"
        2 -> "Two"
        3 -> "Three"
        4 -> "Four"
        else -> "Five"
    }
    return if (count == 0) "$word chosen yet." else "$word chosen."
}

/**
 * A daily budget, set from inside the profile it belongs to.
 *
 * A budget is not a separate kind of rule so much as a softer verb on the rules already there: the
 * apps this profile blocks get a number of minutes a day instead of none. So the sheet asks for the
 * number and writes it onto every app the profile already names, which is the only version of
 * "half an hour of socials" that means anything — a budget with nothing under it blocks nothing.
 */
@Composable
fun BudgetSheet(model: CurfewViewModel, profileId: String, onDone: () -> Unit) {
    val apps = remember(profileId) { model.blockedApps(profileId) }
    var minutes by remember { mutableStateOf(30) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("A daily budget") },
        text = {
            Column {
                Text(
                    if (apps.isEmpty()) {
                        "This profile does not block any app yet, so there is nothing to ration. " +
                            "Add some apps first and the budget will apply to those."
                    } else {
                        "${apps.size} app${if (apps.size == 1) "" else "s"} in this profile will " +
                            "open until the budget is spent, then close for the rest of the day."
                    },
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = Palette.Muted,
                )
                Gap(14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { option ->
                        Pill(
                            text = spellDuration(option),
                            selected = minutes == option,
                            onClick = { minutes = option },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = apps.isNotEmpty(),
                onClick = {
                    apps.forEach { packageName ->
                        model.saveRule(
                            profileId,
                            dev.curfew.policy.Rule(
                                target = dev.curfew.policy.Target.AppPackage(packageName),
                                action = Action.Budget(seconds = minutes * 60),
                            ),
                        )
                    }
                    onDone()
                },
            ) { Text("Set it") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** "Nothing yet", or what the profile holds, counted in the two kinds it is kept in. */
private fun blocksLine(apps: Int, sites: Int): String = when {
    apps == 0 && sites == 0 -> "Nothing yet"
    sites == 0 -> "$apps app${if (apps == 1) "" else "s"}"
    apps == 0 -> "$sites site${if (sites == 1) "" else "s"}"
    else -> "$apps app${if (apps == 1) "" else "s"} and $sites site${if (sites == 1) "" else "s"}"
}
