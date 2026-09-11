package dev.curfew.app

import java.io.File

/**
 * Finds copy that a translator cannot reach.
 *
 * The app's text is inline in Kotlin — `Text("Nothing is blocked")` — so it is good copy that cannot be
 * translated. `strings.xml` holds twelve strings and six of them are consumed, all by surfaces that are
 * not Compose. `GAPS.md` E6 asks for externalisation "from day one", which this is the long tail of.
 *
 * ## Why a scanner rather than a lint rule
 *
 * Android's own lint has `HardcodedText`, and it is **not enabled here for a reason worth stating**: it
 * reads compiled resources and reports per-layout-file, and Compose has no layouts. It does not see a
 * `Text("…")` call at all, which is every string in this app. So the check has to read the source, and
 * this is it.
 *
 * ## What counts, exactly
 *
 * A **user-facing literal** is a double-quoted Kotlin string that is either the first positional
 * argument to something that draws or says text, or the value of a named parameter that carries copy.
 * Those two lists below are the whole definition — there is no third clause and no judgement call.
 *
 * and that additionally
 *
 *  - contains **no `$`**. Interpolation is a different, harder job — the literal has to become a format
 *    string with numbered arguments, and getting it wrong shows a user `%1$s`. Those are counted
 *    separately and by a different name, so a migration cannot confuse "move the words" with "move the
 *    words and rebuild the sentence around a value".
 *  - is not obviously an identifier: no `^[a-z0-9_.-]+$`, no hex colour, no single character.
 *
 * ## The two tests
 *
 * They are a **ratchet**, and both halves are needed for that:
 *
 *  - a literal that is not in the allowlist fails, so new copy cannot be added without externalising it
 *  - an allowlist entry that is *no longer found* also fails, so the list cannot quietly rot — every
 *    string somebody externalises must have its line deleted, and the file's length is the honest
 *    remaining count
 *
 * The second half is what makes the number trustworthy. A list nobody prunes stops meaning anything
 * within a month, and this document's whole problem has been numbers that stopped meaning anything.
 */
