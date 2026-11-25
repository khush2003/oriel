package app.oriel.ui.screens

import app.oriel.core.model.ChannelAbout
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oriel.AppGraph
import app.oriel.core.model.ChannelPage
import app.oriel.core.model.ChannelTab
import app.oriel.core.model.Chip
import app.oriel.core.model.Feed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChannelState(
    val page: ChannelPage? = null,
    val tabs: List<ChannelTab> = emptyList(),
    val selectedTab: String? = null,
    val feed: Feed? = null,
    val loading: Boolean = true,
    val loadingTab: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val error: String? = null,
    val sortChips: List<Chip> = emptyList(),
    val selectedSort: String? = null,
    val searchQuery: String? = null,
    val about: ChannelAbout? = null,
    val aboutFailed: Boolean = false,
)

class ChannelModel(private val graph: AppGraph, private val id: String, params: String?) : ViewModel() {
    private val _state = MutableStateFlow(ChannelState())
    val state: StateFlow<ChannelState> = _state.asStateFlow()
    private var job: Job? = null
    /** The shown tab's params, so a refresh reloads that tab. */
    private var tabParams: String? = params

    init { load(params) }

    companion object {
        /** Frame strips: a page to show in place of the network. */
        @androidx.annotation.VisibleForTesting
        internal var previewPage: ChannelPage? = null
    }

    /** Pull to refresh: the shown tab again from YouTube, sort and search let go. */
    fun refresh() {
        if (_state.value.page == null) return load(tabParams)
        cancelLoads()
        _state.update { it.copy(refreshing = true, error = null) }
        job = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { graph.yt.channel(id, tabParams, fresh = true) } }
            r.onSuccess { p ->
                _state.update { it.copy(page = p, feed = p.feed, refreshing = false, sortChips = p.feed.chips, selectedSort = null, searchQuery = null) }
            }.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _state.update { it.copy(refreshing = false, error = friendlyError(e)) } }
        }
    }

    fun load(params: String? = null) {
        cancelLoads()
        _state.update { it.copy(loading = it.page == null, error = null) }
        job = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { previewPage ?: graph.yt.channel(id, params) } }
            r.onSuccess { p ->
                _state.update { it.copy(page = p, tabs = p.tabs.filter { t -> t.params != null || t.title == p.selectedTab }, selectedTab = p.selectedTab, feed = p.feed, loading = false, sortChips = p.feed.chips, selectedSort = null) }
            }.onFailure { e ->
                graph.notePageFailure("Loading a channel", e)
                if (e !is kotlinx.coroutines.CancellationException) _state.update { it.copy(loading = false, error = friendlyError(e)) }
            }
        }
    }

    private var failedTab: ChannelTab? = null
    /** A channel search that failed, for Try again to ask again. */
    private var failedSearch: String? = null
    private var moreJob: Job? = null

    /** Stops whatever was loading, and lets go of the flags it owned. */
    private fun cancelLoads() {
        job?.cancel()
        moreJob?.cancel()
        failedSearch = null
        _state.update { it.copy(refreshing = false, loadingTab = false, loadingMore = false, moreFailed = false) }
    }

    fun selectTab(tab: ChannelTab) {
        if (tab.title == _state.value.selectedTab && _state.value.searchQuery == null && failedTab == null) return
        cancelLoads()
        tabParams = tab.params
        failedTab = null
        _state.update { it.copy(selectedTab = tab.title, loadingTab = true, searchQuery = null, sortChips = emptyList(), selectedSort = null, error = null, moreFailed = false) }
        job = viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { graph.yt.channel(id, tab.params) }.getOrNull() }
            // A failed tab shows its own error, not the last tab's videos under the new tab's chip.
            if (p == null) failedTab = tab
            _state.update { if (p == null) it.copy(loadingTab = false, feed = null, error = "Couldn't load ${tab.title}.") else it.copy(feed = p.feed, loadingTab = false, sortChips = p.feed.chips, error = null) }
        }
    }

    /** The About sheet's details, fetched the first time it opens. */
    fun loadAbout() {
        val token = _state.value.page?.aboutToken
        if (_state.value.about != null) return
        if (token == null) return _state.update { it.copy(about = ChannelAbout(description = it.page?.channel?.description)) }
        _state.update { it.copy(aboutFailed = false) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { graph.yt.channelAbout(token) } }
            _state.update { r.fold({ a -> it.copy(about = a) }, { _ -> it.copy(aboutFailed = true) }) }
        }
    }

    /** Try again after a failure: the tab that failed, or the whole channel. */
    fun retry() {
        failedSearch?.let { return search(it) }
        failedTab?.let { return selectTab(it) }
        if (_state.value.page == null) load(tabParams) else refresh()
    }

    fun sort(chip: Chip) {
        val token = chip.token ?: return
        if (chip.title == _state.value.selectedSort) return
        cancelLoads()
        val before = _state.value.selectedSort
        _state.update { it.copy(selectedSort = chip.title, loadingTab = true, error = null) }
        job = viewModelScope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { graph.yt.more(token) }.getOrNull() }
            // A failed sort puts the old one back, and says so, over the list it left in place.
            _state.update { if (f == null) it.copy(loadingTab = false, selectedSort = before, error = "Couldn't sort by ${chip.title.lowercase()}.") else it.copy(feed = f, loadingTab = false, moreFailed = false) }
        }
    }

    fun search(query: String) {
        val q = query.trim().ifEmpty { return }
        cancelLoads()
        _state.update { it.copy(searchQuery = q, loadingTab = true, sortChips = emptyList(), error = null) }
        job = viewModelScope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { graph.yt.channelSearch(id, q) }.getOrNull() }
            // A failed search says so with Try again, rather than reading as no results.
            if (f == null) failedSearch = q
            _state.update { if (f == null) it.copy(feed = null, loadingTab = false, error = "Couldn't search this channel.") else it.copy(feed = f, loadingTab = false) }
        }
    }

    /** The search closed: back to the tab that was picked before it. */
    fun clearSearch() {
        val s = _state.value
        if (s.searchQuery == null) return
        val tab = s.tabs.firstOrNull { it.title == s.selectedTab } ?: return load(tabParams)
        selectTab(tab)
    }

    fun retryMore() {
        _state.update { it.copy(moreFailed = false) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        val token = s.feed?.continuation ?: return
        if (s.loadingMore || s.loadingTab || s.moreFailed) return
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        moreJob = viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { graph.yt.more(token) }.getOrNull() }
            // A failed page keeps its token: the footer offers to try again rather than ending the list.
            _state.update { st -> if (st.feed?.continuation != token) st else if (next == null) st.copy(loadingMore = false, moreFailed = true) else st.copy(loadingMore = false, feed = st.feed?.let { f -> f + next }) }
        }
    }
}
