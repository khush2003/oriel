package app.oriel.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import app.oriel.ui.player.linkify
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.components.RowDivider
import app.oriel.ui.components.SettingsRow
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.core.model.Channel
import app.oriel.core.model.ChannelAbout
import app.oriel.util.Thumbnails
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.AppGraph
import app.oriel.core.model.ChannelPage
import app.oriel.core.model.ChannelTab
import app.oriel.core.model.Chip
import app.oriel.core.model.Feed
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.ActionPill
import app.oriel.ui.components.SubscribePill
import app.oriel.ui.components.TopBarContentHeight
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.smoothstep
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.util.lerp
import app.oriel.ui.components.PillSize
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.ChipsRow
import app.oriel.ui.components.TabStrip
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.PillAction
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.components.SearchField
import app.oriel.ui.components.SkeletonLine
import app.oriel.ui.components.shimmer
import app.oriel.ui.components.VerifiedBadge
import app.oriel.ui.components.VideoCardSkeleton
import app.oriel.ui.components.feedShelves
import app.oriel.ui.components.topBarContentPadding
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

    /**
     * Stops whatever was loading, and lets go of the flags it owned: a refresh cut short by a
     * tab used to leave its spinner up for good, and a next page landing after a tab switch
     * was added to the new tab's list.
     */
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

/**
 * A channel: its banner fading into the page, the avatar and name, the
 * subscribe button, then its tabs as chips (Home, Videos, Shorts, Live,
 * Playlists, Posts) with each tab's sort chips under them.
 */
