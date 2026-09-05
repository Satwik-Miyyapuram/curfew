package dev.curfew.app.ui

import dev.curfew.app.data.Downtime
import dev.curfew.policy.ActivationSource
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import dev.curfew.policy.PassRefusal
import dev.curfew.policy.SessionSource
import java.text.DateFormat
import java.util.Calendar
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
 * Which day an instant falls on, said the way a person would: "Today", "Tomorrow", else the date.
 *
 * The preview timeline groups by this, so a row two days out is never mistaken for one this
 * evening. Both instants go through the device's own calendar, so a day boundary is the user's
 * midnight rather than UTC's.
 */
fun dayLabel(epochSeconds: Long, now: Long): String {
    val day = Calendar.getInstance().apply { time = Date(epochSeconds * 1000) }
    val today = Calendar.getInstance().apply { time = Date(now * 1000) }
    val sameYear = day.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val delta = if (sameYear) {
        day.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
    } else {
        // Across a new year, only "tomorrow" is worth the arithmetic; anything further reads as a
        // date anyway.
        if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR) + 1 &&
            day.get(Calendar.DAY_OF_YEAR) == 1 &&
            today.get(Calendar.DAY_OF_YEAR) == today.getActualMaximum(Calendar.DAY_OF_YEAR)
        ) {
            1
        } else {
            99
        }
    }
    return when (delta) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))
    }
}

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

/**
 * Why no emergency pass is available, said as a time rather than a refusal.
 *
 * This is read at the worst moment — someone locked out of something they need — so it never says
 * only "no". Every answer but the disabled one carries a when, because a wait a person can plan
 * around is a very different thing from a door that will not open.
 */
fun describePassRefusal(refusal: PassRefusal, now: Long): String = when (refusal) {
    is PassRefusal.Disabled ->
        "Emergency passes are switched off in your rules. Turning them on now will not unlock " +
            "this session — they only count from when you enable them."
    is PassRefusal.QuotaSpent ->
        "No emergency passes left. The next one comes back ${relative(refusal.nextAt, now)}."
    is PassRefusal.CoolingDown ->
        "You used a pass recently. The next one can be spent ${relative(refusal.until, now)}."
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

/**
 * A gap, said plainly enough that a user can decide whether it mattered.
 *
 * Rules were still in force for the whole period as far as the core is concerned — a session's end
 * is an instant, not a countdown that pauses — but nothing was watching the screen, so this says
 * what was not happening rather than implying the session was void.
 */
fun describeDowntime(downtime: Downtime): String = if (downtime.backwards) {
    "The device's clock jumped back ${duration(downtime.seconds.toInt())}, to ${dateTime(downtime.to)}. " +
        "Sessions still end at the times they were given, so nothing was shortened."
} else {
    "Nothing was blocked between ${dateTime(downtime.from)} and ${dateTime(downtime.to)} — " +
        "${duration(downtime.seconds.toInt())}. Curfew was stopped, by a restart or by the system."
}

/**
 * A refused clock change, in plain words.
 *
 * The number matters: it says exactly how much time the device claimed and did not get, which is
 * what tells an honest user their clock is genuinely wrong rather than merely disbelieved.
 */
fun describeClockTamper(tamper: dev.curfew.app.data.ClockTamper): String = if (tamper.forward) {
    "The device's clock jumped forward ${duration(tamper.seconds.toInt())}, which is more time than " +
        "has actually passed. Curfew is still counting from ${dateTime(tamper.at)}, so locks are " +
        "unchanged. If the clock is genuinely wrong, fixing it will not shorten a running lock."
} else {
    "The device's clock jumped back ${duration(tamper.seconds.toInt())}. Curfew ignored it and is " +
        "still counting from ${dateTime(tamper.at)}: a lock cannot be made longer or shorter by " +
        "moving the clock."
}
