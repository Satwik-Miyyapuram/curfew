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
 * Appearance, the switch that makes blocking precise, the emergency passes, and links to every
 * other screen. Whether Curfew can enforce anything is answered on the Health screen, which is one
 * row away, so this screen does not repeat its permission list.
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
        }.onFailure { model.fail("That file could not be written.", it) }
    }

    // Two counts, because the Health screen lists every permission and this line summarises that
    // same list: counting only the required ones said "one permission is missing" under a card
    // showing three red marks, and the reader believed the card.
    val missing = state.grants.count { !it.granted }
    val blocking = state.grants.count { it.grant.required && !it.granted }

    Screen(spacing = 0.dp) {
        Gap(Space.l)
        Title("Settings")

        // ---------------------------------------------------------------------------------------
        // Appearance. `CurfewTheme` reads `Appearance`, and these controls are the only writers.
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
                    stringResource(R.string.settings_accent_note),
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
        }

        Gap(Space.l)
        SectionLabel(stringResource(R.string.settings_accessibility_mode))
        Gap(Space.m)
        DCard {
            // The switch is the only blocking control here. Which service is running, and the
            // warnings about it, are on the Health screen under Advanced.
            val detector = Grant.detector(context)
            WebsiteSwitch(
                on = detector != Detector.Poller,
                // Always tappable: it only opens the system page, and off is exactly when it is needed.
                enabled = true,
            ) { _ ->
                // Turning the switch on turns precise blocking on. The settings page is what changes
                // which accessibility service the system runs, so that is where the user goes next.
                EnforcementMode.remember(context, EnforcementMode.PRECISE)
                Watchers.rememberAccessibilityWanted(context, wanted = true)
                openGrantPage(context, Grant.Accessibility)
            }
            Gap(Space.s)
            Text(
                stringResource(detector.label()),
                style = Type.meta,
                color = Palette.muted,
            )
        }

        Gap(Space.l)
        SectionLabel("If you get locked out")
        Gap(Space.m)
        // The emergency ration. It is written into the plan file, which the app already owns: read,
        // modify, write, reload. See `withEmergencySettings`.
        val ration = remember(state.configToml) {
            runCatching { dev.curfew.policy.emergencySettings(state.configToml) }
                .getOrDefault(dev.curfew.policy.EmergencySettings(0, 604800, 21600))
        }
        DCardFlush {
            Column(Modifier.fillMaxWidth().padding(Dsn.CardPad)) {
                // A local, because `passRefusal` is a delegated property riding on a `StateFlow`
                // collection and Kotlin will not smart-cast it at the point of use.
                val why = state.passRefusal
                if (ration.passes == 0) {
                    Pill(stringResource(R.string.settings_hatch_off), tint = Palette.warn)
                    Gap(Space.s)
                }
                Text(
                    when {
                        ration.passes == 0 ->
                            "A lock runs to its end time."
                        why == null -> "${state.passesLeft} of ${ration.passes} left this week."
                        else ->
                            "${state.passesLeft} of ${ration.passes} left this week, and " +
                                describePassRefusal(why, state.now)
                                    .replaceFirstChar { it.lowercase() }
                    },
                    style = Type.meta,
                    color = Palette.text,
                )
            }
            Rule()
            Column(Modifier.fillMaxWidth().padding(Dsn.CardPad)) {
                SectionLabel("Emergency passes per week")
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
                    "Ends any lock at once. Logged, and not given back.",
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
            Rule()
            Column(Modifier.fillMaxWidth().padding(Dsn.CardPad)) {
                SectionLabel("Wait between passes")
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
            }
        }

        Gap(Space.l)
        SectionLabel("The rest of Curfew")
        Gap(Space.m)
        DCardFlush {
            Entry(
                title = "Is Curfew working",
                note = when {
                    missing == 0 -> "All set."
                    // Everything still missing is one Curfew cannot work without.
                    missing == blocking -> "$missing missing. Nothing is blocked."
                    // Some of what is missing only makes Curfew harder to escape, not able to run.
                    blocking > 0 -> "$missing missing, $blocking needed to block."
                    else -> "$missing missing. Blocking still works."
                },
                // Red only when nothing is blocked; a gap that just makes Curfew easier to escape
                // is a warning.
                tone = when {
                    missing == 0 -> null
                    blocking > 0 -> Palette.bad
                    else -> Palette.warn
                },
            ) { onOpen(Routes.HEALTH) }
            Rule()
            Entry(
                title = "Calendar",
                note = if (state.calendarGranted) {
                    "${state.calendarEvents.size} events, next 8 weeks."
                } else {
                    "Not connected."
                },
                tone = if (state.calendarGranted) null else Palette.warn,
            ) { onOpen(Routes.CALENDAR) }
            Rule()
            Entry(
                title = "Where your time went",
                note = "Time and opens per app.",
            ) { onOpen(Routes.USAGE) }
            Rule()
            Entry(
                title = "Devices",
                note = if (state.sync.active.isEmpty()) {
                    "No other device paired yet."
                } else {
                    "${state.sync.active.size} paired. Blocks follow you between them."
                },
            ) { onOpen(Routes.DEVICES) }
            Rule()
            // Kept here rather than on Health: Health takes no navigation callback, and this row
            // needs one.
            Entry(
                title = stringResource(R.string.settings_sensitive_entry),
                note = stringResource(R.string.settings_sensitive_entry_note),
            ) { onOpen(Routes.SENSITIVE_APPS) }
            Rule()
            Entry(
                title = "Config file & backup",
                note = "Export or import curfew.toml.",
            ) { showConfig = true }
        }

        Gap(Space.l)
        DCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("⛨", style = Type.h3, fontWeight = FontWeight.Normal, color = Palette.muted)
                Text(
                    // The shared sentence, the same one Health shows. See [Privacy].
                    Privacy.NO_SERVER,
                    style = Type.meta,
                    color = Palette.muted,
                )
            }
        }
        Gap(Space.s)
    }

    if (showConfig) {
        DSheet(
            title = "curfew.toml",
            sub = "Back up or move your configuration.",
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
                SheetNote("Import checks the file first. A running lock keeps its end time.")
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

/**
 * Held or not held, as a shape and a colour rather than as the word "granted". Red only for a grant
 * blocking cannot work without; a missing optional one is a warning, marked with an amber square
 * because amber text on white does not read.
 */
@Composable
internal fun GrantMark(granted: Boolean, required: Boolean) {
    if (!granted && !required) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(Palette.warn))
        }
        return
    }
    Text(
        if (granted) "✓" else "!",
        style = Type.body,
        fontWeight = FontWeight.Bold,
        color = if (granted) Palette.ok else Palette.bad,
        modifier = Modifier.size(18.dp),
    )
}

