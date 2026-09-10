package dev.curfew.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The one screen that is about the app rather than about the phone.
 *
 * It leads with the Simple/Power switch, because that switch changes what every other screen looks
 * like and a user who cannot find it is stuck in whichever half of the app suits them less.
 *
 * Under it is the only question this screen really has to answer: **can Curfew actually enforce
 * anything right now**. That is a list of what it can do with a tick beside it, and a Fix button
 * beside anything it cannot — not a wall of permission names, and not something filed under an
 * advanced heading, because a permission Curfew is missing is a block that is not going to happen.
 *
 * Below that are the screens the tab bar has no room for. There is no beginner/expert switch: an
 * app that hides half of itself behind a mode makes the reader wonder what else it is hiding, and
 * every screen here is one tap away regardless.
 */
@Composable
fun SettingsScreen(model: CurfewViewModel, onOpen: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Two counts, because the card above lists every permission and this line summarises that
    // same list: counting only the required ones said "one permission is missing" under a card
    // showing three red marks, and the reader believed the card.
    val missing = state.grants.count { !it.granted }
    val blocking = state.grants.count { it.grant.required && !it.granted }

    Screen(spacing = 0.dp) {
        Gap(14.dp)
        Title("Settings", size = 24)
        Gap(16.dp)

        Gap(18.dp)
        SectionLabel("Curfew can enforce")
        Gap(10.dp)
        DCardFlush {
            state.grants.forEachIndexed { index, entry ->
                if (index > 0) Rule()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 17.dp, vertical = 15.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (entry.granted) {
                                "${entry.grant.title}, allowed"
                            } else {
                                "${entry.grant.title}, not allowed. ${entry.grant.cost}"
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Mark(entry.granted)
                    Column(Modifier.weight(1f)) {
                        Text(entry.grant.title, fontSize = 15.sp, color = Palette.Text)
                        if (!entry.granted) {
                            Text(
                                entry.grant.cost,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = Palette.Muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (!entry.granted) {
                        // The button goes straight to the system page for this one permission.
                        // Sending the user to a Health screen to press a second button was the
                        // longest way round to the only thing they came here to do.
                        Fix {
                            val permission = entry.grant.runtimePermission()
                            val settings = entry.grant.settingsIntent(context)
                            when {
                                permission != null -> requestRuntimePermission(context, permission)
                                settings != null -> context.startActivity(
                                    settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    }
                }
            }
        }

        Gap(18.dp)
        SectionLabel("Syncing")
        Gap(10.dp)
        DCard(padding = 18.dp) {
            // Syncing used to be visible only on the Devices screen, which Simple mode never
            // reached: a user whose blocks were following them between two devices had no way of
            // seeing that this was happening, or of making it happen now. There is no "last
            // synced" clock to show — devices talk when they are in earshot of each other, not on
            // a schedule — so it says what is actually true at this moment.
            Text(
                when {
                    !state.sync.available -> "Syncing is not set up on this device."
                    state.sync.active.isEmpty() -> "No other device paired yet."
                    !state.sync.running -> "Paired, but not listening right now."
                    state.sync.nearby.isEmpty() ->
                        "${state.sync.active.size} device(s) paired. None in earshot right now."
                    else ->
                        "${state.sync.nearby.size} of your ${state.sync.active.size} device(s) in " +
                            "earshot. Blocks follow you between them."
                },
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = if (state.sync.running) Palette.Muted else Palette.Live,
            )
            state.sync.error?.let { problem ->
                Gap(6.dp)
                Text(problem, fontSize = 13.sp, lineHeight = 20.sp, color = Palette.Bad)
            }
            if (state.sync.available) {
                Gap(12.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(text = "Sync now", onClick = model::syncNow)
                    GhostButton(text = "Devices") { onOpen(Routes.DEVICES) }
                }
            }
        }

        Gap(18.dp)
        SectionLabel("The rest of Curfew")
        Gap(10.dp)
        DCardFlush {
            Entry(
                title = "Is Curfew working",
                note = when {
                    missing == 0 -> "Everything it needs, it has."
                    // Everything still missing is one Curfew cannot work without.
                    missing == blocking && missing == 1 ->
                        "One permission is missing. Nothing is blocked without it."
                    missing == blocking ->
                        "$missing permissions are missing. Nothing is blocked without them."
                    // Some of what is missing only makes Curfew harder to escape, not able to run.
                    blocking > 0 ->
                        "$missing permissions are missing" +
                            if (blocking == 1) {
                                ", and one of them has to be allowed before anything is blocked."
                            } else {
                                ", and $blocking of them have to be allowed before anything is " +
                                    "blocked."
                            }
                    missing == 1 -> "One permission is missing. Curfew still blocks without it."
                    else -> "$missing permissions are missing. Curfew still blocks without them."
                },
                warn = missing > 0,
            ) { onOpen(Routes.HEALTH) }
            Rule()
            Entry(
                title = "Your calendar",
                note = if (state.calendarGranted) {
                    "${state.calendarEvents.size} events read, over the next eight weeks."
                } else {
                    "Not connected. Blocks from your calendar will not start."
                },
                warn = !state.calendarGranted,
            ) { onOpen(Routes.CALENDAR) }
            Rule()
            Entry(
                title = "Where your time went",
                note = "Time and opens, per app, for as long as Curfew has been watching.",
            ) { onOpen(Routes.USAGE) }
            Rule()
            Entry(
                title = "Your other devices",
                note = if (state.sync.active.isEmpty()) {
                    "No other device paired yet."
                } else {
                    "${state.sync.active.size} paired. Blocks follow you between them."
                },
            ) { onOpen(Routes.DEVICES) }
        }

        Gap(18.dp)
        DCard(padding = 16.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("⛨", fontSize = 17.sp, color = Palette.Muted)
                Text(
                    "Curfew has no internet permission at all. Your devices sync directly to " +
                        "each other — no account, no server.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Palette.Muted,
                )
            }
        }
        Gap(8.dp)
    }
}

/** Held or not held, as a shape and a colour rather than as the word "granted". */
@Composable
private fun Mark(granted: Boolean) {
    Text(
        if (granted) "✓" else "!",
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = if (granted) Palette.Ok else Palette.Bad,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun Fix(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Fix", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    }
}

@Composable
private fun Entry(title: String, note: String, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text(
                note,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                // A missing permission is coloured, and nothing else on this screen is. It is the
                // only line here that means something is currently not happening.
                color = if (warn) Palette.Bad else Palette.Muted,
            )
        }
        Text("›", fontSize = 20.sp, color = Palette.Dim)
    }
}
