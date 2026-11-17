package dev.curfew.app

import dev.curfew.app.ui.describeFailure
import dev.curfew.app.ui.needsMoreThanConfirm
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import dev.curfew.policy.NoPass
import dev.curfew.policy.PassRefusal
import dev.curfew.policy.Refusal
import dev.curfew.policy.Refused
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ending a session, from every screen that offers it.
 *
 * This exists because the block screen had its own End button that went straight to the core with
 * nothing satisfied. On an "ask me first" session the core refused, correctly, and the screen printed
 * the refusal's `toString()` in red: `Locked(missing=[Confirm], endsAt=…, delayedReleaseAt=null)`.
 * Two defects, and neither screen nor test noticed, because the Now screen's flow was the only one
 * that was ever checked.
 */
class EndFlowTest {

    private val now = 1_800_000_000L

    /** Everything the core can refuse with, as the user would see it. */
    private val failures = listOf(
        Refused(Refusal.NotRunning),
        Refused(Refusal.Locked(listOf(Lock.Confirm), endsAt = now + 600)),
        Refused(Refusal.Locked(listOf(Lock.Challenge(ChallengeKind.MATH)), delayedReleaseAt = now + 60)),
        NoPass(PassRefusal.Disabled),
        NoPass(PassRefusal.QuotaSpent(nextAt = now + 3600)),
        NoPass(PassRefusal.CoolingDown(until = now + 60)),
    )

    @Test
    fun `a refusal is a sentence, never a data class dump`() {
        for (failure in failures) {
            val text = describeFailure(failure, now)
            assertTrue("no text for $failure", !text.isNullOrBlank())
            for (leak in listOf("(", "=", "missing", "Locked", "NotRunning", "null")) {
                assertFalse("\"$leak\" leaked into: $text", text!!.contains(leak))
            }
        }
    }

    @Test
    fun `ask me first is not a hard lock`() {
        assertFalse(needsMoreThanConfirm(emptyList()))
        assertFalse(needsMoreThanConfirm(listOf(Lock.Confirm)))
        assertTrue(needsMoreThanConfirm(listOf(Lock.Confirm, Lock.Challenge(ChallengeKind.MATH))))
        assertTrue(needsMoreThanConfirm(listOf(Lock.RestartRequired)))
    }

    /**
     * A tripwire, not a proof: every screen that ends a session must take [challengeStep]'s steps,
     * and none may print a failure's own message. A new End button that skips either fails here.
     */
    @Test
    fun `every screen that ends a session takes the steps`() {
        val root = File(UntranslatedCopy.moduleRoot, "src/main/java/dev/curfew/app")
        val enders = root.walkTopDown()
            .filter { it.extension == "kt" && !it.name.startsWith("Curfew") }
            .filter { Regex("""\.endSession\(""").containsMatchIn(it.readText()) }
            .toList()
        assertTrue("found no screen that ends a session under $root", enders.isNotEmpty())
        for (file in enders) {
            val code = file.readText()
            assertTrue("${file.name} ends a session without challengeStep", "challengeStep(" in code)
            assertFalse("${file.name} shows a raw failure message", Regex("""it\.message\s*\?:""").containsMatchIn(code))
        }
    }
}
