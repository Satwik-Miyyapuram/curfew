package dev.curfew.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlin.math.roundToInt
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
            Gap(Space.l)
            Title("Insights")
            Gap(Space.l)
        }
        // The comparison is the best thing on this screen, and it is absent for two different
        // reasons — F-13. It needs **Usage access**, and it needs a whole day on each side of the
        // install (`screenTimeComparison` returns null if either is missing). Nothing said so: the
        // card simply was not there, and a reader cannot tell "not yet" from "not working" from
        // "nothing worth showing". The screen's own empty state lower down already explains its
        // scope; this is the same treatment for the same kind of absence.
        val comparison = state.screenTime
        if (comparison != null) {
            item {
                ComparisonCard(comparison)
                Gap(Space.m)
            }
        } else {
            item {
                val usageAccess =
                    state.grants.firstOrNull { it.grant == Grant.UsageAccess }?.granted == true
                Text(
                    if (usageAccess) {
                        // The baseline is taken once and never rewritten, and the average needs a
                        // completed day it can exclude today from. Both are deliberate; only this
                        // sentence is new.
                        "The before-and-after goes here, and needs a full day of use to be worth " +
                            "showing — it compares an average day before Curfew with an average day " +
                            "now, so it cannot say anything on day one."
                    } else {
                        "The before-and-after goes here. It needs Usage access, because both halves " +
                            "are Android's own screen-time figures rather than anything Curfew " +
                            "counts. Grant it on Health and this fills in."
                    },
                    style = Type.meta,
                    color = Palette.muted,
                )
                Gap(Space.m)
            }
        }
        item {
            DCard {
                StreakSummary(state.stats)
                Gap(Space.l)
                DayBars(state.stats)
            }
            Gap(Space.m)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                // The summary, and only the summary. The log it was built from stays here.
                ExportPill("Export CSV", tint = Palette.accent) { exportCsv.launch("curfew-stats.csv") }
                ExportPill("Export JSON", tint = Palette.accent) { exportJson.launch("curfew-stats.json") }
            }
            Gap(Space.xl)
            // -------------------------------------------------------------------------------------
            // The shape of the fortnight — `docs/design.md` section 5.8.
            //
            // `Stats` reports one row per day, so the page could say *how much* time was lost and
            // nothing about *when* or *to what*. Both of those come from the same slices the
            // enforcer already writes, which is why neither of these is a new history.
            // -------------------------------------------------------------------------------------
            SectionLabel("When you lose time")
            Gap(Space.m)
            HourHeatmap(state.usageShape)

            Gap(Space.xl)
            SectionLabel("What it costs you")
            Gap(Space.m)
            TargetLeaderboard(state.usageShape)

            Gap(Space.xl)
            SectionLabel("Today")
            Gap(Space.m)
        }

        if (state.spentSeconds.isEmpty()) {
            item {
                Text(
                    "Nothing counted yet. Time is only measured for targets a budget or a launch " +
                        "limit actually covers — Curfew does not keep a record of everything you " +
                        "open.",
                    style = Type.meta,
                    color = Palette.muted,
                )
            }
        }

        items(state.spentSeconds, key = { it.first }) { (key, seconds) ->
            val opens = state.launchCounts[key] ?: 0
            DCard(modifier = Modifier.padding(bottom = Space.s), padding = 14.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        contentDescription = "${describeTarget(key)}: ${duration(seconds)}"
                    },
                ) {
                    Text(
                        describeTarget(key),
                        style = Type.body,
                        fontWeight = FontWeight.Bold,
                        color = Palette.text,
                    )
                    // A bar rather than a progress indicator: this is a share of the busiest
                    // thing today, not a task on its way to finishing.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(Palette.raised)
                            .border(Dsn.Border, Palette.line),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    if (busiest > 0) seconds.toFloat() / busiest else 0f,
                                )
                                .fillMaxHeight()
                                .background(Palette.accent),
                        )
                    }
                    Text(
                        duration(seconds) + if (opens > 0) " · opened $opens times" else "",
                        style = Type.meta,
                        color = Palette.muted,
                        modifier = Modifier.padding(top = Space.s),
                    )
                }
            }
        }

        item {
            Gap(Space.m)
            SectionLabel("What Curfew did")
            Gap(Space.s)
            Text(
                "Kept on this device for 30 days, then deleted.",
                style = Type.meta,
                color = Palette.dim,
            )
            Gap(Space.m)
        }
        items(state.audit, key = { it.id }) { row ->
            // One entry, one thing to hear: the sentence and the time it happened.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Space.m)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    describeAudit(row.kind, row.detail),
                    style = Type.body,
                    color = Palette.text,
                )
                Text(dateTime(row.at), style = Type.meta, color = Palette.dim)
            }
        }
        item { Gap(Dsn.BottomRoom) }
    }
}

