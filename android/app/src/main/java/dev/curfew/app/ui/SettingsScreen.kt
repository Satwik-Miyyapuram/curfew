package dev.curfew.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.curfew.app.R
import dev.curfew.app.enforce.Detector
import dev.curfew.app.enforce.EnforcementMode
import dev.curfew.app.enforce.Watchers
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The one screen that is about the app rather than about the phone.
 *
 * It leads with the only question this screen really has to answer: **can Curfew actually enforce
 * anything right now**. That is a list of what it can do with a tick beside it, and a Fix button
 * beside anything it cannot — not a wall of permission names, and not something filed under an
 * advanced heading, because a permission Curfew is missing is a block that is not going to happen.
 *
 * Below that are the screens the tab bar has no room for. There is no beginner/expert switch: an
 * app that hides half of itself behind a mode makes the reader wonder what else it is hiding, and
 * every screen here is one tap away regardless.
 */
@Composable
fun SettingsScreen(model: CurfewViewModel, onOpen: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showConfig by remember { mutableStateOf(false) }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            if (text == null) model.say("That file could not be read.") else model.importConfig(text)
        }
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (state.configError != null) {
            model.say("Your config could not be read, so there is nothing to export. Nothing was written.")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(state.configToml.toByteArray())
            }
        }.onSuccess {
            model.note("Config exported to file.")
        }.onFailure { model.say("That file could not be written.") }
    }

    // Two counts, because the card above lists every permission and this line summarises that
    // same list: counting only the required ones said "one permission is missing" under a card
    // showing three red marks, and the reader believed the card.
    val missing = state.grants.count { !it.granted }
    val blocking = state.grants.count { it.grant.required && !it.granted }

    Screen(spacing = 0.dp) {
        Gap(14.dp)
        Title("Settings", size = 24)
        Gap(16.dp)

        Gap(18.dp)
        SectionLabel("Curfew can enforce")
        Gap(10.dp)
        DCardFlush {
            state.grants.forEachIndexed { index, entry ->
                if (index > 0) Rule()
                // Resolved outside the semantics block, which is not a composable scope, and
                // composed from resources with numbered arguments rather than concatenated — the
                // same reason as on the health screen: a sentence built with `+` is English only.
                val name = stringResource(entry.grant.title)
                val cost = stringResource(entry.grant.cost)
                val described = if (entry.granted) {
                    context.getString(R.string.settings_grant_allowed, name)
                } else {
                    context.getString(R.string.settings_grant_refused, name, cost)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 17.dp, vertical = 15.dp)
                        .semantics(mergeDescendants = true) { contentDescription = described },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Mark(entry.granted)
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 15.sp, color = Palette.Text)
                        if (!entry.granted) {
                            Text(
                                cost,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = Palette.Muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (!entry.granted) {
                        // The button goes straight to the system page for this one permission.
                        // Sending the user to a Health screen to press a second button was the
                        // longest way round to the only thing they came here to do.
                        Fix {
                            // Was a bare `startActivity`, which throws on a device whose OEM build
                            // lacks the page — see `openGrantPage`. F-33.
                            openGrantPage(context, entry.grant)
                        }
                    }
                }
            }
        }

        Gap(18.dp)
        SectionLabel("Syncing")
        Gap(10.dp)
        DCard(padding = 18.dp) {
            // Syncing used to be visible only on the Devices screen, one tap further in and reachable
            // only once a device was paired: a user whose blocks were following them between two
            // devices had no way of seeing that this was happening, or of making it happen now.
            //
            // This comment used to say "which Simple mode never reached". **There is no Simple mode.**
            // It was designed in `docs/PLAN-mobile-polish.md` §4 and never built — the de-cluttering it
            // was meant to achieve was done by shortening the nav bar for everyone instead. The reason
            // above is unchanged and still true; only the mechanism named was fiction, which is the
            // fifth time this codebase has had a comment describing something the code does not do.
            //
            // There is no "last synced" clock to show — devices talk when they are in earshot of each
            // other, not on a schedule — so it says what is actually true at this moment instead.
            Text(
                when {
                    !state.sync.available -> "Syncing is not set up on this device."
                    state.sync.active.isEmpty() -> "No other device paired yet."
                    !state.sync.running -> "Paired, but not listening right now."
                    state.sync.nearby.isEmpty() ->
                        "${state.sync.active.size} device(s) paired. None in earshot right now."
                    else ->
                        "${state.sync.nearby.size} of your ${state.sync.active.size} device(s) in " +
                            "earshot. Blocks follow you between them."
                },
                fontSize = 14.sp,
                lineHeight = 21.sp,
                // The same correction as DevicesScreen: amber for *not* listening was backwards, and
                // the neutral sentence read as "a block is running". See the note there.
                color = if (state.sync.running) Palette.Ok else Palette.Muted,
            )
            state.sync.error?.let { problem ->
                Gap(6.dp)
                Text(problem, fontSize = 13.sp, lineHeight = 20.sp, color = Palette.Bad)
            }
            if (state.sync.available) {
                Gap(12.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(text = "Sync now", onClick = model::syncNow)
                    GhostButton(text = "Devices") { onOpen(Routes.DEVICES) }
                }
            }
        }

        Gap(18.dp)
        SectionLabel(stringResource(R.string.settings_accessibility_mode))
        Gap(10.dp)
        DCard(padding = 18.dp) {
            // The choice the accessibility grant hides inside it, given its own section because it is
            // the one permission where the *kind* of access matters more than whether it is held.
            //
            // **What is running and what is chosen are different things, and this used to conflate
            // them.** `chosenMode` is a remembered preference; `enforcementMode` is read from the
            // system's own list of enabled services. A user who picked a mode and has not granted it
            // yet — or who granted a different one in Settings — saw the preference reported as though
            // it were the fact, which is the app lying about the user's own device. So the status
            // shown is always what is *running*, the buttons are what is *chosen*, and a disagreement
            // is stated rather than smoothed over.
            // **What is detecting right now, which is the question this section exists to answer.**
            // Three answers, not two: one of the two accessibility services, or the usage log, which
            // needs no special access at all. The third is what makes "app blocking without
            // accessibility" a real mode rather than something the architecture document claimed and
            // the interface denied.
            val detector = Grant.detector(context)
            val chosen = Grant.chosenMode(context)

            ModeStatus(detector)
            Gap(14.dp)
            ModePicker(chosen) { mode ->
                // Remembered *and* acted on: remembering keeps the next screen agreeing with this one
                // while the user is still in Settings, and the settings intent is what changes which
                // service the system runs. The poller needs neither — it is what happens when this is
                // turned off, which is why turning it off is a legitimate answer rather than a failure.
                EnforcementMode.remember(context, mode)
                Watchers.rememberAccessibilityWanted(context, wanted = true)
                openGrantPage(context, Grant.Accessibility)
            }
            Gap(10.dp)
            // The unified switch's "off". One tap, and blocking keeps working through the polling
            // detector — it is only the precision, the site rules and the uninstall guard that go.
            GhostButton(
                text = stringResource(R.string.settings_use_no_special_access),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Watchers.rememberAccessibilityWanted(context, wanted = false)
                // Nothing to open: the poller has no settings page. Turning the services *off* is what
                // this asks for, and Android has no intent for that, so the user does it in Settings —
                // which the copy says.
                turnOffAccessibilityHint(context)
            }
            Gap(12.dp)
            // Said plainly, because a mode named "App blocking" reads as though the choice were about
            // *which* apps may be blocked. It is not: every version blocks every app the user picks.
            // What changes is how fast a block lands and whether Curfew may read an address bar.
            Text(
                stringResource(R.string.perm_accessibility_mode_neither_blocks_apps),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Palette.Text,
            )

            // Having both services on is the worst of both worlds and worth saying out loud: the
            // banking warning is present because one of them holds the capability, and the other adds
            // nothing that the reader does not already do.
            val other = EnforcementMode.entries.firstOrNull {
                it.serviceClass() != detector.serviceClass() && Grant.isModeEnabled(context, it)
            }
            if (other != null) {
                Gap(12.dp)
                Notice(
                    text = stringResource(
                        R.string.perm_accessibility_mode_other_still_on,
                        stringResource(other.label()),
                    ),
                    tone = NoticeTone.Warning,
                )
            }

            // Chosen but not what is detecting: said out loud rather than left as a button that looks
            // selected over a device running something else. The poller makes this the ordinary case
            // rather than an error, so it is a note and not a complaint.
            if (detector != Detector.Poller && chosen != detector.mode()) {
                // Resolved into names first. Two nested `stringResource` calls inside this one read as
                // a single argument to the guard that checks format arity across the app, and it was
                // right to complain: the shallow reading is the one a human makes too.
                val chosenName = stringResource(chosen.label())
                val runningName = stringResource(detector.label())
                Gap(12.dp)
                Notice(
                    text = stringResource(
                        R.string.perm_accessibility_mode_chosen_not_running,
                        chosenName,
                        runningName,
                    ),
                    tone = NoticeTone.Warning,
                )
            }

            Gap(14.dp)
            Rule()
            // The list itself, one tap away rather than summarised here: which apps are exempt is a
            // long list, and the only two reasons anyone comes to change it are a regional bank that
            // is missing and an app wrongly caught by the label signal.
            Entry(
                title = stringResource(R.string.settings_sensitive_entry),
                note = stringResource(R.string.settings_sensitive_entry_note),
            ) {
                onOpen(Routes.SENSITIVE_APPS)
            }
        }

        Gap(18.dp)
        SectionLabel("The rest of Curfew")
        Gap(10.dp)
        DCardFlush {
            Entry(
                title = "Is Curfew working",
                note = when {
                    missing == 0 -> "Everything it needs, it has."
                    // Everything still missing is one Curfew cannot work without.
                    missing == blocking && missing == 1 ->
                        "One permission is missing. Nothing is blocked without it."
                    missing == blocking ->
                        "$missing permissions are missing. Nothing is blocked without them."
                    // Some of what is missing only makes Curfew harder to escape, not able to run.
                    blocking > 0 ->
                        "$missing permissions are missing" +
                            if (blocking == 1) {
                                ", and one of them has to be allowed before anything is blocked."
                            } else {
                                ", and $blocking of them have to be allowed before anything is " +
                                    "blocked."
                            }
                    missing == 1 -> "One permission is missing. Curfew still blocks without it."
                    else -> "$missing permissions are missing. Curfew still blocks without them."
                },
                warn = missing > 0,
            ) { onOpen(Routes.HEALTH) }
            Rule()
            Entry(
                title = "Your calendar",
                note = if (state.calendarGranted) {
                    "${state.calendarEvents.size} events read, over the next eight weeks."
                } else {
                    "Not connected. Blocks from your calendar will not start."
                },
                warn = !state.calendarGranted,
            ) { onOpen(Routes.CALENDAR) }
            Rule()
            Entry(
                title = "Where your time went",
                note = "Time and opens, per app, for as long as Curfew has been watching.",
            ) { onOpen(Routes.USAGE) }
            Rule()
            Entry(
                title = "Your other devices",
                note = if (state.sync.active.isEmpty()) {
                    "No other device paired yet."
                } else {
                    "${state.sync.active.size} paired. Blocks follow you between them."
                },
            ) { onOpen(Routes.DEVICES) }
            Rule()
            Entry(
                title = "Config file & backup",
                note = "Export or import your curfew.toml configuration file.",
            ) { showConfig = true }
        }

        Gap(18.dp)
        DCard(padding = 16.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("⛨", fontSize = 17.sp, color = Palette.Muted)
                Text(
                    // The true claim, and the same one Health shows: this sentence existed twice and
                    // one copy was false. Saying "no internet permission at all" was the earlier
                    // version of the same mistake — the manifest declares INTERNET for LAN sync —
                    // and it is load-bearing, because this is the card a user reads to decide
                    // whether to trust a screen-watching app. See [Privacy].
                    Privacy.NO_SERVER,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Palette.Muted,
                )
            }
        }
        Gap(8.dp)
    }

    if (showConfig) {
        DSheet(
            title = "curfew.toml",
            sub = "Your complete configuration file. Export it to back it up or copy it to another device.",
            onDismiss = { showConfig = false },
            confirm = "Done",
            confirmEnabled = true,
            onConfirm = { showConfig = false },
        ) {
            SheetSection("Backup & Restore") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Export config", Modifier.weight(1f)) {
                        runCatching {
                            exportFile.launch("curfew.toml")
                        }.onFailure { model.say(context.getString(R.string.file_write_failed)) }
                    }
                    GhostButton("Import file", Modifier.weight(1f)) {
                        runCatching {
                            importFile.launch(arrayOf("*/*", "text/plain", "text/x-toml", "application/octet-stream"))
                        }.onFailure { model.say(context.getString(R.string.file_read_failed)) }
                    }
                }
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Copy to clipboard", Modifier.weight(1f)) {
                        if (state.configToml.isNotBlank()) {
                            clipboard.setText(AnnotatedString(state.configToml))
                            model.note("Config copied to clipboard.")
                        }
                    }
                }
                Gap(10.dp)
                SheetNote(
                    "Importing validates the config before applying. A session already running keeps running until its own lock expires.",
                )
            }
            if (state.configToml.isNotBlank()) {
                SheetSection("Current configuration") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Palette.Raised)
                            .padding(12.dp),
                    ) {
                        Text(
                            state.configToml,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Palette.Text,
                        )
                    }
                }
            }
        }
    }
}

