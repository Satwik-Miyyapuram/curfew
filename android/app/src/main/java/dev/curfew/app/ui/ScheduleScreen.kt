package dev.curfew.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * What is going to happen, and the rules that decide it.
 *
 * The timeline comes first because the honest question about a scheduling tool is "what is this
 * going to do to me later today?", and that has to be answerable without reading a config. The
 * config itself is below it, as text.
 *
 * Editing the rules as TOML is a deliberate choice for the first release, not a placeholder: the
 * config is the thing a user backs up, diffs and carries between devices, and a form that can only
 * express a subset of it would quietly become the real interface while the file became a mystery.
 * The core validates before anything is written, so a bad edit cannot leave the device unprotected.
 */
@Composable
fun ScheduleScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(state.configToml) }
    var editing by remember { mutableStateOf(false) }

    // Adopt the saved config whenever it changes underneath an untouched editor, so the text does
    // not silently go stale — but never overwrite an edit in progress.
    LaunchedEffect(state.configToml) {
        if (!editing) draft = state.configToml
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Coming up", style = MaterialTheme.typography.headlineSmall)

        if (state.activations.isEmpty()) {
            Text(
                "Nothing is scheduled. Add a profile with a schedule below and it will appear here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.activations.forEach { activation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(activation.profile, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${clockTime(activation.start)} – ${clockTime(activation.end)} · " +
                            describeSource(activation.source),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (activation.start > state.now) {
                            "Starts ${relative(activation.start, state.now)}"
                        } else {
                            "Running, ends ${relative(activation.end, state.now)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (activation.locks.isNotEmpty()) {
                        Text(
                            "Will lock: needs " +
                                activation.locks.joinToString(", ") { describeLock(it) } + ".",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        Text(
            "Rules",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; editing = true },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            label = { Text("curfew.toml") },
            minLines = 8,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { model.saveConfig(draft); editing = false },
                enabled = editing && draft != state.configToml,
            ) {
                Text("Save")
            }
            TextButton(
                onClick = { draft = state.configToml; editing = false },
                enabled = editing,
            ) {
                Text("Discard")
            }
        }
        Text(
            "Saving does not end a session that is already running. A lock you asked for is not " +
                "something a settings edit can undo.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
