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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 */
@Composable
fun ChallengeDialog(
    challenge: Challenge,
    onDismiss: () -> Unit,
    onSatisfied: () -> Unit,
) {
    var input by remember(challenge) { mutableStateOf("") }
    val satisfied = Challenge.isSatisfied(challenge, input)

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
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = challenge is Challenge.Math,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (challenge is Challenge.Math) {
                            KeyboardType.Number
                        } else {
                            KeyboardType.Text
                        },
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSatisfied, enabled = satisfied) { Text("End the session") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep going") } },
    )
}
