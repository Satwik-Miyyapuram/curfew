package dev.curfew.app.enforce

/**
 * The two edges a foreground detector needs, from whatever source of events it has.
 *
 * A deliberately tiny model rather than `UsageEvents.Event`, which is the platform's own type and
 * cannot be constructed from Kotlin — every field is getter-only in the SDK stub, so a test cannot
 * build the input the code under test is supposed to receive. Depending on it would have made this
 * logic untestable a second time, which is how it came to be wrong for as long as it was.
 */
data class UsageStep(
    val atMillis: Long,
    val kind: Kind,
    val packageName: String,
) {
    enum class Kind {
        /** An activity came to the front. `ACTIVITY_RESUMED` on API 29+, `MOVE_TO_FOREGROUND` before. */
        Resumed,

        /** An activity left the front. `ACTIVITY_PAUSED` / `MOVE_TO_BACKGROUND`. */
        Paused,

        /** An activity was destroyed rather than backgrounded. `ACTIVITY_STOPPED`. */
        Stopped,

        /** Anything else. Moves the watermark and is otherwise ignored. */
        Other,
        ;
    }
}

/**
 * Folds a batch of usage steps into "what is in front", with the two rules that were missing.
 *
 * **A usage event stream is a log, not a state.** The bug this replaces read the last
 * `MOVE_TO_FOREGROUND` in a two-minute window and called that "the app in front". That is wrong from
 * the moment the user leaves an app whose departure produces no later resume for anything else — and
 * a launcher that is already running produces none, so pressing Home left the previous app reported
 * as being in front for the rest of the window. During a block that meant the block screen kept being
 * shown over the launcher, and budget time kept being charged to an app that was not on screen.
 *
 * The two rules that make a log into a state:
 *
 *  1. A resume sets the app in front.
 *  2. A pause or stop **of that same app** clears it.
 *
 * Acts on each step in arrival order rather than deferring pauses to the end of the batch, so an app
 * that paused and resumed within one poll window is still in front — the later resume sets it again.
 * Order is the whole mechanism.
 */
class UsageEventFold {

    /**
     * The app in front, or null when nothing is.
     *
     * Carried across calls, which is the point: a poll that finds no new events must answer
     * *unchanged* rather than *unknown*, and only the previous answer can say which.
     */
    var foreground: String? = null
        private set

    /**
     * The newest step timestamp seen, in milliseconds, or zero when none has been.
     *
     * The caller asks for only what is new next time, using this. It advances on *every* step,
     * including the kinds this class ignores — leaving those behind would make the next read fetch
     * them again, which is the re-reading this exists to remove. It never goes backwards, because a
     * watermark that could move back would make the next query re-read everything in between.
     */
    var newestTimestamp: Long = 0L
        private set

    /** Fold one batch of [steps] into [foreground]. */
    fun accept(steps: List<UsageStep>) {
        for (step in steps) {
            if (step.atMillis > newestTimestamp) newestTimestamp = step.atMillis
            when (step.kind) {
                UsageStep.Kind.Resumed -> foreground = step.packageName
                UsageStep.Kind.Paused,
                UsageStep.Kind.Stopped,
                -> if (step.packageName == foreground) foreground = null
                UsageStep.Kind.Other -> Unit
            }
        }
    }

    /** Forget everything. A restart or a permission change makes the watermark meaningless. */
    fun reset() {
        foreground = null
        newestTimestamp = 0L
    }
}
