package app.oriel.data

import app.oriel.core.model.Feed
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Watched videos are dropped from any recommendation feed. All data is invented. */
class WatchedFilterTest {
    private fun feed(vararg shelves: Shelf) = Feed(shelves = shelves.toList())
    private fun shelf(vararg ids: String, title: String? = null, kind: ShelfKind = ShelfKind.FEED) =
        Shelf(title = title, items = ids.map { Video(it, "Video $it") }, kind = kind)

    @Test fun watchedVideosAreDroppedFromEveryShelf() {
        val out = HomeComposer.dropWatched(feed(shelf("a", "b"), shelf("c", "d", kind = ShelfKind.SHORTS)), setOf("b", "c"))
        assertEquals(listOf("a"), out.shelves[0].items.map { it.id })
        assertEquals(listOf("d"), out.shelves[1].items.map { it.id })
    }

    @Test fun aShelfLeftEmptyGoes() {
        val out = HomeComposer.dropWatched(feed(shelf("a"), shelf("b")), setOf("a"))
        assertEquals(1, out.shelves.size)
    }

    @Test fun anOldWatchIsStillDropped() {
        val watched = (1..500).mapTo(HashSet()) { "old$it" }
        val out = HomeComposer.dropWatched(feed(shelf("old1", "new")), watched)
        assertEquals(listOf("new"), out.items.map { it.id })
    }

    @Test fun aVideoWithAProgressBarIsKept() {
        val resuming = Video("a", "A", progress = 0.4f)
        val out = HomeComposer.dropWatched(feed(Shelf(items = listOf(resuming, Video("b", "B")), kind = ShelfKind.FEED)), setOf("a", "b"))
        assertEquals(listOf("a"), out.items.map { it.id })
    }

    @Test fun aVideoWatchedToTheEndGoesWhereverItWasWatched() {
        val finishedHere = Video("a", "A", progress = 1f)
        val finishedElsewhere = Video("b", "B", progress = 0.95f)
        val out = HomeComposer.dropWatched(feed(Shelf(items = listOf(finishedHere, finishedElsewhere, Video("c", "C")), kind = ShelfKind.FEED)), setOf("a"))
        assertEquals(listOf("c"), out.items.map { it.id })
    }

    @Test fun aVideoListedInTwoShelvesShowsInTheFirst() {
        val out = HomeComposer.dropRepeats(feed(shelf("a", "b"), shelf("s", kind = ShelfKind.SHORTS), shelf("b", "c", "a")))
        assertEquals(listOf("a", "b", "s", "c"), out.items.map { it.id })
    }

    @Test fun aShelfOfNothingButRepeatsGoes() {
        assertEquals(1, HomeComposer.dropRepeats(feed(shelf("a"), shelf("a"))).shelves.size)
    }

    @Test fun aFeedWithoutRepeatsIsLeftAsItIs() {
        val f = feed(shelf("a", "b"), shelf("c"))
        assertSame(f, HomeComposer.dropRepeats(f))
    }

    @Test fun theContinueWatchingShelfIsNeverFiltered() {
        val f = feed(shelf("a", title = HomeComposer.RESUME_TITLE, kind = ShelfKind.CAROUSEL))
        assertEquals(listOf("a"), HomeComposer.dropWatched(f, setOf("a")).items.map { it.id })
    }

    @Test fun nothingWatchedLeavesTheFeedAsItIs() {
        val f = feed(shelf("a"))
        assertSame(f, HomeComposer.dropWatched(f, setOf("zzz")))
    }
}
