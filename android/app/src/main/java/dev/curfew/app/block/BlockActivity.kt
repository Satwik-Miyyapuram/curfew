package dev.curfew.app.block

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Browser
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.curfew.app.ui.Type
import dev.curfew.app.ui.Space
import dev.curfew.app.ui.LockActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.curfew.app.R
import dev.curfew.app.curfew
import dev.curfew.app.enforce.Watchers
import dev.curfew.app.ui.CurfewTheme
import dev.curfew.app.ui.glass
import dev.curfew.app.ui.Palette
import dev.curfew.app.ui.Dsn
import dev.curfew.app.ui.GhostButton
import dev.curfew.app.ui.PrimaryButton
import dev.curfew.app.ui.plate
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
        dev.curfew.app.ui.Appearance.attach(this)
        dev.curfew.app.ui.Juice.attach(this)
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
        val explanation = intent.getStringExtra(EXTRA_EXPLANATION).orEmpty()
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY, 0)
        val isWeb = target.startsWith("web:")

        onBackPressedDispatcher.addCallback(this) {
            if (isWeb) openNewTab() else goHome()
        }

        setContent {
            CurfewTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (delaySeconds > 0) {
                        DelayScreen(
                            target = target,
                            seconds = delaySeconds,
                            onProceed = { finish() },
                            onGiveUp = { if (isWeb) openNewTab() else goHome() },
                        )
                    } else {
                        BlockScreen(
                            target = target,
                            isWeb = isWeb,
                            profile = profile,
                            explanation = explanation,
                            onOpenNewTab = { openNewTab() },
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

    /**
     * Opens a fresh tab in the active browser, leaving the blocked URL behind.
     *
     * The browser comes from [Watchers], which the URL reader sets while the user is in a browser and
     * clears when they leave one or the service stops. Advisory: pinning the intent to a package that
     * turned out to be wrong would fail rather than fall back, so the failure path below re-sends the
     * intent unpinned and lets the system choose.
     */
    private fun openNewTab() {
        val browserPackage = Watchers.activeBrowserPackage
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
            putExtra("create_new_tab", true)
            if (!browserPackage.isNullOrBlank()) {
                `package` = browserPackage
                putExtra(Browser.EXTRA_APPLICATION_ID, browserPackage)
            }
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
                putExtra("create_new_tab", true)
            }
            runCatching { startActivity(fallback) }
        }
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
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_PROFILE, profileName)
                .putExtra(EXTRA_EXPLANATION, explain(reason, profileName))

        fun delayIntent(context: Context, target: String, seconds: Int): Intent =
            Intent(context, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_DELAY, seconds)

        /** Plain language, in the user's own terms, for every reason the core can give. */
        fun explain(
            reason: dev.curfew.policy.BlockReason,
            profileName: String = reason.profile,
        ): String = when (reason) {
            // [reasonLine] already names the profile and its end; saying "while X is running" again
            // read as a stutter.
            is dev.curfew.policy.BlockReason.Blocked -> "This is on its block list."
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
    isWeb: Boolean,
    profile: String,
    explanation: String,
    onOpenNewTab: () -> Unit,
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
    // Why there are none, when there are none. Without this the screen said "No emergency pass left
    // this month" to a user who had never been given one — a default install configures no passes at
    // all — which reads as "you spent them". The refusal already carries the honest sentence, and
    // `Format.describePassRefusal` is the one place that copy lives.
    val passRefusal = remember(lock) { runCatching { context.curfew.passRefusal() }.getOrNull() }

    // The ways out, on the screen that is actually blocking them.
    //
    // **This reverses a deliberate earlier decision**, and the reversal is the point. The screen used
    // to name the emergency pass without offering it, reasoning that a pass should be spent from
    // inside Curfew rather than from the screen an annoyed thumb is already on. That reasoning was
    // about one scarce resource and it generalised into a rule that left the only screen a person is
    // genuinely stuck on with no way forward at all: "back to home" and a note about a pass they
    // could not spend. The screen exists because somebody wanted the app; sending them away to find
    // Curfew, navigate to the block and find it again is friction in the wrong place, and it never
    // made the pass harder to spend — the puzzle does that, and the puzzle works here identically.
    //
    // Pause is deliberately absent on a locked session: see [LockActions]. What a lock permits is
    // what this screen offers, and nothing more.
    var busy by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var askingRelease by remember { mutableStateOf(false) }
    var askingPass by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The screen holds a *merged* [LockSet], which carries conditions and end times but no id —
    // ids belong to sessions. So the session this screen is about is found in the running list by
    // profile, which is how the block screen was opened in the first place: the target was blocked
    // because that profile is running.
    //
    // Read at the moment an action is taken rather than held in state, because a session can end
    // while this screen is up — the countdown reaching zero is the common case — and a stale id
    // would send `endSession` after a block that is already over.
    fun runningIdNow(): String? = runCatching {
        context.curfew.policy.sessions().running
            .firstOrNull { it.profile.equals(profile, ignoreCase = true) }
            ?.id
    }.getOrNull()

    /** Everything on this screen ends or releases through the runtime, then leaves. */
    fun after(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        refusal = null
        scope.launch {
            runCatching { work() }
                .onFailure {
                    refusal = if (it is dev.curfew.policy.NoPass) {
                        dev.curfew.app.ui.describePassRefusal(it.refusal, System.currentTimeMillis() / 1000)
                    } else {
                        it.message ?: "That did not work."
                    }
                }
            busy = false
        }
    }
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
            // Flat ground, not a gradient. `docs/design.md` section 3.1: *"No gradients, glass
            // effects, soft shadows, floating pills or glossy surfaces."* The warmth this screen
            // used to wash towards is carried by the run colour on the badge and the countdown
            // instead, which is where it actually means something.
            .background(Palette.ground)
            .padding(horizontal = Space.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The same solid tile every other glyph in the app sits on, in the colour of a running block.
        dev.curfew.app.ui.Glyph(Palette.live, size = 96.dp) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.surface, modifier = Modifier.size(48.dp))
        }

        Spacer(Modifier.height(Space.xxl))
        Text(
            "$name is blocked",
            style = Type.h1,
            fontWeight = FontWeight.Bold,
            color = Palette.text,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Space.m))
        Text(
            reasonLine(profile, explanation, endsAt),
            style = Type.body,
            color = Palette.muted,
            textAlign = TextAlign.Center,
        )

        if (endsAt != null) {
            Spacer(Modifier.height(Space.m))
            Text(
                countdown(endsAt - now),
                style = Type.mega,
                fontWeight = FontWeight.Bold,
                color = Palette.live,
                // The number changes every second; a screen reader is told the block and its end
                // once, not sixty times a minute.
                modifier = Modifier.semantics { contentDescription = "" },
            )
        }

        Spacer(Modifier.height(Space.xxl))
        if (isWeb) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .plate(fill = Palette.raised, offset = Dsn.ShadowControl)
                    .clickable(onClick = onOpenNewTab),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.block_open_new_tab),
                    style = Type.h3,
                    color = Palette.text,
                )
            }
            Spacer(Modifier.height(Space.m))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.block_back_to_home),
                    style = Type.body,
                    color = Palette.muted,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .plate(fill = Palette.raised, offset = Dsn.ShadowControl)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.block_back_to_home),
                    style = Type.h3,
                    color = Palette.text,
                )
            }
        }

        Spacer(Modifier.height(Space.xl))
        LockActions(
            name = name,
            which = dev.curfew.app.ui.LockActionsWhen.LOCKED_SCREEN,
            endsAt = endsAt,
            now = now,
            passesLeft = passes,
            locked = lock.isLocked,
            releaseAsked = lock.delayedReleaseAt != null,
            // No tag reader here: the scanner lives in Curfew's own UI, and a button that opens
            // nothing is worse than no button.
            wantsTag = false,
            // Null on a locked session, which is what makes the button disappear rather than lie.
            // The pause is taken here, behind the same sum [PausePuzzleSheet] asks everywhere: the
            // core has no pause primitive, so a pause ends the session and says so.
            onPause = if (lock.isLocked) null else ({ pausing = true }),
            onEnd = { after { runningIdNow()?.let { context.curfew.endSession(it) } } },
            onRelease = { askingRelease = true },
            onPresentTag = {},
            onEmergency = { askingPass = true },
        )

        // The same two confirmations the Now screen asks, with the same words: both actions are
        // irreversible, and the screen they are taken from does not change what they cost.
        if (askingPass) {
            dev.curfew.app.ui.DConfirm(
                title = stringResource(R.string.confirm_pass_title),
                body = stringResource(R.string.confirm_pass_body, profile),
                dismiss = "Keep it",
                confirm = stringResource(R.string.confirm_pass_confirm),
                destructive = true,
                onDismiss = { askingPass = false },
                onConfirm = {
                    askingPass = false
                    after { runningIdNow()?.let { context.curfew.spendPass(it) } }
                },
            )
        }
        if (pausing) {
            dev.curfew.app.ui.PausePuzzleSheet(
                name = name,
                now = now,
                onDismiss = { pausing = false },
                onPaused = { _, _ ->
                    pausing = false
                    after { runningIdNow()?.let { context.curfew.endSession(it) } }
                },
            )
        }
        if (askingRelease) {
            dev.curfew.app.ui.DConfirm(
                title = stringResource(R.string.confirm_release_title, profile),
                body = stringResource(R.string.confirm_release_body),
                dismiss = "Not yet",
                confirm = stringResource(R.string.confirm_release_confirm),
                destructive = true,
                onDismiss = { askingRelease = false },
                onConfirm = {
                    askingRelease = false
                    after { runningIdNow()?.let { context.curfew.requestRelease(it) } }
                },
            )
        }

        if (refusal != null) {
            Spacer(Modifier.height(Space.m))
            Text(
                refusal.orEmpty(),
                style = Type.meta,
                color = Palette.bad,
                textAlign = TextAlign.Center,
            )
        }

        // Only a lock needs a way past it. With End now on screen, pass talk is noise.
        if (lock.isLocked) {
            Spacer(Modifier.height(Space.m))
            Text(
                if (passes > 0) {
                    "Emergency pass · ${if (passes == 1) "1 left" else "$passes left"} this month"
                } else if (passRefusal != null) {
                    // The sentence names what is missing and, where there is one, when it comes back.
                    // A blocked screen is the worst place in the app to be vague.
                    dev.curfew.app.ui.describePassRefusal(passRefusal, now)
                } else {
                    "No emergency pass right now"
                },
                style = Type.meta,
                color = Palette.dim,
                textAlign = TextAlign.Center,
            )
        }
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

