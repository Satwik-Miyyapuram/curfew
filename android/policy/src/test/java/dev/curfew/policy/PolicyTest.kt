package dev.curfew.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Kotlin side of the boundary, running against the real Rust library on the host JVM.
 *
 * `curfew-ffi`'s own tests already prove the core answers correctly; what is proved here is that
 * the Kotlin shapes are the *same* shapes — that nothing is renamed, dropped or silently defaulted
 * on the way across. A drift between these declarations and the Rust ones shows up as a
 * deserialization failure rather than as a rule that quietly stops firing.
 */
class PolicyTest {

    private val configToml =
        """
        schema_version = 1
        timezone = "Europe/London"

        [[profiles]]
        id = "deep-work"
        name = "Deep work"

        [[profiles.rules]]
        target = { kind = "app_package", package = "com.instagram.android" }
        action = { kind = "block" }

        [[profiles.rules]]
        target = { kind = "app_package", package = "com.slack" }
        action = { kind = "delay", seconds = 15 }

        [[profiles.rules]]
        target = { kind = "domain", domain = "reddit.com" }
        action = { kind = "budget", seconds = 1200, refill = { kind = "daily", at_minute = 240 } }

        [[profiles.rules]]
        target = { kind = "notification_source", package = "com.whatsapp" }
        action = { kind = "mute_notifications" }

        [[weekly]]
        id = "mornings"
        profile = "deep-work"
        days = [0, 1, 2, 3, 4]
        start_minute = 540
        end_minute = 720
        locks = [{ kind = "timer" }]

        [[calendars]]
        id = "work-focus"
        profile = "deep-work"
        pad_before_seconds = 300
        locks = [{ kind = "confirm" }]
        matcher = { title = "*focus*", calendar = "Work", busy_only = true }
        """.trimIndent()

    /** 2026-09-04 09:30 Europe/London: a Friday, inside the `mornings` window. */
    private val friday0930 = 1_788_510_600L

    private fun policy() = Policy.load(configToml)

    private fun locked(profile: String, locks: List<Lock>, endsAt: Long?) =
        Session(
            id = "s1",
            profile = profile,
            source = SessionSource.Manual,
            startedAt = friday0930,
            lock = LockSet(conditions = locks, endsAt = endsAt),
        )

    // --- config -----------------------------------------------------------------------------------

    @Test
    fun `a config round trips through the core`() {
        val reloaded = Policy.load(policy().configToml())
        assertTrue(reloaded.configToml().contains("deep-work"))
    }

    @Test
    fun `a broken config fails to load rather than loading half of itself`() {
        val result = Policy.check("schema_version = ")
        assertTrue(result.isFailure)
    }

    @Test
    fun `check reports what the config contains`() {
        val summary = Policy.check(configToml).getOrThrow()
        assertTrue(summary, summary.contains("Europe/London"))
    }

    // --- decisions --------------------------------------------------------------------------------

    @Test
    fun `every decision variant survives the crossing`() {
        val p = policy()
        p.startSession(locked("deep-work", emptyList(), friday0930 + 3600))

        assertEquals(
            Decision.Block(BlockReason.Blocked("deep-work")),
            p.decide(friday0930, Observation.App("com.instagram.android")),
        )
        assertEquals(
            Decision.Delay(15),
            p.decide(friday0930, Observation.App("com.slack")),
        )
        assertEquals(
            Decision.Mute,
            p.decide(friday0930, Observation.Notification("com.whatsapp", "hello")),
        )
        assertEquals(Decision.Allow, p.decide(friday0930, Observation.Idle))
    }

    @Test
    fun `usage handed in from storage is what exhausts a budget`() {
        val p = policy()
        p.startSession(locked("deep-work", emptyList(), friday0930 + 3600))
        val usage = UsageState(
            usage = mapOf("domain:reddit.com" to Consumption(listOf(Rollup(friday0930 - 60, 1200)))),
        )
        val decision =
            p.decide(friday0930, Observation.Web(Url.parse("https://reddit.com/r/all")), usage)
        assertEquals(Decision.Block(BlockReason.BudgetExhausted("deep-work", 1200)), decision)
    }

    @Test
    fun `nothing is blocked when no session is running`() {
        assertEquals(
            Decision.Allow,
            policy().decide(friday0930, Observation.App("com.instagram.android")),
        )
    }

