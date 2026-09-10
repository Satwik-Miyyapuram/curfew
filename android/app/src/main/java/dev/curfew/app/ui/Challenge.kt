package dev.curfew.app.ui

import dev.curfew.policy.ChallengeKind
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

    /** The text to reproduce, shown above the field. */
    data class Typing(val passage: String) : Challenge {
        override val prompt = "Type this out to end the session."
    }

    /** A small sum. [answer] never leaves the process. */
    data class Math(val question: String, val answer: Int) : Challenge {
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
         * allowed; growing by more than [LONGEST_KEYSTROKE] characters at once is not.
         */
        fun isTyped(challenge: Challenge, previous: String, next: String): Boolean =
            challenge !is Typing || next.length - previous.length <= LONGEST_KEYSTROKE

        private fun normalize(s: String) =
            s.trim().replace(Regex("""\s+"""), " ").lowercase()
    }
}
