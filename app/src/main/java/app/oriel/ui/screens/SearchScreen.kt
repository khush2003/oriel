package app.oriel.ui.screens

import app.oriel.ui.theme.LocalLiquidGlassEnabled
import app.oriel.ui.theme.LocalLiquidMotion
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.ChipsRow
import app.oriel.ui.components.ChoiceRow
import app.oriel.ui.components.FilterChip
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.RowDivider
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SheetAction
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.SearchField
import app.oriel.ui.components.TextAction
import app.oriel.ui.components.feedShelves
import app.oriel.ui.components.cardSkeletons
import app.oriel.ui.LocalWindowLayout
import app.oriel.ui.PageWidth
import app.oriel.ui.components.pagePadding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Search, as YouTube has it: the field focused on
 * arrival, recent searches until you type, YouTube's suggestions with the
 * completion in bold while you do, then results under the result chips.
 * Topics live on Home's chips, so they aren't repeated here.
 */
@Composable
fun SearchScreen(initial: String?) {
    val app = LocalAppState.current
    val graph = app.graph
    val model: SearchModel = viewModel(key = "search:${initial.orEmpty()}") { SearchModel(graph, initial, createSavedStateHandle()) }
    val state by model.state.collectAsState()
    // Only the recent searches: the whole library moves with every resume point while something plays.
    val recent by graph.library.state.collectSlice { it.searchHistory.distinct().take(20) }
    var focused by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val page = state.page
    val filters = remember(page?.filters) { page?.filters.orEmpty().distinctBy { it.label }.take(24) }
    val showResults = state.submitted != null && (!focused || state.query == state.submitted)
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    val resultsShown by rememberUpdatedState(showResults)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 6 } == true }
            .collectLatest { near -> while (near) { if (resultsShown) model.loadMore(); kotlinx.coroutines.delay(1_000) } }
    }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress && focused) { focusManager.clearFocus(); keyboard?.hide() } }
    // Liquid with glass, the field is the tab bar's Search droplet at the bottom, as the mock has it.
    val bottomField = LocalLiquidMotion.current && LocalLiquidGlassEnabled.current && LocalTabBarVisible.current
    // At the bottom the field is the tab bar's, composed a frame or two after this page.
    suspend fun focusField() {
        repeat(30) {
            if (focused) return
            runCatching { focus.requestFocus() }
            withFrameNanos { }
        }
    }
    // Arriving with nothing searched yet means you're here to type, as on YouTube: the field takes focus.
    LaunchedEffect(Unit) { if (state.submitted == null) { focusField(); keyboard?.show() } }
    // Search asked for again while open: back to the top of the results, or into the field.
    val shownAt = remember { System.nanoTime() }
    LaunchedEffect(Unit) {
        app.searchAgain.filter { it > shownAt }.collect {
            if (state.submitted != null && listState.firstVisibleItemIndex > 0) listState.animateScrollToItem(0)
            else { focusField(); keyboard?.show() }
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
    val voiceButton: @Composable () -> Unit = {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Search by voice") {
                haptics.play(Haptic.Tap)
                focusManager.clearFocus(); keyboard?.hide()
                runCatching { listen.launch(voiceIntent) }
            },
            contentAlignment = Alignment.Center,
        ) { Icon(OrielIcons.Mic, "Search by voice", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
    val field: @Composable (Modifier) -> Unit = remember {
        { m -> BottomSearchField(state.query, model::type, ::submit, m, focus, { focused = it }, if (canListen) voiceButton else null) }
    }
    DisposableEffect(bottomField) {
        if (bottomField) app.searchField = field
        onDispose { if (app.searchField === field) app.searchField = null }
    }

    // Suggestions and recents are one centred column; results are the feed's grid.
    val layout = LocalWindowLayout.current
    val kind = if (showResults) PageWidth.Feed else PageWidth.List
    val columns = layout.columns(kind)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally) {
        if (bottomField) Spacer(Modifier.fillMaxWidth().statusBarsPadding().height(8.dp)) else Row(
            Modifier.widthIn(max = layout.contentWidth(PageWidth.List)).fillMaxWidth().statusBarsPadding().padding(start = PAGE_GUTTER, end = PAGE_GUTTER - 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                state.query, model::type, ::submit, Modifier.weight(1f), focusRequester = focus, onFocus = { focused = it },
                idleTrailing = if (canListen) voiceButton else null,
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
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().then(if (bottomField) Modifier.imePadding() else Modifier), contentPadding = pagePadding(top = 0.dp, withTabBar = LocalTabBarVisible.current, horizontal = layout.inset(kind))) {
            when {
                focused && state.query.isNotBlank() && state.query != state.submitted -> {
                    val typed = state.query.trim()
                    items(state.suggestions.distinct(), key = { "s:$it" }, contentType = { "suggestion" }) { s -> SuggestionRow(completion(typed, s), OrielIcons.Search, onClick = { submit(s) }, onFill = { model.type("$s ") }) }
                    if (state.suggestions.isEmpty()) item(key = "typed", contentType = "suggestion") { SuggestionRow(AnnotatedString(typed), OrielIcons.Search, onClick = { submit(typed) }, onFill = null) }
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
                    if (state.chips.isNotEmpty()) item(key = "chips") {
                        val chips = state.chips.mapIndexed { i, c -> c.copy(selected = if (state.selectedChip == null) i == 0 else c.title == state.selectedChip) }
                        // YouTube's full filters (upload date, type, duration, features, sort) sit behind a
                        // leading button, as its own app has them, so they're there even when chips are.
                        ChipsRow(chips, onSelect = model::chip, leading = if (filters.isNotEmpty()) ({
                            FilterChip(if (state.picked.isEmpty()) "Filters" else "Filters · ${state.picked.size}", state.picked.isNotEmpty(), leading = OrielIcons.Tune) { filtersOpen = true }
                        }) else null)
                    } else if (filters.isNotEmpty()) item(key = "filters") {
                        ChipsRow(emptyList(), onSelect = {}, leading = {
                            FilterChip(if (state.picked.isEmpty()) "Filters" else "Filters · ${state.picked.size}", state.picked.isNotEmpty(), leading = OrielIcons.Tune) { filtersOpen = true }
                        })
                    }
                    // What's on, each with its own ✕, as YouTube lists applied filters under the bar.
                    if (state.picked.isNotEmpty()) item(key = "picked") {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PAGE_GUTTER, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.picked.forEach { f -> FilterChip(f.label, false, leading = OrielIcons.Close, role = Role.Button) { model.filter(f) } }
                            if (state.picked.size > 1) FilterChip("Clear all", false, role = Role.Button) { model.filter(null) }
                        }
                    }
                    when {
                        state.loading && page == null -> cardSkeletons(5, columns)
                        state.error != null && page == null -> item(key = "error", contentType = "message") { MessageState(state.error!!, action = "Try again", onAction = model::retry) }
                        page != null && page.feed.isEmpty -> item(key = "none", contentType = "message") { MessageState(page.feed.message ?: "No results for “${state.submitted}”.", icon = OrielIcons.Search) }
                        page != null -> {
                            if (state.loading) item(key = "reloading") { LoadingRow() }
                            else state.error?.let { e -> item(key = "notice") { NoticeRow(e, action = "Try again", onAction = model::retry) } }
                            feedShelves(page.feed, keyPrefix = "search", columns = columns, shortColumns = layout.shortColumns(PageWidth.Feed))
                        }
                    }
                    moreFooter(state.loadingMore, state.moreFailed, model::retryMore)
                }
                recent.isNotEmpty() -> {
                    item(key = "recent-h") {
                        Row(Modifier.fillMaxWidth().padding(start = PAGE_GUTTER, end = PAGE_GUTTER - 4.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600), modifier = Modifier.weight(1f))
                            TextAction("Clear", { graph.library.clearSearches() })
                        }
                    }
                    items(recent, key = { "r:$it" }, contentType = { "suggestion" }) { q ->
                        SuggestionRow(AnnotatedString(q), OrielIcons.History, onClick = { submit(q) }, onFill = { model.type("$q ") }, onRemove = { graph.library.removeSearch(q) })
                    }
                }
                else -> item(key = "empty", contentType = "message") {
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
                    if (state.picked.isNotEmpty()) SheetAction("Reset") { model.filter(null) }
                    SheetAction("Done") { close {} }
                }
            })
            all.groupBy { it.group }.forEach { (group, options) ->
                SettingsGroup(header = group) {
                    options.forEachIndexed { i, f ->
                        if (i > 0) RowDivider(inset = false)
                        val on = state.picked.any { it.label == f.label } || (f.params == null && state.picked.none { it.group == f.group })
                        val disabled = !on && shown.any { it.label == f.label && it.disabled }
                        ChoiceRow(f.label, on, enabled = !disabled) { if (!(on && f.params == null)) model.filter(f) }
                    }
                }
            }
            if (state.loading) LoadingRow()
        }
    }
}
