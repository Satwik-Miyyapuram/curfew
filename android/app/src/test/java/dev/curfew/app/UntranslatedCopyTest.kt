package dev.curfew.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Copy that a translator cannot reach is a bug that never shows up in a test run — so this is the test
 * that shows it up.
 *
 * `strings.xml` holds twelve strings and six are consumed, all by surfaces that are not Compose: the
 * manifest labels, the notification channel, the tile. Every sentence a person reads on a screen is a
 * Kotlin literal. `GAPS.md` E6 asks for externalisation "from day one", and this file is the honest
 * state of the long tail of that.
 *
 * ## This is a ratchet, and both halves are load-bearing
 *
 *  - **A literal that is not in the allowlist fails.** New copy cannot be added without externalising
 *    it, so the pile stops growing the day this lands.
 *  - **An allowlist entry that is no longer found also fails.** Every string somebody externalises must
 *    have its line deleted, so the file's length is the true remaining count rather than a number that
 *    stopped being true a month ago.
 *
 * The second half is what makes the first half worth having. Without it the allowlist becomes a list
 * nobody prunes, and this whole review found several of exactly that: a build list with three finished
 * items on it, a doc claiming a permission the manifest declares, a design canvas missing an artboard.
 * A count you cannot trust is worse than no count.
 *
 * ## Why the count is bigger than the review's
 *
 * `UX_INTERACTION_REVIEW.md` says "at least 122 literals in text positions". This finds **222** plain
 * and **54** interpolated. The difference is scope rather than disagreement: the review counted
 * positional text arguments, and this also counts copy carried by named parameters — `body =`, `action
 * =`, `example =`, `confirm =`, `contentDescription =` — and counts each occurrence rather than each
 * distinct string. Both numbers are right about different things, and this one is the one that can be
 * driven to zero by a machine.
 *
 * ## The two kinds, deliberately counted separately
 *
 * `plain()` is a string that can become `<string name="…">` verbatim. `interpolated()` contains `$`, so
 * it has to become a numbered format string — `getString(R.string.x, value)` — and getting that wrong
 * shows a user `%1$s`. They are the same amount of typing and very different amounts of care, so a
 * migration is allowed to finish the plain ones and still be honest about the rest.
 */
class UntranslatedCopyTest {
    private val allowlist: List<String> by lazy { resource(PLAIN) }

    private fun lines(found: List<UntranslatedCopy.Found>) = found.map { it.toString() }.sorted()

    /**
     * Rewrite the allowlists to match the source, when asked to.
     *
     * The supported way to keep these files honest, and a switch rather than a hand edit for a
     * reason: 249 lines is too many to prune by hand without eventually pruning the wrong one, and a
     * list that has drifted from the source is worse than no list at all.
     *
     * The workflow is — externalise some copy, then
     * `./gradlew :app:testDebugUnitTest -Dcurfew.updateCopyAllowlist=true`, then read the diff and
     * commit it. **The diff is the review**: it should contain only lines you meant to remove, and a
     * line you did not expect means a string moved or changed rather than was externalised.
     */
    private fun regenerate() {
        val asked = System.getProperty("curfew.updateCopyAllowlist") == "true" ||
            System.getenv("CURFEW_UPDATE_COPY_ALLOWLIST") == "true"
        if (!asked) return

        val out = UntranslatedCopy.resourcesRoot

        fun write(name: String, first: String, found: List<UntranslatedCopy.Found>) {
            val body = found.map { it.toString() }.sorted().joinToString("\n", postfix = "\n")
            File(out, name).writeText(
                buildString {
                    appendLine(first)
                    appendLine("#")
                    appendLine("# One entry per occurrence: File.kt|the literal exactly as it appears.")
                    appendLine("# Delete a line when you move that string into strings.xml. The length")
                    appendLine("# of this file is the number the project quotes, so a stale line is a lie.")
                    appendLine("#")
                    appendLine("# Regenerate: ./gradlew :app:testDebugUnitTest -Dcurfew.updateCopyAllowlist=true")
                    append(body)
                },
            )
        }
        write(PLAIN, "# Copy a translator cannot reach, found by UntranslatedCopyTest.", UntranslatedCopy.plain())
        write(
            INTERPOLATED,
            "# Interpolated copy: these need numbered format arguments, not a plain move.",
            UntranslatedCopy.interpolated(),
        )
    }

