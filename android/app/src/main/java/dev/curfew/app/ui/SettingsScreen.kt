package dev.curfew.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
    // The appearance sheet, opened by the Theme and Accent rows in the list below.
    var showAppearance by remember { mutableStateOf(false) }

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
        }.onFailure { model.fail("That file could not be written.", it) }
    }

    // Two counts, because the card above lists every permission and this line summarises that
    // same list: counting only the required ones said "one permission is missing" under a card
    // showing three red marks, and the reader believed the card.
    val missing = state.grants.count { !it.granted }
    val blocking = state.grants.count { it.grant.required && !it.granted }

    Screen(spacing = 0.dp) {
        Gap(Space.l)
        Title("Settings")
        Gap(Space.s)
        Sub("Everything here is stored on this phone. Nothing is sent anywhere.")
        Gap(Space.l)

        // ---------------------------------------------------------------------------------------
        // Appearance.
        //
        // `docs/design.md` section 5.11 lists a Theme setting and an Accent setting, and until now
        // there was neither: `CurfewTheme` read `isSystemInDarkTheme()` and nothing else, so the
        // paper palette the design document treats as the default was reachable only by changing a
        // system setting. The plumbing existed — `Appearance` stores it and `CurfewTheme` reads it —
        // and there was no control anywhere that wrote it, which is a setting in name only.
        // ---------------------------------------------------------------------------------------
        Gap(Space.l)
        SectionLabel("Appearance")
        Gap(Space.m)
        DCardFlush {
            SettingRow("Theme") {
                ChoiceRow(
                    options = listOf(
                        Appearance.SYSTEM to "System",
                        Appearance.LIGHT to "Paper",
                        Appearance.DARK to "Dusk",
                    ),
                    chosen = Appearance.theme,
                    onPick = { Appearance.setTheme(context, it) },
                )
            }
            Rule()
            SettingRow("Accent") {
                ChoiceRow(
                    options = Accent.entries.map { it.id to it.label },
                    chosen = Appearance.accent,
                    onPick = { Appearance.setAccent(context, it) },
                )
                Gap(Space.s)
                Text(
                    "The accent colours the primary action and the selected tab, and nothing else. " +
                        "Orange still means a block is running, green still means live, red still " +
                        "means refused.",
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
            Rule()
            SettingRow("Text size") {
                Text(
                    "Curfew follows your phone's own display size, so a larger system font makes " +
                        "every screen here larger too. There is no second setting to keep in step.",
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
        }

        Gap(Space.l)
        SectionLabel("Curfew can enforce")
        Gap(Space.m)
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
                        .padding(horizontal = Space.l, vertical = Space.l)
                        .semantics(mergeDescendants = true) { contentDescription = described },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    Mark(entry.granted)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = Type.body, color = Palette.text)
                        if (!entry.granted) {
                            Text(
                                cost,
                                style = Type.meta,
                                color = Palette.muted,
                                modifier = Modifier.padding(top = Space.xxs),
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

        Gap(Space.l)
        SectionLabel("Syncing")
        Gap(Space.m)
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
                style = Type.body,
                // The same correction as DevicesScreen: amber for *not* listening was backwards, and
                // the neutral sentence read as "a block is running". See the note there.
                color = if (state.sync.running) Palette.ok else Palette.muted,
            )
            state.sync.error?.let { problem ->
                Gap(Space.s)
                Text(problem, style = Type.meta, color = Palette.bad)
            }
            if (state.sync.available) {
                Gap(Space.m)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    GhostButton(text = "Sync now", onClick = model::syncNow)
                    GhostButton(text = "Devices") { onOpen(Routes.DEVICES) }
                }
            }
        }

        Gap(Space.l)
        SectionLabel(stringResource(R.string.settings_accessibility_mode))
        Gap(Space.m)
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

            // Said before the buttons, because it is the thing that confuses people: Android's own
            // list shows two Curfew rows and they are one choice at two levels.
            Text(
                stringResource(R.string.settings_accessibility_one_setting),
                style = Type.meta,
                color = Palette.muted,
            )
            Gap(Space.m)

            ModeStatus(detector)
            Gap(Space.l)
            // The only setting here, and it is a switch rather than a choice between two things.
            WebsiteSwitch(
                on = detector != Detector.Poller,
                enabled = detector != Detector.Poller,
            ) { wantsSites ->
                // Remembered *and* acted on: remembering keeps the next screen agreeing with this one
                // while the user is still in Settings, and the settings page is what changes which
                // service the system runs.
                // There is one service now, so turning the switch on is turning precise blocking on.
                // The switch itself stays because the copy around it — what precise blocking adds,
                // and what it costs — is the thing a user needs to read before granting it.
                val mode = EnforcementMode.PRECISE
                EnforcementMode.remember(context, mode)
                Watchers.rememberAccessibilityWanted(context, wanted = true)
                openGrantPage(context, Grant.Accessibility)
            }
            if (detector == Detector.Poller) {
                Gap(Space.s)
                Text(
                    stringResource(R.string.settings_accessibility_needs_turning_on),
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
            Gap(Space.m)
            // The unified switch's "off". One tap, and blocking keeps working through the polling
            // detector — it is only the precision and the site rules that go.
            GhostButton(
                text = stringResource(R.string.settings_use_no_special_access),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Watchers.rememberAccessibilityWanted(context, wanted = false)
                // The running reader can switch itself off, which is what lets a payment app run.
                // When it cannot — not running, or a lock holds it — the user does it in Settings.
                if (Watchers.turnOffReader(context)) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.settings_use_no_special_access_done),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } else {
                    turnOffAccessibilityHint(context)
                }
            }
            Gap(Space.m)
            // Said plainly, because a mode named "App blocking" reads as though the choice were about
            // *which* apps may be blocked. It is not: every version blocks every app the user picks.
            // What changes is how fast a block lands and whether Curfew may read an address bar.
            Text(
                stringResource(R.string.perm_accessibility_mode_neither_blocks_apps),
                style = Type.meta,
                color = Palette.text,
            )

            // Having both services on is the worst of both worlds and worth saying out loud: the
            // banking warning is present because one of them holds the capability, and the other adds
            // nothing that the reader does not already do.
            val other = EnforcementMode.entries.firstOrNull {
                it.serviceClass() != detector.serviceClass() && Grant.isModeEnabled(context, it)
            }
            if (other != null) {
                Gap(Space.m)
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
                Gap(Space.m)
                Notice(
                    text = stringResource(
                        R.string.perm_accessibility_mode_chosen_not_running,
                        chosenName,
                        runningName,
                    ),
                    tone = NoticeTone.Warning,
                )
            }

            Gap(Space.l)
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

        Gap(Space.l)
        // ---------------------------------------------------------------------------------------
        // The emergency ration.
        //
        // This block exists because it did not. `[emergency]` in the plan file holds the only way
        // out of a lock that the user themselves can reach, its default is `passes = 0`, and the
        // core reads zero as "the hatch is off" — so on every install that never hand-edited the
        // file the ration was empty for ever, the button that spends a pass was never drawn (it is
        // only shown when one is available), and the only explanation on screen was a refusal
        // sentence about a config field with no interface. A safety hatch with no handle.
        //
        // The values are written into the plan file, which the app already owns: read, modify,
        // write, reload. See `withEmergencySettings` for why that is a text edit rather than a
        // re-serialisation — in one line, the user's own comments survive.
        // ---------------------------------------------------------------------------------------
        Gap(Space.l)
        SectionLabel("If you get locked out")
        Gap(Space.m)
        val ration = remember(state.configToml) {
            runCatching { dev.curfew.policy.emergencySettings(state.configToml) }
                .getOrDefault(dev.curfew.policy.EmergencySettings(0, 604800, 21600))
        }
        DCardFlush {
            Column(Modifier.fillMaxWidth().padding(Space.l)) {
                // A local, because `passRefusal` is a delegated property riding on a `StateFlow`
                // collection and Kotlin will not smart-cast it at the point of use.
                val why = state.passRefusal
                Text(
                    when {
                        ration.passes == 0 ->
                            "The emergency hatch is off. A lock you cannot end early then has no " +
                                "way out except its own end time and the last-resort release."
                        why == null -> "${state.passesLeft} of ${ration.passes} left this week."
                        else ->
                            "${state.passesLeft} of ${ration.passes} left this week, and " +
                                describePassRefusal(why, state.now)
                                    .replaceFirstChar { it.lowercase() }
                    },
                    style = Type.meta,
                    color = if (ration.passes == 0) Palette.warn else Palette.text,
                )
            }
            Rule()
            Column(Modifier.fillMaxWidth().padding(Space.l)) {
                SectionLabel("Passes a week")
                Gap(Space.s)
                ChoiceRow(
                    options = listOf(
                        "0" to "Off",
                        "1" to "1",
                        "2" to "2",
                        "4" to "4",
                        "7" to "7",
                    ),
                    chosen = ration.passes.toString(),
                    onPick = { picks ->
                        model.setEmergency(ration.copy(passes = picks.toInt()))
                    },
                )
                Gap(Space.s)
                Text(
                    "An emergency pass ends any lock immediately, including the strongest one. " +
                        "Spending one is written into your history and they cannot be given back.",
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
            Rule()
            Column(Modifier.fillMaxWidth().padding(Space.l)) {
                SectionLabel("Gap between uses")
                Gap(Space.s)
                ChoiceRow(
                    options = listOf(
                        "3600" to "1h",
                        "10800" to "3h",
                        "21600" to "6h",
                        "43200" to "12h",
                        "86400" to "24h",
                    ),
                    chosen = ration.cooldownSeconds.toString(),
                    onPick = { picks ->
                        model.setEmergency(ration.copy(cooldownSeconds = picks.toLong()))
                    },
                )
                Gap(Space.s)
                Text(
                    "The core refuses a zero gap when passes are on, because two uses in a row " +
                        "would make the ration a formality rather than a limit.",
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
        }
        SectionLabel("The rest of Curfew")
        Gap(Space.m)
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
            // Theme and Accent as their own rows, beside the other things you can go and change.
            //
            // The Appearance card above has the controls in it, and that is where the choice is
            // made. These rows are here because "Settings" is a list of what this app can be told,
            // and two of the things it can be told — how it looks, and what colour its action is —
            // were the only ones with no row. A section that exists but is not listed is a section
            // people scroll past.
            Entry(
                title = "Theme",
                note = when (Appearance.theme) {
                    Appearance.LIGHT -> "Paper — the default."
                    Appearance.DARK -> "Dusk."
                    else -> "Following your phone."
                },
            ) { showAppearance = true }
            Rule()
            Entry(
                title = "Accent",
                note = Accent.of(Appearance.accent).label + ". Colours the action and the tab you " +
                    "are on, and nothing that means a state.",
            ) { showAppearance = true }
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

        Gap(Space.l)
        DCard(padding = 16.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("⛨", style = Type.h3, fontWeight = FontWeight.Normal, color = Palette.muted)
                Text(
                    // The true claim, and the same one Health shows: this sentence existed twice and
                    // one copy was false. Saying "no internet permission at all" was the earlier
                    // version of the same mistake — the manifest declares INTERNET for LAN sync —
                    // and it is load-bearing, because this is the card a user reads to decide
                    // whether to trust a screen-watching app. See [Privacy].
                    Privacy.NO_SERVER,
                    style = Type.meta,
                    color = Palette.muted,
                )
            }
        }
        Gap(Space.s)
    }

    // The appearance controls, in a sheet for the rows in the list.
    //
    // The same three controls the card above carries, because the rows in the list are a way *in*
    // rather than a second place the setting lives: there is one value, in `Appearance`, and both
    // surfaces write it.
    if (showAppearance) {
        DSheet(
            title = "Appearance",
            sub = "How Curfew looks. Stored on this phone.",
            onDismiss = { showAppearance = false },
            confirm = "Done",
            confirmEnabled = true,
            onConfirm = { showAppearance = false },
        ) {
            SheetSection("Theme") {
                ChoiceRow(
                    options = listOf(
                        Appearance.SYSTEM to "System",
                        Appearance.LIGHT to "Paper",
                        Appearance.DARK to "Dusk",
                    ),
                    chosen = Appearance.theme,
                    onPick = { Appearance.setTheme(context, it) },
                )
            }
            SheetSection("Accent") {
                ChoiceRow(
                    options = Accent.entries.map { it.id to it.label },
                    chosen = Appearance.accent,
                    onPick = { Appearance.setAccent(context, it) },
                )
                Gap(Space.s)
                Text(
                    "The accent colours the primary action and the selected tab, and nothing else. " +
                        "Orange still means a block is running, green still means live, red still " +
                        "means refused.",
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
        }
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
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    PrimaryButton("Export config", Modifier.weight(1f)) {
                        runCatching {
                            exportFile.launch("curfew.toml")
                        }.onFailure { model.fail(context.getString(R.string.file_write_failed), it) }
                    }
                    GhostButton("Import file", Modifier.weight(1f)) {
                        runCatching {
                            importFile.launch(arrayOf("*/*", "text/plain", "text/x-toml", "application/octet-stream"))
                        }.onFailure { model.fail(context.getString(R.string.file_read_failed), it) }
                    }
                }
                Gap(Space.s)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    GhostButton("Copy to clipboard", Modifier.weight(1f)) {
                        if (state.configToml.isNotBlank()) {
                            clipboard.setText(AnnotatedString(state.configToml))
                            model.note("Config copied to clipboard.")
                        }
                    }
                }
                Gap(Space.m)
                SheetNote(
                    "Importing validates the config before applying. A session already running keeps running until its own lock expires.",
                )
            }
            if (state.configToml.isNotBlank()) {
                SheetSection("Current configuration") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.raised, RoundedCornerShape(Dsn.CtlRadius))
                            .padding(Space.m),
                    ) {
                        Text(
                            state.configToml,
                            style = Type.micro.mono,
                            color = Palette.text,
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
        style = Type.body,
        fontWeight = FontWeight.Bold,
        color = if (granted) Palette.ok else Palette.bad,
        modifier = Modifier.size(18.dp),
    )
}

/** What is detecting right now, and what that costs. */
@Composable
private fun ModeStatus(detector: Detector) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_mode_running_label),
            style = Type.micro,
            fontWeight = FontWeight.Bold,
            color = Palette.dim,
        )
        Gap(Space.xxs)
        Text(
            stringResource(detector.label()),
            style = Type.h3,
            // The polling detector is not a failure and is not coloured as one: it is a real detector
            // the user may have chosen on purpose, because it needs no alarming permission.
            color = Palette.text,
        )
        Gap(Space.xxs)
        Text(
            stringResource(detector.note()),
            style = Type.meta,
            color = Palette.muted,
        )
    }
}

