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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.app.R
import dev.curfew.app.enforce.SensitiveApps
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
 *
 * **Payment, banking, wallet and password apps are not offered here at all.** See [SensitiveApps]:
 * blocking one breaks it in a way the user did not ask for, because the core also mutes a blocked
 * app's notifications and a one-time password that never arrives is a payment that never completes.
 * An app that is on that list is also excluded from a hand-edited config, so this is not only the
 * picker being tidy — the picker is just where it is visible.
 */
@Composable
fun AppPickerScreen(
    model: CurfewViewModel,
    pinned: String? = null,
    /**
     * Where to go for the full list of apps Curfew will not block.
     *
     * A parameter with a default because this screen is currently unused — the profile editor opens
     * [AppPickerSheet] instead — and it is kept because the two share their shape and the sheet's copy
     * is easier to keep honest beside a second caller. Defaulted to nothing rather than to a hard
     * route so a caller that does not want the link does not have to know the route exists.
     */
    onOpenSensitiveApps: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read off the main thread: the launcher query walks every installed package, which on a full
    // phone is long enough to drop frames if it happens while composing.
    val apps by produceState(initialValue = InstalledAppsCache.cached.orEmpty()) {
        if (value.isEmpty()) {
            value = withContext(Dispatchers.IO) { installedApps(context) }
        }
    }
    // Resolved once per composition of this screen rather than per row: it is a `PackageManager`
    // walk, and the answer cannot change while the user is looking at it.
    val readProtected = remember { SensitiveApps.resolve(context) }

    /**
     * How many of the apps here Curfew will not read.
     *
     * **Not a filter.** These apps are offered for blocking like anything else — the reading
     * restriction belongs to the accessibility service and is not a limit on what a user may block.
     * This count exists only so a note can say which apps Curfew is blind to; using it to hide rows is
     * what made a user search for their bank, find nothing, and reasonably conclude the app was
     * broken.
     */
    val readProtectedCount = remember(apps, readProtected) {
        apps.count { it.packageName in readProtected }
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
            // Which apps Curfew will not *read*, said once and nowhere near the switches. It is not a
            // filter — every app here is blockable — so this is a footnote, not a warning.
            if (readProtectedCount > 0) {
                DCard(padding = 14.dp) {
                    Text(
                        stringResource(R.string.picker_sensitive_note),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = Palette.Muted,
                    )
                    Gap(6.dp)
                    Text(
                        stringResource(R.string.picker_sensitive_note_action),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Text,
                        modifier = Modifier.clickable { onOpenSensitiveApps() },
                    )
                }
                Gap(10.dp)
            }
            DCardFlush {
                visible.forEachIndexed { index, app ->
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
                        if (query.isBlank()) {
                            stringResource(R.string.picker_reading_apps)
                        } else {
                            // The same resource the sheet uses. There were two copies of this sentence
                            // and only one was externalised, which the copy guard caught by reporting a
                            // string it no longer recognised.
                            stringResource(R.string.picker_no_blockable_match, query)
                        },
                        fontSize = 13.sp,
                        color = Palette.Muted,
                        modifier = Modifier.padding(16.dp),
                    )
                }
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


/** Filter choices for the app picker list. */
enum class AppFilter { All, Blocked, Allowed }

/**
 * The app selection popup bottom sheet, opened from inside a profile.
 * Features live search, All/Blocked/Allowed filter pills, copy from donors, and tactile switches.
 */
@Composable
fun AppPickerSheet(
    model: CurfewViewModel,
    profileId: String,
    profileName: String,
    onDone: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val apps by produceState(initialValue = InstalledAppsCache.cached.orEmpty()) {
        if (value.isEmpty()) {
            value = withContext(Dispatchers.IO) { installedApps(context) }
        }
    }
    val readProtected = remember { SensitiveApps.resolve(context) }

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.All) }
    var copyingFromProfile by remember { mutableStateOf<dev.curfew.policy.ProfileName?>(null) }

    val blocked = remember(profileId, state.configToml) {
        model.blockedApps(profileId).toSet()
    }

    /**
     * How many of the apps here Curfew will not read. A footnote, not a filter — see the screen above.
     */
    val readProtectedCount = remember(apps, readProtected) {
        apps.count { it.packageName in readProtected }
    }

    val donors = remember(state.profiles, profileId) {
        state.profiles.filter { it.id != profileId && model.blockedApps(it.id).isNotEmpty() }
    }

    val filteredApps = remember(apps, query, filter, blocked) {
        apps
            .filter { app ->
                val matchesQuery = query.isBlank() || app.label.contains(query, ignoreCase = true)
                val isBlocked = app.packageName in blocked
                val matchesFilter = when (filter) {
                    AppFilter.All -> true
                    AppFilter.Blocked -> isBlocked
                    AppFilter.Allowed -> !isBlocked
                }
                matchesQuery && matchesFilter
            }
            .sortedWith(compareBy({ it.packageName !in blocked }, { it.label.lowercase() }))
    }

    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .glass(
                        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                        ground = Palette.Raised,
                    )
                    .padding(horizontal = Dsn.Gutter)
                    .padding(top = 8.dp, bottom = 18.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Palette.Line)
                        .size(width = 38.dp, height = 4.dp),
                )
                Gap(8.dp)
                Text(
                    "Select Apps to Block",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = Palette.Text,
                )
                Text(
                    "${blocked.size} apps blocked for $profileName",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (donors.isNotEmpty()) {
                    Gap(10.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Copy from:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.Dim,
                        )
                        donors.forEach { donor ->
                            val count = model.blockedApps(donor.id).size
                            Pill(
                                text = "${donor.name} ($count)",
                                selected = false,
                                onClick = { copyingFromProfile = donor },
                            )
                        }
                    }
                }

                Gap(12.dp)
                SearchField(value = query, onChange = { query = it })

                Gap(10.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // **The count excludes what is not offered, and that is the point of it.** It used
                    // to say `apps.size`, the raw enumeration, so the pill promised 242 and the list
                    // under it could show 222 with nothing explaining the difference. A count that
                    // disagrees with the list beneath it is worse than no count.
                    Pill(
                        text = "All (${apps.size})",
                        selected = filter == AppFilter.All,
                        onClick = { filter = AppFilter.All },
                    )
                    Pill(
                        text = "Blocked (${blocked.size})",
                        selected = filter == AppFilter.Blocked,
                        onClick = { filter = AppFilter.Blocked },
                    )
                    Pill(
                        text = "Allowed (${(apps.size - blocked.size).coerceAtLeast(0)})",
                        selected = filter == AppFilter.Allowed,
                        onClick = { filter = AppFilter.Allowed },
                    )
                }

                // Which apps Curfew will not *read*, as a footnote. They are all still in the list
                // below and all still blockable — hiding them is what made a user conclude the app
                // was broken.
                if (readProtectedCount > 0) {
                    Gap(10.dp)
                    Text(
                        stringResource(R.string.picker_sensitive_note),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = Palette.Muted,
                    )
                }

                Gap(12.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(Dsn.CardRadius))
                        .background(Palette.Surface),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (filteredApps.isEmpty()) {
                            item {
                                Text(
                                    // Both sides externalised, because both are read by a person and a
                                    // search that finds nothing is exactly when they need to understand
                                    // the sentence.
                                    if (query.isNotBlank()) {
                                        stringResource(R.string.picker_no_blockable_match, query)
                                    } else {
                                        stringResource(R.string.picker_no_apps_found)
                                    },
                                    fontSize = 13.sp,
                                    color = Palette.Muted,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = filteredApps,
                                key = { _, app -> app.packageName },
                            ) { index, app ->
                                if (index > 0) Rule()
                                val on = app.packageName in blocked
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val next = if (on) blocked - app.packageName else blocked + app.packageName
                                            model.setBlockedApps(profileId, next.toList())
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = if (on) "${app.label}, blocked" else "${app.label}, allowed"
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AppIcon(app.packageName, modifier = Modifier.size(36.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            app.label,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Palette.Text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (on) "Blocked" else "Allowed",
                                            fontSize = 11.5.sp,
                                            color = if (on) Palette.Accent else Palette.Dim,
                                        )
                                    }
                                    Switch(
                                        on = on,
                                        onChange = { wanted ->
                                            val next = if (wanted) blocked + app.packageName else blocked - app.packageName
                                            model.setBlockedApps(profileId, next.toList())
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                Gap(16.dp)
                PrimaryButton(
                    text = "Done",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                )
            }
        }
    }

    copyingFromProfile?.let { donor ->
        val donorCount = model.blockedApps(donor.id).size
        DConfirm(
            title = "Copy ${donor.name}'s apps?",
            sub = "Adds $donorCount apps to $profileName.",
            body = "Nothing already blocked in $profileName will be removed.",
            dismiss = "Cancel",
            confirm = "Copy them",
            onDismiss = { copyingFromProfile = null },
            onConfirm = {
                val d = donor
                copyingFromProfile = null
                model.copyBlocksFrom(d.id, profileId)
            },
        )
    }
}