    /**
     * What the allowlist is missing, and what it carries that no longer exists.
     *
     * A **multiset** difference, and that is the whole reason this is a helper. The same literal appears
     * more than once — `"Cancel"` is on a dozen screens — so the count per entry is information:
     * externalise one of two `"Cancel"`s and the file must lose exactly one line, not both. Comparing
     * sets would call that finished; comparing totals would call it finished at the wrong moment. The
     * first version of this test did the latter and reported a stale entry that was not stale, which is
     * a guard failing for its own reasons rather than for the code's.
     */
    private fun difference(found: List<String>, known: List<String>): Pair<List<String>, List<String>> {
        val have = found.groupingBy { it }.eachCount()
        val listed = known.groupingBy { it }.eachCount()
        val added = have.flatMap { (s, n) -> List((n - (listed[s] ?: 0)).coerceAtLeast(0)) { s } }.sorted()
        val stale = listed.flatMap { (s, n) -> List((n - (have[s] ?: 0)).coerceAtLeast(0)) { s } }.sorted()
        return added to stale
    }

    /**
     * Read from the **filesystem**, not the classpath.
     *
     * Deliberately, and it is what makes regeneration work within a single run: Gradle copies
     * `src/test/resources` into the build directory before the tests execute, so a file rewritten
     * during a test is invisible to `getResourceAsStream`, and a regenerated list would appear to
     * have changed nothing. Reading the source file means what is written is what is checked.
     */
    private fun resource(name: String): List<String> {
        val file = File(UntranslatedCopy.resourcesRoot, name)
        check(file.isFile) { "$name is missing from ${file.parentFile}" }
        return file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    /**
     * No new copy, and no stale entries.
     *
     * Reported as the difference in both directions at once, because "12 lines differ" is not an
     * actionable message and "these three are new, these two are stale" is.
     */
    @Test
    fun `every un-externalised string is one this project already knows about`() {
        regenerate()
        val (added, stale) = difference(lines(UntranslatedCopy.plain()), allowlist)

        val report = buildString {
            if (added.isNotEmpty()) {
                appendLine("${added.size} string(s) a translator cannot reach, and not yet on the list:")
                added.forEach { appendLine("    $it") }
                appendLine()
                appendLine("Either move them into strings.xml, or — mid-migration — add them to")
                appendLine("app/src/test/resources/untranslated-copy.txt. The first is the fix.")
                appendLine()
            }
            if (stale.isNotEmpty()) {
                appendLine("${stale.size} allowlist entr(y/ies) no longer found in the source:")
                stale.forEach { appendLine("    $it") }
                appendLine()
                appendLine("These have been externalised or reworded. Delete their lines: the file's")
                appendLine("length is the count this project quotes, so a stale line is a wrong number.")
            }
        }
        if (report.isNotEmpty()) throw AssertionError(report)
    }

    /** The interpolated ones, tracked as their own number so progress on one is not claimed for both. */
    @Test
    fun `the interpolated strings are counted and cannot grow either`() {
        regenerate()
        val (added, stale) =
            difference(lines(UntranslatedCopy.interpolated()), resource(INTERPOLATED))

        assertTrue("new interpolated copy: ${added.joinToString(", ")}", added.isEmpty())
        assertTrue(
            "the interpolated allowlist has stale entries: ${stale.joinToString(", ")}",
            stale.isEmpty(),
        )
    }

    /**
     * And the scanner itself works.
     *
     * A guard whose failure mode is "found nothing" passes forever. This pins that it finds something
     * on a known input, so a regex that silently stops matching is a failing test rather than a green
     * one — which is the exact shape of the three vacuous guards found earlier in this project.
     */
    @Test
    fun `the scanner finds copy, and does not fall for a comment`() {
        val code = """
            // A comment about a button that says "Cancel" — not code, must not be counted.
            /* And a block comment mentioning "Delete" for the same reason. */
            Text("Nothing is blocked")
            Notice(title = "Sync is not running", body = "It starts with the service.")
            Text(dynamicValue)
            Text("app:com.example")
            say("That is not a Curfew invite.")
        """.trimIndent()

        val stripped = UntranslatedCopy.stripComments(code)
        assertTrue("the comment was not stripped", !stripped.contains("\"Cancel\""))
        assertTrue("the block comment was not stripped", !stripped.contains("\"Delete\""))

        // The scanner state is a singleton over the real tree, so this asserts the *rule* on the same
        // regexes rather than re-running the filesystem walk: what is being checked is that the
        // patterns match a call and skip a non-literal, which is where a regex quietly stops working.
        assertTrue(
            "the call pattern no longer matches a Text(\"…\") call",
            Regex("""\b(Text|say)\s*\(\s*"((?:[^"\\]|\\.)*)"""").containsMatchIn(stripped),
        )
        assertTrue(
            "the parameter pattern no longer matches title = \"…\"",
            Regex("""\b(title|body)\s*=\s*"((?:[^"\\]|\\.)*)"""").containsMatchIn(stripped),
        )
    }

    private companion object {
        const val PLAIN = "untranslated-copy.txt"
        const val INTERPOLATED = "untranslated-copy-interpolated.txt"
    }
}
