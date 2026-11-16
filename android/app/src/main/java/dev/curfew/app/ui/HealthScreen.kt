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
    // No single grant is required, but some pair is: see [Grant.canBlockApps]. Without one, the
    // missing half of the usage-access route is what to name, since that route has no side effects.
    val missingRequired = state.grants.filter { !it.granted }.filter {
        it.grant.required ||
            (!Grant.canBlockApps(context) && it.grant in listOf(Grant.UsageAccess, Grant.Overlay))
    }
    val fine = missingRequired.isEmpty()

    Screen(spacing = 0.dp) {
        Title(
            stringResource(
                if (fine) R.string.health_can_enforce else R.string.health_cannot_enforce,
            ),
        )
        Gap(Space.s)
        Text(
            if (fine) {
                stringResource(R.string.health_all_in_place)
            } else {
                // One resource with the reasons substituted in, not a sentence built with `+`.
                // A concatenation of a lead-in and a list cannot be translated: the clause order
                // and the punctuation belong to the language, not to the list.
                //
                // `map` before `joinToString`, and that ordering is load-bearing rather than a
                // style choice: `stringResource` is composable, `map` is `inline` so it inherits the
                // composable context, and `joinToString` is not — so resolving inside the join is a
                // compile error. Which is how this was found.
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
                // scope — and the whole row is read as one thing: a screen reader user should hear
                // the permission, whether it is held, and what is lost without it as a single
                // sentence, rather than swiping through four fragments to assemble it.
                //
                // The sentence itself is a resource with numbered arguments, so a translator can put
                // the clauses in their own order. Concatenating three resolved fragments would read
                // correctly in English and in nothing else.
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
                        Text(
                            if (entry.granted) "✓" else "!",
                            style = Type.body,
                            fontWeight = FontWeight.Bold,
                            color = if (entry.granted) Palette.ok else Palette.bad,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            name,
                            style = Type.body,
                            fontWeight = FontWeight.Bold,
                            color = Palette.text,
                            modifier = Modifier.weight(1f),
                        )
                        if (!entry.granted) {
                            Grant {
                                // One shared implementation: this was the hardened copy of three that
                                // had drifted, one of which crashed and one of which failed silently.
                                // See `openGrantPage`.
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
        DCard(padding = 16.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("⛨", style = Type.h3, fontWeight = FontWeight.Normal, color = Palette.muted)
                Text(
                    // Not "no internet permission at all": the manifest declares INTERNET, because
                    // LAN sync needs it, and a user can falsify that sentence in ten seconds in
                    // Android Settings. And not "nothing leaves this device" either, which is what
                    // this card said — false whenever sync is on, and contradicted by its own next
                    // clause. The shared sentence is the honest one. See [Privacy].
                    Privacy.NO_SERVER + " Its database is encrypted with a key held by this " +
                        "device's keystore.",
                    style = Type.meta,
                    color = Palette.muted,
                )
            }
        }
        Gap(Space.s)

        // -----------------------------------------------------------------------------------------
        // Appearance, here as well as in Settings.
        //
        // This is the tab that is about the app itself rather than about the plan, and the theme is
        // a property of the app — someone who has just been told how enforcement is going is in the
        // right place to say "and make it dark". It lives in `Appearance` either way: these controls
        // and the ones in Settings write the same value, so the two can never disagree.
        // -----------------------------------------------------------------------------------------
        Gap(Space.xl)
        SectionLabel("How Curfew looks")
        Gap(Space.m)
        DCardFlush {
            Column(Modifier.fillMaxWidth().padding(Space.l)) {
                SectionLabel("Theme")
                Gap(Space.s)
                ChoiceRow(
                    options = listOf(
                        Appearance.SYSTEM to "System",
                        Appearance.LIGHT to "Paper",
                        Appearance.DARK to "Dusk",
                    ),
                    chosen = Appearance.theme,
                    onPick = { Appearance.setTheme(context, it) },
                )
            }
            Rule()
            Column(Modifier.fillMaxWidth().padding(Space.l)) {
                SectionLabel("Accent")
                Gap(Space.s)
                ChoiceRow(
                    options = Accent.entries.map { it.id to it.label },
                    chosen = Appearance.accent,
                    onPick = { Appearance.setAccent(context, it) },
                )
            }
        }
    }
}



/** The one action on a row: the system page for exactly this permission. */
@Composable
private fun Grant(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .background(Palette.accent, RoundedCornerShape(Dsn.CtlRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Text("Allow", style = Type.meta, fontWeight = FontWeight.Bold, color = Palette.onFill)
    }
}
