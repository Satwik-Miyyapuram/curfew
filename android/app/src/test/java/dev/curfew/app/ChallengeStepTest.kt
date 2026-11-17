package dev.curfew.app

import dev.curfew.app.ui.Challenge
import dev.curfew.app.ui.ChallengeStep
import dev.curfew.app.ui.challengeStep
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What still stands between a user and the end of their own block.
 *
 * This exists because that question was answered wrongly, in a way that locked a person out of their
 * own phone. A session with "Lock it" **and** "Solve math" carries two conditions the app is the
 * only witness to: `Lock.Confirm` and `Lock.Challenge`. The screen asked for the confirmation, and
 * the confirmation's button went straight to the core with `listOf(Lock.Confirm)` — satisfying one
 * condition and silently abandoning the other. The core refused, correctly, naming the arithmetic
 * the user had never been shown. The dialog above it had promised *"Nothing else is standing in the
 * way"*.
 *
 * So the rule is asserted here rather than trusted to a screen: **every** condition needs accounting
 * for, in the order a person meets it, and the end only reaches the core when none are left.
 */
class ChallengeStepTest {

    private val confirm = Lock.Confirm
    private val math = Lock.Challenge(ChallengeKind.MATH)
    private val typing = Lock.Challenge(ChallengeKind.TYPING)

    private fun step(
        conditions: List<Lock>,
        satisfied: List<Lock> = emptyList(),
        posed: Challenge? = null,
    ) = challengeStep(conditions, satisfied, posed, Random(7))

    @Test
    fun `an unlocked session is still asked before it ends`() {
        // One tap ended a block with no locks, which is the reflex the app exists to interrupt.
        val s = step(emptyList())
        assertTrue("expected the ask, got $s", s is ChallengeStep.AskConfirm)
    }

    @Test
    fun `an unlocked session ends once asked`() {
        assertEquals(ChallengeStep.End, step(emptyList(), satisfied = listOf(confirm)))
    }

    @Test
    fun `a confirmation is asked for first`() {
        val s = step(listOf(confirm))
        assertTrue("expected the confirmation, got $s", s is ChallengeStep.AskConfirm)
    }

    @Test
    fun `an answered confirmation moves on rather than ending`() {
        // The exact case that locked the user out: the confirmation is done, the sum is not.
        val s = step(listOf(confirm, math), satisfied = listOf(confirm))
        assertTrue("expected the challenge, got $s", s is ChallengeStep.PoseChallenge)
    }

    @Test
    fun `a confirmation with nothing else does end it`() {
        val s = step(listOf(confirm), satisfied = listOf(confirm))
        assertEquals(ChallengeStep.End, s)
    }

    @Test
    fun `a challenge alone is posed without a confirmation`() {
        val s = step(listOf(math))
        assertTrue("expected the challenge, got $s", s is ChallengeStep.PoseChallenge)
    }

    @Test
    fun `an answered challenge ends it`() {
        assertEquals(ChallengeStep.End, step(listOf(math), satisfied = listOf(math)))
    }

    @Test
    fun `both answered ends it`() {
        assertEquals(ChallengeStep.End, step(listOf(confirm, math), satisfied = listOf(confirm, math)))
    }

    @Test
    fun `the posed challenge is the kind the lock asked for`() {
        val s = step(listOf(typing)) as ChallengeStep.PoseChallenge
        assertEquals(ChallengeKind.TYPING, s.challenge.kind)
    }

    @Test
    fun `a challenge already on screen is not replaced`() {
        // The second half of the lockout: the sum was generated inline, so a screen that recomposes
        // every second produced a new question under the user's typing and the answer could never
        // match. Once posed, the same challenge comes back.
        val posed = Challenge.Math("2 + 2 × 2", 6)
        val s = step(listOf(math), posed = posed) as ChallengeStep.PoseChallenge
        assertEquals(posed, s.challenge)
    }

    @Test
    fun `an unclaimable condition is not something this screen can satisfy`() {
        // A credential, a tag, a restart and a peer release are proved to the core by other routes.
        // Listing them changes nothing, and this pins that they do not send the screen into a
        // confirmation or a challenge it cannot complete.
        assertEquals(ChallengeStep.End, step(listOf(Lock.DeviceCredential), satisfied = listOf(confirm)))
    }
}