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

    @Test fun shortsWatchedBeforeAreLeftOutOfABatch() {
        assertEquals(listOf("c"), ShortsModel.unseen(listed = listOf("a"), batch = listOf("a", "b", "c"), seen = setOf("b"), allowSeen = false))
    }

    @Test fun aBatchOfNothingButWatchedShortsAddsNothing() {
        assertEquals(emptyList<String>(), ShortsModel.unseen(listed = emptyList(), batch = listOf("a", "b"), seen = setOf("a", "b"), allowSeen = false))
    }

    @Test fun pastTheLimitWatchedShortsAreBetterThanNone() {
        assertEquals(listOf("b"), ShortsModel.unseen(listed = listOf("a"), batch = listOf("a", "b", "b"), seen = setOf("a", "b"), allowSeen = true))
        // Still only the unseen ones when the batch has any.
        assertEquals(listOf("c"), ShortsModel.unseen(listed = emptyList(), batch = listOf("b", "c"), seen = setOf("b"), allowSeen = true))
    }
}