/** An epoch second as a wall clock, in the device's own format. See [clockTime]. */
private fun clockAt(epochSeconds: Long): String = dev.curfew.app.ui.clockTime(epochSeconds)

/**
 * "1:12" for an hour and twelve minutes; "14m 09s" below that; "48s" under a minute.
 *
 * The seconds are kept for this screen and not for the Now dial, and that is the whole of the
 * difference between them: this is the screen somebody is sitting in front of, waiting, and a number
 * that visibly moves is the point. What is *not* kept is the old format — this used to render
 * fourteen minutes as "14:00", which reads as two in the afternoon rather than as a countdown, and
 * which disagreed with the Now screen's "14m" for the very same session. Both now come from
 * [dev.curfew.app.ui.countdown], so they cannot drift again.
 */
private fun countdown(secondsLeft: Long): String =
    dev.curfew.app.ui.countdown(secondsLeft, withSeconds = true)

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
        modifier = Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Wait a moment", style = Type.h1)
        Text(
            if (remaining > 0) "$remaining seconds" else "You can go ahead now.",
            style = Type.body,
            modifier = Modifier
                .padding(top = Space.m)
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
        Box(Modifier.fillMaxWidth().padding(top = Space.xxl)) {
            PrimaryButton(
                text = "Open $target",
                enabled = remaining <= 0,
                onClick = onProceed,
            )
        }
        Spacer(Modifier.height(Space.s))
        Box(Modifier.fillMaxWidth()) {
            GhostButton("Not now", Modifier.fillMaxWidth(), onClick = onGiveUp)
        }
    }
}
