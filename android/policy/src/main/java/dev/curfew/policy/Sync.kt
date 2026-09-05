package dev.curfew.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import uniffi.curfew_ffi.Sync as FfiSync

/**
 * The Kotlin face of sync: pairing, the local-network node, and one call per tick that squares the
 * shared log with what this device is enforcing.
 *
 * Two rules survive the crossing and the UI has to be built around them rather than around a hope
 * that peers behave:
 *
 *  - Another device saying a session is over is a *request*. If the lock is still live here, this
 *    device keeps blocking and reports the session in [Pass.stillLocked]. That is not a wrinkle to
 *    be smoothed over — it is invariant 2, and the user is owed the explanation.
 *  - The pairing phrase is checked by a human. Nothing in this class can verify that two screens
 *    showed the same six digits, so [accept] must not be reached until the person said they did.
 */
class Sync private constructor(private val inner: FfiSync) {

    companion object {
        /**
         * Open, or create on first run, this device's sync state in [dir].
         *
         * [dir] must be storage only this app can read — `Context.filesDir` and below. The identity
         * file is this device's private key, and a device that can be impersonated can be told its
         * locks are over.
         */
        fun open(dir: String, deviceName: String): Sync = Sync(FfiSync.open(dir, deviceName))
    }

    /** Trouble found while opening: an unreadable log, a lost identity. Empty on a healthy device. */
    fun complaints(): List<String> = inner.complaints()

    /** This device's id, as peers see it. */
    fun deviceId(): String = inner.deviceId()

    /** The fingerprint shown beside the id, for a user comparing two screens. */
    fun fingerprint(): String = inner.fingerprint()

    // --- pairing -----------------------------------------------------------------------------------

    /** An offer to pair, as the JSON that goes into a QR code. Not secret; it may be photographed. */
    fun invite(now: Long): String = inner.inviteJson(now)

    /**
     * Answer someone else's invite with this device's keys and the same nonce.
     *
     * The phrase covers both devices' keys, so the device that *offered* cannot show one until it
     * has seen the other's keys. The phone scans the PC's code and displays this reply; the PC
     * reads it back; both then show the same six digits.
     */
    fun replyTo(inviteJson: String): String = inner.replyTo(inviteJson)

    /** The six digits to put on screen for the user to compare against the other device's. */
    fun phrase(inviteJson: String): String = inner.phraseFor(inviteJson)

    /** Pair, once the person has said the phrases matched. Returns the new peer's id. */
    fun accept(inviteJson: String, now: Long): String = inner.acceptInvite(inviteJson, now)

    /** Every device this one has paired with, revoked ones included. */
    fun peers(): List<Peer> =
        Policy.json.decodeFromString(PeerList.serializer(), inner.peersJson())
            .devices
            .map { (id, peer) -> peer.copy(id = id) }
            .sortedBy { it.identity.name }

    /**
     * Remove a device. Immediate, local, and needing nothing from the device being removed: a phone
     * that has been lost cannot be asked to agree to its own removal.
     */
    fun revoke(deviceId: String, now: Long) = inner.revoke(deviceId, now)

    // --- the node ----------------------------------------------------------------------------------

    /** Start listening and announcing on the local network. Idempotent. */
    fun startNode() = inner.startNode()

    /**
     * Stop the network threads, for a backgrounded app that would rather not hold a socket open.
     * Enforcement is untouched: what this device already knows, it keeps.
     */
    fun stopNode() = inner.stopNode()

    fun isRunning(): Boolean = inner.isRunning()

    /** Talk to every peer heard from recently. Cheap and safe to call often. */
    fun push(now: Long) = inner.pushAll(now)

    /** The ids of peers seen on this network recently, for the pairing and health screens. */
    fun nearby(now: Long): List<String> = inner.nearby(now)

    /**
     * Exchange through a shared folder — a synced drive, an SD card — for two devices that are
     * never on the same network at the same time.
     */
    fun folderPass(root: String): FolderPass =
        Policy.json.decodeFromString(FolderPass.serializer(), inner.folderPass(root))

