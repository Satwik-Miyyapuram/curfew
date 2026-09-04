package dev.curfew.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Choosing apps without writing TOML.
 *
 * The picker owns exactly one kind of rule — "block this app while the profile is running" — and
 * says so, because a picker that silently rewrote a budget the user had hand-written would make the
 * file untrustworthy. Everything else stays in the config editor, which remains the complete
 * interface.
 */
@Composable
fun AppPickerScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val apps = remember { installedApps(context) }

    var profile by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }

    // Default to the first profile, and re-read the ticks whenever the chosen profile changes or
    // the config is edited elsewhere.
    LaunchedEffect(state.profiles, profile, state.configToml) {
        val chosen = profile ?: state.profiles.firstOrNull()?.id
        if (chosen != profile) profile = chosen
        if (chosen != null) checked = model.blockedApps(chosen).toSet()
    }

    val current = profile
    val visible = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    val saved = current?.let { model.blockedApps(it).toSet() } ?: emptySet()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Apps to block",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )

        if (state.profiles.isEmpty()) {
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
                    onClick = { profile = p.id },
                    label = { Text(p.name) },
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            items(visible, key = { it.packageName }) { app ->
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
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }

        Text(
            "Saving rewrites curfew.toml, which drops any comments you have written in it. " +
                "Rules that are not a plain app block — budgets, delays, launch limits — are left " +
                "alone.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { current?.let { model.setBlockedApps(it, checked.toList()) } },
                enabled = current != null && checked != saved,
            ) {
                Text("Save")
            }
            TextButton(onClick = { checked = saved }, enabled = checked != saved) {
                Text("Discard")
            }
        }
    }
}