/** Held or not held, as a shape and a colour rather than as the word "granted". */
@Composable
private fun Mark(granted: Boolean) {
    Text(
        if (granted) "✓" else "!",
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = if (granted) Palette.Ok else Palette.Bad,
        modifier = Modifier.size(18.dp),
    )
}

/** What is detecting right now, and what that costs. */
@Composable
private fun ModeStatus(detector: Detector) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_mode_running_label),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.Dim,
        )
        Gap(3.dp)
        Text(
            stringResource(detector.label()),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            // The polling detector is not a failure and is not coloured as one: it is a real detector
            // the user may have chosen on purpose, because it needs no alarming permission.
            color = Palette.Text,
        )
        Gap(2.dp)
        Text(
            stringResource(detector.note()),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = Palette.Muted,
        )
    }
}

/**
 * The two modes, as a segmented control rather than two buttons.
 *
 * Two identical-looking buttons over a line of prose made it impossible to see which one was in force,
 * which is the only question this section exists to answer. One border, a shared baseline, and the
 * chosen half filled — so the selection is a shape rather than something to be read out of a sentence.
 */
@Composable
private fun ModePicker(chosen: EnforcementMode, onPick: (EnforcementMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(Dsn.CtlRadius)),
    ) {
        EnforcementMode.entries.forEachIndexed { index, mode ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(Dsn.MinTouch)
                        .background(Palette.Line),
                )
            }
            val selected = mode == chosen
            // Resolved out here: `semantics` takes a non-composable lambda and `stringResource` is
            // composable, so hoisting it is required rather than tidier.
            val name = stringResource(mode.label())
            val described = if (selected) "$name, selected" else name
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Dsn.MinTouch)
                    .background(if (selected) Palette.Accent.copy(alpha = 0.20f) else Color.Transparent)
                    .clickable { onPick(mode) }
                    // The label *and* the state, so a screen reader says which one is chosen instead of
                    // reading two labels and leaving the listener to work it out.
                    .semantics(mergeDescendants = true) { contentDescription = described },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Palette.Text else Palette.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** A sentence worth interrupting the page for. Distinct from body text by shape, not only by colour. */
