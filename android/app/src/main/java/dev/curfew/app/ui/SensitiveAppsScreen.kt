package dev.curfew.app.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.curfew.app.R
import dev.curfew.app.enforce.SensitiveApps

/**
 * Which apps Curfew is not allowed to look at, and the two ways the user can change that.
 *
 * This screen exists because the list is load-bearing in both directions and neither direction is
 * guessable from the outside. Too short and a bank's screen is exposed; too long and an app the user
 * asked to block is silently unblockable. So both mistakes are visible here and both are fixable here,
 * with the one exception that the shipped list cannot be narrowed — it is the promise the permission
 * screen already made.
 *
 * Reached from the accessibility section rather than from a list of features, because what it governs
 * is the accessibility privilege and not a preference.
 */
@Composable
fun SensitiveAppsScreen(model: CurfewViewModel) {
    val context = LocalContext.current
    // A counter rather than a flow: the data changes only when the user taps something on this screen,
    // so a revision key is cheaper and more honest than a subscription.
    var revision by remember { mutableIntStateOf(0) }

    val installed = remember(revision) {
        val manager = context.packageManager
        runCatching {
            manager.getInstalledApplications(PackageManager.GET_META_DATA)
                .mapNotNull { info ->
                    val label = runCatching { manager.getApplicationLabel(info).toString() }
                        .getOrNull() ?: return@mapNotNull null
                    info.packageName to label
                }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }

    val exempt = remember(revision) { SensitiveApps.exemptPackages(context) }
    val extra = remember(revision) { SensitiveApps.extraPackages(context) }
    val resolved = remember(revision) { SensitiveApps.resolve(context) }
    val curated = SensitiveApps.CURATED

    Screen(spacing = 0.dp) {
        Title(stringResource(R.string.sensitive_title))
        Gap(Space.s)
        Text(
            stringResource(R.string.sensitive_intro),
            style = Type.body,
            color = Palette.muted,
        )

        Gap(Space.l)
        SectionLabel(stringResource(R.string.sensitive_curated_section))
        Gap(Space.m)
        DCard(padding = 16.dp) {
            Text(
                stringResource(R.string.sensitive_curated_note, curated.size),
                style = Type.meta,
                color = Palette.muted,
            )
        }

        Gap(Space.l)
        SectionLabel(stringResource(R.string.sensitive_added_section))
        Gap(Space.m)
        DCard(padding = 16.dp) {
            if (extra.isEmpty()) {
                Text(
                    stringResource(R.string.sensitive_added_empty),
                    style = Type.meta,
                    color = Palette.muted,
                )
            } else {
                extra.forEach { packageName ->
                    SensitiveRow(
                        label = installed.firstOrNull { it.first == packageName }?.second ?: packageName,
                        packageName = packageName,
                        action = stringResource(R.string.sensitive_action_remove),
                    ) {
                        SensitiveApps.removeUserPackage(context, packageName)
                        revision++
                    }
                }
            }
        }

        if (exempt.isNotEmpty()) {
            Gap(Space.l)
            SectionLabel(stringResource(R.string.sensitive_exempt_section))
            Gap(Space.m)
            DCard(padding = 16.dp) {
                Text(
                    stringResource(R.string.sensitive_exempt_note),
                    style = Type.meta,
                    color = Palette.muted,
                )
                Gap(Space.s)
                exempt.forEach { packageName ->
                    SensitiveRow(
                        label = installed.firstOrNull { it.first == packageName }?.second ?: packageName,
                        packageName = packageName,
                        action = stringResource(R.string.sensitive_action_restore),
                    ) {
                        SensitiveApps.addUserPackage(context, packageName)
                        revision++
                    }
                }
            }
        }

        Gap(Space.l)
        SectionLabel(stringResource(R.string.sensitive_all_section))
        Gap(Space.m)
        DCard(padding = 16.dp) {
            Column(Modifier.fillMaxWidth()) {
                installed.filter { it.first in resolved }.forEach { (packageName, label) ->
                    if (packageName in curated) {
                        Column(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
                            Text(label, style = Type.body, color = Palette.text)
                            Text(packageName, style = Type.micro, color = Palette.dim)
                        }
                    } else {
                        SensitiveRow(
                            label = label,
                            packageName = packageName,
                            action = stringResource(R.string.sensitive_action_allow),
                        ) {
                            // An exemption rather than a deletion, so the label-and-permissions signal
                            // cannot put it straight back on the next resolve.
                            SensitiveApps.removeUserPackage(context, packageName)
                            revision++
                        }
                    }
                }
            }
        }

        Gap(Space.l)
        SectionLabel(stringResource(R.string.sensitive_candidates_section))
        Gap(Space.m)
        DCard(padding = 16.dp) {
            Text(
                stringResource(R.string.sensitive_candidates_note),
                style = Type.meta,
                color = Palette.muted,
            )
            Gap(Space.s)
            Column(Modifier.fillMaxWidth()) {
                installed.filter { it.first !in resolved }.forEach { (packageName, label) ->
                    SensitiveRow(
                        label = label,
                        packageName = packageName,
                        action = stringResource(R.string.sensitive_action_add),
                    ) {
                        SensitiveApps.addUserPackage(context, packageName)
                        revision++
                    }
                }
            }
        }

        Gap(Space.l)
        // Said at the bottom, because it is what a user is most likely to be surprised by: the
        // guarantee is about reading, not about blocking.
        Text(
            stringResource(R.string.sensitive_footer),
            style = Type.meta,
            color = Palette.dim,
        )
        Gap(Space.xl)
    }
}

/** One row of the list: the app, its package, and the one thing the user can do about it. */
@Composable
private fun SensitiveRow(
    label: String,
    packageName: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.body, color = Palette.text)
            Text(packageName, style = Type.micro, color = Palette.dim)
        }
        GhostButton(text = action, onClick = onAction)
    }
}
