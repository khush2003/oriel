package app.oriel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The values here are YouTube's own single-filter params, as its unfiltered search page lists them. */
class SearchParamsTest {
    private val thisWeek = "EgIIAw=="
    private val videos = "EgIQAQ=="
    private val over20 = "EgIYAg=="
    private val hd = "EgIgAQ=="
    private val fourK = "EgJwAQ=="
    private val popularity = "CAM="

    @Test fun slots() {
        assertEquals("date", SearchParams.slot(thisWeek))
        assertEquals("type", SearchParams.slot(videos))
        assertEquals("duration", SearchParams.slot(over20))
        assertEquals("sort", SearchParams.slot(popularity))
        assertEquals("feature:4", SearchParams.slot(hd))
        assertEquals("feature:14", SearchParams.slot(fourK))
    }

    /** YouTube's own params for "This week" then "Videos" are EgQIAxAB: the same as the two merged. */
    @Test fun combinesAsYouTubeDoes() {
        val merged = java.util.Base64.getDecoder().decode(SearchParams.combine(listOf(thisWeek, videos)))
        // Concatenated messages parse as one merged message: filters { 1: 3 } + filters { 2: 1 } = filters { 1: 3, 2: 1 }.
        assertEquals(listOf(0x12, 0x02, 0x08, 0x03, 0x12, 0x02, 0x10, 0x01), merged.map { it.toInt() and 0xFF })
        assertNull(SearchParams.combine(emptyList()))
    }
}
