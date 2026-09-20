package dev.curfew.app.enforce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fold that turns a usage-event log into "what is in front", with no Android in the way.
 *
 * The bug this replaced — "the last `MOVE_TO_FOREGROUND` in the window wins" — survived because the
 * logic was three lines inside a method that needed a `UsageStatsManager` to run at all, so no test
 * could reach it. Every case below is one the old version got wrong on a real device.
 *
 * No Robolectric, because there is nothing Android about it: [UsageStep] exists precisely so this can
 * be checked with a list. The translation from the platform's own event type into a step is one `when`
 * in `UsageStatsPoller.read`, and that is what a device test covers.
 */
class UsageEventFoldTest {

    private val instagram = "com.instagram.android"
    private val emacs = "org.gnu.emacs"
    private val launcher = "com.sec.android.app.launcher"

    private fun resumed(at: Long, packageName: String) =
        UsageStep(at, UsageStep.Kind.Resumed, packageName)

    private fun paused(at: Long, packageName: String) =
        UsageStep(at, UsageStep.Kind.Paused, packageName)

    private fun stopped(at: Long, packageName: String) =
        UsageStep(at, UsageStep.Kind.Stopped, packageName)

    private fun other(at: Long, packageName: String) =
        UsageStep(at, UsageStep.Kind.Other, packageName)

    /**
     * **The reported bug.** Open Instagram, press Home. The launcher was already running so it emits no
     * resume; Instagram emits a pause. The old fold kept Instagram for the rest of the window.
     */
    @Test
    fun `leaving an app clears it even when nothing else comes to the front`() {
        val fold = UsageEventFold()

        fold.accept(listOf(resumed(0, instagram), paused(1_000, instagram)))

        assertNull("an app the user has left is not in front", fold.foreground)
    }

    /** And without the pause it is in front, so the case above is the pause and not the setup. */
    @Test
    fun `without the pause the app is still in front`() {
        val fold = UsageEventFold()

        fold.accept(listOf(resumed(0, instagram)))

        assertEquals(instagram, fold.foreground)
    }

    /** A stop is the same edge as a pause, for apps torn down rather than backgrounded. */
    @Test
    fun `a stop clears the app too`() {
        val fold = UsageEventFold()

        fold.accept(listOf(resumed(0, instagram), stopped(1_000, instagram)))

        assertNull(fold.foreground)
    }

    /**
     * A pause for a *different* app must not clear the one in front.
     *
     * This is what a "any pause clears everything" fix would get wrong: several apps pause in quick
     * succession as the user switches, and clearing on each would report nothing in front while the
     * user is demonstrably looking at something.
     */
    @Test
    fun `a pause for another app leaves the foreground alone`() {
        val fold = UsageEventFold()

        fold.accept(listOf(resumed(0, instagram), paused(1_000, emacs), stopped(2_000, emacs)))

        assertEquals(instagram, fold.foreground)
    }

    /** A plain app switch: the most recent resume is the one in front. */
    @Test
    fun `the most recent resume is the one in front`() {
        val fold = UsageEventFold()

        fold.accept(listOf(resumed(0, instagram), paused(1_000, instagram), resumed(2_000, emacs)))

        assertEquals(emacs, fold.foreground)
    }

    /**
     * Backgrounded and returned inside one batch. Order is the mechanism: the pause clears, the later
     * resume sets it again, so the app never flickers out of the foreground.
     */
    @Test
    fun `an app that pauses and resumes within one batch is still in front`() {
        val fold = UsageEventFold()

        fold.accept(listOf(resumed(0, instagram), paused(1_000, instagram), resumed(2_000, instagram)))

        assertEquals(instagram, fold.foreground)
    }

    /** And the reverse order — paused last — leaves nothing in front. */
    @Test
    fun `an app that resumes and then pauses within one batch is not in front`() {
        val fold = UsageEventFold()

        fold.accept(listOf(paused(0, instagram), resumed(1_000, instagram), paused(2_000, instagram)))

        assertNull(fold.foreground)
    }

    /**
     * **The point of carrying state.** A poll that finds no new events answers *unchanged*, not
     * *unknown*. The old version returned null here and its caller read that as "nothing in front", so
     * an app that stayed open stopped being charged and a block stopped being re-asserted.
     */
    @Test
    fun `an empty batch leaves the answer unchanged`() {
        val fold = UsageEventFold()
        fold.accept(listOf(resumed(0, instagram)))

        fold.accept(emptyList())

        assertEquals("no news is not the same as no app", instagram, fold.foreground)
    }

    /**
     * The watermark follows the newest step, including one the fold ignores, and never goes backwards.
     * Both halves matter: a watermark that skipped ignored steps would re-read them, and one that could
     * move back would re-read everything between.
     */
    @Test
    fun `the watermark follows every step and stays monotonic`() {
        val fold = UsageEventFold()

        fold.accept(listOf(resumed(0, instagram), other(2_000, emacs)))

        assertEquals(2_000L, fold.newestTimestamp)

        fold.accept(listOf(other(500, emacs)))

        assertEquals(
            "the watermark went backwards, so the next poll re-reads",
            2_000L,
            fold.newestTimestamp,
        )
    }

    /** A reset forgets both: a process start or a permission change means neither holds. */
    @Test
    fun `reset forgets the app and the watermark`() {
        val fold = UsageEventFold()
        fold.accept(listOf(resumed(0, instagram)))

        fold.reset()

        assertNull(fold.foreground)
        assertEquals(0L, fold.newestTimestamp)
    }

    /** The fold special-cases nothing; filtering the launcher out is the poller's business. */
    @Test
    fun `the fold does not special-case any package`() {
        val fold = UsageEventFold()

        fold.accept(listOf(resumed(0, instagram), paused(1_000, instagram), resumed(2_000, launcher)))

        assertEquals(launcher, fold.foreground)
    }

    /**
     * A step whose package name is empty — which the platform can produce — is not filtered here. The
     * fold reports what it was given, and `UsageStatsPoller` is where "not Curfew itself" is decided.
     * Asserted so the division of responsibility is written down rather than assumed.
     */
    @Test
    fun `an empty package name is passed through and filtered by the caller, not here`() {
        val fold = UsageEventFold()
        fold.accept(listOf(resumed(0, instagram)))

        fold.accept(listOf(resumed(1_000, "")))

        assertEquals("", fold.foreground)
    }
}
