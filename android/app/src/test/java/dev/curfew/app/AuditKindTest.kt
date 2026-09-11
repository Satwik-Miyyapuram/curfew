package dev.curfew.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every audit kind the runtime writes has a sentence in the UI.
 *
 * F-36 in the interaction review: the audit list fell back to `"$kind $detail"`, so a user reading a
 * section whose stated purpose is that they can *check* it saw `sync.failed timeout`. The kinds and
 * their sentences now match, and this is what keeps them matching — a new `audit(now, "thing.happened")`
 * call would otherwise ship as an internal token on a screen, which is a defect nobody notices because
 * nothing renders it in a test.
 *
 * ## How it finds the kinds
 *
 * It reads `CurfewRuntime.kt` for the two shapes that record one:
 *
 *  - `audit(now, "kind", detail)` — a session, a release, a pass, a sync result, an enforcement gap
 *  - `commitConfig("kind", detail)` — a change to the config, which is audit-logged by the commit
 *
 * Scraping source with a regex rather than asking for a `List<AuditKind>` is a deliberate trade. An enum
 * would be better and is a change to the data layer; this is a check that can exist now, and if somebody
 * later introduces a type for it they can replace this with a `values()` walk and delete the scraping.
 * The failure mode of scraping is a missed kind, so the scan is itself checked against known kinds below.
 *
 * ## And the fallback
 *
 * Naming twenty kinds does not stop a twenty-first, so the second half of this test is that the
 * fallback does not render the kind. An unrecognised entry should read as a sentence, not as
 * `sync.refused`.
 */
class AuditKindTest {
    private val runtime = java.io.File(
        UntranslatedCopy.sourceRoot,
        "data/CurfewRuntime.kt",
    )

    private val usage = java.io.File(
        UntranslatedCopy.sourceRoot,
        "ui/UsageScreen.kt",
    )

    /** The kinds the runtime writes, in the order they appear. */
    private fun writtenKinds(): List<String> {
        val code = UntranslatedCopy.stripComments(runtime.readText())
        val patterns = listOf(
            // The first argument is an expression, not always a bare name: `audit(now, …)` and
            // `audit(clock.now(), …)` are both real. Requiring `\w+` there silently missed
            // `config.replaced` — and the *other* test in this file is what caught it, which is the
            // argument for checking both directions rather than one.
            Regex("""\baudit\(\s*[\w.]+(?:\([^()]*\))?\s*,\s*"([^"]+)""""),
            Regex("""\bcommitConfig\(\s*"([^"]+)""""),
        )
        return patterns.flatMap { p -> p.findAll(code).map { it.groupValues[1] }.toList() }.sorted().distinct()
    }

    /** The kinds the UI has a sentence for. */
    private fun namedKinds(): List<String> {
        val code = UntranslatedCopy.stripComments(usage.readText())
        val body = Regex("""private fun describeAudit\(.*?\n}""", RegexOption.DOT_MATCHES_ALL)
            .find(code)?.value ?: error("describeAudit not found in UsageScreen.kt")
        // `when (kind)` arms: "some.kind" -> …
        return Regex("""^\s*"([a-z][a-z0-9_.]*)"\s*->""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toList().sorted().distinct()
    }

    @Test
    fun `every kind the runtime writes is named in the UI`() {
        val written = writtenKinds()
        // A scan that finds nothing would make the assertion below vacuous — the failure mode of
        // scraping source, caught rather than trusted.
        assertTrue("the scan found no audit kinds at all, so it is not reading the runtime", written.size > 15)

        val unnamed = written.filterNot { it in namedKinds() }
        assertTrue(
            buildString {
                appendLine("${unnamed.size} audit kind(s) the runtime writes have no sentence:")
                unnamed.forEach { appendLine("    $it") }
                appendLine()
                appendLine("Add a line to `describeAudit` in UsageScreen.kt. A kind that reaches the")
                appendLine("audit list unnamed renders as an internal token on a screen whose whole")
                appendLine("purpose is that the user can check it.")
            },
            unnamed.isEmpty(),
        )
    }

    @Test
    fun `the UI names no kind the runtime never writes`() {
        val stale = namedKinds().filterNot { it in writtenKinds() }
        assertTrue(
            "describeAudit names kind(s) nothing writes: ${stale.joinToString()}",
            stale.isEmpty(),
        )
    }

    /**
     * And the fallback is honest rather than leaking.
     *
     * This is the half that survives somebody adding a kind: the test above will fail, but if it is
     * ever relaxed, an unknown kind must still not print itself.
     */
    @Test
    fun `an unknown kind does not render as a token`() {
        val code = UntranslatedCopy.stripComments(usage.readText())
        val body = Regex("""private fun describeAudit\(.*?\n}""", RegexOption.DOT_MATCHES_ALL)
            .find(code)?.value ?: error("describeAudit not found")
        val fallback = Regex("""else\s*->\s*("(?:[^"\\]|\\.)*")""").find(body)?.groupValues?.get(1)
            ?: error("describeAudit has no else arm, so an unknown kind would not compile — fine, but this test cannot check it")
        assertTrue(
            "the audit fallback is $fallback, which renders the internal kind to the user",
            !fallback.contains("kind"),
        )
    }
}
