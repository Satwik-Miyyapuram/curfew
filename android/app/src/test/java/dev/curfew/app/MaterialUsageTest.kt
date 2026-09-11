package dev.curfew.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Finds the app's *second* visual language.
 *
 * Curfew has its own component set — `DCard`, `PrimaryButton`, `GhostButton`, `DSheet`, `DConfirm`,
 * `Pill`, `Dial` — built from one palette and one set of radii. Material's equivalents are also
 * available, and in several files both are in use, which is what `UX_INTERACTION_REVIEW.md` F-40 is
 * about: *"there are two visual languages for corners, buttons, typography and elevation on the screen
 * the user sees most."*
 *
 * ## What counts, and what deliberately does not
 *
 * Only the five things that **compete** with something Curfew already has:
 *
 *  - `AlertDialog(` — competes with `DConfirm` / `DNote`
 *  - `Card(` — competes with `DCard`
 *  - `TextButton(` and `Button(` — compete with `PrimaryButton` / `GhostButton` / `Tap`
 *  - `MaterialTheme.typography` and `MaterialTheme.colorScheme` — compete with `Dsn` and `Palette`
 *
 * **Not counted, on purpose:** `Text`, `Icon`, `OutlinedTextField`, `Surface`, `Shape`,
 * `minimumInteractiveComponentSize`. Those are not competing with anything — the app has no typography
 * component of its own to replace `Text` with, and `OutlinedTextField` is the only text field in
 * either set. Counting them would inflate the number with work that is not the problem, and a metric
 * that mixes real work with noise is a metric people stop reading.
 *
 * ## The ratchet
 *
 * The same shape as `UntranslatedCopyTest`, and for the same reason: a number that nothing checks is
 * a number that stops being true. A new competing usage fails, and an allowlist entry that is no
 * longer found fails too, so every migration must delete its line.
 *
 * This exists because F-40 named *"eight `AlertDialog`s in NowScreen"* and the truth was **75 usages
 * across seven files**. The review's instance was real and is now fixed; the finding was three times
 * its stated size, and nothing would have shown that.
 */
object MaterialUsage {
    /** The five competing patterns, with what each is competing with. */
    private val competitors = listOf(
        Regex("""\bAlertDialog\(""") to "AlertDialog",
        Regex("""\bCard\(""") to "Card",
        Regex("""\bTextButton\(""") to "TextButton",
        Regex("""\bButton\(""") to "Button",
        Regex("""MaterialTheme\.typography""") to "typography",
        Regex("""MaterialTheme\.colorScheme""") to "colorScheme",
    )

    /** One competing usage: the file, and which of the five it is. */
    data class Found(val file: String, val what: String) {
        override fun toString() = "$file|$what"
    }

    /** The competing usages in one piece of source. Separate so the patterns can be tested. */
    fun scan(code: String): List<String> {
        val out = mutableListOf<String>()
        for ((regex, name) in competitors) {
            for (unused in regex.findAll(code)) out += name
        }
        return out
    }

    fun found(): List<Found> {
        val out = mutableListOf<Found>()
        for (file in UntranslatedCopy.sourceRoot.walkTopDown().filter { it.extension == "kt" }) {
            for (name in scan(UntranslatedCopy.stripComments(file.readText()))) {
                out += Found(file.name, name)
            }
        }
        return out
    }

    /** Where the allowlist lives. Read from source, not the classpath — see `UntranslatedCopyTest`. */
    val allowlist: File get() = File(UntranslatedCopy.resourcesRoot, "material-usage.txt")
}

/**
 * The ratchet on Material components that compete with Curfew's own.
 *
 * See [MaterialUsage] for what is counted and why the obvious extras are not.
 */
