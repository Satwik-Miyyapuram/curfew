package dev.curfew.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset

/**
 * How this app moves, and why it moves that way.
 *
 * ## The argument, first
 *
 * A blocker's whole job is to be *believed*. Everything Curfew claims — that a block is running,
 * that a rule will fire at 22:45, that a refusal is real and not a bug — is a claim about a system
 * the user cannot see. A still screen makes those claims in the flattest possible voice, and a
 * person who does not believe the app is doing anything will fight it or uninstall it.
 *
 * Motion is how the app shows its work. A band that *grows* down the week chart is a rule arriving;
 * a dial arc that *springs* is a value being set; a screen that *shakes* when the core refuses is a
 * "no" that came from somewhere real. None of that is decoration — each one is the visual form of a
 * sentence the app is already saying in words, and the words stay exactly where they were.
 *
 * ## Why it looks like a game
 *
 * The reference is Balatro's feel, and the part of it worth stealing is not the confetti — it is the
 * **spring**. Nothing there eases politely into place; things overshoot, wobble, and settle, which
 * is what makes a static rectangle read as a physical object. So the specs below are springs rather
 * than tweens wherever a thing is *arriving*, and tweens only where a thing is *counting*, because a
 * counter that overshoots its own number is lying.
 *
 * The visual language is untouched: still square corners, still 3px ink, still no gradients and no
 * blur. Juice here is timing and displacement, not a new material.
 *
 * ## Reduced motion is not optional, and it is read from the system
 *
 * `docs/design.md` section 8 requires reduced-motion support, so this reads the platform's own
 * developer/animation scale rather than inventing a switch. At `0f` the app is completely still:
 * every spring collapses to its end state, the shake does not shake, and the particles do not
 * spawn. That is a real accessibility need — vestibular disorders make a wobbling screen genuinely
 * nauseating — and it is also the honest reason the intensity is centralised here rather than
 * sprinkled per screen: one read, one place to switch off.
 */
object Juice {

    /**
     * The platform's animation scale, read once.
     *
     * `ANIMATOR_DURATION_SCALE` is what "Remove animations" in Accessibility settings and the
     * Developer options slider both write to, so this follows the switch the user already has rather
     * than asking them to find a second one. `1f` is the normal scale.
     */
    private var scale: Float = 1f

    /** Called from each activity's `onCreate`, beside `Appearance.attach`. */
    fun attach(context: Context) {
        scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
    }

    /** Whether the user's own settings say to move at all. */
    val moves: Boolean get() = scale > 0.01f

    /** How much of a full-strength effect to apply, 0..1. Zero means still. */
    fun strength(full: Float = 1f): Float = (scale.coerceIn(0f, 1f)) * full

    /**
     * A spring for something arriving: it overshoots, wobbles once, and settles.
     *
     * `MediumBouncy`'s damping ratio is what makes a card feel *placed* rather than slid. Used for
     * scale, offset and rotation — never for a value the user is reading, because a number that
     * wobbles past its own value is a number the user misreads.
     */
    fun <T> land(): FiniteAnimationSpec<T> =
        if (moves) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(durationMillis = 0)
        }

    /**
     * A snappier spring for something that responds to a finger: a toggle, a day chip, a selection.
     *
     * Stiffer and less bouncy than [land], because feedback that arrives late reads as lag. A press
     * should feel like a switch being thrown, and a switch that wobbles for 600ms feels broken.
     */
    fun <T> snap(): FiniteAnimationSpec<T> =
        if (moves) {
            spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessHigh)
        } else {
            tween(durationMillis = 0)
        }

    /** A spring for a value that is being dragged or dialled. No overshoot: it must track the finger. */
    fun <T> track(): FiniteAnimationSpec<T> =
        if (moves) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        } else {
            tween(durationMillis = 0)
        }

    /**
     * A timed curve, for anything *counting*: a bar filling, a number ticking, a shake decaying.
     *
     * Scaled by the platform setting, so a device with animations at 0.5x gets half as long rather
     * than the same duration with half the distance.
     */
    fun <T> over(millis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis = (millis * scale).toInt().coerceAtLeast(0))

    /** A timed curve with an explicit easing, for a shake's or a burst's shape. */
    fun <T> over(millis: Int, easing: androidx.compose.animation.core.Easing): FiniteAnimationSpec<T> =
        tween(durationMillis = (millis * scale).toInt().coerceAtLeast(0), easing = easing)

    /** Decelerating, for something thrown outward: fast at the start, drifting at the end. */
    val out: androidx.compose.animation.core.Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Accelerating, for something being pulled away. */
    val inn: androidx.compose.animation.core.Easing = CubicBezierEasing(0.5f, 0f, 0.84f, 0f)

    /** The overshoot curve for a one-shot punch: past the target, then back onto it. */
    val punch: androidx.compose.animation.core.Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    /** How long a shaken screen takes to settle, in milliseconds at full scale. */
    const val SHAKE_MILLIS = 420

    /** How far a refusal shakes the page, in dp at full strength. */
    const val SHAKE_DP = 9f

    /**
     * A decaying shake offset for [progress] in `0..1`.
     *
     * Pure, so the shape of the shake can be asserted without a composition — `MotionTest` checks
     * that it starts at zero displacement, that it decays, and that it lands exactly at zero rather
     * than near it. A screen that ends its shake a pixel off is a screen with a permanent wobble.
     */
    fun shakeOffset(progress: Float, amplitude: Float): IntOffset {
        if (progress >= 1f) return IntOffset.Zero
        val decay = 1f - progress
        // Two frequencies, so the motion reads as a knock rather than as a sine wave, and the
        // horizontal is stronger than the vertical because a page that moves up and down reads as
        // scrolling.
        val x = kotlin.math.sin(progress * Math.PI.toFloat() * 6f) * amplitude * decay * decay
        val y = kotlin.math.sin(progress * Math.PI.toFloat() * 4f) * amplitude * decay * decay * 0.4f
        return IntOffset(x.toInt(), y.toInt())
    }

    /**
     * A one-shot trigger: a counter that goes up when something worth animating happens.
     *
     * Deliberately **not** a `Boolean` flag. Two refusals in a row are two events, and a flag would
     * play the second one not at all because it was already true. A counter that increments always
     * plays, and `key`-ing an effect on it is the whole mechanism.
     */
    fun trigger(): MutableTrigger = MutableTrigger()
}

/** A monotonically increasing trigger. See [Juice.trigger]. */
class MutableTrigger {
    var count: Int by mutableStateOf(0)
        private set

    /** Fire. Called from a click handler or an effect, never during composition. */
    fun fire() {
        count++
    }
}

/** Remembers a trigger for the lifetime of a composition. */
@Composable
fun rememberTrigger(): MutableTrigger = androidx.compose.runtime.remember { MutableTrigger() }