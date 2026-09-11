package dev.curfew.app.ui

import dev.curfew.app.data.Downtime
import dev.curfew.policy.ActivationSource
import dev.curfew.policy.ChallengeKind
import dev.curfew.policy.Lock
import dev.curfew.policy.PassRefusal
import dev.curfew.policy.CalendarSchedule
import dev.curfew.policy.SessionSource
import dev.curfew.policy.WeeklySchedule
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

/**
 * How much time is left, said as an amount of time rather than as a clock face.
 *
 * "1:12" for an hour and over, "14m" under it, "14s" under a minute. [withSeconds] adds the seconds
 * to the middle case, for the one screen where the number visibly ticks.
 *
 * **One rule, one place.** There were two of these, one on the Now screen and one on the block screen,
 * and they disagreed below an hour: the same fourteen minutes remaining read "14m" on Now and
 * "14:00" while an app was blocked. That is not a difference of precision — "14:00" reads as a
 * wall-clock time, so the block screen appeared to say the session ends at two in the afternoon. Two
 * implementations of one idea will always drift; the fix is that there is now one.
 *
 * Above an hour both already agreed on "1:12", and that is kept: with "left" beside it, an H:MM
 * reading is a duration everywhere a person meets one, and it is shorter than "1 hr 12 min" on a
 * screen with a large ticking number on it.
 */
fun countdown(secondsLeft: Long, withSeconds: Boolean = false): String {
    val left = secondsLeft.coerceAtLeast(0)
    val hours = left / 3600
    val minutes = (left % 3600) / 60
    return when {
        hours > 0 -> "$hours:%02d".format(minutes)
        minutes > 0 -> if (withSeconds) "${minutes}m ${"%02d".format(left % 60)}s" else "${minutes}m"
        else -> "${left}s"
    }
}

/**
 * A time of day the user typed and will type again, so it is not localised on purpose.
 *
 * This is the one clock in the app that deliberately ignores the device's format. The strings on the
 * schedule editor are also what gets written into the config and read back by the core, and a window
 * saved as "9:00 PM" somewhere a 12-hour clock is set would come back as an error rather than as a
 * window. Round-tripping beats prettiness where the same text is both a label and an input.
 *
 * It takes **minutes past midnight** rather than an epoch second, which is the other reason it is not
 * [clockTime]: a weekly window has no date, and a screen that formatted one would eventually show a
 * date it invented.
 */
fun clockMinute(minute: Int): String {
    val m = ((minute % (24 * 60)) + 24 * 60) % (24 * 60)
    if (m == 0) return "midnight"
    return "%02d:%02d".format(m / 60, m % 60)
}

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
    is Lock.PeerRelease -> "another of your devices to let it go"
    is Lock.Token -> "the tag you set aside (${lock.id})"
    is Lock.RestartRequired -> "this device to be restarted"
}

/**
 * How many scheduled locks name [deviceId] as the device that can release them.
 *
 * This exists for one sentence, on one confirmation: **removing a paired device can take away a lock's
 * only way out.** `Lock.PeerRelease` requires a *specific* device (`lock.rs`: *"Only a specific paired
 * device can release"*), so the exit a user configured is that device and nothing else. Removing it
 * leaves the lock unsatisfiable — the session cannot be ended by any means the user still has.
 *
 * F-14 in the interaction review, and the review is right about the substance. It is wrong about one
 * detail: it says the action's confirmation "Devices never renders", which was true when it was written
 * and is not now — `MainActivity` renders `state.message` for the whole app (entry 19), so
 * *"That device will be ignored from now on."* does appear. The unconfirmed tap and the missing
 * consequence were both still real.
 *
 * **Pure, and that is why it lives here rather than in the screen**: it takes the two lists the UI
 * already holds and returns a number, so it can be tested without an `Application`, a database or a
 * composition — none of which this host can provide.
 *
 * Sessions are deliberately **not** included. A running session's lock set was copied from a schedule
 * when it started, so counting both would report a lock twice for as long as it runs; the schedule is
 * the durable answer and the one the user can still edit.
 */
fun locksAwaitingDevice(
    weekly: List<WeeklySchedule>,
    rules: List<CalendarSchedule>,
    deviceId: String,
): Int {
    fun names(lock: Lock) = lock is Lock.PeerRelease && lock.deviceId == deviceId
    return weekly.sumOf { window -> window.locks.count(::names) } +
        rules.sumOf { rule -> rule.locks.count(::names) }
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

/**
 * Where an activation came from, in the words the user would use for it.
 *
 * [titles] maps calendar event ids to their names, and it matters more than it looks: an id here is
 * something like `5552:1788984000`, which is the calendar provider's row and instant and means
 * nothing at all to the person reading it. When the event is not in the window Curfew reads, the id
 * is still shown — a name that cannot be found is not a reason to say nothing about the source.
 */
fun describeSource(source: ActivationSource, titles: Map<String, String> = emptyMap()): String =
    when (source) {
        is ActivationSource.Weekly -> source.schedule
        is ActivationSource.Calendar -> titles[source.event] ?: source.event
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
