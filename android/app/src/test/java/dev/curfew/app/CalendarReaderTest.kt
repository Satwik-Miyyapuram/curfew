package dev.curfew.app

import android.database.MatrixCursor
import android.provider.CalendarContract
import dev.curfew.app.data.CalendarReader
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Android's calendar provider hands back, and what it means.
 *
 * The dates are named rather than relative because the bug this guards against is invisible for
 * half the year: an all-day row is stored at midnight UTC, so in British Summer Time a holiday read
 * at face value starts blocking at 01:00 and stops at 01:00 the next day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarReaderTest {

    private val london = ZoneId.of("Europe/London")

    private fun cursor(vararg rows: Array<Any>) =
        MatrixCursor(CalendarReader.PROJECTION.map { it }.toTypedArray()).apply {
            rows.forEach { addRow(it) }
        }

    private fun row(
        id: Long = 1,
        title: String = "Meeting",
        calendar: String = "Work",
        location: String = "",
        begin: Long,
        end: Long,
        allDay: Int = 0,
        availability: Int = CalendarContract.Instances.AVAILABILITY_BUSY,
    ): Array<Any> = arrayOf(id, title, calendar, location, begin, end, allDay, availability)

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneId.of("UTC")).toEpochSecond() * 1000

    private fun localSeconds(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, london).toEpochSecond()

    @Test
    fun `a timed event keeps the instant the provider gave it`() {
        val events = CalendarReader.read(
            cursor(row(begin = utcMillis(2026, 9, 4, 9), end = utcMillis(2026, 9, 4, 10))),
            london,
        )

        assertEquals(1, events.size)
        assertEquals(utcMillis(2026, 9, 4, 9) / 1000, events[0].start)
        assertEquals(utcMillis(2026, 9, 4, 10) / 1000, events[0].end)
        assertFalse(events[0].allDay)
        assertTrue(events[0].busy)
    }

    @Test
    fun `an all-day event in summer starts when the day starts here, not an hour into it`() {
        // 2026-09-04 is BST. The provider stores 00:00 UTC; the day locally begins an hour earlier.
        val events = CalendarReader.read(
            cursor(
                row(
                    title = "Conference",
                    begin = utcMillis(2026, 9, 4),
                    end = utcMillis(2026, 9, 5),
                    allDay = 1,
                )
            ),
            london,
        )

        assertTrue(events[0].allDay)
        assertEquals(localSeconds(2026, 9, 4), events[0].start)
        assertEquals(localSeconds(2026, 9, 5), events[0].end)
    }

    @Test
    fun `an all-day event in winter is unchanged, because London is UTC then`() {
        val events = CalendarReader.read(
            cursor(
                row(begin = utcMillis(2026, 1, 15), end = utcMillis(2026, 1, 16), allDay = 1)
            ),
            london,
        )

        assertEquals(utcMillis(2026, 1, 15) / 1000, events[0].start)
        assertEquals(localSeconds(2026, 1, 15), events[0].start)
    }

    @Test
    fun `an all-day event that spans the clock change still covers whole local days`() {
        // The UK leaves summer time on 2026-10-25, so this range is 3 days and one extra hour.
        val events = CalendarReader.read(
            cursor(
                row(begin = utcMillis(2026, 10, 24), end = utcMillis(2026, 10, 27), allDay = 1)
            ),
            london,
        )

        assertEquals(localSeconds(2026, 10, 24), events[0].start)
        assertEquals(localSeconds(2026, 10, 27), events[0].end)
        assertEquals(3 * 24 * 3600 + 3600, events[0].end - events[0].start)
    }

    @Test
    fun `an all-day event is read for the user's zone, not the machine's`() {
        val tokyo = CalendarReader.read(
            cursor(row(begin = utcMillis(2026, 9, 4), end = utcMillis(2026, 9, 5), allDay = 1)),
            ZoneId.of("Asia/Tokyo"),
        )

        assertEquals(
            ZonedDateTime.of(2026, 9, 4, 0, 0, 0, 0, ZoneId.of("Asia/Tokyo")).toEpochSecond(),
            tokyo[0].start,
        )
    }

    @Test
    fun `each occurrence of a repeating event gets its own id`() {
        val events = CalendarReader.read(
            cursor(
                row(id = 7, begin = utcMillis(2026, 9, 4, 9), end = utcMillis(2026, 9, 4, 10)),
                row(id = 7, begin = utcMillis(2026, 9, 5, 9), end = utcMillis(2026, 9, 5, 10)),
            ),
            london,
        )

        assertEquals(2, events.map { it.id }.toSet().size)
    }

    @Test
    fun `an event marked free is not busy`() {
        val events = CalendarReader.read(
            cursor(
                row(
                    begin = utcMillis(2026, 9, 4, 9),
                    end = utcMillis(2026, 9, 4, 10),
                    availability = CalendarContract.Instances.AVAILABILITY_FREE,
                )
            ),
            london,
        )

        assertFalse(events[0].busy)
    }

    @Test
    fun `an event whose end precedes its start never lasts negative time`() {
        // A block of negative length would be a lock with no exit rather than no lock at all.
        val events = CalendarReader.read(
            cursor(row(begin = utcMillis(2026, 9, 4, 10), end = utcMillis(2026, 9, 4, 9))),
            london,
        )

        assertTrue(events[0].end >= events[0].start)
    }

    @Test
    fun `a row with no title or calendar name still produces an event`() {
        // Providers hand back nulls for these more often than one would like, and a rule that
        // matches on "any busy event" must not be defeated by a missing string.
        val events = CalendarReader.read(
            MatrixCursor(CalendarReader.PROJECTION).apply {
                addRow(
                    arrayOf(
                        1L, null, null, null,
                        utcMillis(2026, 9, 4, 9), utcMillis(2026, 9, 4, 10), 0,
                        CalendarContract.Instances.AVAILABILITY_BUSY,
                    )
                )
            },
            london,
        )

        assertEquals(1, events.size)
        assertEquals("", events[0].title)
        assertEquals("", events[0].calendar)
    }

    @Test
    fun `an empty cursor is not an error`() {
        assertTrue(CalendarReader.read(cursor(), london).isEmpty())
    }
}
