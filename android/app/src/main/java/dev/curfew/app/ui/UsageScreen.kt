package dev.curfew.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.Stats

/**
 * Where the day went, and what Curfew did about it.
 *
 * Both halves are here on purpose. The usage figures are the evidence behind every budget decision,
 * and the log below them is the record of every session that started or ended — which is what makes
 * the claim "it only does what you asked" something a user can check rather than take on trust.
 *
 * None of this leaves the device: the only network traffic Curfew makes is to devices the user
 * paired, on their own network, and the database this is read from is encrypted.
 */
@Composable
fun UsageScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val busiest = state.spentSeconds.firstOrNull()?.second ?: 0
    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) model.exportStats(uri, asCsv = true) }
    val exportJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) model.exportStats(uri, asCsv = false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Dsn.Gutter),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Gap(14.dp)
            Title("Where your time went", size = 26)
            Gap(16.dp)
        }
        state.screenTime?.let { comparison ->
            item {
                ComparisonCard(comparison)
                Gap(10.dp)
            }
        }
        item {
            DCard {
                StreakSummary(state.stats)
                Gap(14.dp)
                DayBars(state.stats)
            }
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The summary, and only the summary. The log it was built from stays here.
                ExportPill("Export CSV", tint = Palette.Accent) { exportCsv.launch("curfew-stats.csv") }
                ExportPill("Export JSON", tint = Palette.Accent) { exportJson.launch("curfew-stats.json") }
            }
            Gap(20.dp)
            SectionLabel("Today")
            Gap(10.dp)
        }

        if (state.spentSeconds.isEmpty()) {
            item {
                Text(
                    "Nothing counted yet. Time is only measured for targets a budget or a launch " +
                        "limit actually covers — Curfew does not keep a record of everything you " +
                        "open.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Palette.Muted,
                )
            }
        }

        items(state.spentSeconds, key = { it.first }) { (key, seconds) ->
            val opens = state.launchCounts[key] ?: 0
            DCard(modifier = Modifier.padding(bottom = 8.dp), padding = 14.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        contentDescription = "${describeTarget(key)}: ${duration(seconds)}"
                    },
                ) {
                    Text(
                        describeTarget(key),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Text,
                    )
                    // A bar rather than a progress indicator: this is a share of the busiest
                    // thing today, not a task on its way to finishing.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .padding(top = 0.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Palette.Raised),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    if (busiest > 0) seconds.toFloat() / busiest else 0f,
                                )
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(Palette.Accent),
                        )
                    }
                    Text(
                        duration(seconds) + if (opens > 0) " · opened $opens times" else "",
                        fontSize = 12.sp,
                        color = Palette.Muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item {
            Gap(12.dp)
            SectionLabel("What Curfew did")
            Gap(6.dp)
            Text(
                "Kept on this device for 30 days, then deleted.",
                fontSize = 12.sp,
                color = Palette.Dim,
            )
            Gap(10.dp)
        }
        items(state.audit, key = { it.id }) { row ->
            // One entry, one thing to hear: the sentence and the time it happened.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    describeAudit(row.kind, row.detail),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Palette.Text,
                )
                Text(dateTime(row.at), fontSize = 12.sp, color = Palette.Dim)
            }
        }
        item { Gap(Dsn.BottomRoom) }
    }
}