object UntranslatedCopy {
    /** Where the app's Kotlin lives, found by walking up so the test does not care about its cwd. */
    val sourceRoot: File = run {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "src/main/java/dev/curfew/app").isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) {
            "could not find src/main/java/dev/curfew/app above ${File(".").absolutePath}"
        }
        File(dir, "src/main/java/dev/curfew/app")
    }

    /**
     * The module root — the directory holding `build.gradle.kts`.
     *
     * Found by walking up rather than by counting `.parentFile` hops. Counting got it wrong the first
     * time and wrote a file into `src/main`, which is a fair demonstration that a path derived by
     * arithmetic is a path that will eventually be wrong.
     */
    val moduleRoot: File = run {
        var dir: File = sourceRoot
        while (!File(dir, "build.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no build.gradle.kts above $sourceRoot")
        }
        dir
    }

    /** Where the allowlists live. Read from source, not the built classpath — see the test. */
    val resourcesRoot: File get() = File(moduleRoot, "src/test/resources")

    /** Composables and helpers whose first positional argument is shown to a person. */
    private val textCalls = listOf(
        "Text", "Sub", "Title", "SectionLabel", "Pill", "Tap", "GhostButton", "PrimaryButton",
        "DialNumber", "say", "note", "Notice", "SearchField", "ExportPill", "PrimaryButton",
    )

    /**
     * Named parameters that carry copy rather than data.
     *
     * **Derived from the code, not guessed.** The first version of this list was guessed and missed
     * `because` and `cost` — which are the nine-entry permission table that `UX_INTERACTION_REVIEW.md`
     * names *by name* when it raises F-45. A scanner that cannot see the example the finding is about
     * reports a number nobody should trust, so the list was rebuilt by enumerating every
     * `name = "literal"` in the tree and keeping the ones that carry prose.
     *
     * The ones deliberately **not** here, with the reason — because an omission is the only way this
     * guard can be wrong: `id`, `key`, `TAG`, `head`, `timezone`, `value`, `TRANSFORMATION`,
     * `PROFILE_NEW`, `ACTION_ACCESSIBILITY_DETAILS`, `EXTRA_ACCESSIBILITY_COMPONENT` are route names,
     * intent extras, log tags and test keys. None is read by a person.
     */
    private val textParams = listOf(
        "text", "title", "note", "label", "body", "example", "placeholder", "action", "subtitle",
        "contentDescription", "confirm", "because", "cost", "heading", "prompt",
        "onClickLabel", "onLongClickLabel",
    )

    /** One thing found in the source. */
    data class Found(val file: String, val literal: String) {
        /** The allowlist line for this, which is what a failure message should tell you to delete. */
        override fun toString() = "$file|$literal"
    }

    /**
     * The file with comments blanked out, keeping every newline so line numbers survive.
     *
     * Necessary rather than tidy: this codebase's comments *quote the copy they are explaining* — a
     * doc comment about a button says `"Cancel"` — and a scanner that reads comments reports those as
     * untranslated strings. Three assertions in this project were made vacuous by exactly that mistake,
     * which is why it is handled here rather than hoped about.
     */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var inLine = false
        var inBlock = false
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append('\n') } else out.append(' ')
                    i++
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; out.append("  "); i += 2 } else {
                        out.append(if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                inString -> {
                    out.append(c)
                    if (c == '\\') { next?.let { out.append(it) }; i += 2; continue }
                    if (c == '"') inString = false
                    i++
                }
                c == '/' && next == '/' -> { inLine = true; i += 2 }
                c == '/' && next == '*' -> { inBlock = true; i += 2 }
                c == '"' -> { inString = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun isCopy(s: String): Boolean {
        if (!s.any { it.isLetter() }) return false
        if (s.length < 2) return false
        if (Regex("^[a-z0-9_.\\-]+\$").matches(s)) return false // a key, an id, a package
        if (Regex("^#[0-9A-Fa-f]+\$").matches(s)) return false // a colour
        return true
    }

    /** Everything an ordinary string resource could hold. */
    fun plain(): List<Found> = scan(interpolated = false)

    /** The same, but with `$` — these need numbered format arguments, which is a separate job. */
    fun interpolated(): List<Found> = scan(interpolated = true)

    /**
     * Read the arguments of a call, given the index of its opening parenthesis.
     *
     * Returns the half-open range **inside** the parentheses, with nesting balanced. That balance is
     * the whole point: a cast like `(application as Application)` inside an argument list contains
     * parentheses, and a scanner that stops at the first `)` would end the argument list early and
     * miss everything after it.
     */
    private fun argumentText(code: String, openParen: Int): String {
        var depth = 0
        var i = openParen
        var inString = false
        while (i < code.length) {
            val c = code[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return code.substring(openParen + 1, i)
                    }
                }
            }
            i++
        }
        // Unbalanced source — a truncated file, or a `)` inside a raw string. Taking the rest of the
        // file is the tolerant answer, and the literals found are still real ones.
        return code.substring(openParen + 1)
    }

    private fun scan(interpolated: Boolean): List<Found> {
        val callAlternatives = textCalls.joinToString("|") { Regex.escape(it) }
        val paramAlternatives = textParams.joinToString("|") { Regex.escape(it) }
        val calls = Regex("""\b($callAlternatives)\s*\(""")
        val params = Regex("""\b($paramAlternatives)\s*=\s*"((?:[^"\\]|\\.)*)"""")
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")

        val found = mutableListOf<Found>()
        for (file in sourceRoot.walkTopDown().filter { it.extension == "kt" }) {
            val code = stripComments(file.readText())

            // A named copy parameter: the literal is right there after the `=`.
            for (m in params.findAll(code)) {
                val value = m.groupValues[2]
                if (value.contains('$') == interpolated && isCopy(value)) {
                    found += Found(file.name, value)
                }
            }

            // A text call: **every** literal in its argument list, not just the first.
            //
            // This is the correction that matters. Scanning only the literal directly after the
            // parenthesis missed `say(it.message ?: "That invite could not be made.")` — 159
            // literals across the app, including most of the error copy in the ViewModel. A
            // measurement that cannot see a third of its subject is not a measurement, and this is
            // the second time this scanner has been widened after finding its own blind spot.
            for (m in calls.findAll(code)) {
                val open = m.range.last // the `(` the regex consumed
                for (l in literal.findAll(argumentText(code, open))) {
                    val value = l.groupValues[1]
                    if (value.contains('$') == interpolated && isCopy(value)) {
                        found += Found(file.name, value)
                    }
                }
            }
        }
        return found
    }
}
