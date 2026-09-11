package dev.curfew.app

import dev.curfew.app.ui.describeSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence that explains the profile Curfew wrote on a fresh install — F-2.
 *
 * This is the only place the app admits that it has **already written a policy on the user's behalf**,
 * and it appears exactly once, on the first Now render. Two ways to get it wrong, and both matter:
 *
 *  - **Saying too little** — a count with no names is a number the user cannot check against anything,
 *    and a blunt "we blocked some apps" from a tool that watches what you open is worse than silence.
 *  - **Saying it badly** — `and 1 others` is the kind of small wrongness that makes a reader distrust
 *    the large claims on the same screen. The app's copy is otherwise careful about this, so a ragged
 *    sentence here would be conspicuous.
 *
 * The seeding itself is sound and documented; only the silence was the defect, so nothing below tests
 * *whether* to seed — that is `CurfewRuntime`'s decision and it is not this function's to second-guess.
 */
class DescribeSeedTest {
    private fun blocked(vararg labels: String) = labels.toList()

    @Test
    fun `two names then a count - the shape the review asked for`() {
        assertEquals(
            "Curfew set up a profile called Distractions, blocking Instagram, YouTube and 7 others. " +
                "Change it on Plan.",
            describeSeed(
                "Distractions",
                blocked("Instagram", "YouTube", "Reddit", "TikTok", "X", "Facebook", "Snapchat", "Chrome", "Netflix"),
            ),
        )
    }

    /** Ten apps on this phone, so the number in this test is the number a real seed produces. */
    @Test
    fun `five blocked is two names and three others`() {
        assertTrue(
            describeSeed("Distractions", blocked("A", "B", "C", "D", "E"))
                .endsWith("blocking A, B and 3 others. Change it on Plan."),
        )
    }

    /** The plural that would read as a bug: "and 1 others". */
    @Test
    fun `three blocked is two names and the singular other`() {
        assertTrue(
            describeSeed("Distractions", blocked("A", "B", "C"))
                .endsWith("blocking A, B and 1 other. Change it on Plan."),
        )
    }

    @Test
    fun `two blocked is two names and no count`() {
        assertTrue(
            describeSeed("Distractions", blocked("A", "B"))
                .endsWith("blocking A and B. Change it on Plan."),
        )
    }

    @Test
    fun `one blocked is one name and no count`() {
        assertTrue(
            describeSeed("Distractions", blocked("Instagram"))
                .endsWith("blocking Instagram. Change it on Plan."),
        )
    }

    /**
     * The case that is easy to forget: a phone with none of the starter apps installed.
     *
     * `seedStarterProfile` filters to apps that are actually present, so a profile with no rules is a
     * real outcome on a minimal phone — and "blocking ." would be the bug. The profile still exists,
     * which is why the sentence still says so.
     */
    @Test
    fun `nothing blocked still names the profile and says so`() {
        assertEquals(
            "Curfew set up a profile called Distractions, with nothing in it yet. Change it on Plan.",
            describeSeed("Distractions", emptyList()),
        )
    }

    /** And the name is the user-visible one, not the id. F-28's lesson, applied here. */
    @Test
    fun `the profile is named, never its id`() {
        val said = describeSeed("Distractions", blocked("Instagram"))
        assertTrue("the sentence printed a slug", !said.contains("distractions,"))
        assertTrue(said.startsWith("Curfew set up a profile called Distractions,"))
    }
}
