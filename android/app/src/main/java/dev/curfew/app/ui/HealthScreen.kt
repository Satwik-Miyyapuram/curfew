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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import dev.curfew.app.R
import dev.curfew.app.enforce.Detector
import dev.curfew.app.enforce.EnforcementMode
import dev.curfew.app.enforce.Watchers
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Whether Curfew is actually working, said without euphemism.
 *
 * A blocker that has quietly stopped enforcing is worse than no blocker, because the user is still
 * relying on it. So this screen leads with the plain answer, one sentence coloured only when
 * something is wrong, lists every permission with what is lost while it is missing, and never
 * phrases a missing permission as a feature the user might enjoy turning on. Advanced detection
 * details sit at the bottom.
 *
 * It is drawn in the canvas's own vocabulary rather than in Material defaults, because a screen
 * that looks like a different app is a screen the user reads as a system dialog and dismisses.
 */
@Composable
fun HealthScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // No single grant is required, but some pair is: see [Grant.canBlockApps]. Without one, the
    // missing half of the usage-access route is what to name, since that route has no side effects.
    val missingRequired = state.grants.filter { !it.granted }.filter {
        it.grant.required ||
            (!Grant.canBlockApps(context) && it.grant in listOf(Grant.UsageAccess, Grant.Overlay))
    }
    val fine = missingRequired.isEmpty()

    Screen(spacing = 0.dp) {
        Title("Is Curfew working")
        Gap(Space.s)
        Text(
            if (fine) {
                stringResource(R.string.health_all_in_place)
            } else {
                // One resource with the reasons substituted in, not a sentence built with `+`.
                // `map` before `joinToString` is load-bearing: `stringResource` is composable, and
                // `joinToString` is not, so resolving inside the join would not compile.
                stringResource(
                    R.string.health_nothing_blocked,
                    missingRequired.map { stringResource(it.grant.cost) }.joinToString(" "),
                )
            },
            style = Type.body,
            // The only coloured sentence on the screen, and only when it means something is not
            // happening right now.
            color = if (fine) Palette.muted else Palette.bad,
        )

        if (state.restrictedSettings) {
            Gap(Space.l)
            DCard {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text("!", style = Type.h3, color = Palette.bad)
                    Column {
                        Text(
                            stringResource(R.string.health_restricted_title),
                            style = Type.body,
                            fontWeight = FontWeight.Bold,
                            color = Palette.text,
                        )
                        Text(
                            RestrictedSettings.INSTRUCTIONS,
                            style = Type.meta,
                            color = Palette.muted,
                            modifier = Modifier.padding(top = Space.s),
                        )
                    }
                }
                Gap(Space.m)
                GhostButton(text = stringResource(R.string.health_open_app_info)) {
                    context.startActivity(
                        RestrictedSettings.appInfoIntent(context)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }

        Gap(Space.l)
        SectionLabel(stringResource(R.string.health_every_permission))
        Gap(Space.m)
        DCardFlush {
            state.grants.forEachIndexed { index, entry ->
                if (index > 0) Rule()
                // Resolved here rather than inside the semantics block, which is not a composable
                // scope. The whole row is read as one sentence by a screen reader.
                val name = stringResource(entry.grant.title)
                val why = stringResource(entry.grant.because)
                val without = stringResource(entry.grant.cost)
                val described = if (entry.granted) {
                    context.getString(R.string.health_grant_allowed, name, why)
                } else {
                    context.getString(R.string.health_grant_refused, name, why, without)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.l, vertical = Space.l)
                        .semantics(mergeDescendants = true) { contentDescription = described },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        GrantMark(entry.granted, entry.grant.required)
                        Text(
                            name,
                            style = Type.body,
                            fontWeight = FontWeight.Bold,
                            color = Palette.text,
                            modifier = Modifier.weight(1f),
                        )
                        if (!entry.granted) {
                            Grant {
                                // One shared implementation. See `openGrantPage`.
                                openGrantPage(context, entry.grant)
                            }
                        }
                    }
                    Text(
                        why,
                        style = Type.meta,
                        color = Palette.muted,
                        modifier = Modifier.padding(top = Space.s, start = Space.xxl),
                    )
                    if (!entry.granted) {
                        Text(
                            stringResource(R.string.health_without_it, without),
                            style = Type.meta,
                            color = Palette.bad,
                            modifier = Modifier.padding(top = Space.xs, start = Space.xxl),
                        )
                    }
                }
            }
        }

        Gap(Space.l)
        DCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("⛨", style = Type.h3, fontWeight = FontWeight.Normal, color = Palette.muted)
                Text(
                    // Not "no internet permission at all": the manifest declares INTERNET, because
                    // LAN sync needs it. The shared sentence is the honest one. See [Privacy].
                    Privacy.NO_SERVER + " Its database is encrypted with a key held by this " +
                        "device's keystore.",
                    style = Type.meta,
                    color = Palette.muted,
                )
            }
        }

        // ---------------------------------------------------------------------------------------
        // Advanced: which service is detecting, and the controls that change it. These used to sit
        // on the Settings screen, which repeated the permission list above and was not the place a
        // user goes to check that blocking works.
        // ---------------------------------------------------------------------------------------
        Gap(Space.l)
        SectionLabel("Advanced")
        Gap(Space.m)
        DCard {
            val detector = Grant.detector(context)
            val chosen = Grant.chosenMode(context)

            ModeStatus(detector)
            if (detector == Detector.Poller) {
                Gap(Space.s)
                Text(
                    stringResource(R.string.settings_accessibility_needs_turning_on),
                    style = Type.meta,
                    color = Palette.dim,
                )
            }
            Gap(Space.m)
            // One tap, and blocking keeps working through the polling detector. It is only the
            // precision and the site rules that go.
            GhostButton(
                text = stringResource(R.string.settings_use_no_special_access),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Watchers.rememberAccessibilityWanted(context, wanted = false)
                // The running reader can switch itself off, which is what lets a payment app run.
                // When it cannot, the user does it in Settings.
                if (Watchers.turnOffReader(context)) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.settings_use_no_special_access_done),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } else {
                    turnOffAccessibilityHint(context)
                }
            }

            // Having both services on is the worst of both worlds and worth saying out loud.
            val other = EnforcementMode.entries.firstOrNull {
                it.serviceClass() != detector.serviceClass() && Grant.isModeEnabled(context, it)
            }
            if (other != null) {
                Gap(Space.m)
                Notice(
                    text = stringResource(
                        R.string.perm_accessibility_mode_other_still_on,
                        stringResource(other.label()),
                    ),
                    tone = NoticeTone.Warning,
                )
            }

            // Chosen but not what is detecting: said out loud rather than left as a button that looks
            // selected over a device running something else.
            if (detector != Detector.Poller && chosen != detector.mode()) {
                val chosenName = stringResource(chosen.label())
                val runningName = stringResource(detector.label())
                Gap(Space.m)
                Notice(
                    text = stringResource(
                        R.string.perm_accessibility_mode_chosen_not_running,
                        chosenName,
                        runningName,
                    ),
                    tone = NoticeTone.Warning,
                )
            }
        }
        Gap(Space.s)
    }
}

/**
 * The one action on a row: the system page for exactly this permission. At least a full touch
 * target tall, so it is not smaller than the other buttons on the screen.
 */
@Composable
private fun Grant(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = Dsn.MinTouch)
            .background(Palette.accent, RoundedCornerShape(Dsn.CtlRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Text("Allow", style = Type.meta, fontWeight = FontWeight.Bold, color = Palette.onFill)
    }
}
