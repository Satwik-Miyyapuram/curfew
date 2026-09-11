package dev.curfew.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.Rule
import dev.curfew.policy.Target
import dev.curfew.policy.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two halves of "what does this profile block", each reachable without a scroll. */
private enum class Pane { Apps, Sites }

/**
 * What a profile switches off: its apps, and its websites.
 *
 * Two things the canvas insists on, and the old screen got wrong.
 *
 * **The lists belong to the profile, not to the app.** The profile chips sit at the very top, and
 * the count under them says what the *other* profiles hold, so it is obvious at a glance that
 * these really are separate sets rather than one list with a filter over it.
 *
 * **Apps and websites are two tabs, not one scroll.** Websites used to start wherever two hundred
 * installed apps ended, which made the shorter and more often edited of the two lists the harder
 * one to reach.
 *
 * Every switch here saves the moment it is touched. The previous version collected ticks and
 * offered a Save button, which meant the screen could show one thing while the config said
 * another, and switching profiles mid-edit needed a dialog to ask about work the user did not know
 * they had. A block that a user can see is on, is on.
 */
@Composable
fun AppPickerScreen(model: CurfewViewModel, pinned: String? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read off the main thread: the launcher query walks every installed package, which on a full
    // phone is long enough to drop frames if it happens while composing.
    val apps by produceState(initialValue = emptyList<InstalledApp>()) {
        value = withContext(Dispatchers.IO) { installedApps(context) }
    }

    var profile by remember { mutableStateOf(pinned) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var pane by remember { mutableStateOf(Pane.Apps) }
    var copying by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.profiles, profile) {
        if (profile == null || state.profiles.none { it.id == profile }) {
            profile = pinned ?: state.profiles.firstOrNull()?.id
        }
    }

    val current = profile
    // Re-read on every config change, so a switch flipped here is reflected without a refresh and
    // an edit made on another screen cannot leave this one lying.
    val blocked = remember(current, state.configToml) {
        current?.let { model.blockedApps(it).toSet() }.orEmpty()
    }
    val sites = remember(current, state.configToml) {
        current?.let { model.rulesBeyondApps(it) }.orEmpty()
    }
    val counts = remember(state.profiles, state.configToml) {
        state.profiles.associate { it.id to model.blockedApps(it.id).size }
    }
    val siteCounts = remember(state.profiles, state.configToml) {
        state.profiles.associate { it.id to model.rulesBeyondApps(it.id).size }
    }

    // Blocked apps first, then the rest, each half alphabetical. What a profile blocks is the
    // answer this screen exists to give, and it should not be somewhere down a list of two hundred.
    val visible = apps
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedWith(compareBy({ it.packageName !in blocked }, { it.label.lowercase() }))

    if (state.profiles.isEmpty() && !state.loading) {
        Screen {
            Title("Nothing to block yet")
            Sub(
                "Blocking belongs to a profile — a named set of apps and sites. Make one on the " +
                    "Plan tab and it will appear here.",
            )
        }
        return
    }

    Screen(spacing = 0.dp) {
        // Opened from inside a profile, the profile is not a question: the row of pills would be
        // asking the user to pick the thing they are already standing in.
        if (pinned == null) {
            SectionLabel("Blocked by this profile")
            Gap(8.dp)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.profiles.forEach { p ->
                    Pill(p.name, selected = current == p.id, onClick = { profile = p.id })
                }
            }
            Gap(7.dp)
            Text(
                othersHold(state.profiles.filter { it.id != current }, counts, siteCounts),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Palette.Dim,
            )
            Gap(16.dp)
        }
        // Copying is the difference between a second profile that is right and a second profile
        // nobody finishes. It adds to what is here rather than replacing it, and it says whose
        // list it is taking so it is never a mystery afterwards.
        val donors = state.profiles.filter {
            it.id != current && ((counts[it.id] ?: 0) > 0 || (siteCounts[it.id] ?: 0) > 0)
        }
        if (current != null && donors.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Copy from", fontSize = 12.sp, color = Palette.Dim)
                donors.forEach { donor ->
                    Pill(donor.name, selected = false, onClick = { copying = donor.id })
                }
            }
            Gap(14.dp)
        }
        copying?.let { donorId ->
            val donor = state.profiles.firstOrNull { it.id == donorId }
            val mine = current
            if (donor == null || mine == null) {
                copying = null
            } else {
                AlertDialog(
                    onDismissRequest = { copying = null },
                    title = { Text("Copy ${donor.name}'s list?") },
                    text = {
                        Text(
                            "${counts[donorId] ?: 0} apps and ${siteCounts[donorId] ?: 0} sites " +
                                "get added here. Nothing already blocked is removed.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            model.copyBlocksFrom(donorId, mine)
                            copying = null
                        }) { Text("Copy them") }
                    },
                    dismissButton = {
                        TextButton(onClick = { copying = null }) { Text("Cancel") }
                    },
                )
            }
        }
        PaneTabs(
            pane = pane,
            apps = blocked.size,
            sites = sites.size,
            onChange = { pane = it },
        )
        Gap(14.dp)

        // A config that could not be read is not a config with nothing in it.
        //
        // Both panes below derive from the config, and a failed read produced empty sets — which this
        // screen drew as every app *allowed* and every site *unblocked*. That is the app telling the
        // user something false about their own plan, on the one screen whose entire job is to answer
        // "what does this profile block?". Nothing here was destructive — the write path re-reads the
        // config from the runtime rather than using this state, so a toggle would have worked or
        // refused — but a screen that says "you block nothing" when it cannot see is worse than one
        // that says it cannot see.
        //
        // Above the pane split rather than inside each half, because both panes read the same file and
        // the honest answer is the same for both. Refusing to draw the rows rather than drawing them
        // disabled: there is no tick state to show, and a column of switches that cannot be trusted is
        // not a thing to put a finger near.
        if (state.configError != null) {
            DCard {
                Text(
                    "Your plan could not be read, so Curfew cannot show what this profile blocks. " +
                        "Nothing has been changed, and this screen will work again once the config " +
                        "can be read.\n\n" + state.configError,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Palette.Bad,
                )
            }
            return@Screen
        }

        if (pane == Pane.Apps) {
            SearchField(query) { query = it }
            Gap(10.dp)
            DCardFlush {
                visible.take(APP_LIMIT).forEachIndexed { index, app ->
                    if (index > 0) Rule()
                    val on = app.packageName in blocked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription =
                                    if (on) "${app.label}, blocked" else "${app.label}, allowed"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        AppIcon(app.packageName, modifier = Modifier.size(36.dp))
                        Text(
                            app.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Palette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(on) { wanted ->
                            val id = current ?: return@Switch
                            val next =
                                if (wanted) blocked + app.packageName else blocked - app.packageName
                            model.setBlockedApps(id, next.toList())
                        }
                    }
                }
                if (visible.isEmpty()) {
                    Text(
                        if (query.isBlank()) "Reading your apps…" else "No app matches “$query”.",
                        fontSize = 13.sp,
                        color = Palette.Muted,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (visible.size > APP_LIMIT) {
                Gap(10.dp)
                Text(
                    "${visible.size - APP_LIMIT} more. Search to narrow the list.",
                    fontSize = 12.sp,
                    color = Palette.Dim,
                )
            }
        } else {
            DCardFlush {
                sites.forEachIndexed { index, rule ->
                    if (index > 0) Rule()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Palette.Raised),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(glyphFor(rule.target), fontSize = 15.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.target.label(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = Palette.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(describeRule(rule), fontSize = 12.sp, color = Palette.Dim)
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    onClickLabel = "Stop blocking ${rule.target.label()}",
                                ) { current?.let { model.deleteRule(it, rule.target) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✕", fontSize = 15.sp, color = Palette.Dim)
                        }
                    }
                }
                if (sites.isEmpty()) {
                    Text(
                        "Nothing yet. A site blocks it and everything under it; a word blocks " +
                            "anything whose address or title contains it.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Palette.Muted,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Gap(12.dp)
            // Styled as an empty field rather than as a button, because what it opens asks for a
            // line of text and this is where that line will end up.
            DCard(padding = 0.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(enabled = current != null) { adding = true }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("+", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                    Text(
                        "Address, pattern or word…",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Palette.Muted,
                    )
                }
            }
        }

        Gap(16.dp)
        Text(
            "Changes apply the next time ${nameOf(state, current)} starts — never to a block " +
                "already running.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Palette.Dim,
        )
    }

    if (adding && current != null) {
        BlockDialog(
            onDismiss = { adding = false },
            onSave = { target ->
                adding = false
                model.saveRule(current, Rule(target = target))
            },
        )
    }
}

/** How many rows the app list draws before it asks the user to search instead. */
private const val APP_LIMIT = 60

/**
 * The Apps / Websites switch.
 *
 * A tray with the selected half filled, rather than Material's segmented buttons: the canvas puts
 * the count inside each half, and the count is the reason the control is worth its height — it
 * says what is in the tab you are not looking at.
 */
@Composable
private fun PaneTabs(pane: Pane, apps: Int, sites: Int, onChange: (Pane) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(Palette.Raised)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PaneTab("Apps", apps, pane == Pane.Apps, Modifier.weight(1f)) { onChange(Pane.Apps) }
        PaneTab("Websites", sites, pane == Pane.Sites, Modifier.weight(1f)) { onChange(Pane.Sites) }
    }
}

@Composable
private fun PaneTab(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Palette.Accent else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) Palette.Ink else Palette.Muted,
        )
        Text(
            count.toString(),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Palette.Ink.copy(alpha = 0.62f) else Palette.Dim,
        )
    }
}

