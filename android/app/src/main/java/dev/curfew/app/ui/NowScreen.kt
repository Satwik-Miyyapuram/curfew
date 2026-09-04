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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.app.data.Downtime
import dev.curfew.policy.Lock
import dev.curfew.policy.Refusal
import dev.curfew.policy.Session

/**
 * What is running right now, and the only place a session can be ended.
 *
 * Ending is deliberately not one tap: the button asks the core, the core refuses if the lock is not
 * satisfied, and this screen then says exactly what is missing and offers the one thing that would
 * satisfy it. Nothing here decides on the core's behalf.
 */
@Composable
fun NowScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity

    // The conditions this screen can satisfy are gathered one at a time, in a fixed order, and
    // handed to the core together. The core is still the judge: it refuses if the set is short,
    // and the refusal dialog is what the user sees when it does.
    var pending by remember { mutableStateOf<PendingEnd?>(null) }

    fun finish(session: Session, satisfied: List<Lock>) {
        pending = null
        val credential = session.lock.conditions.filterIsInstance<Lock.DeviceCredential>()
        if (credential.isNotEmpty() && activity != null) {
            Auth.prove(
                activity,
                title = "End ${session.profile}",
                subtitle = "Confirm it is you.",
            ) { proven -> model.endSession(session, satisfied + proven) }
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            if (state.isEnforcing) "Curfew is enforcing" else "Nothing is running",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            if (state.isEnforcing) {
                "${state.sessions.size} session${if (state.sessions.size == 1) "" else "s"} active."
            } else {
                "No profile is active. Schedules will start one when their time comes."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )

        state.downtime?.let { downtime ->
            DowntimeBanner(downtime = downtime, onDismiss = model::dismissDowntime)
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.sessions, key = { it.id }) { session ->
                SessionCard(
                    session = session,
                    now = state.now,
                    onEnd = { end(session) },
                    onRelease = { model.requestRelease(session) },
                )
            }
            if (state.sessions.isEmpty()) {
                item {
                    TextButton(onClick = model::reconcileNow) { Text("Check the schedules now") }
                }
            }
        }
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

@Composable
private fun SessionCard(
    session: Session,
    now: Long,
    onEnd: () -> Unit,
    onRelease: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                session.profile,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(describeSource(session.source), style = MaterialTheme.typography.bodySmall)

            session.lock.endsAt?.let { endsAt ->
                Text(
                    "Ends ${relative(endsAt, now)} (${clockTime(endsAt)})",
                    style = MaterialTheme.typography.bodyMedium,
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

/** An end that is waiting on the user to work through a challenge. */
private data class PendingEnd(
    val session: Session,
    val lock: Lock.Challenge,
    val challenge: Challenge,
)
