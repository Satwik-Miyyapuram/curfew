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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import androidx.compose.ui.res.stringResource
import dev.curfew.app.R

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
    Math("Solve math", "A quick puzzle before it opens.", Lock.Challenge(ChallengeKind.MATH)),
    Typing("Type it out", "A sentence to copy before it opens.", Lock.Challenge(ChallengeKind.TYPING)),
    Locked("Until it ends", "Nothing ends this early. The 24-hour release is the way out.", Lock.Timer),
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
    // Whether the profile chooser is up.
    var pickingProfile by remember { mutableStateOf(false) }

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
        // ---------------------------------------------------------------------------------------
        // The order is strength, then time, then what it switches off — `docs/design.md` 5.2.
        //
        // This screen had it the other way round: the dial came first because it is the prettiest
        // thing here, which put the hardest question last. By the time a reader met "how hard should
        // this be to leave" they had already committed to a length. The dial is the nice part and it
        // is still the centrepiece; it is just no longer the first thing asked.
        // ---------------------------------------------------------------------------------------
        BackRow("Start a block", onDone)

        Gap(Space.s)
        SectionLabel("1 · How hard should this be to leave?")
        Gap(Space.s)
        // A strength the device cannot satisfy is not offered at all.
        //
        // `Lock.DeviceCredential` on a phone with no screen lock is **a lock with no exit**:
        // `Auth.prove` reports `false` because there is no keyguard to raise, the core then refuses
        // the end for an unmet condition, and the session runs to its end with the user unable to do
        // anything about it. The choice was offered anyway, because the enum is a list of strengths
        // and nothing connected it to whether the device could keep them. That is F-6 in the
        // interaction review, and the reason it is worth a comment is that a silent omission here
        // would be indistinguishable from a bug.
        val satisfiable = Strength.entries.filter {
            it != Strength.Credential || Auth.isAvailable(context)
        }
        DCardFlush {
            satisfiable.forEachIndexed { index, option ->
                if (index > 0) Rule()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dsn.MinTouch)
                        .clickable { strength = option }
                        .padding(Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    // A square mark, and the word beside it. Nothing round.
                    Box(
                        Modifier
                            .size(20.dp)
                            .background(if (strength == option) Palette.accent else Palette.raised, RoundedCornerShape(Dsn.CtlRadius)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (strength == option) {
                            Text("✓", style = Type.meta, color = Palette.surface)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label.uppercase(),
                            style = Type.button,
                            color = Palette.text,
                        )
                        Text(option.note, style = Type.meta, color = Palette.muted)
                    }
                }
            }
            // Said out loud, next to where it would have been. A missing option with no explanation
            // reads as a bug, and the user's next step — set a screen lock — is theirs to take.
            if (satisfiable.size != Strength.entries.size) {
                Rule()
                Text(
                    "Fingerprint or PIN is not offered, because this phone has no screen lock set. " +
                        "Curfew cannot ask for one that does not exist, and a lock nothing can open " +
                        "would hold you until it ran out.",
                    style = Type.meta,
                    color = Palette.muted,
                    modifier = Modifier.padding(Space.l),
                )
            }
        }

        Gap(Space.xl)
        SectionLabel("2 · How long?")
        Gap(Space.s)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Draggable, one turn to the hour: the stepper is for "a bit longer", the ring is for
            // landing on the exact minute the meeting ends.
            DragDial(
                minutes = minutes,
                max = MAX_MINUTES,
                onChange = { minutes = it },
                diameter = 236.dp,
                stroke = 12.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DialNumber(spellDuration(minutes))
                    Gap(Space.s)
                    Text(stringResource(R.string.timer_until, endsAt(state.now, minutes)), style = Type.body, color = Palette.muted)
                }
            }

            Gap(Space.l)
            Text("drag the ring, or pick one", style = Type.meta, color = Palette.dim)

            // The presets, evenly spread: with the ring taking every value between them, a stepper
            // beside them was a third way to say the same thing, and the one nobody reached for.
            Gap(Space.l)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
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

        // ---------------------------------------------------------------------------------------
        // Starting a block for a profile that already has one **extends it**, and says so.
        //
        // The core merges by profile on purpose (`session.rs`: `existing.lock.merge(&session.lock)`,
        // and the end time is the later of the two) because a running block is a promise and no act
        // of starting another may weaken it. That is right, and it is completely invisible: the
        // Start screen looked exactly the same whether or not something was already running, so
        // tapping it read as "a new block" while actually adding a lock to the existing one and
        // pushing its end further out. Locks appeared to accumulate by themselves.
        //
        // It is not refused, because extending a block is a thing people mean. It is *stated*.
        // ---------------------------------------------------------------------------------------
        val alreadyRunning = state.sessions.firstOrNull { it.profile == chosen }
        if (alreadyRunning != null) {
            Gap(Space.l)
            DCard(fill = Palette.warn.copy(alpha = 0.18f)) {
                SectionLabel("This profile is already blocked")
                Gap(Space.s)
                Text(
                    stringResource(R.string.timer_already_blocked_body),
                    style = Type.body,
                    color = Palette.text,
                )
            }
        }

        Gap(Space.xl)
        SectionLabel("3 · What does it switch off?")
        Gap(Space.s)
        if (state.profiles.isEmpty()) {
            DCard {
                Text(
                    "You have not set up anything to block yet. Make a profile on the Plan tab " +
                        "first — a timer needs to know what it is switching off.",
                    style = Type.meta,
                    color = Palette.muted,
                )
            }
        } else {
            // One card, not a row of chips: which profile a timer uses is a single choice with a
            // current answer, and tapping it cycles to the next one rather than opening a screen.
            val index = state.profiles.indexOfFirst { it.id == chosen }.coerceAtLeast(0)
            // **A chooser, not a rotator.**
            //
            // This row used to cycle to the next profile on every tap. With two profiles that is
            // fine. With fifteen it is unusable: the only way to reach the last one is to tap
            // through all the ones before it, and there is no way to see what the options *are* —
            // you find out by landing on them. The card still shows the current choice, because
            // that is the one thing the screen has to say at a glance; opening it now shows the
            // whole list.
            DCard(padding = 15.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dsn.MinTouch)
                        .clickable(
                            enabled = state.profiles.size > 1,
                            onClickLabel = "Choose a different profile",
                        ) { pickingProfile = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    val tint = Palette.profileColours[index % Palette.profileColours.size]
                    Glyph(tint, size = 36.dp) {
                        Text(
                            chosenProfile?.name?.take(1)?.uppercase().orEmpty(),
                            style = Type.body,
                            fontWeight = FontWeight.Black,
                            color = Palette.surface,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            chosenProfile?.name.orEmpty(),
                            style = Type.body,
                            fontWeight = FontWeight.Bold,
                            color = Palette.text,
                        )
                        Text(
                            when (state.profiles.size) {
                                1 -> "your only profile"
                                2 -> "tap to switch between your 2 profiles"
                                else -> "tap to choose from ${state.profiles.size} profiles"
                            },
                            style = Type.meta,
                            color = Palette.muted,
                        )
                    }
                    // The affordance that says this opens something, rather than doing something
                    // on the spot.
                    Text("›", style = Type.h2, fontWeight = FontWeight.Normal, color = Palette.dim)
                }
            }
        }

        Gap(Space.m)
        // The commit, and the one place in the app that celebrates.
        //
        // This is the moment the user did something hard on purpose: they picked a strength, chose
        // a length, and pressed the button that hands the decision over to the app. Everything
        // before it is negotiation; this is the promise. A shockwave leaves the button and a burst
        // of squares goes up — and then the app is completely still again, because a celebration
        // that lingers is a celebration that nags.
        val committed = rememberTrigger()
        Box {
            PrimaryButton(
                text = "Lock it in for ${spellDuration(minutes)}",
                enabled = chosen != null,
                // Amber, because from the moment this is tapped the phone is in the state amber
                // means everywhere else in the app.
                colour = Palette.live,
            ) {
                val id = chosen ?: return@PrimaryButton
                // The one moment the accessibility service is genuinely needed is the moment a
                // block starts, so that is where it is asked for. Asking on first launch instead
                // taught people to tap through a wizard; not asking at all let someone start a
                // block that then quietly enforced nothing, which is worse than either.
                if (!Grant.Accessibility.isGranted(context)) {
                    pending = id
                    return@PrimaryButton
                }
                committed.fire()
                begin(id)
            }
            Shockwave(trigger = committed, colour = Palette.live)
            Burst(trigger = committed, colour = Palette.live, spread = 140f)
        }
    }

    if (pickingProfile) {
        // Every profile, in one list, scrollable.
        //
        // A `DSheet` like every other form here, and the rows are the same id/name/`Switch`-free row
        // the Plan screen uses, so a profile is recognisable in both places. The current one is
        // marked in the fill *and* in the word, per section 8.
        DSheet(
            title = "Which profile?",
            sub = if (state.profiles.size == 1) {
                "You have one profile."
            } else {
                "${state.profiles.size} profiles. The one you pick is what this block switches off."
            },
            onDismiss = { pickingProfile = false },
            confirm = "Done",
            confirmEnabled = true,
            onConfirm = { pickingProfile = false },
        ) {
            SheetSection("Profiles") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.profiles.forEachIndexed { i, option ->
                        if (i > 0) Hairline()
                        val on = option.id == chosen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Dsn.MinTouch)
                                .background(if (on) Palette.selected else Palette.surface)
                                .clickable {
                                    profile = option.id
                                    pickingProfile = false
                                }
                                .padding(vertical = Space.m)
                                .semantics {
                                    selected = on
                                    role = Role.RadioButton
                                    contentDescription =
                                        option.name + if (on) ", selected" else ""
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.m),
                        ) {
                            Text(
                                if (on) "✓" else "·",
                                style = Type.meta,
                                fontWeight = FontWeight.Black,
                                color = if (on) Palette.accent else Palette.dim,
                            )
                            Text(
                                option.name,
                                style = Type.body,
                                fontWeight = FontWeight.Black,
                                color = Palette.text,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    confirming?.let { id ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("That is ${spellDuration(minutes)}") },
            text = {
                val ends = endsAt(state.now, minutes)
                Text(
                    if (strength.lock == Lock.Timer) {
                        stringResource(R.string.timer_confirm_timer, ends)
                    } else {
                        stringResource(R.string.timer_confirm_other, ends)
                    },
                )
            },
            confirmButton = {
                TextButton(shape = Capsule, onClick = {
                    confirming = null
                    model.startTimer(id, minutes * 60, listOfNotNull(strength.lock))
                    onDone()
                }) { Text("Start it") }
            },
            dismissButton = {
                TextButton(shape = Capsule, onClick = { confirming = null }) { Text("Go back") }
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
                TextButton(shape = Capsule, onClick = {
                    // `pending` is deliberately NOT cleared here. It is what the resume effect
                    // watches, and clearing it was the whole bug: the user was sent to Settings and
                    // the timer they had configured was forgotten in the same breath.
                    //
                    // And it goes through the shared path, which falls back to App info — this used to
                    // swallow the failure, so on a build without the page the tap did nothing at all.
                    openGrantPage(context, Grant.Accessibility)
                }) { Text("Turn it on") }
            },
            dismissButton = {
                // Left in on purpose. A blocker that refuses to start until a permission is
                // granted is a blocker people uninstall; one that starts, and says plainly that
                // it is not enforcing, is one they come back to and fix.
                TextButton(shape = Capsule, onClick = {
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
/** [now] is the ticking clock from state, so the shown end time moves while the screen is open. */
private fun endsAt(now: Long, minutes: Int): String {
    val seconds = if (now > 0) now else System.currentTimeMillis() / 1000
    val time = java.time.Instant.ofEpochSecond(seconds).atZone(java.time.ZoneId.systemDefault())
        .toLocalTime().plusMinutes(minutes.toLong())
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
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Box(
            modifier = Modifier
                .size(Dsn.MinTouch)
                .background(Palette.raised)
                .clickable(onClickLabel = "Go back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", style = Type.h1, fontWeight = FontWeight.Normal, color = Palette.text)
        }
        Text(title, style = Type.h3, color = Palette.text)
    }
}
