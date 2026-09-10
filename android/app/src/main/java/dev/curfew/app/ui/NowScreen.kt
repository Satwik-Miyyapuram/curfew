package dev.curfew.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.app.data.ClockTamper
import dev.curfew.app.data.Downtime
import dev.curfew.policy.Lock
import dev.curfew.policy.PassRefusal
import dev.curfew.policy.Refusal
import dev.curfew.policy.Session
import dev.curfew.policy.Stats

/**
 * What is running right now, and the only place a session can be ended.
 *
 * Ending is deliberately not one tap: the button asks the core, the core refuses if the lock is not
 * satisfied, and this screen then says exactly what is missing and offers the one thing that would
 * satisfy it. Nothing here decides on the core's behalf.
 */
@Composable
fun NowScreen(model: CurfewViewModel, onStartTimer: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val mode by model.mode.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity

    // The conditions this screen can satisfy are gathered one at a time, in a fixed order, and
    // handed to the core together. The core is still the judge: it refuses if the set is short,
    // and the refusal dialog is what the user sees when it does.
    var pending by remember { mutableStateOf<PendingEnd?>(null) }

    // Spending a pass is asked about first, and asked about here rather than in the view model:
    // it takes something scarce, shared with every paired device, and impossible to give back.
    var confirmingPass by remember { mutableStateOf<Session?>(null) }

    // Giving a peer its release is the same kind of act, and asked about the same way: the other
    // device opens the moment this one says yes, and there is no way to say no afterwards.
    var confirmingRelease by remember { mutableStateOf<String?>(null) }

    // The session a tag is being presented to, if any. The tag itself lives inside the dialog and
    // is never lifted into this state, so a recomposition cannot leave it lying around.
    var presenting by remember { mutableStateOf<Session?>(null) }

    fun finish(session: Session, satisfied: List<Lock>) {
        pending = null
        val credential = session.lock.conditions.filterIsInstance<Lock.DeviceCredential>()
        if (credential.isNotEmpty() && activity != null) {
            Auth.prove(
                activity,
                title = "End ${session.profile}",
                subtitle = "Confirm it is you.",
            ) { proven ->
                // A cancelled prompt still goes to the core, which refuses and says what is
                // missing. Deciding here that it would have been refused would be this screen
                // making the core's decision for it.
                if (proven) model.endWithCredential(session, satisfied)
                else model.endSession(session, satisfied)
            }
        } else {
            model.endSession(session, satisfied)
        }
    }

    fun end(session: Session) {
        val challenge = session.lock.conditions.filterIsInstance<Lock.Challenge>().firstOrNull()
        if (challenge == null) {
            finish(session, emptyList())
        } else {
            pending = PendingEnd(session, challenge, Challenge.generate(challenge.challenge))
        }
    }

    val live = state.sessions.firstOrNull()
    val liveName = live?.let { session ->
        state.profiles.firstOrNull { it.id == session.profile }?.name ?: session.profile
    }

    Screen(spacing = 0.dp) {
        if (live != null) {
            // Amber is reserved for this: a block is running right now. It appears above the
            // heading because the state of the phone is the first thing this screen is for.
            Pill("Blocking now", tint = Palette.Live)
            Gap(14.dp)
            Title(liveName.orEmpty())
            Sub(describeSource(live.source))
        } else {
            Title("Nothing is blocked" + '\n' + "right now.")
            Sub(
                if (state.weekly.isEmpty() && state.calendarRules.isEmpty()) {
                    "Nothing is set to start on its own. Start a block by hand whenever you want."
                } else {
                    "Curfew is watching your schedule. It will start one when its time comes."
                },
            )
        }

        Gap(if (live == null) 26.dp else 22.dp)

        DCard(padding = 22.dp) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (live != null) {
                    val endsAt = live.lock.endsAt
                    Dial(
                        fraction = remaining(live, state.now),
                        diameter = 196.dp,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            DialNumber(endsAt?.let { countdown(it, state.now) } ?: "—")
                            Text(
                                endsAt?.let { "left · ends ${clockOf(it)}" } ?: "no end time",
                                fontSize = 14.sp,
                                color = Palette.Muted,
                            )
                        }
                    }
                } else {
                    Dial(fraction = 0f, diameter = 186.dp, stroke = 10.dp) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Next up", fontSize = 15.sp, color = Palette.Muted)
                            Text(
                                nextStart(state.weekly, state.now) ?: "—",
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-1).sp,
                                color = Palette.Text,
                            )
                            Text(
                                nextNote(
                                    state.weekly,
                                    state.profiles.associate { it.id to it.name },
                                    state.now,
                                ),
                                fontSize = 13.sp,
                                color = Palette.Dim,
                            )
                        }
                    }
                    PrimaryButton("Start a block now", onClick = onStartTimer)
                }
            }
        }

        Gap(14.dp)
        GivenBackCard(stats = state.stats, detailed = mode.isPower)

        state.downtime?.let { downtime ->
            Gap(14.dp)
            DowntimeBanner(downtime = downtime, onDismiss = model::dismissDowntime)
        }

        state.clockTamper?.let { tamper ->
            Gap(14.dp)
            ClockTamperBanner(tamper = tamper, onDismiss = model::dismissClockTamper)
        }

        state.sessions.forEach { session ->
            Gap(14.dp)
            SessionCard(
                session = session,
                name = state.profiles.firstOrNull { it.id == session.profile }?.name
                    ?: session.profile,
                now = state.now,
                passesLeft = state.passesLeft,
                passRefusal = state.passRefusal,
                onEnd = { end(session) },
                onRelease = { model.requestRelease(session) },
                onEmergency = { confirmingPass = session },
                onPresentTag = { presenting = session },
            )
        }

        // Sessions another device is waiting on this one for. Usually not in the list above: the
        // lock is over there, and this device is only the key.
        if (state.releasable.isNotEmpty()) {
            Gap(14.dp)
            ReleaseCard(sessions = state.releasable, onRelease = { confirmingRelease = it })
        }

        if (state.sessions.isEmpty()) {
            Gap(14.dp)
            GhostButton("Check the schedules now", colour = Palette.Muted, onClick = model::reconcileNow)
        }

        // Power mode adds the timeline. Simple mode deliberately does not have it: "something will
        // start later" is the whole of what a simple user needs, and a list of the next four
        // activations with their exact windows is the kind of detail that makes a calm screen look
        // like a control panel.
        if (mode.isPower) {
            Gap(20.dp)
            SectionLabel("Upcoming · next 24h")
            Gap(10.dp)
            val soon = state.upcoming
                .filter { it.start > state.now }
                .sortedBy { it.start }
                .take(4)
            DCardFlush {
                if (soon.isEmpty()) {
                    Text(
                        "Nothing scheduled in the next day.",
                        fontSize = 13.sp,
                        color = Palette.Muted,
                        modifier = Modifier.padding(Dsn.CardPad),
                    )
                }
                soon.forEachIndexed { index, activation ->
                    if (index > 0) Rule()
                    val name = state.profiles.firstOrNull { it.id == activation.profile }?.name
                        ?: activation.profile
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Palette.Text,
                            )
                            Text(
                                describeSource(activation.source),
                                fontSize = 12.sp,
                                color = Palette.Dim,
                            )
                        }
                        Text(
                            "${clockOf(activation.start)}–${clockOf(activation.end)}",
                            fontSize = 13.sp,
                            color = Palette.Muted,
                        )
                    }
                }
            }

            Gap(12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    if (state.downtime == null) "Ticks on time" else "Missed a stretch",
                    tint = if (state.downtime == null) Palette.Ok else Palette.Live,
                )
                Pill(
                    when (state.sync.active.size) {
                        0 -> "No peers"
                        1 -> "1 peer"
                        else -> "${state.sync.active.size} peers"
                    },
                )
                Pill("${state.audit.size} logged")
            }
        }
    }

    confirmingPass?.let { session ->
        AlertDialog(
            onDismissRequest = { confirmingPass = null },
            title = { Text("Use an emergency pass?") },
            text = {
                Text(
                    "This ends ${session.profile} now. It counts against your ration on every " +
                        "paired device, it is written into your history, and it cannot be given " +
                        "back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = session
                    confirmingPass = null
                    model.spendPass(chosen)
                }) { Text("Use one") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingPass = null }) { Text("Keep it") }
            },
        )
    }

    confirmingRelease?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmingRelease = null },
            title = { Text("Let the other device out?") },
            text = {
                Text(
                    "Your other device is holding a session that only this one can end. Saying " +
                        "yes ends it there as soon as the two devices next talk, and there is no " +
                        "way to take it back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = id
                    confirmingRelease = null
                    model.releasePeer(chosen)
                }) { Text("Let it out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRelease = null }) { Text("Not yet") }
            },
        )
    }

    presenting?.let { session ->
        TagDialog(
            profile = session.profile,
            activity = activity,
            onDismiss = { presenting = null },
            onPresent = { payload ->
                presenting = null
                model.scanToken(session, payload)
            },
        )
    }

    pending?.let { p ->
        ChallengeDialog(
            challenge = p.challenge,
            onDismiss = { pending = null },
            onSatisfied = { finish(p.session, listOf(p.lock)) },
        )
    }

    state.refusal?.let { refusal ->
        RefusalDialog(refusal = refusal, now = state.now, onDismiss = model::dismissRefusal)
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissMessage,
            confirmButton = { TextButton(onClick = model::dismissMessage) { Text("OK") } },
            text = { Text(message) },
        )
    }
}

