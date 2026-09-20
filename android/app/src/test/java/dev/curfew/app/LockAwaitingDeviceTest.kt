package dev.curfew.app

import dev.curfew.app.ui.locksAwaitingDevice
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.Lock
import dev.curfew.policy.WeeklySchedule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Counting the locks that name a device as their way out.
 *
 * This number decides whether the confirmation before "Remove this device" carries the one sentence
 * that matters — that the lock's exit is about to stop existing — and getting it wrong in either
 * direction is bad in a specific way:
 *
 *  - **Too low** and the warning is absent on the removal that needed it. The user removes a device, a
 *    lock becomes unsatisfiable, and nothing said so.
 *  - **Too high** and every removal carries a scare that is not true, which teaches people to dismiss
 *    the dialog — and then the true one gets dismissed too.
 *
 * So the cases below are the shapes that would get it wrong, not a sampling of happy paths.
 */
class LockAwaitingDeviceTest {
    private fun window(vararg locks: Lock) = WeeklySchedule(
        id = "w-1",
        profile = "deep-work",
        startMinute = 21 * 60,
        endMinute = 0,
        locks = locks.toList(),
    )

    private fun rule(vararg locks: Lock) = CalendarSchedule(
        id = "c-1",
        profile = "deep-work",
        locks = locks.toList(),
    )

    @Test
    fun `a window awaiting this device is counted`() {
        assertEquals(1, locksAwaitingDevice(listOf(window(Lock.PeerRelease("laptop"))), emptyList(), "laptop"))
    }

    @Test
    fun `a rule awaiting this device is counted`() {
        assertEquals(1, locksAwaitingDevice(emptyList(), listOf(rule(Lock.PeerRelease("laptop"))), "laptop"))
    }

    @Test
    fun `windows and rules are both counted, and add up`() {
        assertEquals(
            3,
            locksAwaitingDevice(
                listOf(window(Lock.PeerRelease("laptop")), window(Lock.PeerRelease("laptop"))),
                listOf(rule(Lock.PeerRelease("laptop"))),
                "laptop",
            ),
        )
    }

    /** The device being removed is not the one the lock awaits — no warning belongs here. */
    @Test
    fun `a lock awaiting a different device is not counted`() {
        assertEquals(0, locksAwaitingDevice(listOf(window(Lock.PeerRelease("phone"))), emptyList(), "laptop"))
    }

    /**
     * A `PeerRelease` names an **id**, not a name.
     *
     * The peer list shows `identity.name` and the lock stores `deviceId`, so these are different strings
     * and a caller that passed the wrong one would silently count zero. Pinned because that is the exact
     * mistake the screen could make, and it would fail in the direction that omits the warning.
     */
    @Test
    fun `the id is matched, not the display name`() {
        val locks = listOf(window(Lock.PeerRelease("dev-7f3a")))
        assertEquals(1, locksAwaitingDevice(locks, emptyList(), "dev-7f3a"))
        assertEquals(0, locksAwaitingDevice(locks, emptyList(), "Satwik's laptop"))
    }

    @Test
    fun `other lock kinds are not counted`() {
        val others = listOf(
            Lock.Timer,
            Lock.Confirm,
            Lock.DeviceCredential,
            Lock.RestartRequired,
            Lock.Token("tag"),
            Lock.Challenge(dev.curfew.policy.ChallengeKind.TYPING),
        )
        assertEquals(0, locksAwaitingDevice(listOf(window(*others.toTypedArray())), emptyList(), "laptop"))
    }

    /** A window with no locks at all, and an empty schedule — the two common cases. */
    @Test
    fun `nothing is counted when nothing awaits a device`() {
        assertEquals(0, locksAwaitingDevice(listOf(window()), listOf(rule()), "laptop"))
        assertEquals(0, locksAwaitingDevice(emptyList(), emptyList(), "laptop"))
    }

    /** Two locks on one window, both naming the device: both count, because both would lose their exit. */
    @Test
    fun `two locks on one window both count`() {
        assertEquals(
            2,
            locksAwaitingDevice(
                listOf(window(Lock.PeerRelease("laptop"), Lock.PeerRelease("laptop"))),
                emptyList(),
                "laptop",
            ),
        )
    }
}
