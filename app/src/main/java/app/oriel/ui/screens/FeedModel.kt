package app.oriel.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oriel.AppGraph
import app.oriel.core.model.Feed
import app.oriel.data.HomeComposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class FeedState(
    val feed: Feed? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** True while [feed] is the copy from disk and the network one hasn't landed. */
    val stale: Boolean = false,
    /** When [feed] was fetched, for "Updated 20 min ago" when a refresh fails. */
    val updatedAt: Long? = null,
    /** The next page failed; the list ends in a "Try again" until it's asked for again. */
    val moreFailed: Boolean = false,
    /** Successful refreshes the viewer asked for, so Home can show the new page from its beginning. */
    val refreshVersion: Long = 0,
)

/**
 * One browsable feed with stale-while-revalidate: the last copy from disk
 * paints at once, the network copy replaces it when it lands, and the
 * continuation extends it as the list nears its end.
 *
 * A feed doesn't stay as it was first loaded: [refreshIfStale], called when
 * its screen comes back into view, fetches it again once it's [maxAgeMs]
 * old, or a minute old when something was watched since, as what YouTube
 * recommends moves with what you watch.
 *
 * A copy nobody asked for (that refresh, or the network copy behind the disk
 * one) only replaces the list while the reader is at its top ([readerAt]):
 * landing a few seconds after it was asked for, it used to swap the list
 * under someone who had scrolled into it meanwhile. It waits for them to
 * come back up.
 */