/** The two export buttons, as pills. */
@Composable
private fun ExportPill(text: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    // Deferred to the standard [Pill], which is a square tag with a 3px edge — the same control a
    // preset, a filter and a profile chip are. It used to be a **999dp-radius pill in the raised
    // tone**, which was the old design's shape and made the one screen that is about numbers the
    // one screen with a different silhouette. `tint` still picks the colour it always did.
    Box(
        modifier = Modifier.heightIn(min = Dsn.MinTouch),
        contentAlignment = Alignment.Center,
    ) {
        Pill(text = text, tint = tint, onClick = onClick)
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
            style = Type.h2,
            fontWeight = FontWeight.Bold,
            color = Palette.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Best so far: ${stats.longestStreak}. " +
                "In total, ${duration(stats.totalBlockedSeconds.toInt())} across " +
                "${stats.totalSessions} session${if (stats.totalSessions == 1) "" else "s"}.",
            style = Type.meta,
            color = Palette.muted,
            modifier = Modifier.padding(top = Space.s),
        )
        if (stats.currentStreak > 0 && stats.days.lastOrNull()?.sessions == 0) {
            // Said out loud so a streak that looks a day short does not read as a bug.
            Text(
                "Today has not finished, so it does not count against you yet.",
                style = Type.meta,
                color = Palette.dim,
                modifier = Modifier.padding(top = Space.xs),
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
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
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
                        .background(if (day.sessions > 0) Palette.accent else Palette.line)
                        .border(Dsn.Border, Palette.line),
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
        Gap(Space.m)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("Before Curfew", style = Type.meta, color = Palette.dim)
                Text(
                    duration(comparison.beforeSeconds.toInt()),
                    style = Type.h2,
                    fontWeight = FontWeight.Bold,
                    color = Palette.muted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Now", style = Type.meta, color = Palette.dim)
                Text(
                    duration(comparison.nowSeconds.toInt()),
                    style = Type.h2,
                    fontWeight = FontWeight.Bold,
                    color = if (comparison.savedSeconds > 0) Palette.ok else Palette.text,
                )
            }
        }
        Gap(Space.m)
        Text(
            if (comparison.savedSeconds > 0) {
                "${duration(comparison.savedSeconds.toInt())} a day back, against the " +
                    "${comparison.baselineDays} days before you started."
            } else {
                "No lower than before yet, against the ${comparison.baselineDays} days before " +
                    "you started."
            },
            style = Type.meta,
            color = if (comparison.savedSeconds > 0) Palette.ok else Palette.muted,
        )
        Text(
            "Android's own figures, averaged over the last ${comparison.recentDays} whole days.",
            style = Type.meta,
            color = Palette.dim,
        )
    }
}