    // --- the tick ----------------------------------------------------------------------------------

    /**
     * One pass: publish what this device is enforcing, then adopt what the others are.
     *
     * The budgets cross as JSON because on Android the usage history lives in Room rather than in
     * the core. What comes back is what the log says the totals now are, and the caller writes that
     * back — a target used on the phone and on the PC is charged once, not twice.
     */
    fun pass(
        policy: Policy,
        now: Long,
        usage: Map<String, Consumption>,
        launches: Map<String, Launches>,
        calendar: List<CalendarEvent> = emptyList(),
    ): Pass {
        val result = inner.pass(
            policy.core(),
            now,
            Policy.json.encodeToString(UsageMap, usage),
            Policy.json.encodeToString(LaunchMap, launches),
            Policy.json.encodeToString(EventList, calendar),
        )
        return Policy.json.decodeFromString(Pass.serializer(), result)
    }

    /** Drop log entries older than [through]. Called from the daily maintenance worker. */
    fun compact(through: Long, now: Long) = inner.compact(through, now)

    /** Write the log and the peer list to disk. */
    fun save() = inner.save()

    fun close() = inner.close()
}

/** What one [Sync.pass] did, and what the caller must now store. */
@Serializable
data class Pass(
    /** How many entries this device wrote into the shared log. */
    val published: Int = 0,
    /** Sessions that started elsewhere and are now running here too. */
    val adopted: List<String> = emptyList(),
    /**
     * Sessions another device ended that are still locked here.
     *
     * The UI owes the user this: their other device says the block is over and this one disagrees,
     * and the reason is the lock they themselves asked for.
     */
    @SerialName("still_locked") val stillLocked: List<String> = emptyList(),
    val usage: Map<String, Consumption> = emptyMap(),
    val launches: Map<String, Launches> = emptyMap(),
    /**
     * Events the other devices' calendars hold, each tagged with the device that saw it.
     *
     * This is how a phone that was never given calendar permission still goes quiet during a
     * meeting: the PC can see the meeting and says so. This device's own events are not in here —
     * it already has them, untagged.
     */
    val calendar: List<CalendarEvent> = emptyList(),
    /**
     * Sessions whose lock names *this* device as the one that must let them out, and which this
     * device has not released yet.
     *
     * Recomputed every pass, because adopting a peer's session is what makes such a lock appear
     * here at all: the phone can be asked to release a lock it only just heard about.
     */
    val releasable: List<String> = emptyList(),
)

/**
 * What one pass over a shared folder did.
 *
 * [lastWritten] is when each peer last left something in the folder, which is the only honest
 * answer to "is the other device still using this?" — a folder transport has no liveness of its
 * own, and a sync client that stopped syncing looks exactly like a device that stopped writing.
 */
@Serializable
data class FolderPass(
    /** Entries taken in from peers this pass. */
    val accepted: Int = 0,
    /** Segments written for peers this pass. */
    val written: Int = 0,
    @SerialName("last_written") val lastWritten: Map<String, Long> = emptyMap(),
)

/** A paired device, as this one remembers it. */
@Serializable
data class Peer(
    val identity: PeerIdentity,
    @SerialName("paired_at") val pairedAt: Long,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    /** Filled in from the map key by [Sync.peers]: the core keys peers by id rather than storing it. */
    @Transient val id: String = "",
) {
    val isActive: Boolean get() = revokedAt == null
}

/**
 * A peer's public keys and the name its owner gave it.
 *
 * The name is advisory and is authenticated by nothing, so it may be shown and must never be
 * decided from: the fingerprint is what identifies a device to a person.
 */
@Serializable
data class PeerIdentity(
    val name: String,
    val signing: List<Int>,
    val exchange: List<Int>,
)

@Serializable
private data class PeerList(val devices: Map<String, Peer> = emptyMap())

private val EventList = ListSerializer(CalendarEvent.serializer())
private val UsageMap = MapSerializer(String.serializer(), Consumption.serializer())
private val LaunchMap = MapSerializer(String.serializer(), Launches.serializer())
