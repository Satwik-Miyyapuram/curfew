package dev.curfew.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.policy.Stats

/**
 * Where the day went, and what Curfew did about it.
 *
 * Both halves are here on purpose. The usage figures are the evidence behind every budget decision,
 * and the log below them is the record of every session that started or ended — which is what makes
 * the claim "it only does what you asked" something a user can check rather than take on trust.
 *
 * None of this leaves the device: the app holds no INTERNET permission, and the database it is read
 * from is encrypted.
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
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "The last two weeks",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        item { StreakSummary(state.stats) }
        item { DayBars(state.stats) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The summary, and only the summary. The log it was built from stays here.
                TextButton(onClick = { exportCsv.launch("curfew-stats.csv") }) { Text("Export CSV") }
                TextButton(onClick = { exportJson.launch("curfew-stats.json") }) { Text("Export JSON") }
            }
        }

        item {
            Text(
                "Today",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }

        if (state.spentSeconds.isEmpty()) {
            item {
                Text(
                    "Nothing counted yet. Time is only measured for targets a budget or a launch " +
                        "limit actually covers — Curfew does not keep a record of everything you open.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(state.spentSeconds, key = { it.first }) { (key, seconds) ->
            val opens = state.launchCounts[key] ?: 0
            Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(describeTarget(key), style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(
                    progress = { if (busiest > 0) seconds.toFloat() / busiest else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        // The bar is decoration over a number that is already read out; giving it
                        // its own description would make a screen reader say everything twice.
                        .semantics {
                            contentDescription = "${describeTarget(key)}: ${duration(seconds)}"
                        },
                )
                Text(
                    duration(seconds) + if (opens > 0) " · opened $opens times" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Text(
                "What Curfew did",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
        }
        item {
            Text(
                "Kept on this device for 30 days, then deleted.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(state.audit, key = { it.id }) { row ->
            // One entry, one thing to hear: the sentence and the time it happened.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(describeAudit(row.kind, row.detail), style = MaterialTheme.typography.bodyMedium)
                Text(dateTime(row.at), style = MaterialTheme.typography.bodySmall)
            }
        }
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
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Best so far: ${stats.longestStreak}. " +
                "In total, ${duration(stats.totalBlockedSeconds.toInt())} across " +
                "${stats.totalSessions} session${if (stats.totalSessions == 1) "" else "s"}.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (stats.currentStreak > 0 && stats.days.lastOrNull()?.sessions == 0) {
            // Said out loud so a streak that looks a day short does not read as a bug.
            Text(
                "Today has not finished, so it does not count against you yet.",
                style = MaterialTheme.typography.bodySmall,
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
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (day.sessions > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
            }
        }
    }
}

/** The audit kinds the runtime writes, in the words a user would use. */
private fun describeAudit(kind: String, detail: String): String = when (kind) {
    "session.started" -> "A session started."
    "session.ended" -> "A session ended."
    "release.requested" -> "You asked for a delayed release."
    "config.replaced" -> "The rules were changed."
    "enforcement.gap" -> "Curfew was not running for a while, so nothing was blocked."
    "enforcement.clock" -> "The device's clock was changed, and the change was refused."
    else -> "$kind $detail".trim()
}
