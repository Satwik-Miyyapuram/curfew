package dev.curfew.app.ui

import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import kotlin.random.Random

/**
 * The friction locks: retype a passage, or do some arithmetic.
 *
 * These exist to make ending a session a deliberate act rather than a reflex, so the bar is set at
 * "twenty seconds of attention", not "impossible". They are generated fresh every time — a fixed
 * passage becomes muscle memory within a week, at which point the lock is decoration — and the
 * answer is checked here rather than by the core, because the core has no business knowing what a
 * keyboard is.
 *
 * The check is deliberately forgiving about the things that are not the point: surrounding space,
 * a double space, the case of a letter. It is unforgiving about the words themselves.
 */
sealed interface Challenge {

    /** What the user is asked to do, in one sentence. */
    val prompt: String

    /**
     * Which kind of challenge this is.
     *
     * Exposed so the screen can find the **session's own** `Lock.Challenge` again once the answer is
     * right, rather than handing the core a lock value it invented here. The core compares locks by
     * value, so a reconstructed one would work today and would quietly stop working the day the
     * variant grew a field — which is the kind of breakage that shows up as "the right answer did
     * not open the lock".
     */
    val kind: ChallengeKind

    /** The text to reproduce, shown above the field. */
    data class Typing(val passage: String) : Challenge {
        override val kind = ChallengeKind.TYPING
        override val prompt = "Type this out to end the session."
    }

    /** A small sum. [answer] never leaves the process. */
    data class Math(val question: String, val answer: Int) : Challenge {
        override val kind = ChallengeKind.MATH
        override val prompt = "Answer this to end the session."
    }

    companion object {
        /**
         * The passages. Long enough to take real attention, short enough that a person in a
         * genuine hurry can still get through one, and about the thing the user is doing rather
         * than a scolding: a commitment device that lectures gets uninstalled.
         */
        internal val PASSAGES = listOf(
            "I decided this in advance, when I was not in the middle of wanting it.",
            "The session has time left on it. I am choosing to end it early anyway.",
            "Nothing here is urgent enough that it could not wait until the timer runs out.",
            "I am ending a block I set for myself, on purpose, and I know what that costs.",
        )

        fun generate(kind: ChallengeKind, random: Random = Random.Default): Challenge = when (kind) {
            ChallengeKind.TYPING -> Typing(PASSAGES[random.nextInt(PASSAGES.size)])
            ChallengeKind.MATH -> {
                // Two-digit operands and a subtraction that never goes negative: hard enough not to
                // be automatic, not so hard that it becomes a calculator hunt.
                val a = random.nextInt(12, 40)
                val b = random.nextInt(12, 40)
                val c = random.nextInt(2, 10)
                Math("$a + $b × $c", a + b * c)
            }
        }

        /** Whether [input] answers [challenge]. */
        fun isSatisfied(challenge: Challenge, input: String): Boolean = when (challenge) {
            is Typing -> normalize(input) == normalize(challenge.passage)
            is Math -> input.trim().toIntOrNull() == challenge.answer
        }

        /**
         * The most a single edit may grow a typing answer by and still count as typing.
         *
         * A keyboard produces one character at a time; accepting a suggestion swaps in one word.
         * A paste of the passage arrives all at once, and a passage pasted in is a challenge not
         * taken. Generous enough for the longest word a suggestion strip offers, and nowhere near
         * a sentence.
         */
        const val LONGEST_KEYSTROKE = 24

        /**
         * Whether an edit from [previous] to [next] could have been typed. Deleting is always
         * allowed; inserting more than [LONGEST_KEYSTROKE] characters at once is not.
         *
         * The inserted run is measured, not the change in length: selecting all and pasting the
         * passage over it changes the length very little but inserts the whole thing.
         */
        fun isTyped(challenge: Challenge, previous: String, next: String): Boolean {
            if (challenge !is Typing) return true
            val prefix = previous.commonPrefixWith(next).length
            val suffix = previous.substring(prefix).commonSuffixWith(next.substring(prefix)).length
            return next.length - prefix - suffix <= LONGEST_KEYSTROKE
        }

        private fun normalize(s: String) =
            s.trim().replace(Regex("""\s+"""), " ").lowercase()
    }
}

/**
 * What is still standing between the user and the end of their block.
 *
 * Extracted as a pure value so the rule can be asserted without a screen — see `ChallengeStepTest`.
 * The bug it exists to prevent is described there: a confirmation that ended a session while a
 * challenge was still unmet, which left the user with a refusal naming a question they had never
 * been shown.
 */
sealed interface ChallengeStep {
    /** Everything the app can witness has been witnessed; hand it to the core. */
    data object End : ChallengeStep

    /** Show the confirmation, then come back. */
    data object AskConfirm : ChallengeStep

    /** Pose this challenge. The value is carried so a re-render cannot replace it. */
    data class PoseChallenge(val challenge: Challenge) : ChallengeStep
}

/**
 * The next thing to do about ending a session, given what has already been accounted for.
 *
 * [conditions] is the session's whole lock set, [satisfied] the conditions the user has already met
 * on this screen, and [posed] the challenge already on screen, if any. The order is deliberate:
 * `Lock.Confirm` is the cheapest thing to ask and the challenge the most expensive, so a person
 * meets the small question before the large one and has committed by the time they reach it.
 *
 * Conditions this screen cannot witness — a credential, a tag, a restart, a peer release — are
 * ignored here, because they are proved to the core by other routes and listing them ends nothing.
 * The screen answers [ChallengeStep.AskConfirm] with a sum and passes `Lock.Confirm` back as met;
 * the core ignores a claimed condition the session does not have, so that is safe on any lock.
 */
fun challengeStep(
    conditions: List<Lock>,
    satisfied: List<Lock>,
    posed: Challenge?,
    random: Random = Random.Default,
): ChallengeStep {
    val challenge = conditions.filterIsInstance<Lock.Challenge>().firstOrNull()
    // Every End is asked, locked or not: a single tap ending a block was the reflex this app exists
    // to interrupt. A session with a challenge of its own is asked by that challenge instead, unless
    // it also asked for the confirm, in which case the small question still comes first.
    val asks = challenge == null || conditions.any { it is Lock.Confirm }
    if (asks && satisfied.none { it is Lock.Confirm }) {
        return ChallengeStep.AskConfirm
    }
    if (challenge != null && satisfied.none { it is Lock.Challenge }) {
        return ChallengeStep.PoseChallenge(posed ?: Challenge.generate(challenge.challenge, random))
    }
    return ChallengeStep.End
}
/**
 * Whether a lock needs more than the user's own yes.
 *
 * `Lock.Confirm` alone ("ask me first") is a lock in the core's eyes, but any screen can meet it, so
 * treating it as a hard lock hid pause and offered a 24-hour wait and an emergency pass for a session
 * that one sum ends. A pause's sum is a stronger yes than the confirm, so it counts as one.
 */
fun needsMoreThanConfirm(conditions: List<Lock>): Boolean = conditions.any { it !is Lock.Confirm }