/** The search box, in the app's own shape rather than Material's. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(Palette.Raised)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = Palette.Text),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text("Search your apps", fontSize = 15.sp, color = Palette.Dim)
        }
    }
}

/** The line under the profile chips: what the sets you are *not* looking at hold. */
private fun othersHold(
    others: List<dev.curfew.policy.ProfileName>,
    apps: Map<String, Int>,
    sites: Map<String, Int>,
): String {
    val head = "Each profile keeps its own apps and its own websites."
    val other = others.firstOrNull() ?: return head
    val a = apps[other.id] ?: 0
    val s = sites[other.id] ?: 0
    val more = if (others.size > 1) " and ${others.size - 1} more" else ""
    return "$head ${other.name} blocks ${count(a, "app")} and ${count(s, "site")}$more."
}

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun nameOf(state: UiState, id: String?): String =
    state.profiles.firstOrNull { it.id == id }?.name ?: "this profile"

/** A glyph standing for the kind of thing a rule aims at, since a website has no icon to show. */
private fun glyphFor(target: Target): String = when (target) {
    is Target.Domain -> "🌐"
    is Target.Url -> "*"
    is Target.Keyword -> "⌗"
    else -> "▢"
}

/** What a rule does and where, for one line under its target. */
private fun describeRule(rule: Rule): String {
    val where = if (rule.platforms.isEmpty()) {
        "everywhere"
    } else {
        rule.platforms.joinToString(" and ") { it.name.lowercase() }
    }
    return "${rule.action.label()}, $where"
}