@Composable
fun ChannelScreen(id: String, params: String?) {
    val app = LocalAppState.current
    val graph = app.graph
    val model: ChannelModel = viewModel(key = "channel:$id:$params") { ChannelModel(graph, id, params) }
    val state by model.state.collectAsState()
    val subscribed by graph.library.subscribedIds.collectAsState()
    val listState = rememberLazyListState()
    var aboutOpen by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val channel = state.page?.channel

    PageScaffold(
        title = channel?.title ?: "",
        onBack = app::back,
        state = listState,
        edgeToEdge = true,
        largeTitle = false,
        refreshing = state.refreshing,
        onRefresh = model::refresh,
        onNearEnd = model::loadMore,
        actions = {
            PillAction(OrielIcons.Search, if (searching) "Close search" else "Search channel") {
                searching = !searching
                if (!searching) { query = ""; model.clearSearch() }
            }
            channel?.let { PillAction(OrielIcons.Share, "Share") { app.share(it) } }
        },
    ) {
        item(key = "header", contentType = "header") {
            val liquid = LocalLiquidMotion.current
            val pull = LocalPagePull.current
            // Where the bar's title sits, in the window: what the avatar and the name flow into.
            val barCenter = with(LocalDensity.current) { (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarContentHeight / 2).toPx() }
            Column {
                val banner = state.page?.banner
                // Most channels have a banner: its space is held while the page loads, so the
                // header doesn't drop by a banner's height when it lands.
                val bannered = banner != null || state.page == null && state.loading
                if (banner == null && bannered) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2.9f).shimmer())
                } else if (banner != null) {
                    Box(
                        Modifier.fillMaxWidth().then(
                            if (!liquid) Modifier else Modifier.graphicsLayer {
                                // Pulled down, the banner stretches to fill the gap from its bottom, as a rubber sheet.
                                val p = pull()
                                if (p > 0f && size.height > 0f) {
                                    val k = (size.height + p) / size.height
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                    scaleX = k
                                    scaleY = k
                                }
                            },
                        ),
                    ) {
                        RemoteImage(Thumbnails.sized(banner, 1280), Modifier.fillMaxWidth().aspectRatio(2.9f).graphicsLayer {
                            // A gentle parallax as the page scrolls up.
                            translationY = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset * 0.4f else 0f
                        }, RoundedCornerShape(0.dp), border = false)
                        val bg = MaterialTheme.colorScheme.background
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.35f to Color.Transparent, 0.7f to Color.Transparent, 1f to bg)))
                    }
                } else Spacer(Modifier.height(topBarContentPadding()))
                // Centred, as iOS sets a profile (Contacts, an artist in Music): the avatar ringed in
                // the page's colour where it overlaps the banner, the name and figures under it.
                val bg = MaterialTheme.colorScheme.background
                Box(Modifier.fillMaxWidth().offset(y = if (bannered) (-34).dp else 0.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .then(if (liquid) Modifier.flowIntoBar(barCenter, avatar = true) else Modifier)
                            .clip(CircleShape).background(bg).padding(4.dp),
                    ) { Avatar(channel?.avatar, 92.dp, name = channel?.title) }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER).offset(y = if (bannered) (-24).dp else 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (channel == null) {
                        SkeletonLine(0.5f, 24.dp)
                        Spacer(Modifier.height(8.dp))
                        SkeletonLine(0.36f)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = if (liquid) Modifier.flowIntoBar(barCenter, avatar = false) else Modifier) {
                            Text(channel.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
                            if (channel.verified) VerifiedBadge()
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            listOfNotNull(channel.handle, channel.subscribersText, channel.videosText).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center,
                        )
                        // As on YouTube: a short preview whose tap opens the full About sheet; centred, with "more ›".
                        // An empty About comes back as YouTube's own placeholder; that's a link, not a preview.
                        val preview = remember(channel.description) {
                            channel.description?.takeIf { it.isNotBlank() && !it.trim().equals("More about this channel", ignoreCase = true) }
                                ?.lineSequence()?.filter { it.isNotBlank() }?.take(2)?.joinToString(" ")
                        }
                        if (preview != null || state.page?.aboutToken != null) {
                            Spacer(Modifier.height(10.dp))
                            // At least 44dp to tap, however short the preview.
                            Column(
                                Modifier.clip(RoundedCornerShape(10.dp)).clickable { haptics.play(Haptic.Tap); model.loadAbout(); aboutOpen = true }
                                    .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (preview != null) {
                                    Text(
                                        preview,
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                    Text(if (preview != null) "more" else "About this channel", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W600), color = MaterialTheme.colorScheme.onSurface)
                                    Icon(OrielIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 1.dp).size(13.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val isSub = channel.id in subscribed
                        SubscribePill(isSub, { app.toggleSubscribe(channel) }, Modifier.widthIn(max = 360.dp).fillMaxWidth(), size = PillSize.Medium)
                    }
                }
                Spacer(Modifier.height(if (bannered) 0.dp else 18.dp))
            }
        }
        if (searching) item(key = "search") {
            SearchField(query, { query = it }, { model.search(it) }, Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp), placeholder = "Search ${channel?.title ?: "channel"}")
        }
        if (state.tabs.isNotEmpty()) item(key = "tabs") {
            TabStrip(
                state.tabs.map { it.title },
                selected = if (state.searchQuery != null) -1 else state.tabs.indexOfFirst { it.title == state.selectedTab },
                onSelect = { i -> model.selectTab(state.tabs[i]) },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (state.sortChips.isNotEmpty()) item(key = "sort") {
            ChipsRow(state.sortChips.mapIndexed { i, c -> c.copy(selected = if (state.selectedSort == null) c.selected || i == 0 && state.sortChips.none { it.selected } else c.title == state.selectedSort) }, onSelect = model::sort)
        }
        val feed = state.feed
        when {
            state.loading || state.loadingTab -> items(4, key = { "sk$it" }, contentType = { "skeleton" }) { VideoCardSkeleton(it) }
            state.error != null && feed == null -> item(key = "error", contentType = "message") { MessageState(state.error!!, action = "Try again", onAction = model::retry) }
            feed != null && feed.isEmpty -> item(key = "empty", contentType = "message") { MessageState(feed.message ?: if (state.searchQuery != null) "No results in this channel." else "Nothing here yet.") }
            feed != null -> {
                state.error?.let { e -> item(key = "notice") { NoticeRow(e, action = "Try again", onAction = model::retry) } }
                feedShelves(feed, keyPrefix = "ch:${state.selectedTab}:${state.searchQuery}")
            }
        }
        moreFooter(state.loadingMore, state.moreFailed, model::retryMore)
    }
    if (aboutOpen) state.page?.channel?.let { ch ->
        ChannelAboutSheet(ch, state.about, state.aboutFailed, onRetry = model::loadAbout, onDismiss = { aboutOpen = false })
    }
}

@Composable
private fun ChannelAboutSheet(channel: Channel, about: ChannelAbout?, failed: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalAppState.current
    OrielSheet(onDismiss) {
        val close = LocalSheetClose.current
        SheetTitle("About")
        when {
            about == null && failed -> MessageState("Couldn't load this channel's details.", icon = OrielIcons.Offline, action = "Try again", onAction = onRetry)
            about == null -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { ActivityIndicator(size = 24.dp) }
            else -> Column(Modifier.animateContentSize(spring(stiffness = 500f))) {
                // The About panel's own description, even when empty: the header's preview of an empty
                // one is YouTube's placeholder ("More about this channel"), not words to repeat here.
                val text = about.description
                if (!text.isNullOrBlank()) {
                    Text(
                        linkify(text, MaterialTheme.colorScheme.primary, onTime = {}, onLink = { url -> close { if (!app.openLink(url)) app.openExternally(url) } }),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(top = 4.dp),
                    )
                }
                if (about.links.isNotEmpty()) SettingsGroup("Links") {
                    about.links.forEachIndexed { i, link ->
                        if (i > 0) RowDivider()
                        SettingsRow(
                            link.title, subtitle = link.label, chevron = true,
                            onClick = { close { if (!app.openLink(link.url)) app.openExternally(link.url) } },
                            trailing = link.favicon?.let { f -> { RemoteImage(f, Modifier.size(20.dp), RoundedCornerShape(4.dp), border = false) } },
                        )
                    }
                }
                val facts = listOfNotNull(
                    about.url?.let { OrielIcons.Link to it },
                    (about.subscribersText ?: channel.subscribersText)?.let { OrielIcons.Person to it },
                    (about.videosText ?: channel.videosText)?.let { OrielIcons.Videos to it },
                    about.viewsText?.let { OrielIcons.Chart to it },
                    about.joinedText?.let { OrielIcons.Info to it },
                    about.country?.let { OrielIcons.Globe to it },
                )
                if (facts.isNotEmpty()) SettingsGroup("Channel details") {
                    facts.forEachIndexed { i, (icon, label) ->
                        if (i > 0) RowDivider()
                        SettingsRow(label, icon = icon)
                    }
                }
                Spacer(Modifier.height(12.dp))
                ActionPill("Share channel", OrielIcons.Share, { close { app.share(channel) } }, Modifier.padding(horizontal = PAGE_GUTTER).fillMaxWidth(), size = PillSize.Medium)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * The channel's avatar or name flowing into the bar as the page scrolls up, liquid motion on.
 * The avatar shrinks from as soon as it moves, as a drop drawn toward the bar; at the bar's
 * centre ([barCenter], px in the window) it stops there, spreads sideways and flattens as if
 * merging into it, and is gone. The name rises with the page, stops at the bar and shrinks to
 * the bar title's size as it fades, and the bar's own title takes over. Layer only: where it is
 * comes from its placement, read as it's drawn.
 */
@Composable
private fun Modifier.flowIntoBar(barCenter: Float, avatar: Boolean): Modifier {
    // State, so the list moving the header re-runs the layer block (and only that).
    val at = remember { mutableFloatStateOf(Float.NaN) }
    return this
        .onPlaced { c -> at.floatValue = c.positionInWindow().y + c.size.height / 2f }
        .graphicsLayer {
            val center = at.floatValue
            if (center.isNaN()) return@graphicsLayer
            // Its distance below the bar's centre: positive below it, negative once scrolled past.
            val d = center - barCenter
            if (avatar) {
                val reach = 110.dp.toPx()
                val t = (reach - d) / reach
                if (t <= 0f) return@graphicsLayer
                val k = lerp(1f, 0.28f, smoothstep(0f, 1f, t))
                val merge = smoothstep(1f, 1.3f, t)
                scaleX = k * (1f + 0.5f * merge)
                scaleY = k * (1f - 0.35f * merge)
                if (d < 0f) translationY = -d
                alpha = 1f - smoothstep(0.95f, 1.3f, t)
            } else {
                if (d >= 0f) return@graphicsLayer
                translationY = -d
                val t = -d / 90.dp.toPx()
                val k = lerp(1f, 0.8f, smoothstep(0f, 0.6f, t))
                scaleX = k
                scaleY = k
                alpha = 1f - smoothstep(0.35f, 1f, t)
            }
        }
}
