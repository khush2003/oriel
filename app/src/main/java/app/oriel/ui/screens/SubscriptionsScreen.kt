package app.oriel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.oriel.core.model.Chip
import app.oriel.ui.components.ChipsRow
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.AppGraph
import app.oriel.core.YouTube
import app.oriel.core.model.Feed
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import app.oriel.data.HomeComposer
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.TextAction
import app.oriel.ui.components.VideoCardSkeleton
import app.oriel.ui.components.feedShelves
import app.oriel.ui.navigation.ChannelsRoute
import app.oriel.ui.navigation.LoginRoute
import app.oriel.ui.navigation.Tab
import app.oriel.util.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Subscriptions: YouTube's own feed when signed in; signed out, the latest
 * uploads of the channels followed in Oriel, fetched in parallel and merged
 * newest first.
 */
@Composable
fun SubscriptionsScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val signedIn by graph.account.signedIn.collectAsState()
    val library by graph.library.state.collectAsState()
    val channels = library.subscriptions
    val model: FeedModel = viewModel(key = "subs:$signedIn") {
        FeedModel(graph, cacheKey = "subs-$signedIn", load = { fresh ->
            if (signedIn) graph.yt.browse(YouTube.SUBSCRIPTIONS, fresh = fresh) else localSubscriptionFeed(graph, fresh = fresh)
        }, maxAgeMs = 5 * 60_000L)
    }
    val state by model.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(SubsFilter.All) }
    val fullFeed = state.feed
    val feed = remember(fullFeed, filter) { fullFeed?.let { filter.apply(it) } }
    val filters = remember(fullFeed) { SubsFilter.entries.filter { it != SubsFilter.Live || fullFeed?.items?.any { i -> (i as? Video)?.isLive == true } == true } }
    // The channel strip leads with whoever uploaded last, as YouTube's does.
    val strip = remember(channels, fullFeed) {
        val order = HashMap<String, Int>()
        fullFeed?.items?.forEachIndexed { i, item -> (item as? Video)?.channel?.id?.let { order.putIfAbsent(it, i) } }
        channels.distinctBy { it.id }.sortedBy { order[it.id] ?: Int.MAX_VALUE }.take(40)
    }
    // Signed out the feed is made from the channels followed here: following or dropping one remakes it.
    // Kept on the model, which outlives the tab: a channel followed from elsewhere while this
    // tab was away used to go unnoticed, and so did following one and dropping another.
    val followedIds = remember(channels) { channels.map { it.id }.toSet() }
    LaunchedEffect(followedIds) {
        val before = model.builtFrom
        model.builtFrom = followedIds
        if (!signedIn && before != null && before != followedIds) model.reload(fresh = false, quiet = true)
    }

    PageScaffold(
        title = "Subscriptions",
        tab = Tab.SUBSCRIPTIONS,
        refreshing = state.refreshing,
        onRefresh = { model.reload(true) },
        onNearEnd = model::loadMore,
        onShown = model::refreshIfStale,
    ) {
        if (channels.isNotEmpty()) item(key = "channels", contentType = "channels") {
            LazyRow(contentPadding = PaddingValues(horizontal = PAGE_GUTTER, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(strip, key = { it.id }) { ch ->
                    Column(Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable { app.openChannel(ch.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(ch.avatar, 58.dp, name = ch.title)
                        Spacer(Modifier.height(4.dp))
                        Text(ch.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                // Every channel, as the strip's last bubble.
                item(key = "all") {
                    Column(Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable { app.navigate(ChannelsRoute) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(OrielIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("All", style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        if (fullFeed != null && !fullFeed.isEmpty) item(key = "filters", contentType = "chips") {
            ChipsRow(filters.map { Chip(it.title, selected = it == filter) }, onSelect = { c -> filter = filters.first { it.title == c.title } })
        }
        refreshNotice(state, onRetry = { model.reload(true) })
        when {
            !signedIn && channels.isEmpty() -> item {
                Column {
                    MessageState("Follow channels and their new videos gather here.", icon = OrielIcons.Subscriptions)
                    SignInCard(onSignIn = { app.navigate(LoginRoute) }, onDismiss = null, text = "Or sign in to bring your YouTube subscriptions with you.")
                }
            }
            feed == null && state.loading -> items(4, contentType = { "skeleton" }) { VideoCardSkeleton(it) }
            feed == null && state.error != null -> item { MessageState(state.error!!, action = "Try again", onAction = { model.reload() }) }
            feed != null && feed.isEmpty -> item(key = "empty") { MessageState(feed.message ?: if (filter == SubsFilter.All) "No new videos from your channels." else "Nothing ${filter.empty} from your channels.") }
            feed != null -> feedShelves(feed, keyPrefix = "subs")
        }
        feedFooter(state, onRetry = model::loadMore)
    }
}

/** YouTube's Subscriptions filters, applied here to the feed already loaded. */
private enum class SubsFilter(val title: String, val empty: String) {
    All("All", ""), Today("Today", "new today"), Videos("Videos", "but Shorts"), Shorts("Shorts", "in Shorts"), Live("Live", "live");

    fun apply(feed: Feed): Feed = when (this) {
        All -> feed
        Shorts -> {
            val shorts = feed.items.filterIsInstance<Video>().filter { it.isShort }.distinctBy { it.id }
            feed.copy(shelves = if (shorts.isEmpty()) emptyList() else listOf(Shelf(items = shorts, kind = ShelfKind.SHORTS_GRID)))
        }
        else -> feed.copy(shelves = feed.shelves.filter { it.kind != ShelfKind.SHORTS && it.kind != ShelfKind.SHORTS_GRID }.map { shelf ->
            shelf.copy(items = shelf.items.filter { item ->
                val v = item as? Video ?: return@filter this == Videos
                !v.isShort && when (this) {
                    Today -> (Format.ageSeconds(v.publishedText) ?: Long.MAX_VALUE) < 86_400L || v.isLive
                    Live -> v.isLive
                    else -> true
                }
            })
        }.filter { it.items.isNotEmpty() })
    }
}

/** The newest uploads of the locally followed channels, newest first. */
suspend fun localSubscriptionFeed(graph: AppGraph, limit: Int = 30, fresh: Boolean = false): Feed = coroutineScope {
    val gate = Semaphore(6)
    val channels = graph.library.state.value.subscriptions.take(limit)
    val uploads = channels.map { ch ->
        async {
            gate.withPermit {
                runCatching { graph.yt.channel(ch.id, HomeComposer.VIDEOS_TAB, fresh = fresh).feed.items.filterIsInstance<Video>().take(8) }.getOrDefault(emptyList())
                    .map { v -> if (v.channel == null) v.copy(channel = ch.ref()) else v }
            }
        }
    }.awaitAll().flatten()
    val (shorts, videos) = uploads.distinctBy { it.id }.partition { it.isShort }
    val sorted = videos.sortedBy { Format.ageSeconds(it.publishedText) ?: Long.MAX_VALUE }
    val shelves = buildList {
        if (sorted.isNotEmpty()) add(Shelf(items = sorted.take(6), kind = ShelfKind.FEED))
        if (shorts.isNotEmpty()) add(Shelf(title = "Shorts", items = shorts.take(12), kind = ShelfKind.SHORTS))
        if (sorted.size > 6) add(Shelf(items = sorted.drop(6), kind = ShelfKind.FEED))
    }
    Feed(shelves = shelves)
}
