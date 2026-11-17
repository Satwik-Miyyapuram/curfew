package dev.curfew.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.curfew.app.ui.ButtonRow
import dev.curfew.app.ui.CurfewTheme
import dev.curfew.app.ui.Dsn
import dev.curfew.app.ui.GhostButton
import dev.curfew.app.ui.PrimaryButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared buttons, measured.
 *
 * This exists because a ghost button sat beside a primary one in every sheet at a different height:
 * the ghost had its own shorter minimum and less padding, and nothing looked at the two together
 * until a user did. Heights are asserted here, laid out the way the screens lay them out, so a token
 * or a padding that drifts fails a build instead of a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ButtonSizeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun height(tag: String): Dp = compose.onNodeWithTag(tag).getBoundsInRoot().let { it.bottom - it.top }

    private fun assertSame(a: Dp, b: Dp, what: String) =
        assertEquals(what, a.value, b.value, 0.5f)

    @Test
    fun `a ghost and a primary stacked are the same height`() {
        compose.setContent {
            CurfewTheme {
                Column(Modifier.width(360.dp)) {
                    PrimaryButton("End now", Modifier.testTag("primary")) {}
                    GhostButton("Release", Modifier.testTag("ghost")) {}
                }
            }
        }
        assertSame(height("primary"), height("ghost"), "stacked primary vs ghost")
        assertTrue("shorter than the touch minimum: ${height("ghost")}", height("ghost") >= Dsn.ButtonHeight)
    }

    @Test
    fun `a sheet's two buttons are the same height`() {
        compose.setContent {
            CurfewTheme {
                Column(Modifier.width(360.dp)) {
                    ButtonRow {
                        GhostButton("Cancel", Modifier.weight(1f).fillMaxHeight().testTag("ghost")) {}
                        PrimaryButton("End it", Modifier.weight(1f).fillMaxHeight().testTag("primary")) {}
                    }
                }
            }
        }
        assertSame(height("primary"), height("ghost"), "sheet row")
    }

    @Test
    fun `a label that wraps on one side does not leave the other short`() {
        // A narrow phone and a long label: the primary wraps to two lines, and the ghost beside it
        // must grow with it rather than sit at one line's height.
        compose.setContent {
            CurfewTheme {
                Column(Modifier.width(240.dp)) {
                    ButtonRow {
                        GhostButton("Cancel", Modifier.weight(1f).fillMaxHeight().testTag("ghost")) {}
                        PrimaryButton(
                            "Ask to end in 24 hours",
                            Modifier.weight(1f).fillMaxHeight().testTag("primary"),
                        ) {}
                    }
                }
            }
        }
        assertTrue("the long label did not wrap, so this proves nothing", height("primary") > Dsn.ButtonHeight)
        assertSame(height("primary"), height("ghost"), "wrapped row")
    }
}