@Composable
private fun Notice(text: String, tone: NoticeTone) {
    val edge = if (tone == NoticeTone.Warning) Palette.Live else Palette.Muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(edge.copy(alpha = 0.10f)),
    ) {
        // A bar rather than a tinted card, so the kind is legible without colour: the shape says
        // "notice" and the colour only says which sort.
        Box(Modifier.width(3.dp).height(Dsn.MinTouch).background(edge))
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = Palette.Text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private enum class NoticeTone { Warning, Info }

/** The resource naming this mode, in one place so a call site cannot pick the wrong pairing. */
private fun EnforcementMode.label(): Int = when (this) {
    EnforcementMode.APP_ONLY -> R.string.perm_accessibility_mode_app_only
    EnforcementMode.APP_AND_URL -> R.string.perm_accessibility_mode_app_and_url
}

/** The resource naming a detector, paired with [Detector.note]. */
private fun Detector.label(): Int = when (this) {
    Detector.Tracker -> R.string.perm_accessibility_mode_app_only
    Detector.Reader -> R.string.perm_accessibility_mode_app_and_url
    Detector.Poller -> R.string.settings_detector_poller
}

/** The resource saying what a detector costs. */
private fun Detector.note(): Int = when (this) {
    Detector.Tracker -> R.string.perm_accessibility_mode_app_only_note
    Detector.Reader -> R.string.perm_accessibility_mode_app_and_url_note
    Detector.Poller -> R.string.settings_detector_poller_note
}

/** The mode a detector corresponds to, for comparing a choice against what is running. */
private fun Detector.mode(): EnforcementMode? = when (this) {
    Detector.Tracker -> EnforcementMode.APP_ONLY
    Detector.Reader -> EnforcementMode.APP_AND_URL
    Detector.Poller -> null
}

/**
 * Say where the switch is, since we cannot flip it.
 *
 * There is no intent that turns an accessibility service *off* — `ACTION_ACCESSIBILITY_SETTINGS`
 * opens the list, and the only API that can change a component's enabled state needs a permission
 * this app does not hold and should not ask for. So the honest answer is to say the path and open the
 * list, rather than to leave a toggle that appears to do nothing.
 */
private fun turnOffAccessibilityHint(context: android.content.Context) {
    android.widget.Toast.makeText(
        context,
        context.getString(R.string.settings_use_no_special_access_how),
        android.widget.Toast.LENGTH_LONG,
    ).show()
    openGrantPage(context, Grant.Accessibility)
}

@Composable
private fun Fix(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Fix", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    }
}

@Composable
private fun Entry(title: String, note: String, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text(
                note,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                // A missing permission is coloured, and nothing else on this screen is. It is the
                // only line here that means something is currently not happening.
                color = if (warn) Palette.Bad else Palette.Muted,
            )
        }
        Text("›", fontSize = 20.sp, color = Palette.Dim)
    }
}
