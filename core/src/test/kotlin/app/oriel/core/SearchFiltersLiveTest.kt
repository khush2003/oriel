package app.oriel.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Live (-Plive=true): filters come decoded, and two combined here are both applied by YouTube. */
class SearchFiltersLiveTest {
    @Test fun combinedFiltersApply() = runBlocking {
        if (System.getProperty("oriel.live") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val plain = yt.search("lofi").filters
        val week = plain.first { it.label == "This week" }
        val videos = plain.first { it.label == "Videos" }
        println("FILTERS plain week=${week.params} videos=${videos.params}")
        assertTrue(week.params!!.endsWith("=") && '%' !in week.params!!, "params are decoded")
        val both = yt.search("lofi", SearchParams.combine(listOf(week.params!!, videos.params!!)))
        val on = both.filters.filter { it.selected }.map { it.label }
        println("FILTERS combined selected=$on disabled=${both.filters.filter { it.disabled }.map { it.label }} results=${both.feed.items.size}")
        assertTrue("This week" in on && "Videos" in on, "both filters applied: $on")
    }
}