/**
 * The one extra that precise blocking can be given, as a switch rather than half of a pair.
 *
 * This used to be a segmented control with two equal halves, and that is what made it read as two
 * settings. They are not peers: app blocking is the feature, and reading a browser's address bar is
 * an addition to it. So the addition is drawn as what it is — a labelled switch — and there is
 * nothing to reconcile: turn precise blocking on and every app you pick is blocked; turn this on as
 * well and sites are blocked too.
 *
 * The label, the state word, and the switch's own fill all carry the answer, because section 8
 * requires status by word and shape and not by colour alone.
 */
@Composable
private fun WebsiteSwitch(on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.settings_accessibility_website_rules)
    val note = stringResource(R.string.settings_accessibility_website_rules_note)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dsn.MinTouch)
            .clickable(enabled = enabled) { onToggle(!on) }
            .semantics(mergeDescendants = true) {
                contentDescription = label + if (on) ", on" else ", off"
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label.uppercase(),
                style = Type.button,
                color = if (enabled) Palette.text else Palette.dim,
            )
            Text(
                note,
                style = Type.meta,
                color = if (enabled) Palette.muted else Palette.dim,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
        Box(Modifier.padding(top = Space.xxs)) {
            Switch(on = on, onChange = { if (enabled) onToggle(it) })
        }
    }
}