    /**
     * The Kotlin URL splitter has to agree with the Rust one, because the browser hands us a raw
     * string and only one of the two ever sees it. A port or a set of credentials must not be a way
     * to defeat a rule.
     */
    @Test
    fun `the kotlin url parser agrees with the core about what a url is`() {
        val url = Url.parse("https://user:pw@M.YouTube.com:443/shorts/abc?x=1#frag")
        assertEquals("m.youtube.com", url.host)
        assertEquals("/shorts/abc", url.path)
        assertEquals("x=1", url.query)

        val bare = Url.parse("example.com")
        assertEquals("example.com", bare.host)
        assertEquals("", bare.path)
    }

    // --- sessions ---------------------------------------------------------------------------------

    @Test
    fun `an unlocked session ends on request`() {
        val p = policy()
        p.startSession(locked("deep-work", emptyList(), friday0930 + 3600))
        p.endSession("s1", friday0930)
        assertTrue(p.activeProfiles(friday0930).isEmpty())
    }

    @Test
    fun `a locked session refuses and names exactly what is missing`() {
        val p = policy()
        p.startSession(
            locked("deep-work", listOf(Lock.DeviceCredential, Lock.Confirm), friday0930 + 3600),
        )
        try {
            p.endSession("s1", friday0930, satisfied = listOf(Lock.Confirm))
            fail("a locked session must not end")
        } catch (e: Refused) {
            val refusal = e.refusal as Refusal.Locked
            assertEquals(listOf(Lock.DeviceCredential), refusal.missing)
            assertEquals(friday0930 + 3600, refusal.endsAt)
        }
        assertEquals(listOf("deep-work"), p.activeProfiles(friday0930))
    }

    @Test
    fun `the device credential the platform verified is what releases the lock`() {
        val p = policy()
        p.startSession(locked("deep-work", listOf(Lock.DeviceCredential), null))
        p.endSession("s1", friday0930, satisfied = listOf(Lock.DeviceCredential))
        assertTrue(p.activeProfiles(friday0930).isEmpty())
    }

    @Test
    fun `ending a session that is not running is refused rather than quietly fine`() {
        try {
            policy().endSession("nope", friday0930)
            fail("expected a refusal")
        } catch (e: Refused) {
            assertEquals(Refusal.NotRunning, e.refusal)
        }
    }

    @Test
    fun `the delayed release lands 24 hours out and is never moved later`() {
        val p = policy()
        p.startSession(locked("deep-work", listOf(Lock.DeviceCredential), null))
        val at = p.requestRelease("s1", friday0930)
        assertEquals(friday0930 + 24 * 3600, at)
        assertEquals(at, p.requestRelease("s1", friday0930 + 5_000))
        try {
            p.endSession("s1", at - 1)
            fail("not until it lands")
        } catch (e: Refused) {
            assertEquals(at, (e.refusal as Refusal.Locked).delayedReleaseAt)
        }
        p.endSession("s1", at)
    }

    /** A settings edit is not a way out of a lock. */
    @Test
    fun `replacing the config does not release a running session`() {
        val p = policy()
        p.startSession(locked("deep-work", listOf(Lock.DeviceCredential), friday0930 + 3600))
        // Rename the profile everywhere it is named, so the replacement is itself a valid config:
        // what is under test is the running session, not the config checker.
        p.setConfig(configToml.replace("\"deep-work\"", "\"renamed\""))
        assertEquals(listOf("deep-work"), p.activeProfiles(friday0930))
    }

    /** A reboot, a force-stop and an app update all look like this from the core's side. */
    @Test
    fun `sessions survive a restart with their locks and their countdown intact`() {
        val before = policy()
        before.startSession(locked("deep-work", listOf(Lock.DeviceCredential), friday0930 + 3600))
        before.requestRelease("s1", friday0930)
        val saved = before.sessions()

        val after = policy()
        after.restoreSessions(saved)
        assertEquals(listOf("deep-work"), after.activeProfiles(friday0930))
        try {
            after.endSession("s1", friday0930)
            fail("still locked")
        } catch (e: Refused) {
            assertEquals(
                friday0930 + 24 * 3600,
                (e.refusal as Refusal.Locked).delayedReleaseAt,
            )
        }
    }

    @Test
    fun `the merged lock is what the locked screen shows`() {
        val p = policy()
        p.startSession(locked("deep-work", listOf(Lock.Confirm), friday0930 + 60))
        val lock = p.mergedLock(friday0930)
        assertTrue(lock.isLocked)
        assertEquals(friday0930 + 60, lock.endsAt)
    }

    @Test
    fun `reaping returns the sessions whose time is up`() {
        val p = policy()
        p.startSession(locked("deep-work", emptyList(), friday0930 + 10))
        val reaped = p.reap(friday0930 + 100)
        assertEquals(1, reaped.size)
        assertEquals("deep-work", reaped.first().profile)
    }

