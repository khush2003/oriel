package app.oriel.data

import app.oriel.core.Topic
import app.oriel.core.YouTube
import app.oriel.core.model.Chip
import app.oriel.core.model.Feed
import app.oriel.core.model.Item
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import app.oriel.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * A home feed made on the device, for when YouTube won't make one.
 *
 * Signed out (or with watch history paused) YouTube's own home is just "Your
 * YouTube history is off". This builds the equivalent from what the app knows
 * locally: videos to resume, the latest uploads of subscribed channels, what
 * YouTube recommends next to recently watched videos, and topic charts. No
 * account involved, nothing leaves the device but ordinary page requests.
 */
class HomeComposer(private val yt: YouTube, private val library: LibraryStore) {
    private val gate = Semaphore(12)

    /** [fresh] fetches channels and topics again rather than reusing what's in memory. */
    suspend fun compose(fresh: Boolean = false): Feed = withContext(Dispatchers.IO) {
        // History and subscriptions are the seeds; composing from a library not yet read gives a bare home.
        library.awaitLoaded()
        val state = library.state.value
        coroutineScope {
            val recent = state.history.map { it.video }.filterNot { it.isShort }.distinctBy { it.id }.take(SEEDS)
            val related = recent.map { seed -> async { limited { yt.watch(seed.id).related.filterIsInstance<Video>() } } }
            val uploads = state.subscriptions.take(CHANNELS).map { ch -> async { limited { yt.channel(ch.id, VIDEOS_TAB, fresh).feed.items.filterIsInstance<Video>().take(4) } } }
            val topics = HOME_TOPICS.map { t -> async { limited { yt.topic(t, fresh) } } }

            val watched = state.history.take(50).mapTo(HashSet()) { it.video.id }
            val uploadLists = uploads.awaitAll()
            val relatedLists = related.awaitAll()
            val topicResults = topics.awaitAll()
            val sources = uploadLists + relatedLists + topicResults
            // Nothing came back at all: an error the feed can show and retry, not a bare home.
            if (sources.isNotEmpty() && sources.all { it == null }) throw IOException("None of the home's sources loaded")
            val fromSubs = newestFirst(uploadLists.filterNotNull())
            val recommended = interleave(relatedLists.filterNotNull()).filterNot { it.id in watched || it.isShort }
            val topicFeeds = topicResults.filterNotNull()
            val topicVideos = interleave(topicFeeds.map { f -> f.items.filterIsInstance<Video>().filterNot { it.isShort } })
            val shorts = topicFeeds.flatMap { f -> f.items.filterIsInstance<Video>().filter { it.isShort } }.distinctBy { it.id }

            val main = (interleave(listOf(fromSubs.take(12), recommended)) + topicVideos).distinctBy { it.id }.filterNot { it.id in watched }
            buildFeed(state, main, shorts)
        }
    }

    private fun buildFeed(state: LibraryState, main: List<Video>, shorts: List<Video>): Feed {
        val shelves = ArrayList<Shelf>()
        val resume = state.history.map { it.video }.filter { state.positions[it.id] != null && !it.isShort }.take(12).map { v ->
            val duration = (v.durationSec ?: 0) * 1000L
            v.copy(progress = if (duration > 0) (state.positions[v.id]!!.toFloat() / duration).coerceIn(0f, 1f) else null)
        }
        if (resume.isNotEmpty()) shelves += Shelf(title = "Continue watching", items = resume, kind = ShelfKind.CAROUSEL)
        shelves += Shelf(items = main.take(4), kind = ShelfKind.FEED)
        if (shorts.isNotEmpty()) shelves += Shelf(title = "Shorts", items = shorts.take(12), kind = ShelfKind.SHORTS)
        shelves += Shelf(items = main.drop(4), kind = ShelfKind.FEED)
        return Feed(shelves = shelves.filter { it.items.isNotEmpty() }, chips = chips())
    }

    /** One source, given [REQUEST_TIMEOUT_MS] once it's let through: a slow or failed one is left out rather than holding up the home. */
    private suspend fun <T> limited(block: suspend () -> T): T? = gate.withPermit {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Channels' uploads merged newest first. Upload ages are only readable in English ("3 days
     * ago"); in any other language the channels' own lists, each already newest first, take turns.
     */
    private fun newestFirst(perChannel: List<List<Video>>): List<Video> {
        val all = perChannel.flatten()
        val ages = all.map { Format.ageSeconds(it.publishedText) }
        return if (ages.any { it != null }) all.zip(ages).sortedBy { it.second ?: Long.MAX_VALUE }.map { it.first } else interleave(perChannel)
    }

    /** Round-robin, so no one source dominates the top of the feed. */
    private fun <T : Item> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
        return out
    }

    companion object {
        private const val SEEDS = 6
        private const val CHANNELS = 16
        private const val REQUEST_TIMEOUT_MS = 6_000L
        /** The Videos tab of a channel, newest first. */
        const val VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"
        private val HOME_TOPICS = listOf(Topic.MUSIC, Topic.GAMING, Topic.NEWS, Topic.SPORTS)

        const val TOPIC_PREFIX = "topic:"

        /** "All" plus YouTube's topic destinations, as the home chips. */
        fun chips(selected: String? = null): List<Chip> =
            listOf(Chip("All", selected = selected == null)) +
                Topic.entries.map { Chip(it.title, token = TOPIC_PREFIX + it.name, selected = selected == it.name) }

        fun topicOf(chip: Chip): Topic? = chip.token?.takeIf { it.startsWith(TOPIC_PREFIX) }?.removePrefix(TOPIC_PREFIX)?.let { n -> Topic.entries.firstOrNull { it.name == n } }
    }
}
