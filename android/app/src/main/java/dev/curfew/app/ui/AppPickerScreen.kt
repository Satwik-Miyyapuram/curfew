package dev.curfew.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import dev.curfew.policy.Rule
import dev.curfew.policy.Target
import dev.curfew.policy.label
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two halves of "what does this profile block", each reachable without a scroll. */
private enum class Pane { Apps, Sites }

/**
 * Choosing apps without writing TOML.
 *
 * The picker owns exactly one kind of rule — "block this app while the profile is running" — and
 * says so, because a picker that silently rewrote a budget the user had hand-written would make the
 * file untrustworthy. Below it, "Sites and words" writes the other plain blocks a phone can state:
 * a domain, an address, a word, a window title. Anything with a shape a form cannot express — a
 * budget, a launch limit, a delay — is listed there but edited in the config editor, which remains
 * the complete interface.
 */
@Composable
fun AppPickerScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read off the main thread: the launcher query walks every installed package, which on a full
    // phone is long enough to drop frames if it happens while composing.
    val apps by produceState(initialValue = emptyList<InstalledApp>()) {
        value = withContext(Dispatchers.IO) { installedApps(context) }
    }

    var profile by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    // Which half of "what does this profile block" is on screen. Both used to live in one scroll,
    // which meant the site list started wherever two hundred apps ended — a scroll to nowhere for
    // the shorter and more often edited of the two lists.
    var pane by remember { mutableStateOf(Pane.Apps) }
    // A profile the user asked to switch to while holding unsaved ticks, waiting on an answer.
    var switchingTo by remember { mutableStateOf<String?>(null) }

    // Default to the first profile, and re-read the ticks whenever the chosen profile changes or
    // the config is edited elsewhere.
    LaunchedEffect(state.profiles, profile, state.configToml) {
        val chosen = profile ?: state.profiles.firstOrNull()?.id
        if (chosen != profile) profile = chosen
        if (chosen != null) checked = model.blockedApps(chosen).toSet()
    }

    val current = profile
    val saved = current?.let { model.blockedApps(it).toSet() } ?: emptySet()
    val dirty = current != null && checked != saved
    // Blocked apps first, then the rest, each half alphabetical. What a profile blocks is the
    // answer this screen exists to give, and it should not be somewhere down a list of two hundred.
    val visible = apps
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedWith(compareBy({ it.packageName !in checked }, { it.label.lowercase() }))
    // How many apps each profile blocks, so the chips show that the sets really are separate.
    val counts = remember(state.profiles, state.configToml) {
        state.profiles.associate { it.id to model.blockedApps(it.id).size }
    }
    // Re-read on every config change, so a rule removed here disappears without a manual refresh.
    val beyondApps = remember(current, state.configToml) {
        current?.let { model.rulesBeyondApps(it) }.orEmpty()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Apps to block",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )

        if (state.profiles.isEmpty() && !state.loading) {
            Text(
                "There are no profiles yet. Add one under Schedule and it will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.profiles.forEach { p ->
                FilterChip(
                    selected = current == p.id,
                    // Switching away with unsaved ticks used to drop them silently, because the
                    // effect above re-reads the config for the newly chosen profile. Ask instead.
                    onClick = { if (dirty && p.id != current) switchingTo = p.id else profile = p.id },
                    label = { Text("${p.name} · ${counts[p.id] ?: 0}") },
                )
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Pane.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = pane == option,
                    onClick = { pane = option },
                    shape = SegmentedButtonDefaults.itemShape(index, Pane.entries.size),
                    label = {
                        Text(
                            when (option) {
                                Pane.Apps -> "Apps ${checked.size}"
                                Pane.Sites -> "Websites ${beyondApps.size}"
                            },
                        )
                    },
                )
            }
        }

        if (pane == Pane.Apps) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            if (pane == Pane.Apps) items(visible, key = { it.packageName }) { app ->
                val isChecked = app.packageName in checked
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            checked = if (isChecked) checked - app.packageName
                            else checked + app.packageName
                        }
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription =
                                if (isChecked) "${app.label}, blocked" else "${app.label}, allowed"
                        },
                ) {
                    Checkbox(checked = isChecked, onCheckedChange = null)
                    AppIcon(
                        app.packageName,
                        modifier = Modifier.padding(start = 8.dp).size(32.dp),
                    )
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            if (pane == Pane.Sites) item {
                if (beyondApps.isEmpty()) {
                    Text(
                        "Nothing yet. A site blocks it and its subdomains; a word blocks anything " +
                            "whose title or address contains it.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (pane == Pane.Sites) items(beyondApps, key = { it.target.label() + it.platforms }) { rule ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.target.label(), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            describeRule(rule),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = { current?.let { model.deleteRule(it, rule.target) } },
                    ) {
                        Text("Remove")
                    }
                }
            }

            if (pane == Pane.Sites) item {
                TextButton(onClick = { adding = true }, enabled = current != null) {
                    Text("Block a site or word")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        switchingTo?.let { target ->
            AlertDialog(
                onDismissRequest = { switchingTo = null },
                title = { Text("Unsaved changes") },
                text = {
                    Text(
                        "The ticks for this profile have not been saved. Switching profiles now " +
                            "discards them.",
                    )
                },
                confirmButton = {
                    Button(onClick = { profile = target; switchingTo = null }) { Text("Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { switchingTo = null }) { Text("Stay here") }
                },
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

        if (pane == Pane.Apps) Text(
            "Saving rewrites curfew.toml, which drops any comments you have written in it. " +
                "Rules that are not a plain app block — budgets, delays, launch limits — are left " +
                "alone.",
            style = MaterialTheme.typography.bodySmall,
        )
        // Sites save the moment they are added or removed; only the app ticks are a batch, so the
        // save bar belongs to that half alone rather than sitting greyed out under the other.
        if (pane == Pane.Apps) Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { current?.let { model.setBlockedApps(it, checked.toList()) } },
                enabled = dirty,
            ) {
                Text("Save")
            }
            TextButton(onClick = { checked = saved }, enabled = dirty) {
                Text("Discard")
            }
        }
    }
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
                            }
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
