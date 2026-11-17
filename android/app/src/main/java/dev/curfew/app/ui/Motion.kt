package dev.curfew.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * The pieces the juice is made of, each one small enough to be argued about on its own.
 *
 * The rules that keep this from becoming a fairground:
 *
 *  - **Motion never carries information on its own.** Every animated thing also states itself in a
 *    word or a number that does not move. `docs/design.md` section 8 forbids status by colour alone;
 *    the same argument applies to status by motion alone, and it applies harder, because the reader
 *    who cannot see the wobble is the reader who most needs the sentence.
 *  - **Nothing animates on a loop**, with one named exception: [heartbeat], which runs only while a
 *    block is live. There is no idle shimmer. Everything else here is a response to a finger, an
 *    arrival, or a one-shot event, so a screen with nothing running is completely still.
 *  - **Everything defers to [Juice.moves].** One platform setting turns all of it off, and every
 *    effect below checks it rather than each screen deciding for itself.
 */

/**
 * A press that pushes back: the control shrinks under the finger and springs past its size on release.
 *
 * The scale is deliberately small — 0.94 at the deepest — because this is a control a person taps
 * dozens of times a day and a button that lunges is a button that gets tiring. What sells it is the
 * *release*: coming back with [Juice.snap] overshoots slightly, which is the whole difference
 * between a thing that was pressed and a thing that faded.
 */
@Composable
fun pressBounce(
    interaction: MutableInteractionSource,
    depth: Float = 0.94f,
): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && Juice.moves) depth else 1f,
        animationSpec = if (pressed) Juice.over(70) else Juice.snap(),
        label = "press",
    )
    return scale
}

/** Remembers the interaction source a press effect needs, so a caller does not have to. */
@Composable
fun rememberPress(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * A shake, for exactly one kind of event: **a refusal**.
 *
 * The core saying no is the moment the app's authority is most visible and most likely to be
 * mistaken for a bug. A shake says *something heard you and declined*, and it is used nowhere else
 * — not on an error the user caused, not on a validation failure, because if everything shakes then
 * nothing does.
 *
 * [trigger] is a [MutableTrigger], so two refusals in a row play twice. The offset it applies is an
 * `IntOffset` in whole pixels rather than a fractional layer translation, deliberately: a shake that
 * lands on half-pixels resamples every edge in the app, and this design is made of hard 3px borders
 * that would visibly shimmer.
 */
@Composable
fun Modifier.shakeOn(trigger: MutableTrigger, amplitudeDp: Float = Juice.SHAKE_DP): Modifier {
    if (!Juice.moves) return this
    val progress = remember { Animatable(1f) }
    val density = LocalDensity.current
    LaunchedEffect(trigger.count) {
        if (trigger.count == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, Juice.over(Juice.SHAKE_MILLIS, LinearEasing))
    }
    val px = with(density) { amplitudeDp.dp.toPx() }
    val offset = Juice.shakeOffset(progress.value, px)
    return this.offset { offset }
}

/**
 * The arrival of a thing: it drops in slightly small and slightly low, then springs onto its mark.
 *
 * [index] staggers a list, so a group of rows lands in a fast cascade rather than all at once —
 * which is what makes a screen read as *assembled* rather than *switched on*. The delay is capped,
 * because a list of thirty rows should not take two seconds to appear, and a reader who scrolls
 * during the animation should never be waiting on it.
 */
@Composable
fun Modifier.landIn(index: Int = 0, from: Float = 24f): Modifier {
    if (!Juice.moves) return this
    val visible = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index.coerceAtMost(8) * 45L * Juice.strength()).toLong())
        visible.animateTo(1f, Juice.land())
    }
    val p = visible.value
    return this.graphicsLayer {
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * from
        scaleX = 0.96f + 0.04f * p
        scaleY = 0.96f + 0.04f * p
    }
}

/**
 * A number that is worth watching change.
 *
 * Two effects, and they are different jobs. The **count-up** runs when the value changes, eased so
 * it decelerates onto the answer rather than stopping dead. The **punch** is a scale and colour hit
 * on the frame it is committed, which is what draws the eye to a number the user just caused.
 *
 * Never applied to a countdown. A countdown is read, not admired, and a ticking number that also
 * scales is a number that is hard to read at exactly the moment it matters.
 */
@Composable
fun PopNumber(
    value: Int,
    modifier: Modifier = Modifier,
    trigger: MutableTrigger? = null,
    segments: Int = 12,
    content: @Composable (display: Int, punch: Float) -> Unit,
) {
    val shown = remember { Animatable(value.toFloat()) }
    val punch = remember { Animatable(0f) }
    LaunchedEffect(value) {
        if (!Juice.moves) {
            shown.snapTo(value.toFloat())
            return@LaunchedEffect
        }
        // Steps rather than a smooth climb, because this design counts in whole things. A number
        // that shows 4.7 is a number nobody wrote.
        val from = shown.value
        val steps = (kotlin.math.abs(value - from).coerceAtMost(segments.toFloat())).toInt()
            .coerceAtLeast(1)
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            shown.snapTo(lerp(from, value.toFloat(), t))
            kotlinx.coroutines.delay((28 * Juice.strength()).toLong())
        }
        shown.snapTo(value.toFloat())
    }
    LaunchedEffect(trigger?.count) {
        if (trigger == null || trigger.count == 0 || !Juice.moves) return@LaunchedEffect
        punch.snapTo(1f)
        punch.animateTo(0f, Juice.over(320, Juice.out))
    }
    content(shown.value.toInt(), punch.value)
}

