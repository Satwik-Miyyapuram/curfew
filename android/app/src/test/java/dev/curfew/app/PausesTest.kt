package dev.curfew.app

import org.robolectric.RuntimeEnvironment
import dev.curfew.app.data.Pause
import dev.curfew.app.data.Pauses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pause, which once ended the block and threw the chosen minutes away: the picker was a number
 * nothing read, so "pause for 15 min" and "end" were the same button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PausesTest {

    private val pauses = Pauses(RuntimeEnvironment.getApplication())

    @Test
    fun `a pause comes back once, when it runs out`() {
        pauses.add(Pause("study", resumeAt = 1_000, endsAt = 5_000))
        pauses.add(Pause("phone", resumeAt = 2_000, endsAt = null))
        assertEquals(1_000L, pauses.next())

        assertTrue(pauses.takeDue(999).isEmpty())
        assertEquals(listOf(Pause("study", 1_000, 5_000)), pauses.takeDue(1_000))
        assertTrue("taken twice", pauses.takeDue(1_500).isEmpty())
        assertEquals(listOf(Pause("phone", 2_000, null)), pauses.takeDue(3_000))
        assertEquals(null, pauses.next())
    }

    @Test
    fun `keeping it off drops the pause`() {
        pauses.add(Pause("study", resumeAt = 1_000, endsAt = 5_000))
        pauses.remove("study")
        assertTrue(pauses.all().isEmpty())
    }

    @Test
    fun `every pause sheet passes its minutes on`() {
        val root = File(UntranslatedCopy.moduleRoot, "src/main/java/dev/curfew/app")
        val callers = root.walkTopDown()
            .filter { it.extension == "kt" && "PausePuzzleSheet(" in it.readText() && !it.name.startsWith("Design") }
            .toList()
        assertTrue("found no screen that pauses under $root", callers.isNotEmpty())
        for (file in callers) {
            val code = file.readText()
            assertFalse("${file.name} ignores the minutes", Regex("""onPaused\s*=\s*\{\s*_""").containsMatchIn(code))
            assertTrue("${file.name} pauses without pauseSession", "pauseSession(" in code)
        }
    }
}
