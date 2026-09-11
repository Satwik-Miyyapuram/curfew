package dev.curfew.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock

/** The lengths people actually ask for, and the one they get if they touch nothing. */
private val PRESETS = listOf(25, 50, 90, 180)
private const val DEFAULT_MINUTES = 90

/** The longest a single timer may run, so a slip of the thumb cannot cost a day. */
private const val MAX_MINUTES = 12 * 60

/**
 * Past this, starting asks first.
 *
 * Four hours is longer than any of the presets and longer than a working session, so a timer that
 * long is either deliberate or a thumb that dragged too far. The dialog costs a second in the first
 * case and saves an evening in the second.
 */
private const val LONG_MINUTES = 4 * 60

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
 *
 * The dial is the length. It is drawn as one full turn of twelve hours, so the size of the arc is
 * a real quantity rather than decoration, and the presets under it are the four lengths that cover
 * almost every ask. They scroll rather than wrap: a row of chips that reflows onto a second line
 * looks like a mistake on a phone, and on a 1440px screen it was one.
 */
@Composable
fun TimerScreen(model: CurfewViewModel, onDone: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var minutes by remember { mutableIntStateOf(DEFAULT_MINUTES) }
    var strength by remember { mutableStateOf(Strength.Confirm) }
    val context = LocalContext.current
    var profile by remember { mutableStateOf<String?>(null) }
    // The profile a tap asked to lock, held while the accessibility question is answered.
    var pending by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<String?>(null) }

    // Whichever profile the user has, without making them choose on a fresh install where there is
    // only one. A missing profile is the one thing this screen cannot invent.
    val chosen = profile ?: state.profiles.firstOrNull()?.id
    val chosenProfile = state.profiles.firstOrNull { it.id == chosen }

    // The one gate every route to "start this timer" goes through.
    //
    // There are three of them — the primary button, the return from the accessibility switch, and
    // "Start without it" — and before this they disagreed: the primary button checked the four-hour
    // threshold and the other two did not, so "Start without it" would start the strongest lock in
    // the app from a twelve-hour dial with no confirmation at all. One function means a route added
    // later cannot quietly skip a check the others make.
    fun begin(id: String) {
        if (minutes >= LONG_MINUTES) {
            confirming = id
            return
        }
        model.startTimer(id, minutes * 60, listOfNotNull(strength.lock))
        onDone()
    }

    // Coming back from the accessibility switch, finish what the user asked for.
    //
    // This is the fix for the worst first-run experience in the app. "Turn it on" is the *primary*
    // button in that dialog, and all it did was open Settings — the timer was never started on the
    // way back, so a user who did exactly what they were asked ended up with no block, no receipt,
    // and a Now screen saying nothing was blocked. Declining ("Start without it") worked; complying
    // did not, which is the wrong way round.
    //
    // `strength` and `minutes` are read when resuming rather than captured, so a value the user
    // changed before leaving is the one that takes effect — and `begin` means the long-timer check
    // still applies on this route.
    LifecycleResumeEffect(pending) {
        val id = pending
        if (id != null && Grant.Accessibility.isGranted(context)) {
            pending = null
            begin(id)
        }
        onPauseOrDispose { }
    }

    Screen(spacing = 0.dp) {
        BackRow("Start now", onDone)

        Gap(6.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Draggable, one turn to the hour: the stepper is for "a bit longer", the ring is
            // for landing on the exact minute the meeting ends.
            DragDial(
                minutes = minutes,
                max = MAX_MINUTES,
                onChange = { minutes = it },
                diameter = 236.dp,
                stroke = 12.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DialNumber(spellDuration(minutes), size = 52)
                    Gap(6.dp)
                    Text("until ${endsAt(minutes)}", fontSize = 14.sp, color = Palette.Muted)
                }
            }

            Gap(14.dp)
            Text("drag the ring, or pick one", fontSize = 13.sp, color = Palette.Dim)

            // The presets, evenly spread: with the ring taking every value between them, a
            // stepper beside them was a third way to say the same thing, and the one nobody
            // reached for. Four lengths, each wide enough to hit without looking.
            Gap(14.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PRESETS.forEach { preset ->
                    Pill(
                        text = spellDuration(preset),
                        selected = minutes == preset,
                        onClick = { minutes = preset },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Gap(18.dp)
        if (state.profiles.isEmpty()) {
            DCard {
                Text(
                    "You have not set up anything to block yet. Make a profile on the Plan tab " +
                        "first — a timer needs to know what it is switching off.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Palette.Muted,
                )
            }
        } else {
            // One card, not a row of chips: which profile a timer uses is a single choice with a
            // current answer, and tapping it cycles to the next one rather than opening a screen.
            val index = state.profiles.indexOfFirst { it.id == chosen }.coerceAtLeast(0)
            DCard(padding = 15.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            profile = state.profiles[(index + 1) % state.profiles.size].id
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    val tint = Palette.ProfileColours[index % Palette.ProfileColours.size]
                    Glyph(tint, size = 34.dp) {
                        Text(
                            chosenProfile?.name?.take(1)?.uppercase().orEmpty(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = tint,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            chosenProfile?.name.orEmpty(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.Text,
                        )
                        Text(
                            if (state.profiles.size == 1) {
                                "your only profile"
                            } else {
                                "tap to use another profile"
                            },
                            fontSize = 12.sp,
                            color = Palette.Muted,
                        )
                    }
                }
            }
        }

        Gap(18.dp)
        SectionLabel("If you change your mind")
        Gap(10.dp)
        DCardFlush {
            Strength.entries.forEachIndexed { index, option ->
                if (index > 0) Rule()
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
                            .background(
                                if (strength == option) Palette.Accent else Palette.Raised,
                            ),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.Text,
                        )
                        Text(option.note, fontSize = 12.sp, color = Palette.Muted)
                    }
                }
            }
        }

        Gap(12.dp)
        PrimaryButton(
            text = "Lock it in for ${spellDuration(minutes)}",
            enabled = chosen != null,
            // Amber, because from the moment this is tapped the phone is in the state amber means
            // everywhere else in the app.
            colour = Palette.Live,
        ) {
            val id = chosen ?: return@PrimaryButton
            // The one moment the accessibility service is genuinely needed is the moment a block
            // starts, so that is where it is asked for. Asking on first launch instead taught
            // people to tap through a wizard; not asking at all let someone start a block that
            // then quietly enforced nothing, which is worse than either.
            if (!Grant.Accessibility.isGranted(context)) {
                pending = id
                return@PrimaryButton
            }
            begin(id)
        }
    }

    confirming?.let { id ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("That is ${spellDuration(minutes)}") },
            text = {
                Text(
                    if (strength.lock == Lock.Timer) {
                        "Nothing ends this early except an emergency pass. It runs until " +
                            "${endsAt(minutes)}. Start it?"
                    } else {
                        "It runs until ${endsAt(minutes)}. A long block is easy to set by " +
                            "accident on a dial, so this is the check. Start it?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    model.startTimer(id, minutes * 60, listOfNotNull(strength.lock))
                    onDone()
                }) { Text("Start it") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Go back") }
            },
        )
    }

    pending?.let { id ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("One switch first") },
            text = {
                Text(
                    "Curfew can only replace a blocked app if it is allowed to see which app is " +
                        "in front.\n\n" +
                        "Turn it on and this ${spellDuration(minutes)} block starts the moment you " +
                        "come back. Or start it now, and nothing will be blocked until the switch " +
                        "is on.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // `pending` is deliberately NOT cleared here. It is what the resume effect
                    // watches, and clearing it was the whole bug: the user was sent to Settings and
                    // the timer they had configured was forgotten in the same breath.
                    Grant.Accessibility.settingsIntent(context)?.let { intent ->
                        runCatching {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }) { Text("Turn it on") }
            },
            dismissButton = {
                // Left in on purpose. A blocker that refuses to start until a permission is
                // granted is a blocker people uninstall; one that starts, and says plainly that
                // it is not enforcing, is one they come back to and fix.
                TextButton(onClick = {
                    pending = null
                    begin(id)
                }) { Text("Start without it") }
            },
        )
    }
}

/** "1h 30m", not "90 minutes" and never "5400". */
internal fun spellDuration(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** The wall clock this timer would end at, so the length is also a time of day. */
private fun endsAt(minutes: Int): String {
    val time = java.time.LocalTime.now().plusMinutes(minutes.toLong())
    return "%02d:%02d".format(time.hour, time.minute)
}

/**
 * The row at the top of a screen that is not a tab.
 *
 * A chevron and a name, at the height of the status bar, because these screens are pushed onto the
 * tabs rather than replacing them and the way back has to be somewhere the thumb already is.
 */
@Composable
fun BackRow(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Go back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", fontSize = 26.sp, color = Palette.Text)
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
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
