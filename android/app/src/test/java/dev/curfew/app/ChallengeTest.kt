package dev.curfew.app

import dev.curfew.app.ui.Challenge
import dev.curfew.policy.ChallengeKind
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The friction locks.
 *
 * A challenge that can be answered without reading it is not a lock, and a challenge that rejects a
 * correct answer over a stray space is a tool people learn to hate. Both edges are pinned here.
 */
class ChallengeTest {

    @Test
    fun `a typing challenge is satisfied by the passage and nothing else`() {
        val challenge = Challenge.generate(ChallengeKind.TYPING) as Challenge.Typing

        assertTrue(Challenge.isSatisfied(challenge, challenge.passage))
        assertFalse(Challenge.isSatisfied(challenge, ""))
        assertFalse(Challenge.isSatisfied(challenge, challenge.passage.dropLast(5)))
        assertFalse(Challenge.isSatisfied(challenge, challenge.passage + " and more"))
    }

    @Test
    fun `a passage pasted in is not a passage typed`() {
        val challenge = Challenge.generate(ChallengeKind.TYPING) as Challenge.Typing

        assertFalse(Challenge.isTyped(challenge, "", challenge.passage))
        assertTrue(Challenge.isTyped(challenge, "", "T"))
        assertTrue(Challenge.isTyped(challenge, "Th", "The"))
        // A suggestion strip swaps in a word; that is still typing.
        assertTrue(Challenge.isTyped(challenge, "Th", "Therefore "))
        // Deleting any amount is fine: it never helps.
        assertTrue(Challenge.isTyped(challenge, challenge.passage, ""))
        // Arithmetic has a short answer and nothing to paste from.
        val math = Challenge.generate(ChallengeKind.MATH)
        assertTrue(Challenge.isTyped(math, "", "1234567890123456789012345"))
    }

    @Test
    fun `typing is forgiving about the things that are not the point`() {
        val challenge = Challenge.Typing("I decided this in advance.")

        assertTrue(Challenge.isSatisfied(challenge, "  I decided this in advance.  "))
        assertTrue(Challenge.isSatisfied(challenge, "i DECIDED this  in advance."))
        // A missing word is the point, and is not forgiven.
        assertFalse(Challenge.isSatisfied(challenge, "I decided in advance."))
    }

    @Test
    fun `a math challenge wants the number, in any surrounding whitespace`() {
        val challenge = Challenge.Math("2 + 3 × 4", 14)

        assertTrue(Challenge.isSatisfied(challenge, "14"))
        assertTrue(Challenge.isSatisfied(challenge, " 14 "))
        assertFalse(Challenge.isSatisfied(challenge, "13"))
        assertFalse(Challenge.isSatisfied(challenge, ""))
        assertFalse(Challenge.isSatisfied(challenge, "fourteen"))
    }

    @Test
    fun `generated arithmetic is right, every time`() {
        repeat(500) { seed ->
            val challenge = Challenge.generate(ChallengeKind.MATH, Random(seed)) as Challenge.Math
            val (a, rest) = challenge.question.split(" + ", limit = 2)
            val (b, c) = rest.split(" × ", limit = 2)

            assertEquals(a.toInt() + b.toInt() * c.toInt(), challenge.answer)
        }
    }

    @Test
    fun `the passage is not always the same one`() {
        val seen = (0 until 200)
            .map { (Challenge.generate(ChallengeKind.TYPING, Random(it)) as Challenge.Typing).passage }
            .toSet()

        // Muscle memory is the failure mode: a lock the user can type from memory is decoration.
        assertTrue(seen.toString(), seen.size > 1)
    }
}
