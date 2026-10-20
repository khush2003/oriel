package app.oriel.ui.screens

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.AppGraph
import app.oriel.core.model.Chip
import app.oriel.core.model.SearchFilter
import app.oriel.core.model.SearchPage
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.ChipsRow
import app.oriel.ui.components.ChoiceRow
import app.oriel.ui.components.FilterChip
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.RowDivider
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.SearchField
import app.oriel.ui.components.TextAction
import app.oriel.ui.components.VideoCardSkeleton
import app.oriel.ui.components.feedShelves
import app.oriel.ui.components.pagePadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
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

class SearchModel(private val graph: AppGraph, initial: String?) : ViewModel() {
    private val _state = MutableStateFlow(SearchState(query = initial.orEmpty()))
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null
    private var suggestJob: Job? = null

    init { initial?.takeIf { it.isNotBlank() }?.let { submit(it) } }

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

    fun submit(query: String, params: String? = null, picked: List<SearchFilter> = emptyList(), voice: Boolean = false) {
        val q = query.trim().ifEmpty { return }
        if (!graph.settings.current.pauseSearchHistory) graph.library.addSearch(q)
        // Counts only: never the query.
        if (_state.value.submitted != q) graph.telemetry.track("search", mapOf("voice" to voice))
        suggestJob?.cancel()
        job?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(query = q, submitted = q, params = params, picked = picked, allFilters = if (it.submitted == q) it.allFilters else emptyList(), loading = true, error = null, moreFailed = false, loadingMore = false, suggestions = emptyList(), page = if (it.submitted == q) it.page else null) }
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { graph.yt.search(q, params) } }
            result.onSuccess { page -> _state.update { it.copy(page = page, allFilters = if (params == null && page.filters.isNotEmpty()) page.filters else it.allFilters, loading = false, chips = if (params == null) page.feed.chips else it.chips, selectedChip = if (params == null) null else it.selectedChip) } }
                // Never the query itself: a report may be posted publicly.
                .onFailure { e -> graph.notePageFailure("Searching", e); if (e !is kotlinx.coroutines.CancellationException) _state.update { it.copy(loading = false, error = friendlyError(e)) } }
        }
    }

    /**
     * Turns [f] on or off. A filter replaces any other in its slot (one upload date, one type,
     * one duration, one sort), features stack, and the set is combined into one params string.
     * Null clears them all.
     */
    fun filter(f: SearchFilter?) {
        val s = _state.value
        val q = s.submitted ?: return
        if (f == null) return submit(q)
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
        submit(q, app.oriel.core.SearchParams.combine(picked.mapNotNull { it.params }), picked)
    }

    /** A result chip: params re-run the search, a token swaps in the chip's own results. */
    fun chip(c: Chip) {
        val s = _state.value
        val q = s.submitted ?: return
        if (c.title == s.selectedChip || c.title == s.chips.firstOrNull()?.title) return submit(q)
        c.params?.let { params -> submit(q, params); _state.update { it.copy(selectedChip = c.title) }; return }
        val token = c.token ?: return
        job?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(loading = true, selectedChip = c.title, loadingMore = false, moreFailed = false) }
        job = viewModelScope.launch {
            val feed = withContext(Dispatchers.IO) { runCatching { graph.yt.searchMore(token) }.getOrNull() }
            _state.update { st -> if (feed == null) st.copy(loading = false, error = "Couldn't load these results.") else st.copy(loading = false, page = (st.page ?: SearchPage(feed)).copy(feed = feed)) }
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
}

/**
 * Search, as YouTube has it: the field pinned at the top and focused on
 * arrival, recent searches until you type, YouTube's suggestions with the
 * completion in bold while you do, then results under the result chips.
 * Topics live on Home's chips, so they aren't repeated here.
 */
