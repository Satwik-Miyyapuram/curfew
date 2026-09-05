package dev.curfew.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The other devices this one answers to, and the ceremony that adds them.
 *
 * Two things on this screen are load-bearing rather than decorative:
 *
 *  - The six-digit phrase. Nothing in the app can check that two screens showed the same digits,
 *    so the confirm button is a separate, deliberate press by a person who has looked at both. It
 *    is what stops someone on the same network from pairing themselves in and then asking this
 *    device to end its own locks.
 *  - The "still blocking" notice. When another device says a block is over and this one disagrees,
 *    the user is owed the reason rather than a silent divergence they will read as a bug.
 */
@Composable
fun DevicesScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val sync = state.sync
    val clipboard = LocalClipboardManager.current
    var typed by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    "Your devices",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Curfew syncs directly between your own devices. There is no account and no " +
                        "server: nothing here leaves the network you are on.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (!sync.available) {
            item {
                Notice(
                    title = "Sync is not running",
                    body = "It starts with the enforcement service. Turn Curfew on and come back.",
                )
            }
            return@LazyColumn
        }

        if (sync.stillLocked.isNotEmpty()) {
            item {
                Notice(
                    title = "Still blocking here",
                    body = "Another device says ${sync.stillLocked.size} block(s) ended, but the " +
                        "lock you chose is still live on this device, so it keeps blocking. It " +
                        "will end here when the lock is satisfied here.",
                    action = "Got it" to model::dismissStillLocked,
                )
            }
        }

        sync.error?.let { problem ->
            item {
                Notice(
                    title = "Sync had trouble",
                    body = "$problem\n\nBlocking is unaffected. Curfew keeps enforcing what it " +
                        "already knows whether or not it can reach your other devices.",
                )
            }
        }

        if (sync.complaints.isNotEmpty()) {
            item { Notice(title = "Sync storage", body = sync.complaints.joinToString("\n")) }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("This device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        sync.fingerprint,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Compare this with what your other device shows for it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        sync.deviceId,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        if (sync.running) {
                            "Listening on this network. " +
                                "${sync.nearby.size} of your devices in earshot."
                        } else {
                            "Not listening on this network right now."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = model::syncNow) { Text("Sync now") }
                }
            }
        }

        // --- the ceremony ---
        item {
            val offer = sync.offering
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Add a device", style = MaterialTheme.typography.titleMedium)
                    if (offer == null) {
                        Text(
                            "Show a code here and read it on the other device, or paste the code " +
                                "the other device is showing.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = model::offerPairing,
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Show this device code") }
                    } else {
                        Qr(offer.json)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            TextButton(onClick = { clipboard.copy(offer.json) }) {
                                Text("Copy code")
                            }
                            TextButton(onClick = model::cancelPairing) { Text("Cancel") }
                        }
                        if (offer.phrase.isBlank()) {
                            Text(
                                "Read this on your other device. It will show you a code back, " +
                                    "and six digits — paste its code below to see the same six " +
                                    "digits here.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                offer.phrase,
                                style = MaterialTheme.typography.displaySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                "Pair only if your other device is showing these same six digits. " +
                                    "If it is showing anything else, someone else is trying to " +
                                    "pair with this device — cancel.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = model::confirmPairing,
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("The digits match — pair") }
                        }
                    }

                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Code from the other device") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Row {
                        TextButton(
                            enabled = typed.isNotBlank(),
                            onClick = { model.answerPairing(typed); typed = "" },
                        ) { Text("It is showing a code") }
                        TextButton(
                            enabled = typed.isNotBlank(),
                            onClick = { model.readReply(typed); typed = "" },
                        ) { Text("It answered mine") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Through a folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "For devices that are never on the same network. Point both at one folder " +
                            "a file-sync app keeps in step; Curfew leaves signed updates there and " +
                            "reads what the other device left.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Folder path") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    TextButton(
                        enabled = folder.isNotBlank(),
                        onClick = { model.folderPass(folder) },
                    ) { Text("Exchange now") }
                }
            }
        }

        item {
            Text(
                if (sync.peers.isEmpty()) "No devices paired yet" else "Paired",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }

        items(sync.peers, key = { it.id }) { peer ->
            Card(
                Modifier.fillMaxWidth(),
                colors = if (peer.isActive) {
                    CardDefaults.cardColors()
                } else {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(peer.identity.name, style = MaterialTheme.typography.titleSmall)
                    Text(peer.id, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            !peer.isActive -> "Removed. Nothing it says is listened to."
                            peer.id in sync.nearby -> "On this network now."
                            else -> "Not seen on this network recently."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (peer.isActive) {
                        TextButton(onClick = { model.revokeDevice(peer.id) }) {
                            Text("Remove this device")
                        }
                        Text(
                            "Removing it does not end any block it started here.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        }
    }
}

/**
 * The pairing code as a QR image.
 *
 * Generated locally and never uploaded: the code is an offer to pair, not a secret, but sending it
 * to a QR service would mean a third party learning which devices are being paired together.
 */
@Composable
private fun Qr(text: String) {
    val bitmap = remember(text) { qrBitmap(text) }
    if (bitmap == null) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing code for the other device to read",
            modifier = Modifier.size(240.dp),
        )
    }
}

private fun qrBitmap(text: String, size: Int = 480): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

private fun ClipboardManager.copy(text: String) = setText(AnnotatedString(text))