/** A sentence worth interrupting the page for. Distinct from body text by shape, not only by colour. */
@Composable
private fun Notice(text: String, tone: NoticeTone) {
    val edge = if (tone == NoticeTone.Warning) Palette.live else Palette.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            
            .background(edge.copy(alpha = 0.10f)),
    ) {
        // A bar rather than a tinted card, so the kind is legible without colour: the shape says
        // "notice" and the colour only says which sort.
        Box(Modifier.width(3.dp).height(Dsn.MinTouch).background(edge))
        Text(
            text,
            style = Type.meta,
            color = Palette.text,
            modifier = Modifier.padding(horizontal = Space.m, vertical = Space.m),
        )
    }
}

private enum class NoticeTone { Warning, Info }

/** The resource naming this mode, in one place so a call site cannot pick the wrong pairing. */
private fun EnforcementMode.label(): Int = when (this) {
    EnforcementMode.PRECISE -> R.string.perm_accessibility_mode_app_and_url
}

/** The resource naming a detector, paired with [Detector.note]. */
private fun Detector.label(): Int = when (this) {
    Detector.Reader -> R.string.perm_accessibility_mode_app_and_url
    Detector.Poller -> R.string.settings_detector_poller
}

/** The resource saying what a detector costs. */
private fun Detector.note(): Int = when (this) {
    Detector.Reader -> R.string.perm_accessibility_mode_app_and_url_note
    Detector.Poller -> R.string.settings_detector_poller_note
}

