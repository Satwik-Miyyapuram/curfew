package dev.curfew.app

import dev.curfew.policy.EmergencySettings
import dev.curfew.policy.emergencySettings
import dev.curfew.policy.withEmergencySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `[emergency]` block, edited as text.
 *
 * This exists because the ration had **no interface at all**: it lives in `curfew.toml`, the default
 * is `passes = 0`, and the core reads zero as "the hatch is off". On every install that never
 * hand-edited the file the ration was therefore empty for ever, and the button that spends one was
 * never drawn — because it is only shown when a pass is available. A feature nobody can reach.
 *
 * The editing is text rather than a re-serialisation, so the assertions below are mostly about what
 * is **left alone**: a plan file is a document the user is told to read and edit by hand, and a
 * settings screen that reformats it to change one number has damaged something.
 */
class EmergencySettingsTest {

    private val realFile = """
        # Curfew plan
        [general]
        timezone = "Europe/London"

        # The hatch. Four a week, six hours apart.
        [emergency]
        passes = 4
        window_seconds = 604800
        cooldown_seconds = 21600

        [server]
        enabled = false
    """.trimIndent()

    @Test
    fun `reads the three numbers`() {
        val s = emergencySettings(realFile)
        assertEquals(4, s.passes)
        assertEquals(604800L, s.windowSeconds)
        assertEquals(21600L, s.cooldownSeconds)
    }

    @Test
    fun `a file with no emergency section reads as the hatch off, with the core's own defaults`() {
        val s = emergencySettings("[general]\ntimezone = \"UTC\"\n")
        assertEquals(0, s.passes)
        // The window and gap come back as the core's defaults rather than as zeroes, so a screen
        // showing them is showing what the core will apply and not an absence it invented.
        assertEquals(7L * 24 * 60 * 60, s.windowSeconds)
        assertEquals(6L * 60 * 60, s.cooldownSeconds)
    }

    @Test
    fun `changing the ration keeps every comment and every other section`() {
        val out = withEmergencySettings(realFile, EmergencySettings(2, 604800, 10800))
        assertTrue("the header comment went", out.contains("# Curfew plan"))
        assertTrue("the section comment went", out.contains("# The hatch. Four a week, six hours apart."))
        assertTrue("another section changed", out.contains("[server]\nenabled = false"))
        assertTrue(out.contains("passes = 2"))
        assertTrue(out.contains("cooldown_seconds = 10800"))
    }

    @Test
    fun `the edit round-trips`() {
        val out = withEmergencySettings(realFile, EmergencySettings(3, 86400, 3600))
        val back = emergencySettings(out)
        assertEquals(3, back.passes)
        assertEquals(86400L, back.windowSeconds)
        assertEquals(3600L, back.cooldownSeconds)
    }

    @Test
    fun `a file with no section gets one appended`() {
        val out = withEmergencySettings("[general]\ntimezone = \"UTC\"", EmergencySettings(1, 604800, 21600))
        assertTrue("the original content went", out.contains("[general]\ntimezone = \"UTC\""))
        assertTrue(out.contains("[emergency]"))
        assertEquals(1, emergencySettings(out).passes)
    }

    @Test
    fun `switching the hatch off writes zero, which is how the core spells off`() {
        val out = withEmergencySettings(realFile, EmergencySettings(0, 604800, 21600))
        assertTrue(out.contains("passes = 0"))
        assertEquals(0, emergencySettings(out).passes)
    }

    @Test
    fun `a missing key inside an existing section is added without duplicating the section`() {
        val partial = "[emergency]\npasses = 4\n"
        val out = withEmergencySettings(partial, EmergencySettings(4, 604800, 7200))
        assertEquals("the section header was duplicated", 1, Regex("""\[emergency\]""").findAll(out).count())
        assertEquals(7200L, emergencySettings(out).cooldownSeconds)
    }
}