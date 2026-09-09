package dev.curfew.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The one screen that is about the app rather than about the phone.
 *
 * It leads with the Simple/Power switch, because that switch changes what every other screen looks
 * like and a user who cannot find it is stuck in whichever half of the app suits them less. Under
 * it sit the screens Simple leaves out of the tab bar — not as a consolation, but as the actual
 * rule this design runs on: **Simple hides tabs, not powers**. Nothing here is unavailable in
 * Simple mode; it is one tap further away, which is what "simple" is allowed to mean.
 *
 * Health is the exception to the ordering: it stays visible in both modes and near the top,
 * because a permission Curfew is missing is a block that is not going to happen, and that is not
 * an advanced topic.
 */
@Composable
fun SettingsScreen(model: CurfewViewModel, onOpen: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val mode by model.mode.collectAsStateWithLifecycle()
    val missing = state.grants.count { it.grant.required && !it.granted }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )

        Card(colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("How much do you want to see?", style = MaterialTheme.typography.labelSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Mode.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = mode == option,
                            onClick = { model.setMode(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, Mode.entries.size),
                            label = { Text(if (option == Mode.Simple) "Simple" else "Power") },
                        )
                    }
                }
                Text(
                    if (mode.isPower) {
                        "Every screen in the bar, exact timers, the rule that matched, and the " +
                            "config file itself."
                    } else {
                        "Four tabs and plain sentences. Everything else is still here, listed " +
                            "below — nothing stops working."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
        }

        Text("The rest of Curfew", style = MaterialTheme.typography.labelSmall)
        Card(colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
            Column {
                Entry(
                    title = "Is Curfew working",
                    note = when {
                        missing == 1 -> "One permission is missing. Nothing is being blocked without it."
                        missing > 1 -> "$missing permissions are missing. Nothing is being blocked without them."
                        else -> "Everything it needs, it has."
                    },
                    warn = missing > 0,
                    onClick = { onOpen(Routes.HEALTH) },
                )
                HorizontalDivider(color = Palette.Line)
                Entry(
                    title = "Your calendar",
                    note = if (state.calendarGranted) {
                        "${state.calendarEvents.size} events read for the next day and a half."
                    } else {
                        "Not connected. Blocks from your calendar will not start."
                    },
                    warn = !state.calendarGranted,
                    onClick = { onOpen(Routes.CALENDAR) },
                )
                HorizontalDivider(color = Palette.Line)
                Entry(
                    title = "Where your time went",
                    note = "Time and opens, per app, for as long as Curfew has been watching.",
                    onClick = { onOpen(Routes.USAGE) },
                )
                HorizontalDivider(color = Palette.Line)
                Entry(
                    title = "Your other devices",
                    note = if (state.sync.active.isEmpty()) {
                        "No other device paired yet."
                    } else {
                        "${state.sync.active.size} paired. Blocks follow you between them."
                    },
                    onClick = { onOpen(Routes.DEVICES) },
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
            Text(
                "Curfew has no internet permission at all. Your devices sync directly to each " +
                    "other — there is no account and no server.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun Entry(title: String, note: String, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                // A missing permission is coloured, and nothing else on this screen is. It is the
                // only line here that means something is currently not happening.
                color = if (warn) Palette.Bad else Palette.Muted,
            )
        }
    }
}
