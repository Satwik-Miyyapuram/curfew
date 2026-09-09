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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import dev.curfew.policy.WeeklySchedule

private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")
private val PRESETS = listOf(
    "Study" to "Study",
    "Sleep" to "Sleep",
    "Socials diet" to "Socials diet",
    "Weekend" to "Weekend",
)

/**
 * Making a profile, and changing one.
 *
 * The same screen for both, because "new" and "edit" differ only in whether the name box starts
 * empty — and a create flow that looks nothing like the edit flow teaches the user the app twice.
 *
 * Two rules run through it. **No id ever reaches the screen.** A profile answers to a slug in the
 * config and to a name everywhere a person can see, and the previous version leaked the slug into
 * headings, chips and confirmations, which is how a tool starts feeling like someone else's
 * database. And **every fixed number is a control**: the hour a block starts, the minute it ends,
 * the days it runs. Those were constants in a file, which meant "21:00 on weeknights" was a
 * decision the app had made on the user's behalf and would not discuss.
 */
@Composable
fun ProfileEditScreen(model: CurfewViewModel, id: String?, onDone: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val existing = state.profiles.firstOrNull { it.id == id }
    var name by remember(id) { mutableStateOf(existing?.name.orEmpty()) }

    // The id is minted from the name the first time and never shown or changed afterwards: renaming
    // a profile must not orphan the schedules pointing at it.
    val profileId = id ?: slug(name)
    val windows = state.weekly.filter { it.profile == profileId }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            if (existing == null) "New profile" else existing.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("What should we call it?") },
            supportingText = { Text("A name you would say out loud.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (existing == null) {
            Text("Or start from one of these", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { (label, value) ->
                    Chip(label, selected = name == value, onClick = { name = value })
                }
            }
        }

        Text("When it runs", style = MaterialTheme.typography.labelSmall)
        if (windows.isEmpty()) {
            Text(
                "Nothing starts it yet. Add a window below, or leave it and start it by hand " +
                    "with a timer whenever you want.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }
        windows.forEach { window ->
            WindowCard(
                window = window,
                onChange = { model.saveWeekly(it) },
                onRemove = { model.deleteWeekly(window.id) },
            )
        }

        OutlinedButton(
            onClick = {
                if (name.isBlank()) {
                    model.say("Give it a name first.")
                    return@OutlinedButton
                }
                model.saveProfile(profileId, name.trim())
                model.saveWeekly(
                    WeeklySchedule(
                        // Minted from the clock so two windows added in the same session cannot
                        // collide, and never shown.
                        id = "w-${state.now}",
                        profile = profileId,
                        // A weeknight evening: the commonest thing anyone sets up, and every part
                        // of it is a control on the card that appears.
                        days = listOf(1, 2, 3, 4, 5),
                        startMinute = 21 * 60,
                        endMinute = 24 * 60,
                        locks = listOf(Lock.Confirm),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add a window")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (existing != null) {
                OutlinedButton(
                    onClick = {
                        model.deleteProfile(existing.id)
                        onDone()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Delete", color = Palette.Bad)
                }
            }
            Button(
                onClick = {
                    if (name.isBlank()) {
                        model.say("Give it a name first.")
                        return@Button
                    }
                    model.saveProfile(profileId, name.trim())
                    onDone()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Save")
            }
        }
    }
}

/**
 * One weekly window, with every number in it as a control.
 *
 * Times move in fifteen-minute steps because that is the granularity anyone actually means when
 * they say when they want to stop — minute-precision here would be a spinner nobody can hit and a
 * decision nobody wanted to make.
 */
@Composable
private fun WindowCard(
    window: WeeklySchedule,
    onChange: (WeeklySchedule) -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DAYS.forEachIndexed { index, letter ->
                    // Monday is 1 in the config, and Sunday is 7 — the ISO numbering the core
                    // uses, kept out of the user's way.
                    val day = index + 1
                    val on = day in window.days
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (on) Palette.Accent else Palette.Raised)
                            .clickable {
                                val days = if (on) window.days - day else window.days + day
                                onChange(window.copy(days = days.sorted()))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            letter,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (on) Palette.Ink else Palette.Muted,
                        )
                    }
                }
            }

            HorizontalDivider(color = Palette.Line)

            MinuteRow("Starts", window.startMinute) { onChange(window.copy(startMinute = it)) }
            MinuteRow("Ends", window.endMinute) { onChange(window.copy(endMinute = it)) }

            HorizontalDivider(color = Palette.Line)

            Text("Getting out early", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                strengths().forEach { (label, lock) ->
                    Chip(
                        label,
                        selected = window.locks.firstOrNull() == lock,
                        onClick = { onChange(window.copy(locks = listOfNotNull(lock))) },
                    )
                }
            }

            Text(
                "Remove this window",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Bad,
                modifier = Modifier.clickable(onClick = onRemove),
            )
        }
    }
}

/** The four answers to "how hard should this be to escape", in order of how hard they are. */
private fun strengths(): List<Pair<String, Lock?>> = listOf(
    "I can stop it" to null,
    "Ask me first" to Lock.Confirm,
    "Fingerprint" to Lock.DeviceCredential,
    "Type it out" to Lock.Challenge(ChallengeKind.TYPING),
)

@Composable
private fun MinuteRow(label: String, minute: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Nudge("−", "fifteen minutes earlier") {
                onChange(((minute - 15) + 24 * 60) % (24 * 60 + 1))
            }
            Text(
                clock(minute),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Nudge("+", "fifteen minutes later") { onChange((minute + 15).coerceAtMost(24 * 60)) }
        }
    }
}

/** Minutes past midnight as a clock face. 1440 is midnight at the far end, not 24:00. */
private fun clock(minute: Int): String {
    val m = minute.coerceIn(0, 24 * 60)
    if (m == 24 * 60) return "midnight"
    return "%02d:%02d".format(m / 60, m % 60)
}

private fun slug(name: String): String =
    name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "profile" }

@Composable
private fun Nudge(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Raised)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 17.sp, color = Palette.Text)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Palette.Accent else Palette.Raised)
            .border(
                1.dp,
                if (selected) Palette.Accent else Palette.Line,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Palette.Ink else Palette.Muted,
        )
    }
}
