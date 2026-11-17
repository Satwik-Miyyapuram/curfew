package dev.curfew.app.enforce

import android.content.Context
import androidx.core.content.edit

/**
 * Whether a lock is held, for readers that must answer synchronously.
 *
 * In preferences rather than in the database, because the reader — the accessibility service's
 * one-tap switch-off — has to answer immediately and has no coroutine scope to open an encrypted
 * database from.
 *
 * Nothing here stands in front of uninstalling Curfew. A plain uninstall from Settings or the home
 * screen is always the way out of any lock, with no device admin to deactivate first and no screen
 * held shut; the lock is a promise the app keeps, not a wall around the phone.
 */
object LockFlag {

    fun isHeld(context: Context, now: Long = System.currentTimeMillis() / 1000): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The end time is checked here as well as by whoever clears the flag, so a flag left behind
        // by a process that died before the lock ended stops holding anything once the lock is over.
        return prefs.getBoolean(KEY_HELD, false) && now < prefs.getLong(KEY_UNTIL, Long.MAX_VALUE)
    }

    /** Record whether a lock is held, and until when in epoch seconds; null for no set end. */
    fun set(context: Context, held: Boolean, until: Long? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_HELD, held)
                putLong(KEY_UNTIL, until ?: Long.MAX_VALUE)
            }
    }

    private const val PREFS = "curfew_lock"
    private const val KEY_HELD = "lock_held"
    private const val KEY_UNTIL = "lock_until"
}
