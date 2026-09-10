package dev.curfew.app

import dev.curfew.app.ui.shortestTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of the draggable dial that can be wrong without anyone noticing until a thumb is
 * on it: the seam at twelve o'clock, where the angle a drag is read at goes from 359 to 1.
 */
class DialDragTest {

    @Test
    fun `a small turn is read as a small turn`() {
        assertEquals(10f, shortestTurn(20f, 30f), 0.001f)
        assertEquals(-10f, shortestTurn(30f, 20f), 0.001f)
    }

    @Test
    fun `crossing twelve o'clock forwards is a nudge, not most of an hour backwards`() {
        // 358 degrees to 2 is four degrees clockwise. Read literally it is 356 the other way, which
        // on an hour-per-turn dial would take fifty-nine minutes off the timer mid-drag.
        assertEquals(4f, shortestTurn(358f, 2f), 0.001f)
    }

    @Test
    fun `crossing twelve o'clock backwards is a nudge too`() {
        assertEquals(-4f, shortestTurn(2f, 358f), 0.001f)
    }

    @Test
    fun `no reading is ever more than half a turn`() {
        var from = 0f
        while (from < 360f) {
            var to = 0f
            while (to < 360f) {
                val turn = shortestTurn(from, to)
                assertTrue("$from to $to gave $turn", turn > -180f && turn <= 180f)
                to += 7f
            }
            from += 7f
        }
    }

    @Test
    fun `a full drag round the dial is one turn's worth of minutes`() {
        // Sixty samples of six degrees: what a slow drag right round the ring looks like, and it
        // has to add up to the hour a turn is worth rather than to nothing.
        var total = 0f
        var last = 0f
        repeat(60) {
            val next = (last + 6f) % 360f
            total += shortestTurn(last, next)
            last = next
        }
        assertEquals(360f, total, 0.01f)
    }
}