class FeedModel(
    private val graph: AppGraph,
    private val cacheKey: String?,
    private val load: suspend (fresh: Boolean) -> Feed,
    private val more: suspend (token: String) -> Feed = { graph.yt.more(it) },
    private val maxAgeMs: Long = 10 * MINUTE,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Recommendation feeds: videos already watched here are dropped, whatever the server still lists
     * and whenever they were watched (the list follows the history, not a copy of it from when the
     * page loaded), and a video YouTube lists twice shows once.
     */
    private val hideWatched: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()
    private var job: Job? = null
    private var moreJob: Job? = null
    private var requestVersion = 0L
    /** What's shown when it isn't the feed itself: a chip's page. Refreshes reload that. */
    private var shownLoader: (suspend (Boolean) -> Feed)? = null
    /** Which page [shownLoader] is (a chip's title): a fresh lambda for the same chip isn't a switch. */
    private var shownKey: String? = null

    /**
     * For a feed with chips: the feed's own chips (a chip's page has none of its own) and the
     * one picked. Kept here rather than in the screen, which a tab switch disposes: Home used
     * to come back with a chip still picked and no row of chips to get back to All.
     */
    var baseChips by mutableStateOf<List<app.oriel.core.model.Chip>>(emptyList())
    var selectedChip by mutableStateOf<String?>(null)

    /** Whose feed this is (the signed-in account), so another account signing in reloads it. */
    var owner: String? = null

    /** What a feed made here was built from (the channels followed), to remake it when that changes. */
    var builtFrom: Any? = null
    /** When the shown feed came from the network; null until it has. */
    private var loadedAt: Long? = null

    /** The profile this feed is for: a model kept for another one doesn't load as the profile in use. */
    private val profileId = graph.account.active.value.id

    /** Whether the reader is at the top of the list, as the screen last said. */
    private var readerAtTop = true
    /** A copy that landed while they weren't, and when: shown once they're back at the top. */
    private var held: Pair<Feed, Long>? = null

    private fun tidy(feed: Feed): Feed =
        if (!hideWatched) feed else HomeComposer.dropRepeats(HomeComposer.dropWatched(feed, graph.library.watchedIds()))

    /** The history read from disk, before anything is filtered by it: a feed that beat it there kept everything. */
    private suspend fun awaitHistory() { if (hideWatched) graph.library.awaitLoaded() }

    init {
        // The disk copy is read off the main thread (the model is made during composition);
        // if the network wins the race, the older copy is never shown.
        cacheKey?.let { key ->
            val initialVersion = requestVersion + 1
            val generation = graph.pages.generation
            viewModelScope.launch {
                val cached = withContext(Dispatchers.IO) { runCatching { graph.pages.read(key, Feed.serializer())?.let { it to graph.pages.savedAt(key) } }.getOrNull() }
                awaitHistory()
                if (cached != null && requestVersion == initialVersion && graph.pages.generation == generation) _state.update { if (it.feed == null) it.copy(feed = tidy(cached.first), stale = true, updatedAt = cached.second, loading = false) else it }
            }
        }
        reload(fresh = false, whenAtTop = true)
        // What's watched while the feed is up leaves it then, not at the next refresh.
        if (hideWatched) viewModelScope.launch {
            graph.library.state.map { it.history }.distinctUntilChanged().drop(1).collect {
                // Another profile's history says nothing about this one's feed.
                if (graph.account.active.value.id != profileId) return@collect
                _state.update { s -> s.feed?.let(::tidy)?.takeIf { it !== s.feed }?.let { s.copy(feed = it) } ?: s }
            }
        }
        // A new content country or language: what's shown was for the old one.
        viewModelScope.launch {
            graph.localeVersion.drop(1).collect { if (graph.account.active.value.id == profileId) reload(fresh = true, replace = shownLoader, quiet = true, key = shownKey) }
        }
    }

    /**
     * Loads the feed, or [replace]'s page in its place (a chip), from the
     * network. [quiet] shows no spinner: the list changes when the copy lands,
     * or with [whenAtTop], once the reader is at the top of it.
     */
    fun reload(fresh: Boolean = true, replace: (suspend (Boolean) -> Feed)? = null, quiet: Boolean = false, key: String? = replace?.let { "page" }, whenAtTop: Boolean = false) {
        val version = ++requestVersion
        job?.cancel()
        moreJob?.cancel()
        held = null
        // Another page in place of the shown one (a chip, or back to the feed): skeletons, not
        // the old page under the new chip. The same page again is a refresh.
        val switching = key != shownKey
        shownLoader = replace
        shownKey = key
        _state.update {
            it.copy(
                loading = it.feed == null || switching,
                refreshing = !quiet && fresh && it.feed != null && !switching,
                error = null,
                loadingMore = false,
                moreFailed = false,
            )
        }
        // A cache cleared while this loads (signing out, a new country) mustn't get the old page back.
        val generation = graph.pages.generation
        // And an account switched or signed in or out meanwhile: the answer may be the next one's.
        val epoch = graph.account.epoch
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { (replace ?: load)(fresh) } }
            awaitHistory()
            if (version != requestVersion) return@launch
            if (graph.account.epoch != epoch) {
                // Still this profile's feed to show (it was made as the switch finished): asked again, as it is now.
                if (graph.account.active.value.id == profileId) reload(fresh, replace, quiet = true, key = key, whenAtTop = whenAtTop)
                else _state.update { it.copy(refreshing = false) }
                return@launch
            }
            result.onSuccess { loaded ->
                val feed = tidy(loaded)
                // An empty answer (offline, everything failed) never replaces something to show.
                val shown = _state.value.feed
                if (feed.isEmpty && feed.message == null && shown != null && !shown.isEmpty && !switching) {
                    _state.update { it.copy(loading = false, refreshing = false, error = "Couldn't refresh.") }
                    return@launch
                }
                val now = clock()
                loadedAt = now
                if (whenAtTop && !readerAtTop && shown != null && !shown.isEmpty && !switching) {
                    // Paging goes on through the list they're reading until then.
                    held = loaded to now
                    _state.update { it.copy(loading = false, refreshing = false) }
                } else {
                    _state.value = FeedState(feed = feed, updatedAt = now, refreshVersion = _state.value.refreshVersion + if (fresh && !quiet) 1 else 0)
                    if (replace == null || feed.chips.isNotEmpty()) baseChips = feed.chips
                }
                // An empty page with nothing to say (everything failed) isn't worth keeping: the next
                // launch would paint it over nothing instead of the last good copy.
                val keep = !feed.isEmpty || feed.message != null
                if (replace == null && keep) cacheKey?.let { key -> withContext(Dispatchers.IO) { graph.pages.write(key, Feed.serializer(), feed, generation) } }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                graph.notePageFailure("Loading ${cacheKey?.substringBefore('-') ?: "a feed"}", e)
                // A chip that failed to load leaves no feed under it: the old page under the new
                // chip read as the chip's results. The full-page error offers Try again.
                _state.update { it.copy(feed = if (switching) null else it.feed, stale = it.stale && !switching, loading = false, refreshing = false, error = friendlyError(e)) }
            }
        }
    }

    /**
     * Fetches the feed again, quietly, if it's old: past [maxAgeMs], or a
     * minute old with something watched since. A feed that never loaded is
     * tried again; one loading now is left to finish. A list scrolled away
     * from its start ([atTop] false) is never changed under the reader.
     */
    fun refreshIfStale(atTop: Boolean = true) {
        if (job?.isActive == true) return
        val loaded = loadedAt
        if (loaded != null) {
            val age = clock() - loaded
            val watchedSince = (graph.library.state.value.history.firstOrNull()?.watchedAt ?: 0L) > loaded
            // Someone partway down a list is reading it: it never changes under them.
            if (!atTop) return
            if (age < maxAgeMs && !(watchedSince && age >= AFTER_WATCH_MS)) return
        }
        reload(fresh = true, replace = shownLoader, quiet = true, key = shownKey, whenAtTop = true)
    }

    /**
     * Where the reader is, from the screen: at the top of the list or into it. Back at the
     * top, a copy that landed meanwhile takes the list's place.
     */
    fun readerAt(atTop: Boolean) {
        readerAtTop = atTop
        val (loaded, at) = held?.takeIf { atTop } ?: return
        held = null
        // A next page of the old list still on its way belongs to that list.
        requestVersion++
        moreJob?.cancel()
        val feed = tidy(loaded)
        _state.value = FeedState(feed = feed, updatedAt = at, refreshVersion = _state.value.refreshVersion)
        if (shownLoader == null || feed.chips.isNotEmpty()) baseChips = feed.chips
    }

    fun loadMore() {
        val s = _state.value
        val token = s.feed?.continuation ?: return
        // Paging an older copy while its replacement loads would mix two recommendation rankings.
        // After a failure only "Try again" asks again ([retryMore]): the near-end ticks would retry forever.
        if (s.loadingMore || s.moreFailed || s.loading || job?.isActive == true || moreJob?.isActive == true) return
        val version = requestVersion
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        moreJob = viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { more(token) } }
            next.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _state.update { st ->
                if (version != requestVersion) return@update st
                val feed = st.feed ?: return@update st.copy(loadingMore = false)
                val page = next.getOrNull()
                // A failed page keeps its token, so "Try again" (or scrolling back down) asks again.
                if (page == null) st.copy(loadingMore = false, moreFailed = true)
                // The whole list again, not the page alone: it can repeat what an earlier page listed.
                else st.copy(loadingMore = false, feed = tidy(feed + page))
            }
        }
    }

    /** The footer's "Try again" after a page failed. */
    fun retryMore() {
        _state.update { it.copy(moreFailed = false) }
        loadMore()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val AFTER_WATCH_MS = MINUTE
    }
}