/**
 * The banner that admits Curfew was not watching.
 *
 * Android lets an OEM battery manager kill a foreground service, and no app can stop it. What an
 * honest blocker can do is refuse to paper over the hole: say when it happened, say how long, and
 * leave it on screen until the person has read it.
 */
/**
 * What the blocking has actually bought, on the home screen.
 *
 * The point of a blocker is not that it says no; it is the hours on the other side of the no. Those
 * hours were only ever visible on a separate screen behind a tab, which meant the one number that
 * says "this is working" was the one number nobody saw. It is stated as time the phone stayed shut
 * rather than "time saved", because that is the part Curfew can actually vouch for — the phone was
 * locked for this long, on purpose, because you asked it to be.
 */
@Composable
private fun GivenBackCard(stats: Stats, detailed: Boolean) {
    val today = stats.days.lastOrNull()?.blockedSeconds ?: 0
    val week = stats.days.takeLast(7).sumOf { it.blockedSeconds.toLong() }
    if (today == 0 && week == 0L && stats.currentStreak == 0) return
    DCard(padding = 20.dp) {
        Text(
            if (today > 0) "${duration(today)} away from the phone today"
            else "Nothing blocked yet today",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.Text,
        )
        Gap(6.dp)
        Text(
            buildString {
                append(duration(week.toInt()))
                append(" this week")
                if (stats.currentStreak > 1) {
                    append(" · ")
                    append(stats.currentStreak)
                    append(" days in a row")
                }
            },
            fontSize = 14.sp,
            color = Palette.Ok,
        )
        if (detailed) {
            Gap(4.dp)
            Text(
                "${stats.totalSessions} blocks kept, longest run ${stats.longestStreak} days.",
                fontSize = 13.sp,
                color = Palette.Dim,
            )
        }
    }
}

