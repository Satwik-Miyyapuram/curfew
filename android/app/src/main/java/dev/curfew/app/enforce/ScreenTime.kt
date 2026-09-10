package dev.curfew.app.enforce

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * How long the phone was actually used, per day, according to the system itself.
 *
 * This is not Curfew's own accounting. Curfew can only say what it blocked; the question a person
 * actually asks — "is my screen time going down?" — needs a number from before Curfew existed, and
 * the only place that number lives is Android's own usage statistics, which keep daily totals for
 * weeks before the app was ever installed.
 *
 * Nothing here leaves the device, and nothing here is kept per app: the totals are one number a
 * day, which is the smallest thing that can answer the question.
 */
class ScreenTime(private val context: Context) {

    /**
     * Seconds of foreground use per day, keyed by days-since-epoch, for the last [days] whole days.
     *
     * Today is deliberately excluded by the caller where a partial day would distort an average:
     * half a day always looks like an improvement.
     */
    fun dailyTotals(days: Int, now: Long): Map<Long, Long> {
        if (!UsageStatsPoller.hasPermission(context)) return emptyMap()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val start = startOfDay(now) - days.toLong() * DAY_SECONDS
        val stats = runCatching {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start * 1000, now * 1000)
        }.getOrNull() ?: return emptyMap()

        // Buckets can overlap and repeat: the platform returns one row per app per bucket, and the
        // same day can come back more than once. Summing per day is the only reading that survives
        // that, and Curfew's own foreground time is left out so the app cannot flatter itself.
        val perDay = HashMap<Long, Long>()
        for (row in stats) {
            if (row.packageName == context.packageName) continue
            val day = (row.firstTimeStamp / 1000) / DAY_SECONDS
            val seconds = row.totalTimeInForeground / 1000
            if (seconds <= 0) continue
            perDay[day] = (perDay[day] ?: 0) + seconds
        }
        return perDay
    }

    /** The mean of whole days in [totals], ignoring days the system has nothing for. */
    fun average(totals: Map<Long, Long>): Long {
        val used = totals.values.filter { it > 0 }
        if (used.isEmpty()) return 0
        return used.sum() / used.size
    }

    private fun startOfDay(now: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now * 1000
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / 1000
    }

    companion object {
        const val DAY_SECONDS = 24L * 60 * 60

        /** Two weeks: long enough that one unusual day cannot set the baseline on its own. */
        const val BASELINE_DAYS = 14

        /** What "now" means in the comparison — the last week of whole days. */
        const val RECENT_DAYS = 7
    }
}