/**
 * One part of a state flow, recomposing only when that part changes. The library's state moves
 * every few seconds while something plays (resume points); a page showing only its
 * subscriptions or searches used to recompose with every one. [select] is read once.
 */
@Composable
fun <T, R> StateFlow<T>.collectSlice(select: (T) -> R): State<R> {
    val flow = remember(this) { map(select).distinctUntilChanged() }
    return flow.collectAsState(initial = remember(this) { select(value) })
}

/**
 * A page that failed for a reason other than the network (a parse error, YouTube answering
 * oddly), kept in the problem report's trail; the same failure again only counts up.
 */
fun AppGraph.notePageFailure(where: String, e: Throwable) {
    if (e is CancellationException) return
    val causes = generateSequence(e) { it.cause }.take(16).toList()
    val network = causes.any { it is java.io.IOException || it::class.simpleName == "HttpRequestTimeoutException" }
    if (network && causes.none { it is app.oriel.core.YTException }) return
    app.oriel.util.CrashLog.nonFatal(context, where, e)
}

fun friendlyError(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }.toList()
    return when {
        causes.any { it is java.net.UnknownHostException } -> "You're offline. Check your connection and try again."
        causes.any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException || it::class.simpleName == "HttpRequestTimeoutException" } ->
            "YouTube took too long to answer. Try again."
        causes.any { it is java.net.ConnectException || it is javax.net.ssl.SSLException || it is java.io.IOException } ->
            "Couldn't reach YouTube. Check your connection and try again."
        causes.any { it is app.oriel.core.YTException } -> "YouTube couldn't load this page right now. Try again."
        else -> "Something went wrong loading this page."
    }
}