@Composable
private fun DowntimeBanner(downtime: Downtime, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics { contentDescription = describeDowntime(downtime) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (downtime.backwards) "The clock moved backwards" else "Curfew was not running",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                describeDowntime(downtime),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                Text("Got it")
            }
        }
    }
}

/**
 * The banner that says a clock change was refused.
 *
 * Moving the system clock is the cheapest bypass there is, so it is refused silently by the core —
 * but not invisibly. Someone whose clock really was wrong deserves to know why the app disagrees
 * with the time on their lock screen, and someone who just tried it deserves to be told it failed.
 */
@Composable
private fun ClockTamperBanner(tamper: ClockTamper, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics { contentDescription = describeClockTamper(tamper) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (tamper.forward) "The clock jumped forward" else "The clock jumped backwards",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                describeClockTamper(tamper),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                Text("Got it")
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    name: String,
    now: Long,
    passesLeft: Int,
    passRefusal: PassRefusal?,
    onEnd: () -> Unit,
    onRelease: () -> Unit,
    onEmergency: () -> Unit,
    onPresentTag: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
    ) {
        Column(
            modifier = Modifier
                // A running block is the one state in this app that should look like something is
                // happening. A flat card with a coloured edge said it the way a table row says it;
                // the amber it warms towards is the same amber the notification and the tile use,
                // so the colour means one thing everywhere.
                .background(
                    Brush.linearGradient(
                        0f to Palette.Live.copy(alpha = 0.16f),
                        0.45f to Palette.Live.copy(alpha = 0.04f),
                        1f to Palette.Surface,
                    ),
                )
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Palette.Live)
                        .border(4.dp, Palette.Live.copy(alpha = 0.18f), CircleShape),
                )
                Text(
                    "ENFORCING",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Live,
                )
            }
            Text(
                // The name, never the id. Which profile is running is the user's own word for it.
                name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                describeSource(session.source),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )

            session.lock.endsAt?.let { endsAt ->
                Text(
                    "Ends ${relative(endsAt, now)} (${clockTime(endsAt)})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Live,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        // Screen readers should hear the whole sentence, not a bare clock time.
                        .semantics {
                            contentDescription =
                                "${session.profile} ends ${relative(endsAt, now)}"
                        },
                )
            }

            if (session.lock.isLocked) {
                Text(
                    "Locked: needs " +
                        session.lock.conditions.joinToString(", ") { describeLock(it) } + ".",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Why the hatch is not on this card. Said only where someone would look for it, and
            // never for a hatch nobody switched on: that one is not missing, it is unwanted.
            if (session.lock.isLocked && passesLeft == 0 && passRefusal != null &&
                passRefusal !is PassRefusal.Disabled
            ) {
                Text(
                    describePassRefusal(passRefusal, now),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // A restart is the one condition nothing on this screen can offer a button for, so it
            // is spelled out instead. Said as the plain instruction it is, because a lock whose way
            // out is invisible is indistinguishable from one with no way out at all.
            if (session.lock.conditions.any { it is Lock.RestartRequired }) {
                Text(
                    "Restart this device, then come back and end it. Force-stopping Curfew or " +
                        "reopening it is not a restart.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            session.lock.delayedReleaseAt?.let { at ->
                Text(
                    "A release you asked for lands ${relative(at, now)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Several sessions can be on screen, so each button says which one it ends: a
                // list of identical "End now" buttons is unusable with a screen reader.
                Button(
                    onClick = onEnd,
                    modifier = Modifier.semantics {
                        contentDescription = "End ${session.profile} now"
                    },
                ) { Text("End now") }
                // Only offered when there is no release already pending: asking twice must never
                // become a way to move the landing time closer.
                if (session.lock.isLocked && session.lock.delayedReleaseAt == null) {
                    TextButton(
                        onClick = onRelease,
                        modifier = Modifier.semantics {
                            contentDescription = "Ask to end ${session.profile} in 24 hours"
                        },
                    ) { Text("Ask to end in 24 hours") }
                }
                // Offered only on a session nothing else here can end, and only when there is one
                // to spend. Next to an unlocked session it would teach people to reach for the
                // scarce thing first, which is exactly backwards.
                // Offered only when the lock actually names a tag. A field for one otherwise is
                // an invitation to hunt for a tag that would open nothing.
                if (session.lock.conditions.any { it is Lock.Token }) {
                    TextButton(
                        onClick = onPresentTag,
                        modifier = Modifier.semantics {
                            contentDescription = "Present a tag for ${session.profile}"
                        },
                    ) { Text("Present a tag") }
                }
                if (session.lock.isLocked && passesLeft > 0) {
                    TextButton(
                        onClick = onEmergency,
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Use an emergency pass on ${session.profile}, $passesLeft left"
                        },
                    ) { Text("Emergency pass ($passesLeft)") }
                }
            }
        }
    }
}

/**
 * The core's refusal, said plainly.
 *
 * This dialog exists because "no" without a reason is what makes people fight a blocker. It names
 * what is missing and when the wait ends, and it does not offer a way around either.
 */
@Composable
private fun RefusalDialog(refusal: Refusal, now: Long, onDismiss: () -> Unit) {
    val text = when (refusal) {
        is Refusal.NotRunning -> "That session is not running any more."
        is Refusal.Locked -> buildString {
            append("Still locked. It needs ")
            append(refusal.missing.joinToString(", ") { describeLock(it) })
            append(".")
            refusal.endsAt?.let { append("\n\nIt ends on its own ${relative(it, now)}.") }
            refusal.delayedReleaseAt?.let {
                append("\n\nThe release you asked for lands ${relative(it, now)}.")
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Not yet") },
        text = { Text(text) },
    )
}

/**
 * The card that appears when another device is waiting on this one.
 *
 * It shows up on its own, without anything happening here, because the lock it opens is on the
 * other device. Only ids are shown: the profile name lives over there, and guessing at it would be
 * worse than naming the session plainly.
 */
@Composable
private fun ReleaseCard(sessions: List<String>, onRelease: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Waiting on you",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Another of your devices has a session only this one can end.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            sessions.forEach { id ->
                TextButton(
                    onClick = { onRelease(id) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { contentDescription = "Let session $id out" },
                ) { Text("Let $id out") }
            }
        }
    }
}

/**
 * Presenting a physical tag, by tapping it or by typing what is written on it.
 *
 * Typing is offered next to tapping rather than as a fallback for old phones: a tag whose only
 * reader is the phone it locks is a tag that a broken NFC chip turns into no way out at all. What
 * is typed goes straight to the core and is not kept here.
 */
@Composable
private fun TagDialog(
    profile: String,
    activity: FragmentActivity?,
    onDismiss: () -> Unit,
    onPresent: (String) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val present by rememberUpdatedState(onPresent)

    // The radio is on for exactly as long as this dialog is, and the reader is torn down on the way
    // out whichever way the dialog closes.
    if (activity != null) {
        DisposableEffect(activity) {
            // The reader callback arrives on a binder thread; everything it touches — the dialog's
            // own state, the view model — belongs to the main one.
            val stop = Tags.listen(activity) { payload ->
                activity.runOnUiThread { present(payload) }
            }
            onDispose { stop() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Present the tag") },
        text = {
            Column {
                Text(
                    if (activity != null && Tags.isAvailable(activity)) {
                        "Hold the tag against the back of the phone, or type what is on it."
                    } else {
                        // Said plainly rather than hidden: someone whose NFC is switched off should
                        // know why tapping is doing nothing.
                        "This phone is not reading tags right now. Type what is on it instead."
                    },
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text("Tag") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .semantics { contentDescription = "The tag that ends $profile" },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPresent(typed.trim()) },
                enabled = typed.isNotBlank(),
            ) { Text("Present") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** An end that is waiting on the user to work through a challenge. */
private data class PendingEnd(
    val session: Session,
    val lock: Lock.Challenge,
    val challenge: Challenge,
)

/**
 * How much of a running block is left, as a fraction of the whole of it.
 *
 * A session with no end time draws a full ring rather than an empty one: it is not almost over,
 * it is indefinite, and an empty ring would read as "nearly done".
 */
private fun remaining(session: Session, now: Long): Float {
    val ends = session.lock.endsAt ?: return 1f
    val started = session.startedAt
    val whole = (ends - started).coerceAtLeast(1)
    return ((ends - now).toFloat() / whole.toFloat()).coerceIn(0f, 1f)
}

/**
 * "1:12", the way a clock left-of-the-colon counts down. Seconds only under a minute.
 *
 * Every instant in this app is an epoch SECOND — the core writes them, `state.now` is one, and
 * `Format.clockTime` reads them that way. Dividing by a thousand here turned twenty-five minutes
 * into "1s" while the session card two inches below said "Ends in 24 min".
 */
private fun countdown(endsAt: Long, now: Long): String {
    val left = (endsAt - now).coerceAtLeast(0)
    val hours = left / 3600
    val minutes = (left % 3600) / 60
    return when {
        hours > 0 -> "$hours:%02d".format(minutes)
        minutes > 0 -> "${minutes}m"
        else -> "${left}s"
    }
}

/** An epoch second as a wall clock, in whatever zone the phone is in. */
private fun clockOf(epochSeconds: Long): String {
    val time = java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

/** The earliest start among the weekly windows, as a clock face, or null when there are none. */
/**
 * The schedule that will start next, or null when nothing is scheduled.
 *
 * "Next" is measured forward from right now and wraps past midnight, so at 22:00 a window that
 * starts at 07:00 is nine hours away rather than fifteen hours behind. Paused schedules are left
 * out: a screen that counts down to a window which has been switched off is telling the user
 * something that will not happen.
 */
private fun nextWeekly(
    weekly: List<dev.curfew.policy.WeeklySchedule>,
    now: Long,
): dev.curfew.policy.WeeklySchedule? {
    val minuteNow = minuteOfDay(now)
    return weekly.filter { it.enabled }
        .minByOrNull { (it.startMinute - minuteNow + MINUTES_A_DAY) % MINUTES_A_DAY }
}

private fun nextStart(weekly: List<dev.curfew.policy.WeeklySchedule>, now: Long): String? =
    nextWeekly(weekly, now)
        ?.let { "%02d:%02d".format(it.startMinute / 60, it.startMinute % 60) }

/** "Deep work · 2h" under the next start, or the reason there is nothing to say. */
private fun nextNote(
    weekly: List<dev.curfew.policy.WeeklySchedule>,
    names: Map<String, String>,
    now: Long,
): String {
    val next = nextWeekly(weekly, now) ?: return "nothing scheduled"
    // An end at or before the start means the window runs into the next day, the way the core
    // reads it. Subtracting the two directly made every overnight window read as "0m".
    val length = ((next.endMinute - next.startMinute + MINUTES_A_DAY - 1) % MINUTES_A_DAY) + 1
    val spelled = when {
        length >= 60 && length % 60 == 0 -> "${length / 60}h"
        length >= 60 -> "${length / 60}h ${length % 60}m"
        else -> "${length}m"
    }
    return "${names[next.profile] ?: next.profile} · $spelled"
}

/** Local minutes since midnight for an epoch-second instant. */
private fun minuteOfDay(now: Long): Int =
    java.time.Instant.ofEpochSecond(now)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
        .let { it.hour * 60 + it.minute }

private const val MINUTES_A_DAY = 24 * 60
