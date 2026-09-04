package dev.curfew.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Today", style = MaterialTheme.typography.headlineSmall) }

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
            Column(modifier = Modifier.fillMaxWidth()) {
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
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            Text(
                "Kept on this device for 30 days, then deleted.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(state.audit, key = { it.id }) { row ->
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(describeAudit(row.kind, row.detail), style = MaterialTheme.typography.bodyMedium)
                Text(dateTime(row.at), style = MaterialTheme.typography.bodySmall)
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
    else -> "$kind $detail".trim()
}
