package dev.curfew.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every format argument a screen passes matches the resource it is passed to.
 *
 * This is the failure mode that externalising copy introduces and that nothing else catches. A
 * resource `"%1$s ended."` called with an `Int`, or called with *no* argument at all, compiles
 * perfectly and then throws `IllegalFormatException` at the moment the user is being told something
 * went wrong — so the app crashes instead of explaining. `MissingFormatArgumentException` on a
 * refusal screen is about the worst possible place to discover a typo.
 *
 * The check is deliberately shallow: it counts the numbered and bare specifiers in each resource and
 * compares the *arities* it finds at each call site. It cannot know that `%1$s` is being given a
 * `String` rather than an `Int`. What it can do is catch the two mistakes that actually happen — a
 * resource with two arguments called with one, and a resource with an argument called with none —
 * and it catches them across every call site in the app rather than the three somebody happened to
 * test.
 */
class StringFormatArgsTest {
    private val values: Map<String, String> by lazy {
        val xml = File(UntranslatedCopy.moduleRoot, "src/main/res/values/strings.xml").readText()
        // The **whole element**, body included — and that is the second bug this test had. It first
        // used the match itself (`it.value`), which for `<(?:string|plurals)\s+name="…"` stops at the
        // closing quote of the name attribute. Every resource was therefore recorded as
        // `<string name="paired"` with no body, so every one appeared to need zero arguments
        // and the guard passed everything. Both halves of the pattern are anchored for that reason.
        Regex("""<(string|plurals)\s+name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[2] to it.groupValues[3] }
    }

    /**
     * How many arguments a resource needs.
     *
     * `%1$s` needs one, `%2$s` needs two, and a bare `%s` needs one more than the highest numbered
     * index — the two forms can be mixed in one string.
     *
     * **This is written the long way because the first version was wrong and passed everything.** It
     * derived the index with `"%1\$s".drop(1).dropLast(1)`, which yields `"1\$"`, and `toIntOrNull()`
     * on that is null — so every resource appeared to need zero arguments and both tests below
     * succeeded unconditionally. Two deliberate breakages went undetected, which is what a guard that
     * cannot fail looks like from the outside. The mutation check is the only reason it was found.
     */
    private fun requirements(text: String): Int {
        val numbered = Regex("""%(\d+)\$[sdf]""").findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        val bare = Regex("""%[sdf]""").findAll(text).count()
        return numbered + bare
    }

    /**
     * The arguments at each resource call, by resource name — across **every** call form the app uses.
     *
     * `str` and `plural` are the ViewModel's own helpers; `stringResource` is the Compose one;
     * `getString` on a context is how the non-Compose surfaces do it. Missing any of them made the
     * second test below report healthy screens as broken, which is how the omission was found — a
     * false positive is a far better failure than a false negative, and it is what showed the matcher
     * was too narrow.
     */
    private fun callArities(): List<Triple<File, Int, Pair<String, Int>>> {
        val found = mutableListOf<Triple<File, Int, Pair<String, Int>>>()
        // \s* around the resource name, and the **whole file** scanned rather than line by line:
        // `stringResource(\n  R.string.x,\n  argument)` is a real shape here and a per-line matcher
        // cannot see it. That produced one last false positive, which is how the omission was found.
        val call = Regex(
            """\b(?:str|plural|stringResource|getString)\(\s*R\.(?:string|plurals)\.(\w+)""" +
                """((?:[^()]|\([^()]*\))*)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (file in UntranslatedCopy.sourceRoot.walkTopDown().filter { it.extension == "kt" }) {
            val code = UntranslatedCopy.stripComments(file.readText())
            for (m in call.findAll(code)) {
                val name = m.groupValues[1]
                val args = m.groupValues[2].trim().trimStart(',').trim()
                // The line the call starts on. Counted from the stripped text, whose newlines are
                // preserved by the comment blanking, so the number is the one in the file.
                val line = code.take(m.range.first).count { it == '\n' } + 1
                found += Triple(file, line, name to splitTopLevel(args).size)
            }
        }
        return found
    }

    /** Split an argument list on commas that are not inside brackets or quotes. */
    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        val current = StringBuilder()
        for (c in s) {
            when {
                inString -> {
                    current.append(c)
                    if (c == '"') inString = false
                }
                c == '"' -> { inString = true; current.append(c) }
                c == '(' || c == '[' -> { depth++; current.append(c) }
                c == ')' || c == ']' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { out += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }

    @Test
    fun `no resource is called with fewer arguments than it declares`() {
        val problems = mutableListOf<String>()
        for ((file, line, pair) in callArities()) {
            val (name, given) = pair
            val body = values[name] ?: continue // a name this test cannot see; the count test covers it
            val needed = requirements(body)
            if (given < needed) {
                problems += "${file.name}:$line  $name needs $needed argument(s), called with $given"
            }
        }
        assertTrue(
            "format arguments do not match their resources, which throws at runtime:\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /**
     * And the resources that take an argument are the ones the calls pass them to.
     *
     * The other direction, and the easier mistake to make while editing copy: a resource rewritten to
     * use `%1$s` but still called with nothing is the same crash.
     */
    @Test
    fun `every resource with an argument is given one`() {
        val called = callArities().map { it.third.first }.toSet()
        val consumers = mutableMapOf<String, MutableList<String>>()
        for (file in UntranslatedCopy.sourceRoot.walkTopDown().filter { it.extension == "kt" }) {
            val text = UntranslatedCopy.stripComments(file.readText())
            for ((name, body) in values) {
                if (requirements(body) == 0) continue
                if (Regex("""R\.(?:string|plurals)\.$name\b""").containsMatchIn(text)) {
                    consumers.getOrPut(name) { mutableListOf() } += file.name
                }
            }
        }
        val offenders = consumers.filterKeys { it !in called }
            .map { (name, files) -> "$name is used in ${files.joinToString()} but passed no arguments" }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * And the reading of the resource file works, or the two above prove nothing.
     *
     * Written after they were found to be vacuous **twice**. First the argument index was parsed
     * wrongly — `"%1\$s".drop(1).dropLast(1)` is `"1\$"`, and `toIntOrNull()` on that is null — so
     * every resource appeared to need no arguments. Then, with that fixed, the resource *body* was
     * still being dropped, because the pattern matched only as far as the name attribute. Three
     * deliberate breakages went undetected across those two versions.
     *
     * A guard whose own arithmetic is untested reports success whatever it is given, and this file is
     * the demonstration of it.
     */
    @Test
    fun `counting arguments handles every form the app uses`() {
        assertEquals(1, requirements("%1${'$'}s ended."))
        assertEquals(2, requirements("%1${'$'}s lands %2${'$'}s."))
        assertEquals(3, requirements("%1${'$'}s, %2${'$'}s. %3${'$'}s"))
        assertEquals(0, requirements("Nothing yet"))
        // A bare specifier after numbered ones still needs an argument of its own.
        assertEquals(3, requirements("%1${'$'}s and %2${'$'}s and %s"))
        // A plural's count selects the form, so one argument is always required.
        assertEquals(1, requirements("""<item quantity="other">%1${'$'}d apps</item>"""))
    }

    /** And the resource file was really read, rather than every entry coming back empty. */
    @Test
    fun `the resource file is read with its bodies`() {
        assertEquals("Paired.", values["paired"])
        assertEquals("Without it: %1${'$'}s", values["health_without_it"])
        // A plural keeps **all** its forms — the whole element, inner tags included.
        val apps = values.getValue("apps_blocked")
        assertTrue("a plural lost a form: $apps", apps.contains("quantity=\"one\""))
        assertTrue("a plural lost a form: $apps", apps.contains("quantity=\"other\""))
        assertTrue("far too few resources were read: ${values.size}", values.size > 40)
    }

    /** The scanner underlying this knows what an argument list looks like. */
    @Test
    fun `the argument splitter handles nesting and commas in strings`() {
        assertEquals(listOf("\"a, b\"", "c"), splitTopLevel("\"a, b\", c"))
        assertEquals(listOf("f(x, y)", "z"), splitTopLevel("f(x, y), z"))
        assertEquals(listOf("a"), splitTopLevel("a"))
    }
}
