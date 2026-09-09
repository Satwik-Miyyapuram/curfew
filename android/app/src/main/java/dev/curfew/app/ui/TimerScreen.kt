package dev.curfew.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock

/** The lengths people actually ask for, and the one they get if they touch nothing. */
private val PRESETS = listOf(15, 25, 50, 90, 180)
private const val DEFAULT_MINUTES = 50

/**
 * How hard the user wants this particular block to be to escape.
 *
 * Asked here rather than buried in a profile, because the honest answer changes between a study
 * hour and an evening off, and a user who has to go and change a setting first will simply not
 * start the timer. [Lock.Timer] is the strong end: it means nothing ends this early except an
 * emergency pass.
 */
private enum class Strength(val label: String, val note: String, val lock: Lock?) {
    Open("I can stop it", "Ends the moment you tap stop.", null),
    Confirm("Ask me first", "One confirmation, so it is never an accident.", Lock.Confirm),
    Credential("Fingerprint or PIN", "Proves it is you, not a pocket.", Lock.DeviceCredential),
    Typing("Type it out", "A sentence to copy before it opens.", Lock.Challenge(ChallengeKind.TYPING)),
    Locked("Until it ends", "No way out but an emergency pass.", Lock.Timer),
}

/**
 * Block something right now, for a while, because you decided to.
 *
 * Everything else in Curfew waits for the world to say when — a lecture in a calendar, a weeknight
 * that has arrived. This is the screen for the commonest intention of all, which none of that
 * covers: *not for the next hour*. It is deliberately the shortest path in the app — a length, a
 * profile, a strength, one button — because a person about to be distracted has a few seconds of
 * resolve to spend and a settings tree would eat all of them.
 */
@Composable
fun TimerScreen(model: CurfewViewModel, onDone: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var minutes by remember { mutableIntStateOf(DEFAULT_MINUTES) }
    var strength by remember { mutableStateOf(Strength.Confirm) }
    var profile by remember { mutableStateOf<String?>(null) }

    // Whichever profile the user has, without making them choose on a fresh install where there is
    // only one. A missing profile is the one thing this screen cannot invent.
    val chosen = profile ?: state.profiles.firstOrNull()?.id

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Start now",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )

        Card(colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    spellDuration(minutes),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.6).sp,
                    color = Palette.Text,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { preset ->
                        Chip(
                            text = spellDuration(preset),
                            selected = minutes == preset,
                            onClick = { minutes = preset },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Step("−", "five minutes less") { minutes = (minutes - 5).coerceAtLeast(5) }
                    Text(
                        "or nudge it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Dim,
                    )
                    Step("+", "five minutes more") { minutes = (minutes + 5).coerceAtMost(12 * 60) }
                }
            }
        }

        Text("What it blocks", style = MaterialTheme.typography.labelSmall)
        if (state.profiles.isEmpty()) {
            Text(
                "You have not set up anything to block yet. Make a profile on the Plan tab first " +
                    "— a timer needs to know what it is switching off.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.profiles.forEach { p ->
                    Chip(p.name, selected = p.id == chosen, onClick = { profile = p.id })
                }
            }
        }

        Text("If you change your mind", style = MaterialTheme.typography.labelSmall)
        Card(colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
            Column {
                Strength.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { strength = option }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (strength == option) Palette.Accent else Color.Transparent)
                                .border(1.dp, if (strength == option) Palette.Accent else Palette.Line, CircleShape),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                option.note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.Muted,
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val id = chosen ?: return@Button
                model.startTimer(id, minutes * 60, listOfNotNull(strength.lock))
                onDone()
            },
            enabled = chosen != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                // Amber, because from the moment this is tapped the phone is in the state amber
                // means everywhere else in the app.
                containerColor = Palette.Live,
                contentColor = Palette.Ink,
            ),
        ) {
            Text("Block for ${spellDuration(minutes)}")
        }
    }
}

/** "1h 30m", not "90 minutes" and never "5400". */
private fun spellDuration(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Palette.Accent else Palette.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Palette.Ink else Palette.Muted,
        )
    }
}

@Composable
private fun Step(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Palette.Raised)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 19.sp, color = Palette.Text)
    }
}
