package dev.curfew.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Whether Curfew is actually working, said without euphemism.
 *
 * A blocker that has quietly stopped enforcing is worse than no blocker, because the user is still
 * relying on it. So this screen leads with the plain answer, lists every permission with what is
 * lost while it is missing, and never phrases a missing permission as a feature the user might
 * enjoy turning on.
 */
@Composable
fun HealthScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The next refresh reads the real state; the result itself adds nothing. */ }

    val missingRequired = state.grants.filter { it.grant.required && !it.granted }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    if (missingRequired.isEmpty()) "Curfew can enforce" else "Curfew cannot enforce",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (missingRequired.isEmpty()) {
                        "Everything it needs to block an app is in place."
                    } else {
                        "Nothing is being blocked. " +
                            missingRequired.joinToString(" ") { it.grant.cost }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        items(state.grants, key = { it.grant.name }) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(entry.grant.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (entry.granted) "Granted" else "Not granted",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        entry.grant.because,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (!entry.granted) {
                        Text(
                            "Without it: ${entry.grant.cost}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(onClick = {
                            val permission = entry.grant.runtimePermission()
                            val settings = entry.grant.settingsIntent(context)
                            when {
                                permission != null -> requestPermission.launch(permission)
                                settings != null ->
                                    context.startActivity(
                                        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                            }
                        }) {
                            Text("Grant")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Curfew has no internet permission at all, so nothing it records can leave this " +
                    "device even if it wanted to. Its database is encrypted with a key held by " +
                    "this device's keystore.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
