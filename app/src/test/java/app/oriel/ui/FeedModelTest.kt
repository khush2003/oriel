package app.oriel.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.OrielApp
import app.oriel.TestApp
import app.oriel.core.model.Feed
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import app.oriel.ui.screens.FeedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Feeds keep themselves current: a shown feed is fetched again once it's
 * old, sooner after something was watched, never while it's being read
 * partway down, and a failed page or refresh says so instead of ending
 * quietly.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class FeedModelTest {
    private val graph get() = ApplicationProvider.getApplicationContext<OrielApp>().graph

    // Robolectric freezes the wall clock in app code; this one moves when told.
    private var now = 1_000_000_000L
    private var loads = 0
    private var failLoads = false
    private var failMore = false

    @Before fun clean() = graph.library.clearHistory()

    private fun model(maxAge: Long = 10 * MINUTE) = FeedModel(
        graph, cacheKey = null,
        load = { loads++; if (failLoads) throw java.net.UnknownHostException("offline test"); feed("v$loads", continuation = "more") },
        more = { if (failMore) throw java.io.IOException("dropped"); feed("next") },
        maxAgeMs = maxAge,
        clock = { now },
    )

    private fun feed(id: String, continuation: String? = null) =
        Feed(shelves = listOf(Shelf(items = listOf(Video(id, "Video $id")), kind = ShelfKind.FEED)), continuation = continuation)

    /** Runs the main looper until [done], while the model's IO work finishes on its own threads. */
    private fun settle(done: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(5)
        }
        error("never settled")
    }

    @Test fun aFreshFeedIsLeftAlone() {
        val m = model()
        settle { m.state.value.feed != null && !m.state.value.loading }
        now += 5 * MINUTE
        m.refreshIfStale()
        settle { true }
        assertEquals(1, loads)
    }

    @Test fun anOldFeedIsFetchedAgainQuietly() {
        val m = model()
        settle { m.state.value.feed != null }
        now += 11 * MINUTE
        m.refreshIfStale()
        assertFalse("no spinner for a refresh nobody asked for", m.state.value.refreshing)
        settle { loads == 2 && m.state.value.feed?.items?.firstOrNull()?.let { (it as Video).id } == "v2" }
    }

    @Test fun watchingSomethingMakesHomeDueSooner() {
        val m = model()
        settle { m.state.value.feed != null }
        now += 2 * MINUTE
        m.refreshIfStale()
        settle { true }
        assertEquals("two minutes old, nothing watched", 1, loads)
        // A viewing recorded after the feed loaded. The library stamps it with its own clock, which
        // Robolectric holds still, so the feed's clock is put just before that stamp.
        graph.library.addHistory(Video("watched", "Watched"))
        val watchedAt = graph.library.state.value.history.first().watchedAt
        val m2 = FeedModel(graph, null, load = { loads++; feed("w$loads") }, clock = { now })
        now = watchedAt - 1
        settle { m2.state.value.feed != null }
        now = watchedAt + 2 * MINUTE
        val before = loads
        m2.refreshIfStale()
        settle { loads == before + 1 }
    }

    @Test fun aListReadPartwayDownIsNeverChangedUnderTheReader() {
        val m = model()
        settle { m.state.value.feed != null }
        now += 15 * MINUTE
        m.refreshIfStale(atTop = false)
        settle { true }
        assertEquals(1, loads)
        now += 24 * 60 * MINUTE
        m.refreshIfStale(atTop = false)
        settle { true }
        assertEquals(1, loads)
        // Back at the top, the same old feed is due.
        m.refreshIfStale(atTop = true)
        settle { loads == 2 }
    }

    @Test fun aFailedRefreshKeepsTheFeedAndSaysHowOld() {
        val m = model()
        settle { m.state.value.feed != null }
        val loadedAt = m.state.value.updatedAt
        failLoads = true
        now += 11 * MINUTE
        m.refreshIfStale()
        settle { m.state.value.error != null }
        assertNotNull("the old copy stays", m.state.value.feed)
        assertEquals(loadedAt, m.state.value.updatedAt)
        assertTrue(m.state.value.error!!.startsWith("You're offline"))
    }

    @Test fun aFeedThatNeverLoadedIsTriedAgain() {
        failLoads = true
        val m = model()
        settle { m.state.value.error != null }
        failLoads = false
        m.refreshIfStale()
        settle { m.state.value.feed != null }
        assertNull(m.state.value.error)
    }

    @Test fun aFailedNextPageCanBeAskedForAgain() {
        val m = model()
        settle { m.state.value.feed != null }
        failMore = true
        m.loadMore()
        settle { m.state.value.moreFailed }
        assertEquals("the token is kept", "more", m.state.value.feed?.continuation)
        failMore = false
        // The list's near-end ticks don't retry on their own: that would ask forever while offline.
        m.loadMore()
        settle { true }
        assertTrue("still waiting for Try again", m.state.value.moreFailed)
        m.retryMore()
        settle { !m.state.value.loadingMore && m.state.value.feed?.items?.size == 2 }
        assertFalse(m.state.value.moreFailed)
    }

    /** A chip whose page fails shows the full-page error, not the last page under the new chip. */
    @Test fun aChipThatFailsLeavesNoFeedUnderIt() {
        val m = model()
        settle { m.state.value.feed != null }
        m.reload(false, replace = { throw java.io.IOException("dropped") }, key = "Music")
        settle { m.state.value.error != null }
        assertNull(m.state.value.feed)
        assertFalse(m.state.value.loading)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
