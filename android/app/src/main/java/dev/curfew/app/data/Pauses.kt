package dev.curfew.app.data

import android.content.Context

/** A profile that was paused: when it comes back, and when the block it came from was due to end. */
data class Pause(val profile: String, val resumeAt: Long, val endsAt: Long?)

/**
 * The pauses waiting to run out, one per profile.
 *
 * The core has no pause, only an end, so a pause is an end plus a note to start the same profile
 * again later. The note lives here, outside the core's log, because it is a promise this device makes
 * to itself rather than a fact about the policy: lose it and the block simply stays ended, which is
 * the worst a pause can do and never a lockout.
 */
class Pauses(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<Pause> = prefs.all.mapNotNull { (profile, value) ->
        val parts = (value as? String)?.split('|') ?: return@mapNotNull null
        val resumeAt = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        Pause(profile, resumeAt, parts.getOrNull(1)?.toLongOrNull())
    }

    fun add(pause: Pause) {
        prefs.edit().putString(pause.profile, "${pause.resumeAt}|${pause.endsAt ?: ""}").apply()
    }

    fun remove(profile: String) {
        prefs.edit().remove(profile).apply()
    }

    /** The pauses that have run out by [now], removed as they are returned so each resumes once. */
    fun takeDue(now: Long): List<Pause> {
        val due = all().filter { it.resumeAt <= now }
        if (due.isNotEmpty()) prefs.edit().apply { due.forEach { remove(it.profile) } }.apply()
        return due
    }

    fun next(): Long? = all().minOfOrNull { it.resumeAt }

    private companion object {
        const val PREFS = "curfew.pauses"
    }
}