/** The kinds of thing a phone form can block, and the target each one writes. */
private val BLOCK_KINDS: List<Pair<String, (String) -> Target>> = listOf(
    "Site" to { value -> Target.Domain(domain = value) },
    "Address" to { value -> Target.Url(pattern = value) },
    "Word" to { value -> Target.Keyword(text = value) },
    "Window title" to { value -> Target.WindowTitle(pattern = value) },
)

/**
 * Ask for one thing to block.
 *
 * Only Block is offered. A budget or a delay needs a second number and a refill window, and a
 * half-built form for those would write settings the user could not then see or correct here — so
 * they stay in the config editor until they have a screen of their own.
 */
@Composable
private fun BlockDialog(onDismiss: () -> Unit, onSave: (Target) -> Unit) {
    var kind by remember { mutableStateOf(BLOCK_KINDS.first()) }
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block a site or word") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BLOCK_KINDS.forEach { option ->
                        FilterChip(
                            selected = kind.first == option.first,
                            onClick = { kind = option },
                            label = { Text(option.first) },
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = {
                        Text(
                            when (kind.first) {
                                "Site" -> "reddit.com"
                                "Address" -> "*://*/watch*"
                                "Word" -> "gambling"
                                else -> "* - YouTube*"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    when (kind.first) {
                        "Site" -> "Blocks the site and everything under it."
                        "Address" -> "Matches whole addresses; * stands for any run of characters."
                        "Word" -> "Blocks anything whose address or title contains the word."
                        else -> "Matches the title of a window, on the PC."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(kind.second(value.trim())) },
                enabled = value.isNotBlank(),
            ) {
                Text("Block")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * The app and website lists, opened from inside a profile.
 *
 * Same screen, same writes; it simply arrives already knowing which profile it is editing, because
 * the only way to reach it is from that profile's own card.
 */
@Composable
fun AppPickerSheet(
    model: CurfewViewModel,
    profileId: String,
    profileName: String,
    onDone: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Ink),
        ) {
            Box(Modifier.padding(horizontal = Dsn.Gutter)) {
                BackRow("What $profileName blocks", onDone)
            }
            Box(Modifier.weight(1f)) {
                AppPickerScreen(model, pinned = profileId)
            }
        }
    }
}