@Composable
fun SearchScreen(initial: String?) {
    val app = LocalAppState.current
    val graph = app.graph
    val model: SearchModel = viewModel(key = "search:${initial.orEmpty()}") { SearchModel(graph, initial) }
    val state by model.state.collectAsState()
    val library by graph.library.state.collectAsState()
    var focused by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val page = state.page
    val showResults = state.submitted != null && (!focused || state.query == state.submitted)
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    val resultsShown by rememberUpdatedState(showResults)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 6 } == true }
            .collectLatest { near -> while (near) { if (resultsShown) model.loadMore(); kotlinx.coroutines.delay(1_000) } }
    }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress && focused) { focusManager.clearFocus(); keyboard?.hide() } }
    // Arriving with nothing searched yet means you're here to type, as on YouTube: the field takes focus.
    LaunchedEffect(Unit) { if (state.submitted == null) { runCatching { focus.requestFocus() }; keyboard?.show() } }
    // Search asked for again while open: back to the top of the results, or into the field.
    val shownAt = remember { System.nanoTime() }
    LaunchedEffect(Unit) {
        app.searchAgain.filter { it > shownAt }.collect {
            if (state.submitted != null && listState.firstVisibleItemIndex > 0) listState.animateScrollToItem(0)
            else { runCatching { focus.requestFocus() }; keyboard?.show() }
        }
    }

    fun submit(q: String, voice: Boolean = false) {
        focusManager.clearFocus(); keyboard?.hide()
        model.submit(q, voice = voice)
        app.scope.launch { listState.scrollToItem(0) }
    }

    // Voice search: the system's speech recogniser (Google's, on most phones) hears the query
    // and it's searched straight away, as YouTube's microphone does. Hidden where there's none.
    val context = androidx.compose.ui.platform.LocalContext.current
    val voiceIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search YouTube")
    }
    val canListen = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val listen = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) { model.type(heard); submit(heard, voice = true) }
    }
    val haptics = rememberHaptics()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = PAGE_GUTTER, end = PAGE_GUTTER - 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                state.query, model::type, ::submit, Modifier.weight(1f), focusRequester = focus, onFocus = { focused = it },
                idleTrailing = if (!canListen) null else {
                    {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Search by voice") {
                                haptics.play(Haptic.Tap)
                                focusManager.clearFocus(); keyboard?.hide()
                                runCatching { listen.launch(voiceIntent) }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(OrielIcons.Mic, "Search by voice", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                    }
                },
            )
            AnimatedVisibility(focused || state.submitted != null, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Row {
                    Spacer(Modifier.width(4.dp))
                    TextAction("Cancel", { focusManager.clearFocus(); keyboard?.hide(); model.clear() })
                }
            }
        }
        // A hairline under the bar once content scrolls beneath it, as iOS bars do.
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(if (scrolled) MaterialTheme.colorScheme.outlineVariant else Color.Transparent))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = pagePadding(LocalMiniPlayerVisible.current, top = 0.dp, withTabBar = LocalTabBarVisible.current)) {
            when {
                focused && state.query.isNotBlank() && state.query != state.submitted -> {
                    val typed = state.query.trim()
                    items(state.suggestions.distinct(), key = { "s:$it" }) { s -> SuggestionRow(completion(typed, s), OrielIcons.Search, onClick = { submit(s) }, onFill = { model.type("$s ") }) }
                    if (state.suggestions.isEmpty()) item(key = "typed") { SuggestionRow(AnnotatedString(typed), OrielIcons.Search, onClick = { submit(typed) }, onFill = null) }
                }
                showResults -> {
                    page?.correctedQuery?.let { c ->
                        item(key = "corrected") {
                            Text(
                                "Showing results for “$c”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
                            )
                        }
                    }
                    val filters = page?.filters.orEmpty().distinctBy { it.label }.take(24)
                    if (state.chips.isNotEmpty()) item(key = "chips") {
                        val chips = state.chips.mapIndexed { i, c -> c.copy(selected = if (state.selectedChip == null) i == 0 else c.title == state.selectedChip) }
                        // YouTube's full filters (upload date, type, duration, features, sort) sit behind a
                        // leading button, as its own app has them, so they're there even when chips are.
                        ChipsRow(chips, onSelect = model::chip, leading = if (filters.isNotEmpty()) ({
                            FilterChip(if (state.picked.isEmpty()) "Filters" else "Filters · ${state.picked.size}", state.picked.isNotEmpty(), leading = OrielIcons.Filter) { filtersOpen = true }
                        }) else null)
                    } else if (filters.isNotEmpty()) item(key = "filters") {
                        ChipsRow(emptyList(), onSelect = {}, leading = {
                            FilterChip(if (state.picked.isEmpty()) "Filters" else "Filters · ${state.picked.size}", state.picked.isNotEmpty(), leading = OrielIcons.Filter) { filtersOpen = true }
                        })
                    }
                    // What's on, each with its own ✕, as YouTube lists applied filters under the bar.
                    if (state.picked.isNotEmpty()) item(key = "picked") {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PAGE_GUTTER, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.picked.forEach { f -> FilterChip(f.label, false, leading = OrielIcons.Close) { model.filter(f) } }
                            if (state.picked.size > 1) FilterChip("Clear all", false) { model.filter(null) }
                        }
                    }
                    when {
                        state.loading && page == null -> items(5) { VideoCardSkeleton(it) }
                        state.error != null && page == null -> item(key = "error") { MessageState(state.error!!, action = "Try again", onAction = { model.submit(state.submitted!!, state.params, state.picked) }) }
                        page != null && page.feed.isEmpty -> item { MessageState(page.feed.message ?: "No results for “${state.submitted}”.", icon = OrielIcons.Search) }
                        page != null -> {
                            if (state.loading) item(key = "reloading") { LoadingRow() }
                            else state.error?.let { e -> item(key = "notice") { NoticeRow(e, action = "Try again", onAction = { model.submit(state.submitted!!, state.params, state.picked) }) } }
                            feedShelves(page.feed, keyPrefix = "search")
                        }
                    }
                    moreFooter(state.loadingMore, state.moreFailed, model::retryMore)
                }
                library.searchHistory.isNotEmpty() -> {
                    item(key = "recent-h") {
                        Row(Modifier.fillMaxWidth().padding(start = PAGE_GUTTER, end = PAGE_GUTTER - 4.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600), modifier = Modifier.weight(1f))
                            TextAction("Clear", { graph.library.clearSearches() })
                        }
                    }
                    items(library.searchHistory.distinct().take(20), key = { "r:$it" }) { q ->
                        SuggestionRow(AnnotatedString(q), OrielIcons.History, onClick = { submit(q) }, onFill = { model.type("$q ") }, onRemove = { graph.library.removeSearch(q) })
                    }
                }
                else -> item(key = "empty") {
                    MessageState("Find videos, Shorts, channels and playlists.", icon = OrielIcons.Search)
                }
            }
        }
    }
    if (filtersOpen) {
        // The unfiltered page's list, marked from the shown page: what's on, and what can't go
        // with it (Channels while "This week" is on). Picks apply at once and the sheet stays
        // open, so several can be set in one go; Done (or a swipe) closes it.
        val shown = page?.filters.orEmpty()
        val all = state.allFilters.ifEmpty { shown }
        OrielSheet({ filtersOpen = false }) {
            val close = LocalSheetClose.current
            SheetTitle("Filters", trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.picked.isNotEmpty()) TextAction("Reset", { model.filter(null) })
                    TextAction("Done", { close {} })
                }
            })
            all.groupBy { it.group }.forEach { (group, options) ->
                SettingsGroup(header = group) {
                    options.forEachIndexed { i, f ->
                        if (i > 0) RowDivider(inset = false)
                        val on = state.picked.any { it.label == f.label } || (f.params == null && state.picked.none { it.group == f.group })
                        val disabled = !on && shown.any { it.label == f.label && it.disabled }
                        androidx.compose.foundation.layout.Box(Modifier.graphicsLayer { alpha = if (disabled) 0.4f else 1f }) {
                            ChoiceRow(f.label, on) { if (!disabled && !(on && f.params == null)) model.filter(f) }
                        }
                    }
                }
            }
            if (state.loading) LoadingRow()
        }
    }
}

/** A suggestion with what you typed in the regular weight and YouTube's completion in bold, as YouTube shows them. */
private fun completion(typed: String, suggestion: String): AnnotatedString = buildAnnotatedString {
    if (typed.isNotEmpty() && suggestion.startsWith(typed, ignoreCase = true)) {
        append(suggestion.substring(0, typed.length))
        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(suggestion.substring(typed.length)) }
    } else {
        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(suggestion) }
    }
}

@Composable
private fun SuggestionRow(text: AnnotatedString, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, onFill: (() -> Unit)?, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = PAGE_GUTTER, end = PAGE_GUTTER - 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRemove != null) Icon(OrielIcons.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRemove).padding(9.dp))
        if (onFill != null) Icon(OrielIcons.Fill, "Use", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable(onClick = onFill).padding(8.dp))
    }
}
