package dev.curfew.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No screen may print a profile's **id** where a person reads or hears its **name**.
 *
 * ## The bug this exists for
 *
 * Profiles have two identifiers: `ProfileName.id` is a slug from the config — `deep-work`, `evening` —
 * and `ProfileName.name` is what the user typed. Every surface that shows a session, a window, a rule or
 * a calendar block has to turn one into the other, and the failure is always the same shape: printing the
 * id, because the id is the field that is *right there* on the object in hand.
 *
 * It has been found **thirteen times** on this branch across four places, and every time it was found by
 * accident while doing something else:
 *
 *  - **Windows** (F-28): every surface printed `Session.profile`. Fixed in entry 30.
 *  - **The Android biometric prompt**: `End deep-work` in a box the app does not draw. Entered 45.
 *  - **Android's screen reader**: nine descriptions — `End deep-work now`, `Edit the window for
 *    deep-work`, `Present a tag for deep-work` — which a blind user hears and cannot check against
 *    anything on screen. Entered 46.
 *
 * Finding the same defect four times by accident is the actual finding. F-28 was closed as a Windows bug
 * and it was never a Windows bug; it is a missing convention, and a convention nothing checks is a
 * convention that decays.
 *
 * ## The rule, and why it is absolute
 *
 * **A string literal may not interpolate a bare `.profile` field.** No exceptions, no allowlist.
 *
 * A `.name` is what a person reads; a `.profile` is a key that happens to sit next to it. Where the name
 * genuinely might not exist — a profile deleted while a session from it runs — the lookup with its
 * fallback is bound to a **local** first (`val who = names[id] ?: id`) and the local is interpolated.
 * That keeps this rule checkable by pattern instead of by judgement, which is the whole point: a rule
 * with an exception list is one that gets an exception added for the next mistake.
 *
 * Deliberately **not** flagged: `profile = session.profile`, `it.profile == profileId`, `names[x.profile]`.
 * Those use the id *as* an id, outside a string, which is correct and is most of the codebase.
 */
object ProfileIds {
    /**
     * A bare `.profile` interpolation inside a string literal: `${session.profile}`, `${window.profile}`.
     *
     * The pattern will not match `${named(session.profile)}` or `${names[id] ?: id}`, because the
     * bracketed part must be identifiers and dots only — so a value that goes *through* a naming
     * function is not flagged, which is the behaviour wanted.
     */
    private val bare = Regex("""\$\{\s*[A-Za-z_][A-Za-z0-9_.]*\.profile\s*}""")

    /** A Kotlin string literal, single-line, with escapes honoured. */
    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")

    /** One occurrence: the file, and the offending interpolation. */
    data class Found(val file: String, val what: String) {
        override fun toString() = "$file|$what"
    }

    fun scan(code: String): List<String> =
        literal.findAll(code)
            .flatMap { l -> bare.findAll(l.groupValues[1]).map { it.value } }
            .toList()

    fun found(): List<Found> {
        val out = mutableListOf<Found>()
        for (file in UntranslatedCopy.sourceRoot.walkTopDown().filter { it.extension == "kt" }) {
            // Comments are stripped because this codebase's comments *quote* the bug they explain —
            // the class doc above contains three examples. A scanner reading comments would report
            // its own documentation, which is the trap that made earlier guards in this project inert.
            for (what in scan(UntranslatedCopy.stripComments(file.readText()))) {
                out += Found(file.name, what)
            }
        }
        return out
    }

    /** Every profile id the app is allowed to have, so the fallback sites can be named in the message. */
    val allowlist: File get() = File(UntranslatedCopy.resourcesRoot, "profile-id-prints.txt")
}

/** See [ProfileIds] for what this checks and why the rule has no exceptions. */
class ProfileIdPrintTest {
    /**
     * The guard. See [ProfileIds] for the rule, the thirteen occurrences, and why an allowlist is
     * deliberately *not* used here — the fix is a naming call, not a line to delete later.
     */
    @Test
    fun `no string prints a profile id where a name belongs`() {
        val found = ProfileIds.found()
        assertTrue(
            buildString {
                appendLine("${found.size} string(s) interpolate a profile *id* rather than its name:")
                found.forEach { appendLine("    $it") }
                appendLine()
                appendLine("A profile id is a slug from the config — `deep-work`. A person reads and hears")
                appendLine("the name. Use `runtime.profileName(id)` in a ViewModel, or a `named(id)` local")
                appendLine("in a screen — where the name may be missing, bind the lookup with its fallback")
                appendLine("to a local and print the local.")
            },
            found.isEmpty(),
        )
    }

    /**
     * And the pattern catches the real thing without catching the correct forms.
     *
     * This test exists because the guards on this branch have been wrong on their first attempt four
     * times, and every time a mutation check is what found it. So the near-misses are pinned here
     * rather than discovered later: going *through* a naming function must not be flagged, and using
     * the id as an id must not be flagged.
     */
    @Test
    fun `the pattern catches a bare id and nothing else`() {
        // Caught: a bare field interpolated into a string.
        assertTrue(ProfileIds.scan("""Text("End ${'$'}{session.profile} now")""").isNotEmpty())
        assertTrue(ProfileIds.scan("""Text("Edit the window for ${'$'}{window.profile}")""").isNotEmpty())

        // Not caught: the id going through a naming function.
        assertTrue(ProfileIds.scan("""Text("End ${'$'}{named(session.profile)} now")""").isEmpty())
        assertTrue(ProfileIds.scan("""note(str(R.x, runtime.profileName(s.profile)))""").isEmpty())
        // Not caught: a lookup with a fallback, which is how the deleted-profile case is handled.
        assertTrue(ProfileIds.scan("""Text("${'$'}{names[next.profile] ?: next.profile}")""").isEmpty())
        // Not caught: the name itself, which is the point.
        assertTrue(ProfileIds.scan("""Text("Edit ${'$'}{profile.name}")""").isEmpty())
        // Not caught: the id used as an id, outside any string.
        assertTrue(ProfileIds.scan("""Action(profile = session.profile)""").isEmpty())
        assertTrue(ProfileIds.scan("""filter { it.profile == profileId }""").isEmpty())
    }
}
