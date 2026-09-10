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
 * The screens Simple leaves out of the tab bar are listed below it. That is the rule this design
 * runs on: **Simple hides tabs, not powers**. Nothing here is unavailable in Simple mode; it is one
 * tap further away, which is what "simple" is allowed to mean.
 */
@Composable
fun SettingsScreen(model: CurfewViewModel, onOpen: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val mode by model.mode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val missing = state.grants.count { it.grant.required && !it.granted }

    Screen(spacing = 0.dp) {
        Gap(14.dp)
        Title("Settings", size = 24)
        Gap(16.dp)

        DCard {
            SectionLabel("How much do you want to see?")
            Gap(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dsn.CtlRadius))
                    .background(Palette.Raised)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Mode.entries.forEach { option ->
                    ModeTab(
                        label = if (option == Mode.Simple) "Simple" else "Power",
                        selected = mode == option,
                        modifier = Modifier.weight(1f),
                    ) { model.setMode(option) }
                }
            }
            Gap(14.dp)
            Text(
                if (mode.isPower) {
                    "Every screen in the bar, exact timers, the rule that matched, and the " +
                        "config file itself. Simple keeps four tabs and plain words."
                } else {
                    "Four tabs, plain words, no ids. Power adds Usage, Sync and Health, exact " +
                        "timers, rule syntax and the raw curfew.toml."
                },
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = Palette.Muted,
            )
        }

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
                    missing == 1 -> "One permission is missing. Nothing is blocked without it."
                    missing > 1 -> "$missing permissions are missing. Nothing is blocked without them."
                    else -> "Everything it needs, it has."
                },
                warn = missing > 0,
            ) { onOpen(Routes.HEALTH) }
            Rule()
            Entry(
                title = "Your calendar",
                note = if (state.calendarGranted) {
                    "${state.calendarEvents.size} events read for the next day and a half."
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

/** Half of the Simple/Power tray. */
@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Palette.Accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Palette.Ink else Palette.Muted,
        )
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
