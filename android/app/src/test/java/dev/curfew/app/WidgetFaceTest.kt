package dev.curfew.app

import dev.curfew.app.ui.widget.widgetFace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the home screen and the quick settings tile say.
 *
 * A glance surface that is wrong is worse than one that is absent: somebody who sees "nothing
 * blocked" while a session runs learns to distrust the app, and somebody who sees the opposite
 * goes looking for a block that is not there.
 */
class WidgetFaceTest {

    private val now = 1_757_000_000L

    @Test
    fun `an idle device with nothing due says so`() {
        val face = widgetFace(emptyList(), nextChange = null, streak = 0, now = now)

        assertEquals("Nothing blocked", face.headline)
        assertEquals("No sessions or schedules due", face.detail)
        assertEquals("No days in a row yet", face.footer)
    }

    @Test
    fun `an idle device with a schedule coming says when`() {
        val face = widgetFace(emptyList(), nextChange = now + 1800, streak = 2, now = now)

        assertEquals("Nothing blocked", face.headline)
        assertEquals("Next change in 30 min", face.detail)
        assertEquals("2 days in a row", face.footer)
    }

    /** The names, not a count: "blocking 2 profiles" tells nobody whether their block is on. */
    @Test
    fun `a running session names its profiles`() {
        val face = widgetFace(listOf("deep-work", "evening"), nextChange = now + 60, streak = 1, now = now)

        assertEquals("Blocking", face.headline)
        assertEquals("deep-work, evening", face.detail)
        assertEquals("One day in a row", face.footer)
    }

    /** While something is blocked, that is the news. The next change can wait for the app. */
    @Test
    fun `a running session outranks the next change`() {
        val face = widgetFace(listOf("deep-work"), nextChange = now + 60, streak = 0, now = now)

        assertEquals("deep-work", face.detail)
    }
}