/**
 * The fortnight as twenty-four columns: which hours the time actually goes.
 *
 * `docs/design.md` section 5.8 asks for an hour heatmap, and it is the one view on this page that
 * answers a question a total cannot: two people can lose the same six hours a week and one of them
 * has lost their evenings while the other has lost their mornings, and only one of those is worth
 * changing something about.
 *
 * Bars rather than a colour grid, and the reason is section 8: a heatmap communicates by colour
 * intensity alone, which is exactly the thing that rule forbids. A bar's *height* says the same
 * thing to somebody who cannot tell two shades of blue apart, and the sentence underneath names the
 * worst hour in words.
 */
@Composable
private fun HourHeatmap(shape: dev.curfew.app.data.UsageShape) {
    val peak = shape.hourSeconds.maxOrNull() ?: 0
    Column(Modifier.fillMaxWidth()) {
        if (peak <= 0) {
            Text(
                "No measured time in the last ${shape.days} days. This fills in once a budget, a " +
                    "launch limit or an allowance has something to measure.",
                style = Type.meta,
                color = Palette.dim,
            )
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(84.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            shape.hourSeconds.forEachIndexed { hour, seconds ->
                val share = seconds.toFloat() / peak
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((66f * share).dp.coerceAtLeast(if (seconds > 0) 3.dp else 1.dp))
                            .background(if (seconds > 0) Palette.accent else Palette.raised)
                            .border(Dsn.Border, Palette.line),
                    )
                }
            }
        }
        Gap(Space.xs)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "23").forEach { label ->
                Text(
                    label,
                    style = Type.micro.mono,
                    color = Palette.dim,
                )
            }
        }
        Gap(Space.s)
        val worst = shape.busiestHour
        Text(
            if (worst == null) {
                "Nothing recorded in any hour yet."
            } else {
                val percent = (shape.busiestHourShare * 100).roundToInt()
                "The hour you lose most is ${"%02d".format(worst)}:00, which is $percent% of all the " +
                    "measured time in the last ${shape.days} days."
            },
            style = Type.meta,
            color = Palette.muted,
        )
    }
}

/**
 * What the time is actually going to, worst first.
 *
 * Section 5.8's target leaderboard. It is the list that makes the rest of the page actionable: an
 * hour tells you *when* to change something, and this tells you what.
 *
 * The key is the core's own (`app:com.reddit.frontpage`), turned into something a person recognises
 * by `describeTarget`, which is the same function the rest of the app uses so this list and a rule
 * row cannot disagree about what a target is called.
 */
@Composable
private fun TargetLeaderboard(shape: dev.curfew.app.data.UsageShape) {
    val ranked = shape.byTarget.filter { it.second > 0 }
    Column(Modifier.fillMaxWidth()) {
        if (ranked.isEmpty()) {
            Text(
                "Nothing has been measured yet, so there is nothing to rank. This fills in for the " +
                    "targets a rule actually covers.",
                style = Type.meta,
                color = Palette.dim,
            )
            return@Column
        }
        val worst = ranked.first().second
        ranked.take(8).forEachIndexed { index, (key, seconds) ->
            if (index > 0) Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .landIn(index = index, from = 14f)
                    .padding(vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Text(
                    "${index + 1}",
                    style = Type.meta.mono,
                    fontWeight = FontWeight.Black,
                    color = Palette.dim,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        describeTarget(key),
                        style = Type.body,
                        fontWeight = FontWeight.Bold,
                        color = Palette.text,
                    )
                    Gap(Space.xs)
                    Box(
                        Modifier
                            .fillMaxWidth(seconds.toFloat() / worst)
                            .height(6.dp)
                            .background(Palette.accent)
                            .border(Dsn.Border, Palette.line),
                    )
                }
                Text(
                    duration(seconds.toInt()),
                    style = Type.meta.mono,
                    fontWeight = FontWeight.Bold,
                    color = Palette.muted,
                )
            }
        }
        Gap(Space.s)
        Text(
            "Measured locally, over the last ${shape.days} days. Nothing here leaves the phone.",
            style = Type.micro,
            color = Palette.dim,
        )
    }
}