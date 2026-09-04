package dev.curfew.app.ui

import dev.curfew.policy.ActivationSource
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import dev.curfew.policy.SessionSource
import java.text.DateFormat
import java.util.Date

/**
 * How Curfew talks about time, targets and locks.
 *
 * All of it in one file because these strings are the app's manners. A blocker spends its life
 * telling someone "no", and the difference between a tool a person keeps and one they uninstall in
 * a bad moment is largely whether the "no" was specific, honest, and phrased as something they
 * chose.
 */

/** A length of time, rounded the way a person would say it out loud. */
fun duration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "$s sec"
        s < 3600 -> "${s / 60} min"
        s % 3600 == 0 -> "${s / 3600} hr"
        else -> "${s / 3600} hr ${(s % 3600) / 60} min"
    }
}

/** "in 12 min", "3 hr ago", "now" — for an instant relative to another. */
fun relative(at: Long, now: Long): String {
    val delta = at - now
    if (delta in -30..30) return "now"
    return if (delta > 0) "in ${duration(delta.toInt())}" else "${duration((-delta).toInt())} ago"
}

/** A wall-clock time in the device's own format, for anything more than a few hours away. */
fun clockTime(epochSeconds: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000))

fun dateTime(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(epochSeconds * 1000))

/**
 * A usage key as the user would recognise it.
 *
 * The core keys usage by a rule's target — `app:com.reddit.frontpage`, `domain:reddit.com` — and
 * that prefix is meaningful to the core and noise to a reader.
 */
fun describeTarget(key: String): String {
    val kind = key.substringBefore(':', "")
    val value = key.substringAfter(':', key)
    return when (kind) {
        "app" -> value
        "screen" -> "$value (screen)"
        "exe" -> value
        "title" -> "windows titled “$value”"
        "domain" -> value
        "url" -> value
        "keyword" -> "anything mentioning “$value”"
        "path" -> value
        "notif" -> "$value notifications"
        else -> if (key == "device") "the whole device" else key
    }
}

/** What a lock will want before it lets go. Written as a demand, because that is what it is. */
fun describeLock(lock: Lock): String = when (lock) {
    is Lock.Timer -> "the session's own timer to run out"
    is Lock.Confirm -> "a confirmation"
    is Lock.DeviceCredential -> "your screen lock"
    is Lock.Challenge -> when (lock.challenge) {
        ChallengeKind.TYPING -> "a passage typed out in full"
        ChallengeKind.MATH -> "a few arithmetic problems"
    }
    is Lock.PeerRelease -> "another of your devices to agree"
    is Lock.Token -> "the token you set aside"
    is Lock.RestartRequired -> "a restart of this device"
}

/** Where a running session came from, so nothing appears to have started by itself. */
fun describeSource(source: SessionSource): String = when (source) {
    is SessionSource.Manual -> "started by you"
    is SessionSource.Weekly -> "from the ${source.schedule} schedule"
    is SessionSource.Calendar -> "from the calendar: ${source.event}"
}

fun describeSource(source: ActivationSource): String = when (source) {
    is ActivationSource.Weekly -> source.schedule
    is ActivationSource.Calendar -> source.event
}
