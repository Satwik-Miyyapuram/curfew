package dev.curfew.app

import dev.curfew.app.enforce.EnforcementCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enforcement cadence, and the two thresholds it must respect.
 *
 * Plain JUnit rather than Robolectric: `EnforcementCadence` is a pure object precisely so this can run on a
 * host where Robolectric cannot (Conscrypt ships no `windows-aarch_64` build), and the numbers here are a
 * claim about battery that would otherwise go unchecked — which is how P1-8 happened.
 */
class EnforcementCadenceTest {

    /**
     * **The one that matters.** The heartbeat this loop writes is what the downtime report measures
     * against, and that threshold is five minutes. An idle interval at or above it would report downtime
     * that never happened, on every cycle, so the honest-downtime feature would become a liar.
     */
    @Test
    fun the_idle_interval_stays_far_below_the_downtime_threshold() {
        val threshold = 300L // CurfewRuntime.DOWNTIME_SECONDS
        assertTrue(
            "idle poll ${EnforcementCadence.POLL_IDLE_MILLIS}ms is not safely under the " +
                "${threshold}s downtime threshold",
            EnforcementCadence.POLL_IDLE_MILLIS * 10 <= threshold * 1000,
        )
        assertTrue(
            "the incidental cadence ${EnforcementCadence.INCIDENTAL_MILLIS}ms is not safely under it either",
            EnforcementCadence.INCIDENTAL_MILLIS * 10 <= threshold * 1000,
        )
    }

    /** Active is the documented 1 s, and idle is the documented 15 s — §10 and the code now agree. */
    @Test
    fun the_documented_figures_are_the_built_ones() {
        assertEquals(1_000L, EnforcementCadence.POLL_ACTIVE_MILLIS)
        assertEquals(15_000L, EnforcementCadence.POLL_IDLE_MILLIS)
    }

    /** Idle is slower than active, or the change does nothing. */
    @Test
    fun idle_is_slower_than_active() {
        assertTrue(
            "idle ${EnforcementCadence.POLL_IDLE_MILLIS} is not slower than active " +
                "${EnforcementCadence.POLL_ACTIVE_MILLIS}",
            EnforcementCadence.POLL_IDLE_MILLIS > EnforcementCadence.POLL_ACTIVE_MILLIS,
        )
    }

    /** The wait follows the work. */
    @Test
    fun the_poll_interval_follows_whether_anything_is_running() {
        assertEquals(EnforcementCadence.POLL_ACTIVE_MILLIS, EnforcementCadence.poll(active = true))
        assertEquals(EnforcementCadence.POLL_IDLE_MILLIS, EnforcementCadence.poll(active = false))
    }

    /**
     * **The heartbeat must run on the first pass.** Nothing can measure downtime until one has been
     * written, so a predicate that waited a full interval before the first write would leave the very
     * first restart unable to tell a crash from a fresh install.
     */
    @Test
    fun the_first_incidental_pass_always_runs() {
        assertTrue(EnforcementCadence.incidentalDue(0L))
    }

    /** Then it runs on its own cadence, not the poll's. */
    @Test
    fun the_incidental_pass_runs_on_its_own_cadence() {
        assertFalse(EnforcementCadence.incidentalDue(EnforcementCadence.POLL_ACTIVE_MILLIS))
        assertTrue(EnforcementCadence.incidentalDue(EnforcementCadence.INCIDENTAL_MILLIS))
        assertTrue(EnforcementCadence.incidentalDue(EnforcementCadence.INCIDENTAL_MILLIS * 2))
    }

    /**
     * **Nothing is charged and nothing is sampled while nothing is running.**
     *
     * Safe, not merely cheap: `engine::decide` iterates `state.active_profiles` and returns `Allow` with
     * none, so a foreground observation taken then cannot change an answer.
     */
    @Test
    fun the_meter_and_the_sample_are_gated_on_an_active_profile() {
        assertTrue(EnforcementCadence.enforcementWorkDue(active = true))
        assertFalse(EnforcementCadence.enforcementWorkDue(active = false))
    }

    /** The meter still runs more often than the idle poll, or a budget would run out late. */
    @Test
    fun charging_is_faster_than_the_idle_poll() {
        assertTrue(
            "a budget would run out late: ${EnforcementCadence.CHARGE_MILLIS}ms against " +
                "${EnforcementCadence.POLL_IDLE_MILLIS}ms",
            EnforcementCadence.CHARGE_MILLIS < EnforcementCadence.POLL_IDLE_MILLIS,
        )
    }

    /**
     * **The service uses the policy, and does not keep a second copy of the numbers.**
     *
     * A source scan, because an Android `Service` cannot be executed by this suite and nothing else here
     * could catch the regression that matters: the waits going back to literals while the policy object
     * still exists and its own tests still pass. That is exactly the shape P1-8 had — a documented strategy
     * in one place and the built one in another.
     *
     * Comments are stripped before anything is checked. The previous round's lesson, learned on the tray
     * overlay's guard: a check that a commented-out line can satisfy is not a check.
     */
    @Test
    fun the_service_waits_through_the_policy_and_keeps_no_second_copy() {
        val path = java.io.File(
            UntranslatedCopy.sourceRoot,
            "enforce/EnforcementService.kt",
        )
        assertTrue("the enforcement service is not where this expects it: $path", path.isFile)
        val code = path.readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            "the tick no longer waits through the policy, so the cadence is a literal again",
            code.contains("EnforcementCadence.poll("),
        )
        assertTrue(
            "the tick does not sleep for the interval it computed",
            code.contains("delay(expected)"),
        )
        assertTrue(
            "the meter is no longer gated on an active profile",
            code.contains("EnforcementCadence.enforcementWorkDue("),
        )
        assertTrue(
            "the incidental work is no longer on its own cadence",
            code.contains("EnforcementCadence.incidentalDue("),
        )

        // And the numbers live in exactly one place.
        for (stale in listOf("POLL_MILLIS", "CHARGE_MILLIS", "SYNC_MILLIS")) {
            assertFalse(
                "EnforcementService declares `$stale` again, so there are two cadences to drift apart",
                code.contains("const val $stale"),
            )
        }
    }
}
