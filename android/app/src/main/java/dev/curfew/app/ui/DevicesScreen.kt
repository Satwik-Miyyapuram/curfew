package dev.curfew.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
 *
 * Drawn in the canvas's vocabulary, like every other screen: a pairing ceremony that looks like a
 * system dialog is one people click through without reading, and reading it is the whole point.
 */
@Composable
fun DevicesScreen(model: CurfewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val sync = state.sync
    val clipboard = LocalClipboardManager.current
    var typed by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }

    /**
     * The device whose removal is being confirmed — F-14.
     *
     * It was a `Tap` straight to `model.revokeDevice`, which made it the **only destructive action in
     * the app with no confirmation**. Removing a profile asks (`ProfileEditScreen`), spending a pass
     * asks, releasing a peer asks, and asking for the 24-hour release asks — all of them with the
     * consequence named. This one did not, and it is the action that can leave a lock with no way out.
     */
    var removing by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Dsn.Gutter),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Gap(Space.l)
            Title("Your devices")
            Gap(Space.s)
            Sub(
                "Curfew syncs directly between your own devices. There is no account and no " +
                    "server: nothing here leaves the network you are on.",
            )
            Gap(Space.l)
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
                val ended = count(sync.stillLocked.size, "block")
                Notice(
                    title = "Still blocking here",
                    body = "Another device says $ended ended, but the " +
                        "lock you chose is still live on this device, so it keeps blocking. It " +
                        "will end here when the lock is satisfied here.",
                    tint = Palette.live,
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
                    tint = Palette.bad,
                )
            }
        }

        if (sync.complaints.isNotEmpty()) {
            item { Notice(title = "Sync storage", body = sync.complaints.joinToString("\n")) }
        }

        item {
            DCard {
                SectionLabel("This device")
                Gap(Space.m)
                Text(
                    sync.fingerprint,
                    style = Type.h1.mono,
                    fontWeight = FontWeight.Bold,
                    color = Palette.text,
                )
                Gap(Space.xs)
                Text(
                    "Compare this with what your other device shows for it.",
                    style = Type.meta,
                    color = Palette.muted,
                )
                Gap(Space.m)
                Text(sync.deviceId, style = Type.meta, color = Palette.dim)
                Gap(Space.s)
                Text(
                    if (sync.running) {
                        "Listening on this network. " +
                            "${sync.nearby.size} of your devices in earshot."
                    } else {
                        "Not listening on this network right now."
                    },
                    style = Type.meta,
                    // Ok when listening, Muted when not — and this used to be amber for *not*
                    // listening, which is almost exactly backwards. Amber means "a block is running
                    // right now", so the neutral sentence "Not listening on this network right now"
                    // read as "something is live", on the screen a user opens to check their devices.
                    // Listening is a state that holds, which is what Ok is for; not listening is
                    // ordinary information and gets the quiet tone.
                    color = if (sync.running) Palette.ok else Palette.muted,
                )
                Gap(Space.m)
                GhostButton(text = "Sync now", onClick = model::syncNow)
            }
            Gap(Space.m)
        }

        // --- the ceremony ---
        item {
            val offer = sync.offering
            DCard {
                SectionLabel("Add a device")
                Gap(Space.m)
                if (offer == null) {
                    Text(
                        "Show a code here and read it on the other device, or paste the code the " +
                            "other device is showing.",
                        style = Type.meta,
                        color = Palette.muted,
                    )
                    Gap(Space.m)
                    PrimaryButton(text = "Show this device code", onClick = model::offerPairing)
                } else {
                    Qr(offer.json)
                    Gap(Space.m)
                    Text(
                        offer.json,
                        style = Type.micro,
                        color = Palette.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.raised, RoundedCornerShape(Dsn.CtlRadius))
                            .padding(Space.s),
                    )
                    Gap(Space.s)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Tap("Copy code", Palette.accent) { clipboard.copy(offer.json) }
                        Tap("Cancel", Palette.muted, model::cancelPairing)
                    }
                    Gap(Space.m)
                    if (offer.phrase.isBlank()) {
                        Text(
                            "Read this on your other device. It will show you a code back, and " +
                                "six digits — paste its code below to see the same six digits here.",
                            style = Type.meta,
                            color = Palette.muted,
                        )
                    } else {
                        Text(
                            offer.phrase,
                            style = Type.display.mono,
                            fontWeight = FontWeight.Bold,
                            color = Palette.text,
                        )
                        Gap(Space.s)
                        Text(
                            "Pair only if your other device is showing these same six digits. If " +
                                "it is showing anything else, someone else is trying to pair with " +
                                "this device — cancel.",
                            style = Type.meta,
                            // The one warning on the screen that a wrong answer makes permanent —
                            // and Bad rather than amber, because amber means "a block is running
                            // right now" and nothing is running while somebody reads this. A caution
                            // about an outcome that cannot be undone is a bad thing that has not
                            // happened yet, which is the nearest the palette has to it.
                            color = Palette.bad,
                        )
                        Gap(Space.m)
                        PrimaryButton(
                            text = "The digits match — pair",
                            onClick = model::confirmPairing,
                        )
                    }
                }

                Gap(Space.l)
                Field("Code from the other device", typed) { typed = it }
                Gap(Space.m)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Tap("Paste", Palette.accent) {
                        clipboard.getText()?.text?.let { typed = it.trim() }
                    }
                    Tap(
                        "It is showing a code",
                        if (typed.isBlank()) Palette.dim else Palette.accent,
                    ) {
                        if (typed.isNotBlank()) {
                            model.answerPairing(typed)
                            typed = ""
                        }
                    }
                    Tap("It answered mine", if (typed.isBlank()) Palette.dim else Palette.accent) {
                        if (typed.isNotBlank()) {
                            model.readReply(typed)
                            typed = ""
                        }
                    }
                }
            }
            Gap(Space.m)
        }

        item {
            DCard {
                SectionLabel("Through a folder")
                Gap(Space.m)
                Text(
                    "For devices that are never on the same network. Point both at one folder a " +
                        "file-sync app keeps in step; Curfew leaves signed updates there and reads " +
                        "what the other device left.",
                    style = Type.meta,
                    color = Palette.muted,
                )
                Gap(Space.m)
                Field("Folder path", folder) { folder = it }
                Gap(Space.m)
                Tap("Exchange now", if (folder.isBlank()) Palette.dim else Palette.accent) {
                    if (folder.isNotBlank()) model.folderPass(folder)
                }
            }
            Gap(Space.l)
            SectionLabel(if (sync.peers.isEmpty()) "No devices paired yet" else "Paired")
            Gap(Space.m)
        }

        items(sync.peers, key = { it.id }) { peer ->
            DCard(modifier = Modifier.padding(bottom = Space.s), padding = 16.dp) {
                Text(
                    peer.identity.name,
                    style = Type.body,
                    fontWeight = FontWeight.Bold,
                    color = if (peer.isActive) Palette.text else Palette.dim,
                )
                Text(peer.id, style = Type.meta, color = Palette.dim)
                Gap(Space.s)
                Text(
                    when {
                        !peer.isActive -> "Removed. Nothing it says is listened to."
                        peer.id in sync.nearby -> "On this network now."
                        else -> "Not seen on this network recently."
                    },
                    style = Type.meta,
                    color = if (peer.isActive && peer.id in sync.nearby) {
                        Palette.ok
                    } else {
                        Palette.muted
                    },
                )
                if (peer.isActive) {
                    Gap(Space.m)
                    Tap("Remove this device", Palette.bad) { removing = peer.id }
                    Gap(Space.s)
                    Text(
                        "Removing it does not end any block it started here.",
                        style = Type.meta,
                        color = Palette.dim,
                    )
                }
            }
        }
        item { Gap(Dsn.BottomRoom) }
    }

    // The confirmation, which this action did not have — F-14.
    removing?.let { id ->
        val peer = sync.peers.firstOrNull { it.id == id }
        val name = peer?.identity?.name ?: id
        // Named only when true. A lock that awaits this device loses its exit the moment it goes, and
        // that is the one consequence the old copy did not mention: it said removal "does not end any
        // block it started here", which is about blocks, not about ways out.
        val awaiting = locksAwaitingDevice(state.weekly, state.calendarRules, id)
        DConfirm(
            title = "Stop listening to $name?",
            body = buildString {
                append("It will be ignored from now on, and anything it says will be refused.")
                if (awaiting > 0) {
                    append("\n\n")
                    append(
                        if (awaiting == 1) {
                            "One of your schedules waits for this device specifically to let it go. "
                        } else {
                            "$awaiting of your schedules wait for this device specifically to let go. "
                        },
                    )
                    append(
                        "Removing it leaves that lock with no way out at all — not another device, not " +
                            "a pass. Pair it again first if you still want the way out you built.",
                    )
                }
            },
            dismiss = "Keep it",
            confirm = "Remove it",
            destructive = true,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                model.revokeDevice(id)
            },
        )
    }
}

