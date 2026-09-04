package dev.curfew.app.block

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.curfew.app.ui.CurfewTheme
import kotlinx.coroutines.delay

/**
 * The screen a blocked app is replaced by.
 *
 * It says what happened, which profile asked for it, and when it ends — because the alternative,
 * an app that simply closes with no explanation, is how a blocker becomes something the user fights
 * rather than something they chose. There is no "unblock" button here on purpose: ending a session
 * happens in Curfew's own UI, where the lock the user asked for is enforced.
 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
        val explanation = intent.getStringExtra(EXTRA_EXPLANATION).orEmpty()
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY, 0)

        setContent {
            CurfewTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (delaySeconds > 0) {
                        DelayScreen(
                            target = target,
                            seconds = delaySeconds,
                            onProceed = { finish() },
                            onGiveUp = { goHome() },
                        )
                    } else {
                        BlockScreen(
                            target = target,
                            profile = profile,
                            explanation = explanation,
                            onClose = { goHome() },
                        )
                    }
                }
            }
        }
    }

    /**
     * Back and Home both leave the blocked app rather than returning to it. Anything else turns the
     * block screen into a single tap the user learns to dismiss.
     */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_EXPLANATION = "explanation"
        private const val EXTRA_DELAY = "delay"

        fun intent(context: Context, target: String, reason: dev.curfew.policy.BlockReason): Intent =
            Intent(context, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_PROFILE, reason.profile)
                .putExtra(EXTRA_EXPLANATION, explain(reason))

        fun delayIntent(context: Context, target: String, seconds: Int): Intent =
            Intent(context, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_DELAY, seconds)

        /** Plain language, in the user's own terms, for every reason the core can give. */
        fun explain(reason: dev.curfew.policy.BlockReason): String = when (reason) {
            is dev.curfew.policy.BlockReason.Blocked ->
                "This is blocked while ${reason.profile} is running."
            is dev.curfew.policy.BlockReason.NotAllowlisted ->
                "${reason.profile} only allows a few apps, and this is not one of them."
            is dev.curfew.policy.BlockReason.BudgetExhausted ->
                "You have used all ${reason.seconds / 60} minutes of your time here today."
            is dev.curfew.policy.BlockReason.LaunchLimitReached ->
                "You have opened this ${reason.count} times today, which was the limit you set."
        }
    }
}

@Composable
private fun BlockScreen(
    target: String,
    profile: String,
    explanation: String,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Blocked by Curfew", style = MaterialTheme.typography.headlineMedium)
        Text(
            explanation,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            target,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(onClick = onClose, modifier = Modifier.padding(top = 32.dp)) { Text("Close") }
    }
}

/**
 * The pause before a delayed app opens.
 *
 * The countdown is not a punishment; it is the gap in which the impulse that opened the app passes.
 * Leaving is one tap and always available — this is a speed bump, and it says so.
 */
@Composable
private fun DelayScreen(
    target: String,
    seconds: Int,
    onProceed: () -> Unit,
    onGiveUp: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(target, seconds) {
        // Elapsed real time, not a count of ticks: a coroutine that is descheduled while the screen
        // is off must not shorten the wait.
        val until = SystemClock.elapsedRealtime() + seconds * 1000L
        while (true) {
            val left = ((until - SystemClock.elapsedRealtime()) / 1000).toInt()
            remaining = left.coerceAtLeast(0)
            if (left <= 0) break
            delay(200)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Wait a moment", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (remaining > 0) "$remaining seconds" else "You can go ahead now.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = onProceed,
            enabled = remaining <= 0,
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Text("Open $target")
        }
        TextButton(onClick = onGiveUp) { Text("Not now") }
    }
}
