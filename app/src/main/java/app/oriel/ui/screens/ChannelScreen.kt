package app.oriel.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.ui.LocalAppState
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
import app.oriel.ui.components.feedShelves
import app.oriel.ui.components.cardSkeletons
import app.oriel.ui.LocalWindowLayout
import app.oriel.ui.fullBleed
import app.oriel.ui.PageWidth
import app.oriel.ui.components.topBarContentPadding

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
    val layout = LocalWindowLayout.current
    val columns = layout.columns(PageWidth.Feed)
    // A banner as wide as a tablet would stand taller than the rest of the header put together.
    val bannerRatio = if (layout.wide) 5.5f else 2.9f

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
            Column(Modifier.fullBleed()) {
                val banner = state.page?.banner
                // Most channels have a banner: its space is held while the page loads, so the
                // header doesn't drop by a banner's height when it lands.
                val bannered = banner != null || state.page == null && state.loading
                if (banner == null && bannered) {
                    Box(Modifier.fillMaxWidth().aspectRatio(bannerRatio).shimmer())
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
                        RemoteImage(Thumbnails.sized(banner, 1280), Modifier.fillMaxWidth().aspectRatio(bannerRatio).graphicsLayer {
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
                        SkeletonLine(0.5f, height = 24.dp)
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
            state.loading || state.loadingTab -> cardSkeletons(4, columns)
            state.error != null && feed == null -> item(key = "error", contentType = "message") { MessageState(state.error!!, action = "Try again", onAction = model::retry) }
            feed != null && feed.isEmpty -> item(key = "empty", contentType = "message") { MessageState(feed.message ?: if (state.searchQuery != null) "No results in this channel." else "Nothing here yet.") }
            feed != null -> {
                state.error?.let { e -> item(key = "notice") { NoticeRow(e, action = "Try again", onAction = model::retry) } }
                feedShelves(feed, keyPrefix = "ch:${state.selectedTab}:${state.searchQuery}", columns = columns, shortColumns = layout.shortColumns(PageWidth.Feed))
            }
        }
        moreFooter(state.loadingMore, state.moreFailed, model::retryMore)
    }
    if (aboutOpen) state.page?.channel?.let { ch ->
        ChannelAboutSheet(ch, state.about, state.aboutFailed, onRetry = model::loadAbout, onDismiss = { aboutOpen = false })
    }
}

/**
 * The avatar or name flowing into the bar on scroll. [barCenter]: the bar's centre, px in the
 * window. Layer only, read from placement as it draws.
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