/** What is detecting right now, and what that costs. Shared with the Health screen. */
@Composable
internal fun ModeStatus(detector: Detector) {
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
 * App blocking is the feature, and reading a browser's address bar is an addition to it. So the
 * addition is drawn as what it is, a labelled switch. The label, the state word, and the switch's
 * own fill all carry the answer, because section 8 requires status by word and shape and not by
 * colour alone.
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

/** A sentence worth interrupting the page for. Distinct from body text by shape, not only by colour. Shared with the Health screen. */
@Composable
internal fun Notice(text: String, tone: NoticeTone) {
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

internal enum class NoticeTone { Warning, Info }

/** The resource naming this mode, in one place so a call site cannot pick the wrong pairing. */
internal fun EnforcementMode.label(): Int = when (this) {
    EnforcementMode.PRECISE -> R.string.perm_accessibility_mode_app_and_url
}

/** The resource naming a detector, paired with [Detector.note]. */
internal fun Detector.label(): Int = when (this) {
    Detector.Reader -> R.string.perm_accessibility_mode_app_and_url
    Detector.Poller -> R.string.settings_detector_poller
}

/** The resource saying what a detector costs. */
internal fun Detector.note(): Int = when (this) {
    Detector.Reader -> R.string.perm_accessibility_mode_app_and_url_note
    Detector.Poller -> R.string.settings_detector_poller_note
}

/** The mode a detector corresponds to, for comparing a choice against what is running. */
internal fun Detector.mode(): EnforcementMode? = when (this) {
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
internal fun turnOffAccessibilityHint(context: android.content.Context) {
    android.widget.Toast.makeText(
        context,
        context.getString(R.string.settings_use_no_special_access_how),
        android.widget.Toast.LENGTH_LONG,
    ).show()
    openGrantPage(context, Grant.Accessibility)
}

@Composable
private fun Entry(title: String, note: String, tone: Color? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, fontWeight = FontWeight.Bold, color = Palette.text)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                // A problem is marked with a solid square of its colour, and the words stay ink:
                // amber text on white does not read.
                if (tone != null) {
                    Box(Modifier.padding(top = Space.xs).size(Space.s).background(tone))
                }
                Text(
                    note,
                    style = Type.meta,
                    color = if (tone != null) Palette.text else Palette.muted,
                )
            }
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
