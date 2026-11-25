package app.oriel.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oriel.AppGraph
import app.oriel.core.model.Chip
import app.oriel.core.model.SearchFilter
import app.oriel.core.model.SearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchState(
    val query: String = "",
    val submitted: String? = null,
    val params: String? = null,
    val page: SearchPage? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
    /** YouTube's result chips ("Videos", "Shorts", "Recently uploaded"), kept from the unfiltered page. */
    val chips: List<Chip> = emptyList(),
    val selectedChip: String? = null,
    /** The filters on, as single filters from the unfiltered page; [params] is them combined. */
    val picked: List<SearchFilter> = emptyList(),
    /** Every filter, as the unfiltered page lists them (a filtered page can't undo its own). */
    val allFilters: List<SearchFilter> = emptyList(),
)

class SearchModel(private val graph: AppGraph, initial: String?, private val saved: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(SearchState(query = initial.orEmpty()))
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null
    private var suggestJob: Job? = null
    /** The chip picked before the process was let go, picked again once the results bring the chips back. */
    private var pendingChip: String? = null

    init {
        val submitted = saved.get<String>(KEY_SUBMITTED)
        if (submitted != null) {
            // Back after Android let the process go: the same search, filters and chip, not an empty page.
            pendingChip = saved.get<String>(KEY_CHIP)
            load(submitted, saved.get<String>(KEY_PARAMS), emptyList(), chip = null, record = false)
        } else initial?.takeIf { it.isNotBlank() }?.let { submit(it) }
        viewModelScope.launch {
            _state.collect {
                saved[KEY_SUBMITTED] = it.submitted
                saved[KEY_PARAMS] = it.params
                saved[KEY_CHIP] = it.selectedChip
            }
        }
    }

    fun type(text: String) {
        _state.update { it.copy(query = text) }
        suggestJob?.cancel()
        if (text.isBlank()) return _state.update { it.copy(suggestions = emptyList()) }
        suggestJob = viewModelScope.launch {
            delay(140)
            val list = withContext(Dispatchers.IO) { runCatching { graph.yt.suggestions(text) }.getOrDefault(emptyList()) }
            _state.update { if (it.query == text) it.copy(suggestions = list) else it }
        }
    }

    fun submit(query: String, params: String? = null, picked: List<SearchFilter> = emptyList(), voice: Boolean = false) =
        load(query, params, picked, chip = null, voice = voice)

    /**
     * Searches [query] with [params], showing [chip] as the picked chip. [onFail] puts back what
     * a failed chip changed, so the chips match the results still shown.
     */
    private fun load(
        query: String, params: String?, picked: List<SearchFilter>, chip: String?,
        voice: Boolean = false, record: Boolean = true, onFail: (SearchState) -> SearchState = { it },
    ) {
        val q = query.trim().ifEmpty { return }
        if (record && !graph.settings.current.pauseSearchHistory) graph.library.addSearch(q)
        // Counts only: never the query.
        if (record && _state.value.submitted != q) graph.telemetry.track("search", mapOf("voice" to voice))
        suggestJob?.cancel()
        job?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(query = q, submitted = q, params = params, picked = picked, selectedChip = chip, chips = if (it.submitted == q) it.chips else emptyList(), allFilters = if (it.submitted == q) it.allFilters else emptyList(), loading = true, error = null, moreFailed = false, loadingMore = false, suggestions = emptyList(), page = if (it.submitted == q) it.page else null) }
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { previewPage ?: graph.yt.search(q, params) } }
            result.onSuccess { page ->
                _state.update {
                    it.copy(
                        page = page, loading = false,
                        allFilters = if (params == null && page.filters.isNotEmpty()) page.filters else it.allFilters,
                        // The chips come from the unfiltered page (a restored search may only have a filtered one).
                        chips = if (params == null || it.chips.isEmpty()) page.feed.chips else it.chips,
                    )
                }
                pendingChip?.let { title ->
                    pendingChip = null
                    val c = _state.value.chips.firstOrNull { it.title == title } ?: return@let
                    if (c.params == null) chip(c) else _state.update { it.copy(selectedChip = c.title) }
                }
            }
                // Never the query itself: a report may be posted publicly.
                .onFailure { e -> graph.notePageFailure("Searching", e); if (e !is kotlinx.coroutines.CancellationException) _state.update { onFail(it).copy(loading = false, error = friendlyError(e)) } }
        }
    }

    /** Try again: the search as it stands, filters and chip included. */
    fun retry() {
        val s = _state.value
        // A chip with a page of its own can't be asked for again from here: the search itself is.
        load(s.submitted ?: return, s.params, s.picked, s.selectedChip.takeIf { chipParams(s) != null }, record = false)
    }

    /** The params of the picked chip, when it's one that filters (Videos, Shorts) rather than a page of its own. */
    private fun chipParams(s: SearchState): String? = s.chips.firstOrNull { it.title == s.selectedChip }?.params

    /**
     * Turns [f] on or off. A filter replaces any other in its slot (one upload date, one type,
     * one duration, one sort), features stack, and the set is combined into one params string.
     * Null clears them all. A chip that filters stays on with them.
     */
    fun filter(f: SearchFilter?) {
        val s = _state.value
        val q = s.submitted ?: return
        val chipParams = chipParams(s)
        val chip = s.selectedChip.takeIf { chipParams != null }
        if (f == null) return load(q, chipParams, emptyList(), chip)
        val single = s.allFilters.firstOrNull { it.label == f.label && it.group == f.group } ?: f
        val params = single.params
        val on = s.picked.any { it.label == single.label }
        val picked = when {
            on -> s.picked.filterNot { it.label == single.label }
            // Relevance, the default sort, has no params: picking it just drops the other sort.
            params == null -> s.picked.filterNot { it.group == single.group }
            else -> {
                val slot = app.oriel.core.SearchParams.slot(params)
                s.picked.filterNot { p -> p.params?.let(app.oriel.core.SearchParams::slot) == slot } + single
            }
        }
        load(q, app.oriel.core.SearchParams.combine(listOfNotNull(chipParams) + picked.mapNotNull { it.params }), picked, chip)
    }

    /**
     * A result chip: params re-run the search with the filters that are on, a token swaps in
     * the chip's own results. One that fails puts the last chip back and says so.
     */
    fun chip(c: Chip) {
        val s = _state.value
        val q = s.submitted ?: return
        val filters = s.picked.mapNotNull { it.params }
        val restore: (SearchState) -> SearchState = { it.copy(selectedChip = s.selectedChip, params = s.params) }
        // The first chip ("All"), or the picked one again: the search itself, filters kept.
        if (c.title == s.selectedChip || c.title == s.chips.firstOrNull()?.title) {
            return load(q, app.oriel.core.SearchParams.combine(filters), s.picked, chip = null, record = false, onFail = restore)
        }
        c.params?.let { params ->
            return load(q, app.oriel.core.SearchParams.combine(listOf(params) + filters), s.picked, chip = c.title, record = false, onFail = restore)
        }
        val token = c.token ?: return
        job?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(loading = true, selectedChip = c.title, loadingMore = false, moreFailed = false, error = null) }
        job = viewModelScope.launch {
            val feed = withContext(Dispatchers.IO) { runCatching { graph.yt.searchMore(token) }.getOrNull() }
            _state.update { st ->
                if (feed == null) st.copy(loading = false, selectedChip = s.selectedChip, error = "Couldn't load these results.")
                else st.copy(loading = false, page = (st.page ?: SearchPage(feed)).copy(feed = feed))
            }
        }
    }

    fun clear() {
        job?.cancel()
        moreJob?.cancel()
        _state.value = SearchState()
    }

    private var moreJob: Job? = null

    fun loadMore() {
        val s = _state.value
        val token = s.page?.feed?.continuation ?: return
        if (s.loading || s.loadingMore || s.moreFailed) return
        _state.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { graph.yt.searchMore(token) }.getOrNull() }
            _state.update { st ->
                val page = st.page ?: return@update st.copy(loadingMore = false)
                // Results replaced meanwhile (a new search, a chip): this page belonged to the old ones.
                if (page.feed.continuation != token) return@update st
                // A failed page keeps its token, for the footer's Try again.
                if (next == null) st.copy(loadingMore = false, moreFailed = true)
                else st.copy(loadingMore = false, page = page.copy(feed = page.feed + next))
            }
        }
    }

    fun retryMore() {
        _state.update { it.copy(moreFailed = false) }
        loadMore()
    }

    companion object {
        private const val KEY_SUBMITTED = "submitted"
        private const val KEY_PARAMS = "params"
        private const val KEY_CHIP = "chip"

        /** Frame strips and screenshots: a page to show in place of the network. */
        @androidx.annotation.VisibleForTesting
        internal var previewPage: SearchPage? = null
    }
}
