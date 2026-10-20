package app.oriel.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oriel.AppGraph
import app.oriel.core.model.Feed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 */
class FeedModel(
    private val graph: AppGraph,
    private val cacheKey: String?,
    private val load: suspend (fresh: Boolean) -> Feed,
    private val more: suspend (token: String) -> Feed = { graph.yt.more(it) },
    private val maxAgeMs: Long = 10 * MINUTE,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()
    private var job: Job? = null
    private var moreJob: Job? = null
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

    init {
        // The disk copy is read off the main thread (the model is made during composition);
        // if the network wins the race, the older copy is never shown.
        cacheKey?.let { key ->
            viewModelScope.launch {
                val cached = withContext(Dispatchers.IO) { runCatching { graph.pages.read(key, Feed.serializer())?.let { it to graph.pages.savedAt(key) } }.getOrNull() }
                if (cached != null) _state.update { if (it.feed == null) it.copy(feed = cached.first, stale = true, updatedAt = cached.second, loading = false) else it }
            }
        }
        reload(fresh = false)
        // A new content country or language: what's shown was for the old one.
        viewModelScope.launch {
            graph.localeVersion.drop(1).collect { reload(fresh = true, replace = shownLoader, quiet = true, key = shownKey) }
        }
    }

    /**
     * Loads the feed, or [replace]'s page in its place (a chip), from the
     * network. [quiet] shows no spinner: the list changes when the copy lands.
     */
    fun reload(fresh: Boolean = true, replace: (suspend (Boolean) -> Feed)? = null, quiet: Boolean = false, key: String? = replace?.let { "page" }) {
        job?.cancel()
        moreJob?.cancel()
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
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { (replace ?: load)(fresh) } }
            result.onSuccess { feed ->
                // An empty answer (offline, everything failed) never replaces something to show.
                val shown = _state.value.feed
                if (feed.isEmpty && feed.message == null && shown != null && !shown.isEmpty && !switching) {
                    _state.update { it.copy(loading = false, refreshing = false, error = "Couldn't refresh.") }
                    return@launch
                }
                val now = clock()
                loadedAt = now
                _state.value = FeedState(feed = feed, updatedAt = now)
                if (replace == null && feed.chips.isNotEmpty()) baseChips = feed.chips
                if (replace == null) cacheKey?.let { key -> withContext(Dispatchers.IO) { graph.pages.write(key, Feed.serializer(), feed) } }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                graph.notePageFailure("Loading ${cacheKey?.substringBefore('-') ?: "a feed"}", e)
                _state.update { it.copy(loading = false, refreshing = false, error = friendlyError(e)) }
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
        reload(fresh = true, replace = shownLoader, quiet = true, key = shownKey)
    }

    fun loadMore() {
        val s = _state.value
        val token = s.feed?.continuation ?: return
        // While the network copy of a disk feed is on its way, its own continuation is what's wanted.
        if (s.loadingMore || s.loading || (s.stale && job?.isActive == true) || moreJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        moreJob = viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { more(token) } }
            next.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _state.update { st ->
                val feed = st.feed ?: return@update st.copy(loadingMore = false)
                val page = next.getOrNull()
                // A failed page keeps its token, so "Try again" (or scrolling back down) asks again.
                if (page == null) st.copy(loadingMore = false, moreFailed = true)
                else st.copy(loadingMore = false, feed = feed + page)
            }
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val AFTER_WATCH_MS = MINUTE
    }
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
