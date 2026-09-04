package dev.curfew.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.curfew.policy.CalendarEvent

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
    fun events(from: Long, to: Long): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath((from * 1000).toString())
            .appendPath((to * 1000).toString())
            .build()

        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val begin = cursor.getLong(4) / 1000
                events += CalendarEvent(
                    // The instance's own start is part of the id: a repeating event is many events
                    // to a schedule, and giving them one id would make the second occurrence look
                    // like the first one still running.
                    id = "${cursor.getLong(0)}:$begin",
                    title = cursor.getString(1).orEmpty(),
                    calendar = cursor.getString(2).orEmpty(),
                    location = cursor.getString(3).orEmpty(),
                    start = begin,
                    end = cursor.getLong(5) / 1000,
                    allDay = cursor.getInt(6) == 1,
                    busy = cursor.getInt(7) == CalendarContract.Instances.AVAILABILITY_BUSY,
                )
            }
        }
        return events
    }

    companion object {
        /** How far either side of now to look. Long enough for any rule's padding, and no longer. */
        const val WINDOW_SECONDS = 24L * 60 * 60
    }
}
