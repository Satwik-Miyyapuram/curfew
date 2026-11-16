package dev.curfew.app.ui

import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            Gap(Space.s)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                state.profiles.forEach { p ->
                    Pill(p.name, selected = current == p.id, onClick = { profile = p.id })
                }
            }
            Gap(Space.s)
            Text(
                othersHold(state.profiles.filter { it.id != current }, counts, siteCounts),
                style = Type.meta,
                color = Palette.dim,
            )
            Gap(Space.l)
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
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Copy from", style = Type.meta, color = Palette.dim)
                donors.forEach { donor ->
                    Pill(donor.name, selected = false, onClick = { copying = donor.id })
                }
            }
            Gap(Space.l)
        }
        copying?.let { donorId ->
            val donor = state.profiles.firstOrNull { it.id == donorId }
            val mine = current
            if (donor == null || mine == null) {
                copying = null
            } else {
                // The app's own decision sheet. This was a Material `AlertDialog` — a second
                // surface colour, a second corner radius and a second type ramp, on the screen
                // whose whole job is choosing what to block.
                DConfirm(
                    title = "Copy ${donor.name}'s list?",
                    body = "${counts[donorId] ?: 0} apps and ${siteCounts[donorId] ?: 0} sites get " +
                        "added here. Nothing already blocked is removed.",
                    dismiss = "Cancel",
                    confirm = "Copy them",
                    onDismiss = { copying = null },
                    onConfirm = {
                        model.copyBlocksFrom(donorId, mine)
                        copying = null
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
        Gap(Space.l)

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
                    style = Type.meta,
                    color = Palette.bad,
                )
            }
            return@Screen
        }

        if (pane == Pane.Apps) {
            SearchField(query) { query = it }
            Gap(Space.m)
            // Which apps Curfew will not *read*, said once and nowhere near the switches. It is not a
            // filter — every app here is blockable — so this is a footnote, not a warning.
            if (readProtectedCount > 0) {
                DCard(padding = 14.dp) {
                    Text(
                        stringResource(R.string.picker_sensitive_note),
                        style = Type.meta,
                        color = Palette.muted,
                    )
                    Gap(Space.s)
                    Text(
                        stringResource(R.string.picker_sensitive_note_action),
                        style = Type.meta,
                        fontWeight = FontWeight.Bold,
                        color = Palette.text,
                        modifier = Modifier.clickable { onOpenSensitiveApps() },
                    )
                }
                Gap(Space.m)
            }
            DCardFlush {
                visible.forEachIndexed { index, app ->
                    if (index > 0) Rule()
                    val on = app.packageName in blocked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.l, vertical = Space.m)
                            .semantics(mergeDescendants = true) {
                                contentDescription =
                                    if (on) "${app.label}, blocked" else "${app.label}, allowed"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        AppIcon(app.packageName, modifier = Modifier.size(36.dp))
                        Text(
                            app.label,
                            style = Type.body,
                            color = Palette.text,
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
                        style = Type.meta,
                        color = Palette.muted,
                        modifier = Modifier.padding(Space.l),
                    )
                }
            }
        } else {
            DCardFlush {
                sites.forEachIndexed { index, rule ->
                    if (index > 0) Rule()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.l),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        // A [Glyph], which is the app's square tinted marker with a 3px edge. It was
                        // a hand-rolled 11dp-radius box that matched nothing else on screen.
                        Glyph(tint = Palette.accent, size = 36.dp) {
                            Text(glyphFor(rule.target), style = Type.body, color = Palette.onFill)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.target.label(),
                                style = Type.body.mono,
                                fontWeight = FontWeight.Bold,
                                color = Palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(describeRule(rule), style = Type.meta, color = Palette.dim)
                        }
                        Box(
                            modifier = Modifier
                                .size(Dsn.MinTouch)
                                .clickable(
                                    onClickLabel = "Stop blocking ${rule.target.label()}",
                                ) { current?.let { model.deleteRule(it, rule.target) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✕", style = Type.body, color = Palette.dim)
                        }
                    }
                }
                if (sites.isEmpty()) {
                    Text(
                        "Nothing yet. A site blocks it and everything under it; a word blocks " +
                            "anything whose address or title contains it.",
                        style = Type.meta,
                        color = Palette.muted,
                        modifier = Modifier.padding(Space.l),
                    )
                }
            }
            Gap(Space.m)
            // Styled as an empty field rather than as a button, because what it opens asks for a
            // line of text and this is where that line will end up.
            DCard(padding = 0.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(enabled = current != null) { adding = true }
                        .padding(horizontal = Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    Text("+", style = Type.h3, color = Palette.link)
                    Text(
                        "Address, pattern or word…",
                        style = Type.body.mono,
                        color = Palette.muted,
                    )
                }
            }
        }

        Gap(Space.l)
        Text(
            "Changes apply the next time ${nameOf(state, current)} starts — never to a block " +
                "already running.",
            style = Type.meta,
            color = Palette.dim,
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
            .background(Palette.raised, RoundedCornerShape(Dsn.CtlRadius))
            .padding(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
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
            .background(if (selected) Palette.accent else Palette.raised)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
    ) {
        Text(
            label,
            style = Type.body,
            fontWeight = FontWeight.Bold,
            color = if (selected) Palette.onFill else Palette.muted,
        )
        Text(
            count.toString(),
            style = Type.body,
            fontWeight = FontWeight.Bold,
            color = if (selected) Palette.onFill.copy(alpha = 0.62f) else Palette.dim,
        )
    }
}

/** The search box, in the app's own shape rather than Material's. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dsn.MinTouch)
            .background(Palette.raised, RoundedCornerShape(Dsn.CtlRadius))
            .padding(horizontal = Space.m),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.body.copy(color = Palette.text),
            cursorBrush = SolidColor(Palette.link),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text("Search your apps", style = Type.body, color = Palette.dim)
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

internal fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

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

    // The app's own sheet, with the app's own controls.
    //
    // This was a Material `AlertDialog` containing a `FilterChip` row and an `OutlinedTextField` —
    // three borrowed components in one form, each with its own corner radius, its own surface
    // colour and its own idea of what a label is. The kind chips are [Pill]s, the field is
    // [TextField], and the explanation is a [SheetNote], which is what every other form in the app
    // uses.
    //
    // It is also the last screen in the app that had any Material competitor in it, so
    // `MaterialUsageTest`'s allowlist shrank by four entries rather than being added to.
    DSheet(
        title = "Block a site or word",
        sub = when (kind.first) {
            "Site" -> "Blocks the site and everything under it."
            "Address" -> "Matches whole addresses; * stands for any run of characters."
            "Word" -> "Blocks anything whose address or title contains the word."
            else -> "Matches the title of a window, on the PC."
        },
        onDismiss = onDismiss,
        confirm = "Block it",
        confirmEnabled = value.isNotBlank(),
        onConfirm = { onSave(kind.second(value.trim())) },
    ) {
        SheetSection("What kind") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                BLOCK_KINDS.forEach { option ->
                    Box(
                        modifier = Modifier.heightIn(min = Dsn.MinTouch),
                        contentAlignment = Alignment.Center,
                    ) {
                        Pill(
                            text = option.first,
                            selected = kind.first == option.first,
                            onClick = { kind = option },
                        )
                    }
                }
            }
        }
        SheetSection("What to block") {
            TextField(
                label = when (kind.first) {
                    "Site" -> "Address"
                    "Address" -> "Address pattern"
                    "Word" -> "Word"
                    else -> "Title pattern"
                },
                value = value,
                placeholder = when (kind.first) {
                    "Site" -> "reddit.com"
                    "Address" -> "*://*/watch*"
                    "Word" -> "gambling"
                    else -> "* - YouTube*"
                },
                onChange = { value = it },
            )
        }
    }
}