/**
 * A burst of squares, thrown outward from the middle of whatever it is wrapped around.
 *
 * The one genuinely celebratory thing in the app, and it is spent on exactly two events: a block
 * that the user committed, and a streak milestone. Both are moments where the user did something
 * hard on purpose and the app's entire previous vocabulary for it was a sentence. Section 9 of the
 * design document is about not overstating what Curfew does; it says nothing against the app
 * acknowledging what the *user* did.
 *
 * Squares, not confetti: round particles would be the only circles outside the dial, and squares
 * let each piece keep the 1px ink edge the rest of the app draws. They are also cheap — twelve
 * rectangles with no rasterisation, no blur, no shadow — because a celebration that drops frames is
 * a celebration that reads as a stutter.
 */
@Composable
fun BoxScope.Burst(
    trigger: MutableTrigger,
    colour: Color = Palette.accent,
    pieces: Int = 12,
    spread: Float = 120f,
) {
    if (!Juice.moves) return
    val life = remember { Animatable(1f) }
    LaunchedEffect(trigger.count) {
        if (trigger.count == 0) return@LaunchedEffect
        life.snapTo(0f)
        life.animateTo(1f, Juice.over(620, Juice.out))
    }
    val t = life.value
    if (t >= 1f) return
    val density = LocalDensity.current
    val reach = with(density) { spread.dp.toPx() }
    // A fixed set of angles with varied lengths, so the burst looks thrown rather than metered.
    for (i in 0 until pieces) {
        val angle = (i.toFloat() / pieces) * 2f * Math.PI.toFloat()
        val length = reach * (0.55f + 0.45f * ((i * 37 % 11) / 10f))
        val dx = kotlin.math.cos(angle) * length * t
        val dy = kotlin.math.sin(angle) * length * t
        val size = (2 + (i * 13 % 3)).dp
        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(dx.toInt(), dy.toInt()) }
                .size(size)
                .background(if (i % 3 == 0) Palette.text else colour)
                .graphicsLayer { alpha = (1f - t).coerceIn(0f, 1f) },
        )
    }
}

/**
 * A ring that grows out of a control and fades, once.
 *
 * Used where a burst would be too much and nothing would be too little: the moment a block is
 * committed, and the moment a rule is saved. It is the visual equivalent of a receipt being handed
 * over — the action landed *here*, not somewhere near here.
 */
@Composable
fun BoxScope.Shockwave(
    trigger: MutableTrigger,
    colour: Color = Palette.live,
    from: Float = 0.6f,
    to: Float = 2.4f,
) {
    if (!Juice.moves) return
    val ring = remember { Animatable(1f) }
    LaunchedEffect(trigger.count) {
        if (trigger.count == 0) return@LaunchedEffect
        ring.snapTo(0f)
        ring.animateTo(1f, Juice.over(480, Juice.out))
    }
    val t = ring.value
    if (t >= 1f) return
    Box(
        Modifier
            .align(Alignment.Center)
            .graphicsLayer {
                val s = lerp(from, to, t)
                scaleX = s
                scaleY = s
                alpha = ((1f - t) * 0.7f).coerceIn(0f, 1f)
            }
            .size(72.dp)
            .border(3.dp, colour),
    )
}

/**
 * A pulse that fires once per [key], for a thing that is *happening* rather than a thing that changed.
 *
 * The countdown ring uses this: one throb a second, on the second. [key] is the second itself rather
 * than a trigger, so the pulse is locked to the clock the number shows and cannot drift away from it.
 */
@Composable
fun pulseOn(key: Any?, millis: Int = 260): Float {
    val p = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (!Juice.moves) return@LaunchedEffect
        p.snapTo(1f)
        p.animateTo(0f, Juice.over(millis, Juice.out))
    }
    return p.value
}

/**
 * A gentle resting beat, for the two things that are *alive*: a running block, and a countdown.
 *
 * This is the one looping animation in the app, and it earns the exception because it is a
 * *heartbeat*, not a shimmer: the entire point of the Now screen is to answer "is something
 * happening right now", and a completely still card cannot. It stops the instant nothing is
 * running, so an idle screen is still.
 */
@Composable
fun heartbeat(active: Boolean, periodMillis: Int = 1600): Float {
    val beat = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active || !Juice.moves) {
            beat.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            beat.animateTo(1f, Juice.over((periodMillis * 0.18f).toInt(), Juice.out))
            beat.animateTo(0f, Juice.over((periodMillis * 0.82f).toInt(), Juice.inn))
        }
    }
    return beat.value
}