package dev.curfew.app

import androidx.test.core.app.ApplicationProvider
import dev.curfew.app.data.CurfewRuntime
import dev.curfew.app.data.SyncHub
import dev.curfew.policy.Lock
import dev.curfew.policy.LockSet
import dev.curfew.policy.Session
import dev.curfew.policy.SessionSource
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two devices, as the app puts them together.
 *
 * The core proves the merge rules and the policy module proves the crossing. What is left to prove
 * here is what the app itself adds: that a pass writes the other device's minutes into this one's
 * database exactly once, that a peer cannot talk this device out of a lock even when the request
 * arrives through the app's own plumbing, and that a device with no one to talk to keeps enforcing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncHubTest {

    private val now = TestRuntime.FRIDAY_0930
    private val hour = 3600L

    private fun dir(tag: String) = Files.createTempDirectory("curfew-hub-$tag").toFile()

    private fun hub(tag: String, name: String): SyncHub =
        SyncHub.create(ApplicationProvider.getApplicationContext(), name, dir(tag))!!

    /** Two hubs that have been all the way through the ceremony, phrases compared and all. */
    private fun paired(tag: String): Pair<SyncHub, SyncHub> {
        val phone = hub("$tag-phone", "phone")
        val pc = hub("$tag-pc", "pc")
        val invite = pc.sync.invite(now)
        val reply = phone.sync.replyTo(invite)
        assertEquals(
            "the two devices would have shown the user different phrases",
            phone.sync.phrase(invite),
            pc.sync.phrase(reply),
        )
        phone.sync.accept(invite, now)
        pc.sync.accept(reply, now)
        phone.refreshPeers()
        pc.refreshPeers()
        return phone to pc
    }

    /** Carry everything each device knows to the other, the way a shared folder would. */
    private fun carry(from: SyncHub, to: SyncHub, root: String) {
        from.folderPass(root)
        to.folderPass(root)
    }

    private fun runtime(clock: MovableClock = MovableClock(now)): CurfewRuntime =
        TestRuntime.create(clock = clock)

    private fun session(id: String, locks: List<Lock>, endsAt: Long?) = Session(
        id = id,
        profile = "deep-work",
        source = SessionSource.Manual,
        startedAt = now,
        lock = LockSet(conditions = locks, endsAt = endsAt),
    )

    @Test
    fun `a block started on the PC is enforced on the phone`() = runTest {
        val (phoneHub, pcHub) = paired("adopt")
        val root = dir("adopt-folder").absolutePath
        val phone = runtime()
        val pc = runtime()
        phone.attachSync(phoneHub)
        pc.attachSync(pcHub)

        pc.startSession(session("pc-1", listOf(Lock.DeviceCredential), now + hour))
        pc.syncPass(now)
        carry(pcHub, phoneHub, root)
        val pass = phone.syncPass(now + 1)

        assertEquals(listOf("pc-1"), pass?.adopted)
        assertTrue("the phone did not take up the PC's lock", phone.lock.value.isLocked)
        assertTrue(
            "the adoption was not written down",
            phone.db.audit().recent(50).any { it.kind == "sync.adopted" },
        )
    }

    @Test
    fun `the PC cannot talk the phone out of a lock`() = runTest {
        val (phoneHub, pcHub) = paired("refuse")
        val root = dir("refuse-folder").absolutePath
        val phone = runtime()
        val pc = runtime()
        phone.attachSync(phoneHub)
        pc.attachSync(pcHub)
        pc.startSession(session("pc-1", listOf(Lock.DeviceCredential), now + hour))
        pc.syncPass(now)
        carry(pcHub, phoneHub, root)
        phone.syncPass(now + 1)

        // The PC's user proved the lock there. The phone was handed no such proof.
        pc.recordCredential("pc-1", now + 2)
        pc.endSession("pc-1", now = now + 2)
        pc.syncPass(now + 2)
        carry(pcHub, phoneHub, root)
        val pass = phone.syncPass(now + 3)

        assertEquals(listOf("pc-1"), pass?.stillLocked)
        assertTrue("a peer talked this device out of its lock", phone.lock.value.isLocked)
        assertEquals(listOf("pc-1"), phoneHub.stillLocked.value)
        assertTrue(phone.db.audit().recent(50).any { it.kind == "sync.refused" })
    }

    @Test
    fun `minutes spent on the PC are charged once on the phone`() = runTest {
        val (phoneHub, pcHub) = paired("budget")
        val root = dir("budget-folder").absolutePath
        val phone = runtime()
        val pc = runtime()
        phone.attachSync(phoneHub)
        pc.attachSync(pcHub)
        pc.recordUsage("reddit.com", now, 600)
        pc.syncPass(now)
        carry(pcHub, phoneHub, root)

        // Two passes over the same log. A shared budget that is charged twice would let the second
        // one finish it off, which is the difference between a shared budget and a doubled one.
        phone.syncPass(now + 1)
        phone.syncPass(now + 2)

        val spent = phone.usage(now + 2).usage["reddit.com"]?.rollups?.sumOf { it.seconds }
        assertEquals(600, spent)
    }

    @Test
    fun `a device with nobody to talk to goes on blocking`() = runTest {
        val lonely = hub("lonely", "phone")
        val phone = runtime()
        phone.attachSync(lonely)
        phone.startSession(session("s1", listOf(Lock.Timer), now + hour))

        val pass = phone.syncPass(now)

        assertNotNull("a pass with no peers should still publish", pass)
        assertTrue(pass!!.adopted.isEmpty())
        assertTrue(pass.stillLocked.isEmpty())
        assertTrue("the lock was dropped when sync found nobody", phone.lock.value.isLocked)
    }

    @Test
    fun `a device that was never given sync enforces exactly as before`() = runTest {
        val phone = runtime()
        phone.startSession(session("s1", listOf(Lock.Timer), now + hour))

        assertEquals(null, phone.syncPass(now))
        assertTrue(phone.lock.value.isLocked)
    }

    @Test
    fun `a removed device is not listened to`() = runTest {
        val (phoneHub, pcHub) = paired("revoke")
        val root = dir("revoke-folder").absolutePath
        val phone = runtime()
        val pc = runtime()
        phone.attachSync(phoneHub)
        pc.attachSync(pcHub)

        phoneHub.sync.revoke(pcHub.deviceId, now)
        phoneHub.refreshPeers()
        pc.startSession(session("pc-1", listOf(Lock.Timer), now + hour))
        pc.syncPass(now)
        carry(pcHub, phoneHub, root)
        val pass = phone.syncPass(now + 1)

        assertTrue("a removed device was still obeyed", pass!!.adopted.isEmpty())
        assertFalse(phone.lock.value.isLocked)
        assertFalse(phoneHub.peers.value.single().isActive)
    }

    @Test
    fun `a folder that cannot be used costs an error and nothing else`() = runTest {
        val (lonely, _) = paired("bad-folder")
        val phone = runtime()
        phone.attachSync(lonely)
        phone.startSession(session("s1", listOf(Lock.Timer), now + hour))

        // A file where a folder should be: the one filesystem failure that is easy to arrange, and
        // exactly what a user who picked the wrong thing in a file picker hands over.
        val notAFolder = java.io.File.createTempFile("curfew-not-a-folder", ".txt")
        notAFolder.deleteOnExit()

        val ok = lonely.folderPass(notAFolder.absolutePath)

        assertFalse(ok)
        assertNotNull("the failure was swallowed without trace", lonely.lastError.value)
        assertTrue("a bad folder stopped enforcement", phone.lock.value.isLocked)
        assertNotNull("sync kept working after a bad folder", phone.syncPass(now + 1))
    }
}