/** Filter choices for the app picker list. */
enum class AppFilter { All, Blocked, Allowed }

/**
 * How long the picker waits after the last tap before writing the plan.
 *
 * Long enough that a burst of ticking becomes one save, short enough that walking away and coming
 * back cannot lose an edit. It is not a Save button because there is nothing to decide: the user has
 * already said what they want by tapping, and this only decides when the file hears about it.
 */
private const val PICKER_SAVE_DELAY_MS = 500L

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
     * The set the user is editing, held here rather than read back out of the config on every tap.
     *
     * **This is the lag fix.** Toggling one app used to go straight to `model.setBlockedApps`, which
     * does four expensive things every single time: serialises the whole plan to TOML, parses and
     * validates it, writes it to disk, and then re-reads the entire world — config, sessions, stats,
     * the audit — to rebuild the UI state. With two hundred apps in the list and somebody ticking a
     * dozen of them, that is a dozen full config rewrites and a dozen full state rebuilds while the
     * finger is still moving, which is exactly the stutter that made this sheet feel broken.
     *
     * The visible state is local now and changes on the frame the tap lands. The write happens once,
     * after the tapping stops, which is also the right behaviour for the plan file: one edit is one
     * save rather than a burst of intermediate ones.
     */
    var localBlocked by remember(profileId) { mutableStateOf(blocked) }
    // True from the first un-saved tap until the write has been handed over. It is what stops the
    // config coming back round and stomping on what the user is in the middle of doing.
    var pendingWrite by remember(profileId) { mutableStateOf(false) }

    // Adopt the file's answer whenever the user is not mid-edit, so an edit made elsewhere still
    // shows up here.
    LaunchedEffect(blocked, pendingWrite) {
        if (!pendingWrite) localBlocked = blocked
    }

    // One write, after the tapping stops.
    LaunchedEffect(localBlocked, pendingWrite) {
        if (!pendingWrite) return@LaunchedEffect
        delay(PICKER_SAVE_DELAY_MS)
        model.setBlockedApps(profileId, localBlocked.toList())
        pendingWrite = false
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

    val filteredApps = remember(apps, query, filter, localBlocked) {
        apps
            .filter { app ->
                val matchesQuery = query.isBlank() || app.label.contains(query, ignoreCase = true)
                val isBlocked = app.packageName in localBlocked
                val matchesFilter = when (filter) {
                    AppFilter.All -> true
                    AppFilter.Blocked -> isBlocked
                    AppFilter.Allowed -> !isBlocked
                }
                matchesQuery && matchesFilter
            }
            .sortedWith(compareBy({ it.packageName !in localBlocked }, { it.label.lowercase() }))
    }

    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FullScreenDialogWindow()
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .sheetDragToDismiss(onDone)
                    .glass(ground = Palette.surface)
                    // The pane runs under the gesture bar; its content, and so the Done button, does not.
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = Dsn.Gutter)
                    .padding(top = Space.s, bottom = Space.m),
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = Space.s)
                        .align(Alignment.CenterHorizontally)
    
                        .background(Palette.line)
                        .size(width = 38.dp, height = 4.dp),
                )
                Gap(Space.s)
                Text(
                    "Select Apps to Block",
                    style = Type.h2,
                    fontWeight = FontWeight.Bold,
                    color = Palette.text,
                )
                Text(
                    "${localBlocked.size} apps blocked for $profileName",
                    style = Type.meta,
                    color = Palette.muted,
                    modifier = Modifier.padding(top = Space.xs),
                )

                if (donors.isNotEmpty()) {
                    Gap(Space.m)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Copy from:",
                            style = Type.micro,
                            fontWeight = FontWeight.Bold,
                            color = Palette.dim,
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

                Gap(Space.s)
                SearchField(value = query, onChange = { query = it })

                Gap(Space.s)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
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
                        text = "Blocked (${localBlocked.size})",
                        selected = filter == AppFilter.Blocked,
                        onClick = { filter = AppFilter.Blocked },
                    )
                    Pill(
                        text = "Allowed (${(apps.size - localBlocked.size).coerceAtLeast(0)})",
                        selected = filter == AppFilter.Allowed,
                        onClick = { filter = AppFilter.Allowed },
                    )
                }

                // Which apps Curfew will not *read*, as a footnote. They are all still in the list
                // below and all still blockable — hiding them is what made a user conclude the app
                // was broken.
                if (readProtectedCount > 0) {
                    Gap(Space.s)
                    Text(
                        stringResource(R.string.picker_sensitive_note),
                        style = Type.micro,
                        color = Palette.muted,
                    )
                }

                Gap(Space.s)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(Dsn.CardRadius))
                        .background(Palette.surface),
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
                                    style = Type.meta,
                                    color = Palette.muted,
                                    modifier = Modifier.padding(Space.l),
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = filteredApps,
                                key = { _, app -> app.packageName },
                            ) { index, app ->
                                if (index > 0) Rule()
                                val on = app.packageName in localBlocked
                                // Local and immediate, from the row and the switch alike. The write
                                // follows on a delay; see `localBlocked` above for why this is not the
                                // model call.
                                val toggle = {
                                    localBlocked =
                                        if (on) localBlocked - app.packageName
                                        else localBlocked + app.packageName
                                    pendingWrite = true
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = toggle)
                                        .padding(start = Space.l, end = Space.s, top = Space.xs, bottom = Space.xs)
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = if (on) "${app.label}, blocked" else "${app.label}, allowed"
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                                ) {
                                    AppIcon(app.packageName, modifier = Modifier.size(36.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            app.label,
                                            style = Type.body,
                                            color = Palette.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (on) "Blocked" else "Allowed",
                                            style = Type.micro,
                                            color = if (on) Palette.link else Palette.dim,
                                        )
                                    }
                                    Switch(on = on, onChange = { toggle() })
                                }
                            }
                        }
                    }
                }

                Gap(Space.m)
                PrimaryButton(
                    text = "Done",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                )
            }
            // The app's own banner is drawn under this dialog's window, so a failure from in here
            // (saving the list, copying another profile's) would otherwise be reported to no one.
            state.message?.let { notice ->
                MessageBanner(
                    notice,
                    model::dismissMessage,
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = Dsn.Gutter)
                        .padding(bottom = 88.dp),
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
