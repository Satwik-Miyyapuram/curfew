package dev.curfew.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.curfew.policy.CalendarEvent
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The device's calendar, as the policy core wants to see it.
 *
 * Curfew reads calendars and never writes them. The window read is deliberately narrow — a day
 * either side of now — because a calendar rule can only start a session that is happening soon, and
 * reading a year of someone's meetings to answer a question about the next hour would be exactly
 * the kind of over-collection the project refuses elsewhere.
 */
class CalendarReader(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Events overlapping [from]..[to], or an empty list when the permission was never granted. */
    fun events(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath((from * 1000).toString())
            .appendPath((to * 1000).toString())
            .build()

        return context.contentResolver.query(uri, PROJECTION, null, null, null)
            ?.use { read(it, zone) }
            .orEmpty()
    }

    companion object {
        /** How far either side of now to look. Long enough for any rule's padding, and no longer. */
        const val WINDOW_SECONDS = 24L * 60 * 60

        /**
         * How far ahead the *browsing* read goes, when the user is looking at the list themselves.
         *
         * Enforcement reads a day, because a rule can only start a session that is happening soon.
         * Picking a meeting to block is the opposite question: someone opening the calendar wants
         * to find the exam in three weeks, and a list that stops at Sunday looks broken rather
         * than careful. Eight weeks is far enough to hold a term's fixed commitments and short
         * enough that this is still not a read of someone's whole diary.
         */
        const val BROWSE_SECONDS = 56L * 24 * 60 * 60

        val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
        )

        /**
         * Turn a cursor over [PROJECTION] into events.
         *
         * Kept separate from the query so the interesting half — what the provider's numbers mean —
         * can be tested without standing up a content provider.
         */
        fun read(cursor: Cursor, zone: ZoneId): List<CalendarEvent> {
            val events = mutableListOf<CalendarEvent>()
            while (cursor.moveToNext()) {
                val allDay = cursor.getInt(6) == 1
                val begin = localised(cursor.getLong(4), allDay, zone)
                val end = localised(cursor.getLong(5), allDay, zone)
                events += CalendarEvent(
                    // The instance's own start is part of the id: a repeating event is many events
                    // to a schedule, and giving them one id would make the second occurrence look
                    // like the first one still running.
                    id = "${cursor.getLong(0)}:$begin",
                    title = cursor.getString(1).orEmpty(),
                    calendar = cursor.getString(2).orEmpty(),
                    location = cursor.getString(3).orEmpty(),
                    start = begin,
                    end = maxOf(begin, end),
                    allDay = allDay,
                    busy = cursor.getInt(7) == CalendarContract.Instances.AVAILABILITY_BUSY,
                )
            }
            return events
        }

        /**
         * Seconds for one of the provider's millisecond timestamps.
         *
         * An all-day row is stored at midnight UTC on the day it covers, whatever timezone the user
         * is in: it is a date wearing a timestamp's clothes. Taken at face value, a holiday in
         * London in summer would start blocking at 01:00 and stop at 01:00 the next day — an hour
         * of the wrong day at each end. So an all-day row is read back as a date and re-anchored to
         * midnight where the user actually is.
         */
        fun localised(millis: Long, allDay: Boolean, zone: ZoneId): Long = if (allDay) {
            Instant.ofEpochMilli(millis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(zone)
                .toEpochSecond()
        } else {
            Math.floorDiv(millis, 1000L)
        }
    }
}