class MaterialUsageTest {
    private fun resource(): List<String> {
        val file = MaterialUsage.allowlist
        check(file.isFile) { "material-usage.txt is missing from ${file.parentFile}" }
        return file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    /** Rewrite the allowlist, when asked. Same switch as the string one, same reasoning. */
    private fun regenerate() {
        val asked = System.getProperty("curfew.updateMaterialAllowlist") == "true" ||
            System.getenv("CURFEW_UPDATE_MATERIAL_ALLOWLIST") == "true"
        if (!asked) return
        val body = MaterialUsage.found().map { it.toString() }.sorted()
            .joinToString("\n", postfix = "\n")
        MaterialUsage.allowlist.writeText(
            buildString {
                appendLine("# Material components that compete with one this app already has.")
                appendLine("#")
                appendLine("# One entry per occurrence: File.kt|what. See MaterialUsage for what counts.")
                appendLine("# Delete a line when you replace that usage with Curfew's own component — the")
                appendLine("# length of this file is the number the project quotes.")
                appendLine("#")
                appendLine("# Regenerate: CURFEW_UPDATE_MATERIAL_ALLOWLIST=true ./gradlew :app:testDebugUnitTest")
                append(body)
            },
        )
    }

    /**
     * No new competing components, and no stale entries — counted as a **multiset**.
     *
     * The same subtlety as the string ratchet, and it matters more here: a file that swaps one `Card`
     * for one `TextButton` has not improved, and comparing *totals* would call that unchanged while
     * comparing sets would call it a change. Comparing per-pattern counts catches the swap.
     */
    @Test
    fun `the second visual language only shrinks`() {
        regenerate()
        val have = MaterialUsage.found().map { it.toString() }.groupingBy { it }.eachCount()
        val listed = resource().groupingBy { it }.eachCount()

        val added = have.flatMap { (s, n) -> List((n - (listed[s] ?: 0)).coerceAtLeast(0)) { s } }.sorted()
        val stale = listed.flatMap { (s, n) -> List((n - (have[s] ?: 0)).coerceAtLeast(0)) { s } }.sorted()

        val report = buildString {
            if (added.isNotEmpty()) {
                appendLine("${added.size} new Material component(s) where this app has its own:")
                added.forEach { appendLine("    $it") }
                appendLine()
                appendLine("Use DCard, PrimaryButton, GhostButton, DSheet, DConfirm, DNote, Pill, or")
                appendLine("Dsn and Palette — whichever the app's own set already provides.")
                appendLine()
            }
            if (stale.isNotEmpty()) {
                appendLine("${stale.size} allowlist entr(y/ies) no longer found:")
                stale.forEach { appendLine("    $it") }
                appendLine()
                appendLine("Those were replaced. Delete their lines: the file's length is the number")
                appendLine("this project quotes.")
            }
        }
        if (report.isNotEmpty()) throw AssertionError(report)
    }

    /**
     * And the patterns match what they claim to, including the near-misses.
     *
     * `\bButton\(` must **not** match `TextButton(`, or every text button would be counted twice and
     * the ratchet's numbers would drift with no code change. That is what this pins: not that the
     * regexes match, but that they do not over-match. `DCard(` and `CardDefaults` are the same hazard
     * from the other side — the app's own component, and a Material helper that must not be counted as
     * a competing `Card`.
     */
    @Test
    fun `the patterns match the competitors and nothing adjacent`() {
        assertTrue(MaterialUsage.scan("""Card(modifier = Modifier) {}""").contains("Card"))
        assertTrue(MaterialUsage.scan("""AlertDialog(onDismissRequest = {})""").contains("AlertDialog"))
        assertTrue(MaterialUsage.scan("""TextButton(onClick = {}) {}""").contains("TextButton"))

        // The near-misses.
        assertTrue(
            "DCard was counted as a competing Card",
            !MaterialUsage.scan("""DCard(padding = 22.dp) {}""").contains("Card"),
        )
        assertTrue(
            "CardDefaults was counted as a competing Card",
            !MaterialUsage.scan("""colors = CardDefaults.cardColors()""").contains("Card"),
        )
        assertTrue(
            "TextButton was counted a second time as Button",
            MaterialUsage.scan("""TextButton(onClick = {}) { Text("x") }""").count { it == "Button" } == 0,
        )
        // And the two quiet ones.
        assertTrue(
            MaterialUsage.scan("""style = MaterialTheme.typography.titleMedium""")
                .contains("typography"),
        )
        assertTrue(
            MaterialUsage.scan("""containerColor = MaterialTheme.colorScheme.errorContainer""")
                .contains("colorScheme"),
        )
        // `Text` and `OutlinedTextField` are deliberately not competitors; see the class doc.
        assertTrue(MaterialUsage.scan("""Text("hello")""").isEmpty())
        assertTrue(MaterialUsage.scan("""OutlinedTextField(value = x, onValueChange = {})""").isEmpty())
    }
}
