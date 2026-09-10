package dev.curfew.app.block

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.app.curfew
import dev.curfew.app.ui.CurfewTheme
import dev.curfew.app.ui.Palette
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
                            // The block that put this screen here is over. Closing returns the
                            // user to whatever they were doing rather than leaving a screen up
                            // that says a session is running when none is.
                            onEnded = { finish() },
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

        fun intent(
            context: Context,
            target: String,
            reason: dev.curfew.policy.BlockReason,
            profileName: String = reason.profile,
        ): Intent =
            Intent(context, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_PROFILE, profileName)
                .putExtra(EXTRA_EXPLANATION, explain(reason, profileName))

        fun delayIntent(context: Context, target: String, seconds: Int): Intent =
            Intent(context, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_DELAY, seconds)

        /** Plain language, in the user's own terms, for every reason the core can give. */
        fun explain(
            reason: dev.curfew.policy.BlockReason,
            profileName: String = reason.profile,
        ): String = when (reason) {
            is dev.curfew.policy.BlockReason.Blocked ->
                "This is blocked while $profileName is running."
            is dev.curfew.policy.BlockReason.NotAllowlisted ->
                "$profileName only allows a few apps, and this is not one of them."
            is dev.curfew.policy.BlockReason.BudgetExhausted ->
                "You have used all ${reason.seconds / 60} minutes of your time here today."
            is dev.curfew.policy.BlockReason.LaunchLimitReached ->
                "You have opened this ${reason.count} times today, which was the limit you set."
        }
    }
}

/**
 * What the user actually sees when a blocked app opens.
 *
 * It names the app rather than its package, says which profile is running and until when, and
 * counts the time down — because "blocked" without an end is indistinguishable from broken, and a
 * user who cannot tell how long this lasts goes looking for a way out instead of waiting.
 *
 * The only button leaves. There is no "unblock" here on purpose: ending a session happens in
 * Curfew's own UI, where the lock the user asked for is enforced. The emergency pass is named but
 * not offered as a button — it is spent deliberately, from inside the app, not from the screen a
 * frustrated thumb is already on.
 */
@Composable
private fun BlockScreen(
    target: String,
    profile: String,
    explanation: String,
    onClose: () -> Unit,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    val lock by context.curfew.lock.collectAsStateWithLifecycle()
    val endsAt = lock.endsAt
    val running by context.curfew.activeProfiles.collectAsStateWithLifecycle()

    // A block screen that outlives its block is worse than no block screen at all: it sat there
    // reading "Distractions is running" for as long as the phone was left alone, several minutes
    // after the session had ended itself on time. So this screen watches the thing that justifies
    // it, and leaves when that thing is gone.
    //
    // It waits to have seen the session running at least once first. The runtime's state arrives a
    // moment after the activity does, and closing on that empty first value would dismiss every
    // block screen before it had drawn.
    var sawItRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running, profile) {
        val stillRunning = profile.isEmpty() ||
            running.any { it.equals(profile, ignoreCase = true) }
        if (stillRunning) {
            sawItRunning = true
        } else if (sawItRunning) {
            onEnded()
        }
    }
    val name = remember(target) { appLabel(context, target) }
    val passes = remember(lock) { runCatching { context.curfew.passesRemaining() }.getOrDefault(0) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            now = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Not flat ink: a ground that warms towards the amber the app uses for "running", so
            // the block screen belongs to the same world as the dial that started it.
            .background(
                Brush.verticalGradient(
                    listOf(Palette.Ink, Palette.Surface, Palette.Live.copy(alpha = 0.07f)),
                ),
            )
            .padding(horizontal = 34.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Palette.Live.copy(alpha = 0.12f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("🔒", fontSize = 36.sp)
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "$name is blocked",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = Palette.Text,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            reasonLine(profile, explanation, endsAt),
            fontSize = 15.sp,
            lineHeight = 23.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )

        if (endsAt != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                countdown(endsAt - now),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.5).sp,
                color = Palette.Live,
                // The number changes every second; a screen reader is told the block and its end
                // once, not sixty times a minute.
                modifier = Modifier.semantics { contentDescription = "" },
            )
        }

        Spacer(Modifier.height(34.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                // The same pane of glass as the nav bar: translucent ground, lit top edge.
                .background(Palette.Raised.copy(alpha = 0.88f))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Back to my home screen",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Text,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (passes > 0) {
                "Emergency pass · ${if (passes == 1) "1 left" else "$passes left"} this month"
            } else {
                "No emergency pass left this month"
            },
            fontSize = 13.sp,
            color = Palette.Dim,
        )
    }
}

/**
 * The app's own name.
 *
 * Targets arrive as keys — "app:com.example" — so the kind is stripped before the package manager
 * is asked. If the package cannot be resolved (an uninstalled app, a website rule) the key is
 * described in words rather than printed raw: an id on this screen reads as a crash.
 */
private fun appLabel(context: Context, target: String): String {
    val value = target.substringAfter(':', target)
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(value, 0)).toString()
    }.getOrElse { dev.curfew.app.ui.describeTarget(target) }
}

/** One sentence: which profile, until when, and why it is running. */
private fun reasonLine(profile: String, explanation: String, endsAt: Long?): String {
    if (profile.isBlank()) return explanation
    val until = endsAt?.let { " until ${clockAt(it)}" }.orEmpty()
    return "$profile is running$until. $explanation"
}

private fun clockAt(epochSeconds: Long): String {
    val time = java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

/** "1:12" for an hour and twelve minutes; "4:09" for four minutes and nine seconds under an hour. */
private fun countdown(secondsLeft: Long): String {
    val left = secondsLeft.coerceAtLeast(0)
    return if (left >= 3600) {
        "%d:%02d".format(left / 3600, (left % 3600) / 60)
    } else {
        "%d:%02d".format(left / 60, left % 60)
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
            modifier = Modifier
                .padding(top = 12.dp)
                // A screen reader is told when the wait is over, and not once a second on the way
                // there: `Polite` waits for a pause, and the description only changes at the end.
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (remaining > 0) {
                        "Waiting $remaining seconds before $target opens."
                    } else {
                        "The wait is over. You can open $target."
                    }
                },
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