/** Something worth stopping for: a card with a coloured edge and, sometimes, one way to dismiss it. */
@Composable
private fun Notice(
    title: String,
    body: String,
    tint: Color = Palette.accent,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Space.m)
            .clip(RoundedCornerShape(Dsn.CardRadius))
            .background(tint.copy(alpha = 0.08f))
            .padding(Dsn.CardPad)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            title,
            style = Type.body,
            fontWeight = FontWeight.Bold,
            color = Palette.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            body,
            style = Type.meta,
            color = Palette.muted,
            modifier = Modifier.padding(top = Space.s),
        )
        action?.let { (label, onClick) ->
            Gap(Space.m)
            Tap(label, tint, onClick)
        }
    }
}

/** A text action, sized like a pill so it is a target rather than a word. */
@Composable
private fun Tap(text: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            
            .background(Palette.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.meta, fontWeight = FontWeight.Bold, color = tint)
    }
}

/**
 * A single-line field, drawn rather than themed.
 *
 * A Material text field brings its own container, floating label and focus colour, none of which
 * belong on these cards — and a pairing code pasted into something that looks like a different app
 * is a pairing code people hesitate over.
 */
@Composable
private fun Field(hint: String, value: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(Dsn.CtlRadius))
            .background(Palette.raised)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, style = Type.body, color = Palette.dim)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.body.copy(color = Palette.text),
            cursorBrush = SolidColor(Palette.accent),
            modifier = Modifier.fillMaxWidth(),
        )
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
        Text(text, style = Type.meta, color = Palette.muted)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing code for the other device to read",
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(Dsn.CtlRadius)),
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
