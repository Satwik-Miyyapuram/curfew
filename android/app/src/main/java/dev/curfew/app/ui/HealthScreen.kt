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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Whether Curfew is actually working, said without euphemism.
 *
 * A blocker that has quietly stopped enforcing is worse than no blocker, because the user is still
 * relying on it. So this screen leads with the plain answer — one sentence, coloured only when
 * something is wrong — lists every permission with what is lost while it is missing, and never
 * phrases a missing permission as a feature the user might enjoy turning on.
 *
 * It is drawn in the canvas's own vocabulary rather than in Material defaults, because a screen
 * that looks like a different app is a screen the user reads as a system dialog and dismisses.
 */
@Composable
fun HealthScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val missingRequired = state.grants.filter { it.grant.required && !it.granted }
    val fine = missingRequired.isEmpty()

    Screen(spacing = 0.dp) {
        Title(if (fine) "Curfew can enforce" else "Curfew cannot enforce", size = 26)
        Gap(8.dp)
        Text(
            if (fine) {
                "Everything it needs to block an app is in place."
            } else {
                "Nothing is being blocked. " + missingRequired.joinToString(" ") { it.grant.cost }
            },
            fontSize = 14.sp,
            lineHeight = 21.sp,
            // The only coloured sentence on the screen, and only when it means something is not
            // happening right now.
            color = if (fine) Palette.Muted else Palette.Bad,
        )

        if (state.restrictedSettings) {
            Gap(16.dp)
            DCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("!", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Bad)
                    Column {
                        Text(
                            "Android is blocking the accessibility switch",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.Text,
                        )
                        Text(
                            RestrictedSettings.INSTRUCTIONS,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = Palette.Muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Gap(12.dp)
                GhostButton(text = "Open App info") {
                    context.startActivity(
                        RestrictedSettings.appInfoIntent(context)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }

        Gap(18.dp)
        SectionLabel("Every permission, and what it buys")
        Gap(10.dp)
        DCardFlush {
            state.grants.forEachIndexed { index, entry ->
                if (index > 0) Rule()
                // The whole row is read as one thing: a screen reader user should hear the
                // permission, whether it is held, and what is lost without it as a single
                // sentence, rather than swiping through four fragments to assemble it.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 17.dp, vertical = 15.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (entry.granted) {
                                "${entry.grant.title}, allowed. ${entry.grant.because}"
                            } else {
                                "${entry.grant.title}, not allowed. ${entry.grant.because} " +
                                    "Without it: ${entry.grant.cost}"
                            }
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (entry.granted) "✓" else "!",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (entry.granted) Palette.Ok else Palette.Bad,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            entry.grant.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.Text,
                            modifier = Modifier.weight(1f),
                        )
                        if (!entry.granted) {
                            Grant {
                                val permission = entry.grant.runtimePermission()
                                val settings = entry.grant.settingsIntent(context)
                                when {
                                    permission != null ->
                                        requestRuntimePermission(context, permission)
                                    // Some of these pages do not exist on every OEM's build, and
                                    // an ActivityNotFoundException here would kill the one screen
                                    // whose job is to fix permissions. App info always resolves.
                                    settings != null -> runCatching {
                                        context.startActivity(
                                            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }.onFailure {
                                        context.startActivity(
                                            RestrictedSettings.appInfoIntent(context)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        entry.grant.because,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Palette.Muted,
                        modifier = Modifier.padding(top = 6.dp, start = 30.dp),
                    )
                    if (!entry.granted) {
                        Text(
                            "Without it: ${entry.grant.cost}",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Palette.Bad,
                            modifier = Modifier.padding(top = 4.dp, start = 30.dp),
                        )
                    }
                }
            }
        }

        Gap(18.dp)
        DCard(padding = 16.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("⛨", fontSize = 17.sp, color = Palette.Muted)
                Text(
                    // Not "no internet permission at all": the manifest declares INTERNET, because
                    // LAN sync needs it, and a user can falsify that sentence in ten seconds in
                    // Android Settings. The honest claim is narrower and just as strong — nothing
                    // goes to a server, and the only traffic is to devices this user paired.
                    "Nothing Curfew records leaves this device. There is no account and no server: " +
                        "the only network traffic is to devices you paired, on your own network. " +
                        "Its database is encrypted with a key held by this device's keystore.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Palette.Muted,
                )
            }
        }
        Gap(8.dp)
    }
}

/** The one action on a row: the system page for exactly this permission. */
@Composable
private fun Grant(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Allow", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    }
}
