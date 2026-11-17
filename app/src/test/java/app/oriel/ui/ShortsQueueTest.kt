package app.oriel.ui

import app.oriel.ui.navigation.shortsAfter
import app.oriel.ui.screens.ShortsModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** A short opened from a list carries on through that list. All ids are invented. */
class ShortsQueueTest {
    @Test fun theShortsAfterTheTappedOneFollowInOrder() {
        assertEquals(listOf("c", "d"), shortsAfter("b", listOf("a", "b", "c", "d")))
    }

    @Test fun aShortNotInTheListHasNoQueue() {
        assertEquals(emptyList<String>(), shortsAfter("x", listOf("a", "b")))
    }

    @Test fun theQueueIsCapped() {
        val feed = (0..200).map { "s$it" }
        assertEquals(10, shortsAfter("s0", feed, limit = 10).size)
    }

    @Test fun theModelStartsWithTheTappedShortThenTheQueue() {
        assertEquals(listOf("b", "c", "d"), ShortsModel.seed("b", listOf("c", "b", "d")))
        assertEquals(listOf("b"), ShortsModel.seed("b", emptyList()))
        assertEquals(emptyList<String>(), ShortsModel.seed(null, emptyList()))
    }
}