    // --- schedules --------------------------------------------------------------------------------

    @Test
    fun `a weekly window produces an activation and a session`() {
        val p = policy()
        val activations = p.activations(friday0930, emptyList())
        assertEquals(1, activations.size)
        assertEquals(ActivationSource.Weekly("mornings"), activations.first().source)

        val started = p.reconcile(friday0930, emptyList(), "seed")
        assertEquals(1, started.size)
        assertEquals(listOf("deep-work"), p.activeProfiles(friday0930))
    }

    @Test
    fun `a matching calendar event starts a session five minutes early`() {
        val p = policy()
        val meeting = CalendarEvent(
            id = "e1",
            title = "Focus block",
            calendar = "Work",
            start = friday0930 + 7200,
            end = friday0930 + 10800,
            busy = true,
        )
        val activations = p.activations(friday0930 + 7000, listOf(meeting))
        val calendarOne = activations.first { it.source is ActivationSource.Calendar }
        assertEquals(friday0930 + 7200 - 300, calendarOne.start)
        assertEquals(listOf(Lock.Confirm), calendarOne.locks)
    }

    // --- the preview timeline ---------------------------------------------------------------------

    @Test
    fun `the preview shows a running window whole rather than clipped`() {
        val p = policy()
        // 36 hours from Friday 09:30 reaches Saturday evening, and the weekly window is Mon-Fri,
        // so this Friday morning is the only thing in it.
        val preview = p.upcoming(friday0930, friday0930 + 36 * 3600, emptyList())
        assertEquals(1, preview.size)
        // 09:00, half an hour before "now" — the preview says how long the block really is, not
        // how much of it is left.
        assertEquals(friday0930 - 1800, preview.first().start)
        assertEquals(friday0930 + 2 * 3600 + 1800, preview.first().end)
        assertEquals(listOf(Lock.Timer), preview.first().locks)
    }

    @Test
    fun `a meeting later today is previewed before it starts`() {
        val p = policy()
        val meeting = CalendarEvent(
            id = "e1",
            title = "Focus block",
            calendar = "Work",
            start = friday0930 + 8 * 3600,
            end = friday0930 + 9 * 3600,
            busy = true,
        )
        val preview = p.upcoming(friday0930, friday0930 + 36 * 3600, listOf(meeting))
        val fromCalendar = preview.filter { it.source is ActivationSource.Calendar }
        assertEquals(1, fromCalendar.size)
        assertEquals(friday0930 + 8 * 3600 - 300, fromCalendar.first().start)
        // It is not active yet, so nothing about it appears in the current activations.
        assertTrue(p.activations(friday0930, listOf(meeting)).none { it.source is ActivationSource.Calendar })
    }

    @Test
    fun `a window with nothing in it previews as empty rather than failing`() {
        val p = policy()
        // Saturday 00:00 to Saturday 06:00: outside every rule this config has.
        val saturday = friday0930 + 14 * 3600 + 1800
        assertEquals(emptyList<Activation>(), p.upcoming(saturday, saturday + 6 * 3600, emptyList()))
    }

    /** Deleting the meeting must not be a way out of the lock the meeting started. */
    @Test
    fun `a calendar event that disappears does not end the session it started`() {
        val p = policy()
        val meeting = CalendarEvent(
            id = "e1",
            title = "Focus block",
            calendar = "Work",
            start = friday0930,
            end = friday0930 + 3600,
            busy = true,
        )
        val started = p.reconcile(friday0930, listOf(meeting), "seed")
        val id = started.first()

        p.reconcile(friday0930 + 60, emptyList(), "seed2")
        assertEquals(listOf("deep-work"), p.activeProfiles(friday0930 + 60))
        try {
            p.endSession(id, friday0930 + 60)
            fail("the lock outlives the event")
        } catch (e: Refused) {
            assertTrue(e.refusal is Refusal.Locked)
        }
    }

    @Test
    fun `the next change is reported so the service can set one alarm`() {
        val next = policy().nextChangeAfter(friday0930, emptyList())
        assertNotNull(next)
        assertTrue("$next", next!! > friday0930)
    }

    // --- the app picker ---------------------------------------------------------------------------

    @Test
    fun `the picker reads back the app blocks it owns`() {
        assertEquals(listOf("com.instagram.android"), Policy.blockedApps(configToml, "deep-work"))
    }

