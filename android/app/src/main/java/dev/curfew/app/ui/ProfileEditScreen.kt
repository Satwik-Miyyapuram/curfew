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
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.Action
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import dev.curfew.policy.Rule
import dev.curfew.policy.Target
import dev.curfew.policy.WeeklySchedule
import dev.curfew.policy.label

private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")

/** Starting points, as the canvas spells them: an emoji and a word, not a template menu. */
private val PRESETS = listOf(
    "📚" to "Study",
    "💼" to "Deep Work",
    "🌙" to "Sleep",
    "📱" to "Socials diet",
    "🧘" to "Unplug",
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
    val context = LocalContext.current

    var name by remember(id) { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val existing = state.profiles.firstOrNull { it.id == (id ?: slug(name)) }

    LaunchedEffect(existing?.id) {
        if (name.isBlank()) existing?.let { name = it.name }
    }

    val profileId = id ?: slug(name)
    val blockedApps = remember(profileId, state.configToml) { model.blockedApps(profileId) }
    val blockedRules = remember(profileId, state.configToml) { model.rulesBeyondApps(profileId) }
    val appCount = blockedApps.size
    val siteCount = blockedRules.size
    val windows = state.weekly.filter { it.profile == profileId }
    val budgetSeconds = remember(profileId, state.configToml) { model.budgetSeconds(profileId) }
    val budgeted = budgetSeconds != null
    val mine = state.calendarRules.filter { it.profile == profileId }
    val fromCalendar = mine.isNotEmpty()

    val tint = Palette.profileColours[
        state.profiles.indexOfFirst { it.id == profileId }
            .coerceAtLeast(0) % Palette.profileColours.size,
    ]

    Screen(spacing = 0.dp) {
        BackRow(if (existing == null) "New profile" else existing.name, onDone)

        if (existing == null) {
            Gap(Space.m)
            Title("What should we call this one?")
            Gap(Space.s)
            Sub("A name you would say out loud. You can change it whenever.")
        }

        Gap(Space.xl)
        NameField(name, tint) { name = it }

        if (existing == null) {
            Gap(Space.xl)
            SectionLabel("Or start from a preset")
            Gap(Space.m)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                PRESETS.forEach { (emoji, value) ->
                    Pill("$emoji $value", selected = name == value, onClick = { name = value })
                }
            }
        }

        // ==========================================
        // SECTION: WHAT IT BLOCKS (Primary section)
        // ==========================================
        Gap(Space.xl)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("What it blocks")
            Text(
                blocksLine(appCount, siteCount),
                style = Type.meta,
                fontWeight = FontWeight.Bold,
                color = if (appCount + siteCount > 0) Palette.accent else Palette.dim,
            )
        }
        Gap(Space.m)

        var newWebDomain by remember { mutableStateOf("") }

        DCard {
            // Blocked Apps Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Blocked Apps ($appCount)",
                    style = Type.body,
                    fontWeight = FontWeight.Bold,
                    color = Palette.text,
                )
                if (appCount > 0) {
                    Text(
                        "Tap ✕ to remove",
                        style = Type.micro,
                        color = Palette.dim,
                    )
                }
            }

            if (blockedApps.isNotEmpty()) {
                Gap(Space.s)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    blockedApps.forEach { pkg ->
                        Row(
                            modifier = Modifier
                                .background(Palette.raised)
                                .border(Dsn.Border, Palette.line)
                                .padding(start = Space.s, end = Space.xs, top = Space.xs, bottom = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.s),
                        ) {
                            AppIcon(pkg, Modifier.size(18.dp).clip(CircleShape))
                            Text(
                                appLabel(context, pkg),
                                style = Type.meta,
                                fontWeight = FontWeight.Bold,
                                color = Palette.text,
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Palette.line)
                                    .clickable {
                                        model.deleteRule(profileId, Target.AppPackage(pkg))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", style = Type.micro, color = Palette.muted)
                            }
                        }
                    }
                }
            }

            Gap(Space.m)
            GhostButton("📱 Choose Apps to Block") {
                if (name.isBlank()) {
                    model.say("Give it a name first.")
                } else {
                    model.saveProfile(profileId, name.trim())
                    picking = "apps"
                }
            }

            Gap(Space.l)
            Rule()
            Gap(Space.l)

            // Blocked Websites Section (Managed directly inline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Blocked Websites ($siteCount)",
                    style = Type.body,
                    fontWeight = FontWeight.Bold,
                    color = Palette.text,
                )
                if (siteCount > 0) {
                    Text(
                        "Tap ✕ to remove",
                        style = Type.micro,
                        color = Palette.dim,
                    )
                }
            }

            if (blockedRules.isNotEmpty()) {
                Gap(Space.s)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    blockedRules.forEach { rule ->
                        Row(
                            modifier = Modifier
                                .background(Palette.raised)
                                .border(Dsn.Border, Palette.line)
                                .padding(start = Space.s, end = Space.xs, top = Space.xs, bottom = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.s),
                        ) {
                            Text("🌐", style = Type.micro)
                            Text(
                                rule.target.label(),
                                style = Type.meta,
                                fontWeight = FontWeight.Bold,
                                color = Palette.text,
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Palette.line)
                                    .clickable {
                                        model.deleteRule(profileId, rule.target)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", style = Type.micro, color = Palette.muted)
                            }
                        }
                    }
                }
            }

            Gap(Space.m)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(Dsn.CtlRadius))
                        .background(Palette.raised)
                        .border(Dsn.Border, Palette.line)
                        .padding(horizontal = Space.m),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (newWebDomain.isEmpty()) {
                        Text(
                            "Add domain (e.g. reddit.com)",
                            style = Type.meta,
                            color = Palette.dim,
                        )
                    }
                    BasicTextField(
                        value = newWebDomain,
                        onValueChange = { newWebDomain = it },
                        singleLine = true,
                        textStyle = Type.meta.copy(color = Palette.text),
                        cursorBrush = SolidColor(Palette.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(Dsn.CtlRadius))
                        .background(Palette.raised)
                        .border(Dsn.Border, Palette.line)
                        .clickable {
                            val domain = newWebDomain.trim().lowercase()
                                .removePrefix("https://")
                                .removePrefix("http://")
                                .trimEnd('/')
                            if (domain.isNotBlank()) {
                                if (name.isBlank()) {
                                    model.say("Give it a name first.")
                                } else {
                                    model.saveProfile(profileId, name.trim())
                                    model.saveRule(profileId, Rule(target = Target.Domain(domain), action = Action.Block))
                                    newWebDomain = ""
                                }
                            }
                        }
                        .padding(horizontal = Space.l),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Add",
                        style = Type.meta,
                        fontWeight = FontWeight.Bold,
                        color = Palette.accent,
                    )
                }
            }
        }

        // ==========================================
        // SECTION: WHEN IT RUNS (Togglable Automation)
        // ==========================================
        Gap(Space.xl)
        Title("When should it run?", style = Type.h2)
        Gap(Space.s)
        Sub("Toggle the ways you want this profile to activate. Profiles can also be started manually at any time.")
        Gap(Space.m)

        // 1. Manual / On Demand Card
        DCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        
                        .background(Palette.ok.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⏱️", style = Type.h3, fontWeight = FontWeight.Normal)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "On Demand Timer",
                        style = Type.body,
                        fontWeight = FontWeight.Bold,
                        color = Palette.text,
                    )
                    Text(
                        "Available on Now tab and quick settings",
                        style = Type.meta,
                        color = Palette.muted,
                        modifier = Modifier.padding(top = Space.xxs),
                    )
                }
                Pill("Ready ✓", selected = false, tint = Palette.ok)
            }
        }

        // 2. Weekly Schedule Card (TOGGLABLE)
        Gap(Space.m)
        DCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        
                        .background(Palette.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔁", style = Type.h3, fontWeight = FontWeight.Normal)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Weekly Schedule",
                        style = Type.body,
                        fontWeight = FontWeight.Bold,
                        color = Palette.text,
                    )
                    Text(
                        if (windows.isNotEmpty()) {
                            "${windows.size} active window${if (windows.size == 1) "" else "s"}"
                        } else {
                            "Off · Set specific hours and days"
                        },
                        style = Type.meta,
                        color = if (windows.isNotEmpty()) Palette.ok else Palette.muted,
                        modifier = Modifier.padding(top = Space.xxs),
                    )
                }
                Switch(
                    on = windows.isNotEmpty(),
                    onChange = { turnOn ->
                        if (name.isBlank()) {
                            model.say("Give it a name first.")
                        } else if (turnOn) {
                            model.saveProfileWithWindow(
                                profileId,
                                name.trim(),
                                WeeklySchedule(
                                    id = "w-${state.now}",
                                    profile = profileId,
                                    days = listOf(0, 1, 2, 3, 4),
                                    startMinute = 21 * 60,
                                    endMinute = 0,
                                    locks = listOf(Lock.Confirm),
                                ),
                            )
                        } else {
                            windows.forEach { model.deleteWeekly(it.id) }
                        }
                    },
                )
            }

            // Expanded windows list
            if (windows.isNotEmpty()) {
                windows.forEach { window ->
                    Gap(Space.m)
                    Rule()
                    Gap(Space.m)
                    WindowContent(
                        window = window,
                        onChange = { model.saveWeekly(it) },
                        onRemove = { model.deleteWeekly(window.id) },
                    )
                }
                Gap(Space.m)
                GhostButton("+ Add another window") {
                    if (name.isBlank()) {
                        model.say("Give it a name first.")
                    } else {
                        model.saveWeekly(
                            WeeklySchedule(
                                id = "w-${state.now + windows.size}",
                                profile = profileId,
                                days = listOf(0, 1, 2, 3, 4),
                                startMinute = 21 * 60,
                                endMinute = 0,
                                locks = listOf(Lock.Confirm),
                            ),
                        )
                    }
                }
            }
        }

        // 3. Calendar Events Card (TOGGLABLE)
        val calendarExample = when {
            mine.isEmpty() -> "Events whose title contains \u2018lecture\u2019."
            mine.size == 1 -> {
                val title = mine.first().matcher.title
                if (title.isNullOrBlank()) "One event starts it." else "\u201C$title\u201D starts it."
            }
            else -> "${mine.size} events start it."
        }
        Gap(Space.m)
        DCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        
                        .background(Palette.ok.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📅", style = Type.h3, fontWeight = FontWeight.Normal)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Calendar Events",
                        style = Type.body,
                        fontWeight = FontWeight.Bold,
                        color = Palette.text,
                    )
                    Text(
                        if (fromCalendar) calendarExample else "Off · Does not sync with calendar",
                        style = Type.meta,
                        color = if (fromCalendar) Palette.ok else Palette.muted,
                        modifier = Modifier.padding(top = Space.xxs),
                    )
                }
                Switch(
                    on = fromCalendar,
                    onChange = { turnOn ->
                        if (name.isBlank()) {
                            model.say("Give it a name first.")
                        } else if (turnOn) {
                            model.saveProfile(profileId, name.trim())
                            picking = "calendar"
                        } else {
                            mine.forEach { model.deleteCalendarRule(it.id) }
                        }
                    },
                )
            }

            if (fromCalendar) {
                Gap(Space.m)
                Rule()
                Gap(Space.m)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { picking = "calendar" },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Configure calendar events", style = Type.meta, color = Palette.accent)
                    Text("›", style = Type.h3, color = Palette.accent)
                }
            }
        }

        // 4. Daily Limit Card (TOGGLABLE)
        Gap(Space.m)
        DCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        
                        .background(Palette.bad.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⏳", style = Type.h3, fontWeight = FontWeight.Normal)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Daily Screen-Time Limit",
                        style = Type.body,
                        fontWeight = FontWeight.Bold,
                        color = Palette.text,
                    )
                    Text(
                        if (budgeted) {
                            "${spellDuration(budgetSeconds!! / 60)} a day, then apps lock"
                        } else {
                            "Off · Total block when active"
                        },
                        style = Type.meta,
                        color = if (budgeted) Palette.accent else Palette.muted,
                        modifier = Modifier.padding(top = Space.xxs),
                    )
                }
                Switch(
                    on = budgeted,
                    onChange = { turnOn ->
                        if (name.isBlank()) {
                            model.say("Give it a name first.")
                        } else if (blockedApps.isEmpty()) {
                            model.say("Add some apps first.")
                        } else if (turnOn) {
                            blockedApps.forEach { pkg ->
                                model.saveRule(
                                    profileId,
                                    Rule(Target.AppPackage(pkg), Action.Budget(30 * 60)),
                                )
                            }
                        } else {
                            blockedApps.forEach { pkg ->
                                model.saveRule(
                                    profileId,
                                    Rule(Target.AppPackage(pkg), Action.Block),
                                )
                            }
                        }
                    },
                )
            }

            if (budgeted) {
                Gap(Space.m)
                Rule()
                Gap(Space.m)
                Text("Daily allowance before locking:", style = Type.meta, color = Palette.muted)
                Gap(Space.s)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    listOf(15, 30, 60, 120).forEach { mins ->
                        val selected = (budgetSeconds ?: 0) / 60 == mins
                        Pill(
                            text = spellDuration(mins),
                            selected = selected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                blockedApps.forEach { pkg ->
                                    model.saveRule(
                                        profileId,
                                        Rule(Target.AppPackage(pkg), Action.Budget(mins * 60)),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // ==========================================
        // SUMMARY NOTE & ACTIONS
        // ==========================================
        Gap(Space.l)
        Text(
            "Any toggled trigger that turns on activates the profile. While active, all selected apps and websites are blocked.",
            style = Type.meta,
            color = Palette.dim,
        )

        Gap(Space.xl)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            if (existing != null) {
                GhostButton(
                    text = "Delete",
                    modifier = Modifier.weight(1f),
                    colour = Palette.bad,
                ) { confirmingDelete = true }
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

    if (confirmingDelete && existing != null) {
        DConfirm(
            title = "Delete ${existing.name}?",
            sub = "It stops blocking anything, and its apps, sites and windows go with it.",
            body = "A session it has already started keeps running until its own lock lets it go.\n\nIf you only want it off for a while, turn it off from the schedule list instead — that is reversible.",
            dismiss = "Keep it",
            confirm = "Delete",
            destructive = true,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                val target = existing.id
                confirmingDelete = false
                model.deleteProfile(target) { onDone() }
            },
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
            .background(Palette.surface)
            .border(
                1.dp,
                if (value.isBlank()) Palette.line else tint,
                RoundedCornerShape(Dsn.CardRadius),
            )
            .padding(horizontal = Dsn.CardPad),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text("Name it", style = Type.h2, fontWeight = FontWeight.Normal, color = Palette.dim)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.h2.copy(fontWeight = FontWeight.Bold, color = Palette.text),
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
        WindowContent(window, onChange, onRemove)
    }
}

@Composable
private fun WindowContent(
    window: WeeklySchedule,
    onChange: (WeeklySchedule) -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            DAYS.forEachIndexed { index, letter ->
                // Monday is 1 in the config, and Sunday is 7 — the ISO numbering the core uses,
                // kept out of the user's way.
                val day = index
                val on = day in window.days
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(if (on) Palette.accent else Palette.raised)
                        .clickable {
                            val days = if (on) window.days - day else window.days + day
                            onChange(window.copy(days = days.sorted()))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letter,
                        style = Type.meta,
                        fontWeight = FontWeight.Bold,
                        color = if (on) Palette.ground else Palette.muted,
                    )
                }
            }
        }

        Gap(Space.l)
        Rule()
        Gap(Space.l)

        MinuteRow("Starts", window.startMinute) { onChange(window.copy(startMinute = it)) }
        Gap(Space.m)
        MinuteRow("Ends", window.endMinute) { onChange(window.copy(endMinute = it)) }

        Gap(Space.l)
        Rule()
        Gap(Space.l)

        SectionLabel("How hard is it to get out")
        Gap(Space.s)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            strengths().forEach { (label, lock) ->
                Pill(
                    label,
                    selected = window.locks.firstOrNull() == lock,
                    onClick = { onChange(window.copy(locks = listOfNotNull(lock))) },
                )
            }
        }
        val currentLock = window.locks.firstOrNull()
        val note = when (currentLock) {
            null -> "Ends the moment you tap stop."
            is Lock.Confirm -> "One confirmation, so it is never an accident."
            is Lock.DeviceCredential -> "Proves it is you, not a pocket."
            is Lock.Challenge -> when (currentLock.challenge) {
                ChallengeKind.MATH -> "A quick puzzle before it opens."
                ChallengeKind.TYPING -> "A sentence to copy before it opens."
            }
            is Lock.Timer -> "Nothing ends this early. The 24-hour release is the way out."
            else -> null
        }
        if (note != null) {
            Gap(Space.s)
            Text(note, style = Type.meta, color = Palette.muted)
        }

        Gap(Space.l)
        Text(
            "Remove this window",
            style = Type.meta,
            color = Palette.bad,
            modifier = Modifier.clickable(onClick = onRemove),
        )
    }
}

/** The answers to "how hard should this be to escape", in order of how hard they are. */
private fun strengths(): List<Pair<String, Lock?>> = listOf(
    "I can stop it" to null,
    "Ask me first" to Lock.Confirm,
    "Fingerprint" to Lock.DeviceCredential,
    "Solve math" to Lock.Challenge(ChallengeKind.MATH),
    "Type it out" to Lock.Challenge(ChallengeKind.TYPING),
    "Until it ends" to Lock.Timer,
)

@Composable
private fun MinuteRow(label: String, minute: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.body, color = Palette.text, modifier = Modifier.weight(1f))
        // The stepper from the canvas: a raised tray holding minus, the value, and an accented
        // plus. The tray is what makes the two glyphs read as one control rather than two buttons.
        Row(
            modifier = Modifier
                
                .background(Palette.raised)
                .padding(Dsn.Border),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Nudge("−", "fifteen minutes earlier", accent = false) {
                onChange(((minute - 15) + 24 * 60) % (24 * 60))
            }
            Text(
                clock(minute),
                style = Type.body,
                fontWeight = FontWeight.Bold,
                color = Palette.text,
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
 * Zero is spelled "midnight" rather than "00:00" because in this app it is almost always the far end
 * of an evening window rather than the start of one, and a window that ends at or before it starts is
 * the core's own way of saying "and on into tomorrow".
 *
 * The formatting itself lives in [clockMinute] with the rest of the app's time words, and the reason
 * it is not the localised clock is written down there: this text is typed back into a config.
 */
private fun clock(minute: Int): String = clockMinute(minute)

private fun slug(name: String): String =
    name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "profile" }

@Composable
private fun Nudge(glyph: String, description: String, accent: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            
            .background(if (accent) Palette.accent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = Type.h3,
            color = if (accent) Palette.ground else Palette.muted,
        )
    }
}

/**
 * A daily budget, set from inside the profile it belongs to.
 */
@Composable
fun BudgetSheet(model: CurfewViewModel, profileId: String, onDone: () -> Unit) {
    val apps = remember(profileId) { model.blockedApps(profileId) }
    var minutes by remember { mutableStateOf(30) }

    DSheet(
        title = "A daily budget",
        sub = if (apps.isEmpty()) {
            "This profile does not block any app yet, so there is nothing to ration. Add some apps first and the budget will apply to those."
        } else {
            "${apps.size} app${if (apps.size == 1) "" else "s"} in this profile will open until the budget is spent, then close for the rest of the day."
        },
        onDismiss = onDone,
        confirm = "Set it",
        confirmEnabled = apps.isNotEmpty(),
        onConfirm = {
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
    ) {
        Gap(Space.l)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(15, 30, 60, 120).forEach { option ->
                Pill(
                    text = spellDuration(option),
                    selected = minutes == option,
                    onClick = { minutes = option },
                )
            }
        }
    }
}

/** "Nothing yet", or what the profile holds, counted in the two kinds it is kept in. */
private fun blocksLine(apps: Int, sites: Int): String = when {
    apps == 0 && sites == 0 -> "Nothing yet"
    sites == 0 -> "$apps app${if (apps == 1) "" else "s"}"
    apps == 0 -> "$sites site${if (sites == 1) "" else "s"}"
    else -> "$apps app${if (apps == 1) "" else "s"} and $sites site${if (sites == 1) "" else "s"}"
}
