package dev.curfew.app.data

import android.content.Context
import android.net.wifi.WifiManager
import dev.curfew.policy.Peer
import dev.curfew.policy.Sync
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sync as the app uses it: one [Sync], the multicast lock it needs, and the state the UI reads.
 *
 * Deliberately subordinate to enforcement. Every call here is allowed to fail, and a failure costs
 * an audit line and nothing else — a phone that cannot reach its PC must go on blocking exactly as
 * it was, because the alternative is a blocker that can be switched off by unplugging a router.
 */
class SyncHub private constructor(
    private val context: Context,
    val sync: Sync,
) {
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())

    /** Paired devices, revoked ones included, for the devices screen. */
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _stillLocked = MutableStateFlow<List<String>>(emptyList())

    /**
     * Sessions another device says are over and this one is still holding.
     *
     * Surfaced rather than swallowed: the user's other device told them the block had ended, this
     * one disagrees, and they are owed the reason — which is the lock they asked for.
     */
    val stillLocked: StateFlow<List<String>> = _stillLocked.asStateFlow()

    private val _running = MutableStateFlow(false)

    /**
     * Whether the node is up, as last observed by the sync loop.
     *
     * Cached rather than asked, and this is the whole point of the class right now: asking takes
     * the node's lock inside the core, and that lock is held for as long as a pass takes — measured
     * on this device at fifty-two seconds. The UI refreshes once a second, so a UI that asked
     * directly spent a minute in every minute unable to redraw: a profile saved and did not appear,
     * a permission was granted and the screen went on denying it, a session ended and went on
     * looking live. Nothing on a screen is worth blocking for, so nothing on a screen asks.
     */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _nearby = MutableStateFlow<Set<String>>(emptySet())

    /** Peers heard from recently, as last observed by the sync loop. Cached, for the reason above. */
    val nearby: StateFlow<Set<String>> = _nearby.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** The last thing that went wrong, for the health screen. Null when sync is behaving. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Anything the store complained about at startup: an unreadable log, a lost identity. */
    val complaints: List<String> = runCatching { sync.complaints() }.getOrDefault(emptyList())

    val deviceId: String get() = sync.deviceId()
    val fingerprint: String get() = sync.fingerprint()

    private val multicast: WifiManager.MulticastLock? =
        runCatching {
            context.getSystemService(WifiManager::class.java)
                .createMulticastLock("curfew-sync")
                .apply { setReferenceCounted(false) }
        }.getOrNull()

    /**
     * Start listening for the other devices.
     *
     * The multicast lock is what lets this phone *hear* beacons; without it the node still runs and
     * still answers anyone who connects, so failing to take the lock is worth noting and not worth
     * stopping for.
     */
    fun start() {
        runCatching { multicast?.acquire() }
        attempt("start") { sync.startNode() }
        refreshPeers()
        _running.value = isRunning()
    }

    fun stop() {
        runCatching { sync.stopNode() }
        runCatching { if (multicast?.isHeld == true) multicast.release() }
        _running.value = false
        _nearby.value = emptySet()
    }

    /**
     * Read what the screens show about sync, on the thread that is allowed to wait for it.
     *
     * Called from the sync loop and from nowhere else. Both calls below take the node's lock, so
     * this is exactly the work that must not happen on the UI's refresh: it is done once a minute
     * where waiting costs nothing, and the results sit in [running] and [nearby] to be read for
     * free.
     */
    fun observe(now: Long) {
        _running.value = isRunning()
        _nearby.value = runCatching { sync.nearby(now) }.getOrDefault(emptyList()).toSet()
        refreshPeers()
    }

    /** Blocking. Only ever called from the sync loop — the UI reads [running]. */
    fun isRunning(): Boolean = runCatching { sync.isRunning() }.getOrDefault(false)

    fun refreshPeers() {
        runCatching { sync.peers() }.onSuccess { _peers.value = it }
    }

    /** Tell everyone in earshot what changed here. */
    fun push(now: Long) = attempt("push") { sync.push(now) }

    /** Exchange through a folder the user pointed at — a synced drive, an SD card. */
    fun folderPass(root: String): Boolean =
        attempt("folder") { sync.folderPass(root) }

    fun record(pass: dev.curfew.policy.Pass) {
        _stillLocked.value = pass.stillLocked
    }

    fun forget(sessions: List<String>) {
        _stillLocked.value = _stillLocked.value - sessions.toSet()
    }

    fun save() = attempt("save") { sync.save() }

    fun compact(through: Long, now: Long) = attempt("compact") { sync.compact(through, now) }

    /** Run something that is allowed to fail, remembering why it did. */
    private fun attempt(what: String, body: () -> Unit): Boolean =
        runCatching(body)
            .onSuccess { _lastError.value = null }
            .onFailure { _lastError.value = "$what: ${it.message ?: it::class.java.simpleName}" }
            .isSuccess

    companion object {
        /**
         * Open this device's sync state under the app's private storage.
         *
         * Private storage and nothing else: the identity file is this device's private key, and a
         * device that can be impersonated can be told that its locks are over.
         */
        fun create(
            context: Context,
            deviceName: String,
            dir: File = File(context.filesDir, "sync"),
        ): SyncHub? {
            dir.mkdirs()
            val sync = runCatching { Sync.open(dir.absolutePath, deviceName) }.getOrNull()
            return sync?.let { SyncHub(context.applicationContext, it) }
        }
    }
}