    @Test
    fun `the picker's edit crosses the boundary and still loads`() {
        val edited = Policy
            .setBlockedApps(configToml, "deep-work", listOf("com.twitter.android", "com.tiktok"))
            .getOrThrow()

        val policy = Policy.load(edited)
        assertEquals(
            listOf("com.tiktok", "com.twitter.android"),
            Policy.blockedApps(policy.configToml(), "deep-work"),
        )

        // The rules the picker does not own are still there: a delay and a budget written by hand
        // must survive a visit to a checkbox list.
        val session = Session(
            id = "s1",
            profile = "deep-work",
            source = SessionSource.Manual,
            startedAt = friday0930,
            lock = LockSet(),
        )
        policy.startSession(session)
        assertTrue(policy.decide(friday0930, Observation.App("com.slack")) is Decision.Delay)
    }

    @Test
    fun `the picker refuses a profile that does not exist rather than writing nothing`() {
        assertTrue(Policy.setBlockedApps(configToml, "nope", listOf("com.x")).isFailure)
    }

    // --- the escape hatch ---

    private val hatchToml =
        """
        schema_version = 1
        timezone = "Europe/London"

        [emergency]
        passes = 2
        window_seconds = 604800
        cooldown_seconds = 86400

        [[profiles]]
        id = "deep-work"
        name = "Deep work"
        """.trimIndent()

    private fun hatched(): Policy = Policy.load(hatchToml)

    private fun start(policy: Policy, id: String) {
        policy.startSession(
            Session(
                id = id,
                profile = "deep-work",
                source = SessionSource.Manual,
                startedAt = friday0930,
                lock = LockSet(conditions = listOf(Lock.DeviceCredential), endsAt = null),
            ),
        )
    }

    @Test
    fun `an emergency pass ends a session the screen lock was holding`() {
        val policy = hatched()
        start(policy, "s1")

        policy.spendPass("s1", friday0930)

        assertTrue(policy.sessions().running.isEmpty())
        assertEquals(1, policy.passesRemaining(friday0930))
        assertEquals(listOf(friday0930), policy.passes().used)
    }

    @Test
    fun `a fresh install offers no pass at all`() {
        val policy = policy()
        start(policy, "s1")
        try {
            policy.spendPass("s1", friday0930)
            fail("a config that never mentioned passes handed one out")
        } catch (e: NoPass) {
            assertEquals(PassRefusal.Disabled, e.refusal)
        }
        assertEquals(1, policy.sessions().running.size)
    }

    @Test
    fun `a second pass inside the cooldown is refused and says when`() {
        val policy = hatched()
        start(policy, "s1")
        policy.spendPass("s1", friday0930)
        start(policy, "s2")

        try {
            policy.spendPass("s2", friday0930 + 60)
            fail("the cooldown was not enforced")
        } catch (e: NoPass) {
            assertEquals(PassRefusal.CoolingDown(friday0930 + 86_400), e.refusal)
        }
        assertEquals(1, policy.sessions().running.size)
    }

    @Test
    fun `the quota runs out and comes back a window later`() {
        val policy = hatched()
        start(policy, "s1")
        policy.spendPass("s1", friday0930)
        start(policy, "s2")
        policy.spendPass("s2", friday0930 + 2 * 86_400)
        start(policy, "s3")

        try {
            policy.spendPass("s3", friday0930 + 4 * 86_400)
            fail("a third pass was handed out of a ration of two")
        } catch (e: NoPass) {
            assertEquals(PassRefusal.QuotaSpent(friday0930 + 604_800), e.refusal)
        }
        // And once the oldest use has aged out of the window, the hatch is open again.
        assertEquals(1, policy.passesRemaining(friday0930 + 604_801))
        assertNull(policy.passRefusal(friday0930 + 604_801))
    }

    @Test
    fun `a restored ration is merged rather than replacing what we already heard`() {
        val policy = hatched()
        start(policy, "s1")
        policy.spendPass("s1", friday0930)

        // An older copy from storage, plus a use this device has not seen before.
        policy.restorePasses(Passes(used = listOf(friday0930 - 86_400)))

        assertEquals(listOf(friday0930 - 86_400, friday0930), policy.passes().used)
        assertEquals(0, policy.passesRemaining(friday0930))
    }

    @Test
    fun `a pass spent on a session that has already gone is still spent`() {
        val policy = hatched()
        try {
            policy.spendPass("never-existed", friday0930)
            fail("ending a session that is not running should still refuse")
        } catch (e: Refused) {
            assertEquals(Refusal.NotRunning, e.refusal)
        }
        assertEquals(1, policy.passesRemaining(friday0930))
    }

    @Test
    fun `the profile chooser sees every profile, with the names a person reads`() {
        val profiles = Policy.profiles(configToml)
        assertEquals(listOf(ProfileName("deep-work", "Deep work")), profiles)
    }
}