/** The two export buttons, as pills. */
@Composable
private fun ExportPill(text: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Palette.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

/**
 * Days in a row, and the totals behind them.
 *
 * A streak here is a count of days the user did the thing they said they wanted to do, and nothing
 * more: there is no goal to miss, no score, and nobody to compare it with. Making it a thing to
 * lose would turn a self-control tool into a second compulsion.
 */
@Composable
private fun StreakSummary(stats: Stats) {
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            when (stats.currentStreak) {
                0 -> "No days in a row yet."
                1 -> "One day in a row."
                else -> "${stats.currentStreak} days in a row."
            },
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = Palette.Text,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Best so far: ${stats.longestStreak}. " +
                "In total, ${duration(stats.totalBlockedSeconds.toInt())} across " +
                "${stats.totalSessions} session${if (stats.totalSessions == 1) "" else "s"}.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = Palette.Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (stats.currentStreak > 0 && stats.days.lastOrNull()?.sessions == 0) {
            // Said out loud so a streak that looks a day short does not read as a bug.
            Text(
                "Today has not finished, so it does not count against you yet.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Palette.Dim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** One bar per day, tallest day full height. Empty days are drawn, because a gap is information. */
@Composable
private fun DayBars(stats: Stats) {
    val tallest = stats.days.maxOfOrNull { it.blockedSeconds } ?: 0
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (day in stats.days) {
            val fraction = if (tallest > 0) day.blockedSeconds.toFloat() / tallest else 0f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The row already reads out every day below; a bar that described itself again
                    // would make a screen reader say the fortnight twice.
                    .semantics {
                        contentDescription = "${day.day}: ${duration(day.blockedSeconds)}"
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A day with a session but almost no time still gets a visible mark: it
                        // counted towards the streak, so it should be on the chart.
                        .fillMaxHeight(if (day.sessions > 0) maxOf(fraction, 0.06f) else 0.02f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (day.sessions > 0) Palette.Accent else Palette.Line),
                )
            }
        }
    }
}

/**
 * The audit kinds the runtime writes, in the words a user would use.
 *
 * **Every kind the app writes is named here, and that is now checked by a test.** F-36 in the
 * interaction review: the fallback was `"$kind $detail"`, so a user reading a section whose stated
 * purpose is that they can check it saw `sync.failed timeout` — an internal token, in a list meant to
 * be evidence. The review counted "at least ten" unnamed kinds; the real number was **fourteen**, out of
 * twenty. Four of the five counts in that review have now been wrong in one direction or the other, and
 * the pattern is consistent: the finding is always right and the number never is.
 *
 * The other half of the fix is the fallback itself. Naming twenty kinds does not stop somebody adding a
 * twenty-first, so an unrecognised kind no longer renders its own name: it says plainly that this build
 * does not know it. A user cannot act on `sync.refused`, and cannot tell it from `sync.failed`; both are
 * better served by a sentence, and the kind is still on the row for anyone reading the database.
 */
private fun describeAudit(kind: String, detail: String): String = when (kind) {
    // Sessions.
    "session.started" -> "A session started."
    "session.ended" -> "A session ended."

    // The two ways out, which are the entries a user most needs to recognise.
    "release.requested" -> "You asked for a delayed release."
    "release.given" -> "A delayed release was given, and the waiting device was let out."
    "pass.spent" -> "An emergency pass was spent. The ration is one smaller on every paired device."

    // Configuration.
    "config.replaced" -> "The rules were changed."
    "profile.saved" -> "A profile was saved."
    "profile.removed" -> "A profile was removed."
    "profile.seeded" -> "Curfew set up its starter profile."
    "rule.saved" -> "A rule was saved."
    "rule.removed" -> "A rule was removed."
    "schedule.weekly.saved" -> "A weekly window was saved."
    "schedule.weekly.removed" -> "A weekly window was removed."
    "schedule.calendar.saved" -> "A calendar rule was saved."
    "schedule.calendar.removed" -> "A calendar rule was removed."

    // Sync between paired devices.
    "sync.adopted" -> "A block from another paired device started here too."
    "sync.refused" -> "A block from another device was refused, because this device holds the lock."
    "sync.failed" -> "A sync attempt failed" + reason(detail)

    // Enforcement itself — the two entries that mean nothing was being blocked.
    "enforcement.gap" ->
        "Curfew was not running for a while, so nothing was blocked." + lasting(detail)
    "enforcement.clock" -> "The device's clock was changed, and the change was refused." + lasting(detail)

    else -> "Curfew recorded something this version does not name."
}

/**
 * The runtime's own words, appended only where they say something a person can use.
 *
 * `sync.failed` carries a message — `timeout`, `not paired` — which is genuinely useful. The other kinds
 * carry an id or a slug, which is not, so they get no suffix. That distinction is why this is not a
 * blanket `": $detail"`.
 */
private fun reason(detail: String): String =
    if (detail.isBlank()) "." else ": ${detail.trim().trimEnd('.')}."

/** `"120s"` back into a duration, because the runtime records seconds and a person reads minutes. */
private fun lasting(detail: String): String {
    val seconds = detail.removeSuffix("s").trim().toIntOrNull() ?: return ""
    return " That lasted ${duration(seconds)}."
}

/**
 * Before Curfew, now, and the difference — the only claim on this screen the app did not make itself.
 *
 * Both numbers come from Android's own daily totals, so this is not "time Curfew blocked": it is
 * what actually happened to the phone. That is why it can go the wrong way, and why it says so
 * plainly when it does. A tool that only ever reports progress is not measuring anything.
 */
@Composable
private fun ComparisonCard(comparison: dev.curfew.app.data.ScreenTimeComparison) {
    DCard(padding = 20.dp) {
        SectionLabel("Screen time, a day")
        Gap(12.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("Before Curfew", fontSize = 12.sp, color = Palette.Dim)
                Text(
                    duration(comparison.beforeSeconds.toInt()),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Muted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Now", fontSize = 12.sp, color = Palette.Dim)
                Text(
                    duration(comparison.nowSeconds.toInt()),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (comparison.savedSeconds > 0) Palette.Ok else Palette.Text,
                )
            }
        }
        Gap(12.dp)
        Text(
            if (comparison.savedSeconds > 0) {
                "${duration(comparison.savedSeconds.toInt())} a day back, against the " +
                    "${comparison.baselineDays} days before you started."
            } else {
                "No lower than before yet, against the ${comparison.baselineDays} days before " +
                    "you started."
            },
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = if (comparison.savedSeconds > 0) Palette.Ok else Palette.Muted,
        )
        Text(
            "Android's own figures, averaged over the last ${comparison.recentDays} whole days.",
            fontSize = 12.sp,
            color = Palette.Dim,
        )
    }
}
