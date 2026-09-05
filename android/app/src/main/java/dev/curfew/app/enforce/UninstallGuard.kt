package dev.curfew.app.enforce

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit

/**
 * Uninstall protection without device admin.
 *
 * Being an active device admin is the strongest way to stop Curfew being removed mid-lock, but it
 * is not free: a great many banking and payment apps refuse to run on a device that has any active
 * device admin, and a blocker that costs somebody their bank app has taken more than it gave. So
 * admin is optional, off by default, and this is the route that costs nothing elsewhere.
 *
 * The mechanism is friction rather than a wall, and it is described that way everywhere it appears.
 * While a lock is held, Curfew watches for the two screens that lead to its own removal — its page
 * in Settings, and the uninstall confirmation — and presses Back. Somebody determined can still
 * turn the accessibility service off first, exactly as somebody determined could deactivate a
 * device admin first. What both buy is the same thing: the moment of deliberate effort that a
 * craving does not survive.
 */
object UninstallGuard {

    /**
     * The apps whose screens can remove Curfew.
     *
     * Settings and the package installer on stock Android, plus the vendor "security" apps that
     * own the uninstall flow on Xiaomi, Samsung, Oppo and Huawei devices, where the stock installer
     * is never seen. An unknown vendor simply means the guard does not fire, which is the right
     * failure: a wrong Back press on an unrelated screen would be far worse.
     */
    val WATCHED: Set<String> = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.packageinstaller",
        "com.samsung.android.lool",
        "com.coloros.safecenter",
        "com.oppo.safe",
        "com.huawei.systemmanager",
        "com.vivo.permissionmanager",
    )

    /** Whether a window belonging to this package is worth looking at at all. */
    fun watches(packageName: String): Boolean = packageName in WATCHED

    /**
     * Whether to press Back on the window now in front.
     *
     * All three conditions are needed, and the order they are written in is the order they are
     * cheapest to check: no lock means no reason to intervene, an unwatched app means the screen
     * cannot remove anything, and a watched screen that does not mention Curfew is somebody
     * managing a different app entirely and must be left alone.
     */
    fun shouldIntervene(packageName: String, lockHeld: Boolean, mentionsCurfew: Boolean): Boolean =
        lockHeld && watches(packageName) && mentionsCurfew

    /**
     * Whether this window is about Curfew.
     *
     * The only thing read from the screen, and only while a lock is running: whether Curfew's own
     * name or package appears on it. Nothing is stored, nothing is logged, and no other text is
     * examined — the walk stops the moment it finds a match or runs out of budget.
     */
    fun mentions(root: AccessibilityNodeInfo?, label: String, packageName: String): Boolean {
        if (root == null) return false
        val needles = listOf(label.lowercase(), packageName.lowercase())
        var budget = NODE_BUDGET
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && budget-- > 0) {
            val node = queue.removeFirst()
            val text = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
                .lowercase()
            if (needles.any { it.isNotEmpty() && text.contains(it) }) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }

    /**
     * Whether a lock is currently held.
     *
     * In preferences rather than in the database, because the two readers — an accessibility event
     * and a device-admin broadcast — both have to answer immediately and neither has a coroutine
     * scope to open an encrypted database from.
     */
    fun isLockHeld(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCK_HELD, false)

    fun setLockHeld(context: Context, held: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_LOCK_HELD, held) }
    }

    /** What the user is told when the guard fires. Said as a fact, never as a scolding. */
    const val EXPLANATION: String =
        "A lock is running, so Curfew is holding its own Settings page shut. End the session the " +
            "way it asks and this stops immediately."

    private const val PREFS = "curfew_admin"
    private const val KEY_LOCK_HELD = "lock_held"

    /**
     * How many nodes the search may look at. A settings page is tens of nodes; this is a ceiling
     * against a pathological tree, not a tuning knob.
     */
    private const val NODE_BUDGET = 400
}
