package dev.curfew.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

/**
 * The friction, on screen.
 *
 * The confirm button stays disabled until the answer is right, rather than rejecting a wrong one
 * with an error: there is nothing to punish here, and a person halfway through retyping a sentence
 * should be able to see that they are halfway through.
 *
 * The passage has to be *typed*. It sits right there above the field, so a long-press, copy and
 * paste would answer it in a second and the friction the user set up for themselves would be
 * gone. Two things stop that: the field has no selection toolbar, so there is nothing to paste
 * from; and an edit that grows the answer by more than a keystroke's worth — a paste from the
 * keyboard's own clipboard — is dropped on the floor (see [Challenge.isTyped]).
 */
@Composable
fun ChallengeDialog(
    challenge: Challenge,
    onDismiss: () -> Unit,
    onSatisfied: () -> Unit,
) {
    var input by remember(challenge) { mutableStateOf("") }
    val satisfied = Challenge.isSatisfied(challenge, input)

    CompositionLocalProvider(LocalTextToolbar provides NoToolbar) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(challenge.prompt) },
            text = {
                Column {
                    when (challenge) {
                        is Challenge.Typing -> Text(
                            challenge.passage,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Serif,
                        )
                        is Challenge.Math -> Text(
                            "${challenge.question} = ?",
                            style = MaterialTheme.typography.headlineSmall,
                            // "×" is read out as "x" or skipped entirely by some screen readers, which
                            // turns an arithmetic problem into a guess.
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "What is ${challenge.question.replace("×", "times")}?"
                            },
                        )
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { if (Challenge.isTyped(challenge, input, it)) input = it },
                        singleLine = challenge is Challenge.Math,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (challenge is Challenge.Math) {
                                KeyboardType.Number
                            } else {
                                KeyboardType.Text
                            },
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .semantics {
                                contentDescription = when (challenge) {
                                    is Challenge.Typing -> "Type the passage here."
                                    is Challenge.Math -> "Type the answer here."
                                }
                            },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onSatisfied, enabled = satisfied) { Text("End the session") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Keep going") } },
        )
    }
}

/** A selection toolbar that never appears: no Paste, and nothing else either. */
private object NoToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}