/** The mode a detector corresponds to, for comparing a choice against what is running. */
private fun Detector.mode(): EnforcementMode? = when (this) {
    Detector.Reader -> EnforcementMode.PRECISE
    Detector.Poller -> null
}

/**
 * Say where the switch is, when the reader could not flip it itself.
 *
 * `disableSelf` works only from the running service, and is refused while a lock runs. Otherwise
 * there is no intent that turns an accessibility service *off* — `ACTION_ACCESSIBILITY_SETTINGS`
 * opens the list — so the honest answer is to say the path and open the list.
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
            // The old 32dp chip was smaller than a thumb; MinTouch is the size every other target uses.
            .heightIn(min = Dsn.MinTouch)
            .plate(fill = Palette.accent, shape = Capsule)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Text("FIX", style = Type.button, color = Palette.surface)
    }
}

@Composable
private fun Entry(title: String, note: String, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, fontWeight = FontWeight.Bold, color = Palette.text)
            Text(
                note,
                style = Type.meta,
                // A missing permission is coloured, and nothing else on this screen is. It is the
                // only line here that means something is currently not happening.
                color = if (warn) Palette.bad else Palette.muted,
            )
        }
        Text("›", style = Type.h2, fontWeight = FontWeight.Normal, color = Palette.dim)
    }
}

/**
 * One labelled row of the appearance card: a name, then whatever sets it.
 *
 * Not [DRow], which puts its note *under* a title; an appearance row is a heading and a control.
 */
@Composable
private fun SettingRow(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.l)) {
        SectionLabel(label)
        Gap(Space.s)
        content()
    }
}
