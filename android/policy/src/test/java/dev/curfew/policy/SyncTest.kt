package dev.curfew.policy

import java.nio.file.Files
import kotlin.io.path.absolutePathString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin side of sync, against the real core through JNA.
 *
 * The hard parts are tested in Rust. What is worth testing here is the crossing itself: that the
 * JSON shapes this module declares match the ones the core writes, and that the two rules the UI
 * depends on — a peer cannot end a live lock, and a shared budget is charged once — still hold
 * when they are reached from Kotlin.
 */
class SyncTest {

    private val now = 1_788_510_600L
    private val hour = 3600L

    private fun dir(tag: String): String =
        Files.createTempDirectory("curfew-sync-$tag").absolutePathString()

    private fun policy(): Policy =
        Policy.load(
            """
            timezone = "UTC"

            [[profiles]]
            id = "deep-work"
            name = "Deep work"
            """.trimIndent(),
        )

    /** Two devices that have been through the whole ceremony, phrases compared and all. */
    private fun paired(tag: String): kotlin.Pair<Sync, Sync> {
        val phone = Sync.open(dir("$tag-phone"), "phone")
        val pc = Sync.open(dir("$tag-pc"), "pc")
        val invite = pc.invite(now)
        val reply = phone.replyTo(invite)
        assertEquals(
            "the two devices would have shown the user different phrases",
            phone.phrase(invite),
            pc.phrase(reply),
        )
        phone.accept(invite, now)
        pc.accept(reply, now)
        return phone to pc
    }

    private fun session(id: String, locks: List<Lock>, endsAt: Long?) =
        Session(
            id = id,
            profile = "deep-work",
            source = SessionSource.Manual,
            startedAt = now,
            lock = LockSet(conditions = locks, endsAt = endsAt),
        )

    /** Carry everything one device knows to the other, the way a folder or a network round would. */
    private fun carry(from: Sync, to: Sync, root: String) {
        from.folderPass(root)
        to.folderPass(root)
    }

    @Test
    fun `a device has an id and a fingerprint to show`() {
        val sync = Sync.open(dir("identity"), "phone")

        assertEquals(26, sync.deviceId().length)
        assertTrue(sync.complaints().isEmpty())
        assertTrue(sync.fingerprint().isNotBlank())
        assertFalse(sync.isRunning())
    }

    @Test
    fun `pairing leaves each device knowing the other`() {
        val (phone, pc) = paired("peers")

        val known = phone.peers()
        assertEquals(1, known.size)
        assertEquals("pc", known[0].identity.name)
        assertEquals(pc.deviceId(), known[0].id)
        assertTrue(known[0].isActive)

        phone.revoke(pc.deviceId(), now + 1)
        assertFalse(phone.peers()[0].isActive)
    }

    @Test
    fun `a lock started on the PC is held by the phone`() {
        val (phone, pc) = paired("adopt")
        val root = dir("adopt-folder")
        val (onPhone, onPc) = policy() to policy()
        onPc.startSession(session("pc-1", listOf(Lock.DeviceCredential), now + hour))

        pc.pass(onPc, now, emptyMap(), emptyMap())
        carry(pc, phone, root)
        val pass = phone.pass(onPhone, now + 1, emptyMap(), emptyMap())

        assertEquals(listOf("pc-1"), pass.adopted)
        assertTrue("the phone did not take up the PC's lock", onPhone.mergedLock(now + 1).isLocked)
    }

    @Test
    fun `the PC cannot talk the phone out of a lock`() {
        val (phone, pc) = paired("refuse")
        val root = dir("refuse-folder")
        val (onPhone, onPc) = policy() to policy()
        onPc.startSession(session("pc-1", listOf(Lock.DeviceCredential), now + hour))
        pc.pass(onPc, now, emptyMap(), emptyMap())
        carry(pc, phone, root)
        phone.pass(onPhone, now + 1, emptyMap(), emptyMap())

        // The PC's user satisfied the lock there. The phone was given no such evidence.
        onPc.endSession("pc-1", now + 2, listOf(Lock.DeviceCredential))
        pc.pass(onPc, now + 2, emptyMap(), emptyMap())
        carry(pc, phone, root)
        val pass = phone.pass(onPhone, now + 3, emptyMap(), emptyMap())

        assertEquals(listOf("pc-1"), pass.stillLocked)
        assertTrue("a peer talked this device out of its lock", onPhone.mergedLock(now + 3).isLocked)
    }

    @Test
    fun `time spent on one device counts against the budget on the other`() {
        val (phone, pc) = paired("budget")
        val root = dir("budget-folder")
        val spent = mapOf("com.instagram.android" to Consumption(listOf(Rollup(now, 1800))))

        pc.pass(policy(), now, spent, emptyMap())
        carry(pc, phone, root)
        val pass = phone.pass(policy(), now + 1, emptyMap(), emptyMap())

        val seconds = pass.usage["com.instagram.android"]?.rollups?.sumOf { it.seconds }
        assertEquals(1800, seconds)
    }

    @Test
    fun `what one device knows survives the app being killed`() {
        val place = dir("restart")
        val first = Sync.open(place, "phone")
        val id = first.deviceId()
        val other = Sync.open(dir("restart-pc"), "pc")
        val invite = other.invite(now)
        first.accept(invite, now)
        first.save()
        first.close()

        val second = Sync.open(place, "phone")

        assertEquals("the device changed identity across a restart", id, second.deviceId())
        assertEquals(1, second.peers().size)
    }

    @Test
    fun `the node starts and stops without ceremony`() {
        val sync = Sync.open(dir("node"), "phone")

        sync.startNode()
        assertTrue(sync.isRunning())
        sync.startNode()
        sync.push(now)
        sync.stopNode()

        assertFalse(sync.isRunning())
    }

    /** A policy whose rules act on meetings, so a published event has something to match. */
    private fun calendarPolicy(): Policy =
        Policy.load(
            """
            timezone = "UTC"

            [[profiles]]
            id = "deep-work"
            name = "Deep work"

            [[calendars]]
            id = "meetings"
            profile = "deep-work"

            [calendars.matcher]
            busy_only = true
            """.trimIndent(),
        )

    private fun meeting(title: String, busy: Boolean) =
        CalendarEvent(
            id = "e1",
            title = title,
            calendar = "Work",
            start = now + 600,
            end = now + 4200,
            busy = busy,
        )

    @Test
    fun `a meeting only one device can see reaches the other`() {
        val (phone, pc) = paired("calendar")
        val root = dir("calendar-folder")
        pc.pass(calendarPolicy(), now, emptyMap(), emptyMap(), listOf(meeting("Design review", busy = true)))
        carry(pc, phone, root)

        val pass = phone.pass(policy(), now + 1, emptyMap(), emptyMap())

        assertEquals(1, pass.calendar.size)
        assertEquals("Design review", pass.calendar[0].title)
        assertEquals("${pc.deviceId()}/e1", pass.calendar[0].id)
    }

    @Test
    fun `a meeting no rule cares about is never published`() {
        // Minimal disclosure: the log carries the meetings that drive a block, not a transcript of
        // someone's week.
        val (phone, pc) = paired("calendar-quiet")
        val root = dir("calendar-quiet-folder")
        pc.pass(calendarPolicy(), now, emptyMap(), emptyMap(), listOf(meeting("Lunch", busy = false)))
        carry(pc, phone, root)

        assertTrue(phone.pass(policy(), now + 1, emptyMap(), emptyMap()).calendar.isEmpty())
    }
}
