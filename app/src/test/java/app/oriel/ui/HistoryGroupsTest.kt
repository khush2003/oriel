package app.oriel.ui

import app.oriel.core.model.Video
import app.oriel.data.WatchEntry
import app.oriel.ui.screens.HistoryModel
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HistoryGroupsTest {
    private val zone = TimeZone.getDefault()

    @Before fun newYork() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

    @After fun restore() = TimeZone.setDefault(zone)

    private fun at(month: Int, day: Int, hour: Int) = Calendar.getInstance().apply { clear(); set(2026, month - 1, day, hour, 0) }.timeInMillis

    /** New York's clocks went forward on 8 March 2026, a 23-hour day. */
    @Test fun daysAreCountedAcrossAClockChange() {
        val history = listOf(WatchEntry(Video("a", "A"), at(3, 8, 20)), WatchEntry(Video("b", "B"), at(3, 7, 20)))
        assertEquals(listOf("Yesterday", "This week"), HistoryModel.group(history, "", now = at(3, 9, 10)).map { it.first })
    }
}
