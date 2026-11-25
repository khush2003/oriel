/*
 * Rows, cards and shelves in the manner of BitChord's Common.kt (GPL-3.0):
 * SongRow → VideoRow, ShelfCard → VideoCard/ShortCard, ShelfRow.
 */
package app.oriel.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Channel
import app.oriel.core.model.Feed
import app.oriel.core.model.Item
import app.oriel.core.model.Playlist
import app.oriel.core.model.Post
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import app.oriel.ui.LocalPageInset
import app.oriel.ui.fullBleed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

// ---- shelves -----------------------------------------------------------------------------

/** BitChord's ShelfRow: a snapping LazyRow with the page gutters, 14dp apart. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfRow(items: List<Item>, spacing: Dp = 14.dp, content: @Composable (Item) -> Unit) {
    val state = rememberLazyListState()
    val distinct = remember(items) { items.distinctBy { it.id } }
    // On a centred page the row still scrolls to the screen's edges, its first card lined up with the page.
    val inset = LocalPageInset.current
    LazyRow(
        state = state,
        modifier = Modifier.fullBleed(),
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER + inset),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        flingBehavior = rememberSnapFlingBehavior(state),
    ) {
        items(distinct, key = { it.id }) { content(it) }
    }
}

@Composable
fun ItemCarouselCard(item: Item) {
    when (item) {
        is Video -> if (item.isShort) ShortCard(item) else VideoShelfCard(item)
        is Playlist -> PlaylistShelfCard(item)
        is Channel -> ChannelShelfCard(item)
        is Post -> PostCard(item, Modifier.width(300.dp), inset = false)
    }
}

@Composable
fun ItemRow(item: Item) {
    when (item) {
        is Video -> VideoRow(item)
        is Playlist -> PlaylistRow(item)
        is Channel -> ChannelRow(item)
        is Post -> PostCard(item)
    }
}

@Composable
fun ItemCard(item: Item) {
    when (item) {
        is Video -> VideoCard(item)
        is Playlist -> PlaylistRow(item)
        is Channel -> ChannelRow(item)
        is Post -> PostCard(item)
    }
}

/**
 * Items laid [columns] to a row. Each row is one list item, so the page keeps its lazy list, its
 * scroll state and its pull to refresh; one column is the plain list. A short last row leaves its
 * cells the same width as the rest.
 */
fun <T> LazyListScope.gridItems(
    items: List<T>,
    columns: Int,
    key: (T) -> Any,
    contentType: (T) -> Any? = { null },
    cell: @Composable LazyItemScope.(T) -> Unit,
) {
    if (columns <= 1) {
        items(items, key = key, contentType = contentType) { cell(it) }
        return
    }
    val rows = items.chunked(columns)
    items(rows, key = { "row:" + key(it.first()) }, contentType = { "row$columns:" + contentType(it.first()) }) { row ->
        Row(feedItem(), verticalAlignment = Alignment.Top) {
            row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Placeholder cards while a feed loads, laid out in the columns the feed will have. */
fun LazyListScope.cardSkeletons(count: Int, columns: Int) =
    gridItems(List(maxOf(count, columns * 2)) { it }, columns, key = { "sk$it" }, contentType = { "skeleton" }) { VideoCardSkeleton(it) }

/**
 * Every [Feed] renders through here, one renderer per shelf kind: home,
 * subscriptions, topics, channel tabs, search. [compact] lays plain feeds out
 * as rows rather than full cards.
 * On a wide window video cards sit [columns] to a row; channels, playlists and posts keep a row of their own.
 */
fun LazyListScope.feedShelves(feed: Feed, compact: Boolean = false, keyPrefix: String = "", columns: Int = 1, shortColumns: Int = 2) {
    val feedShorts = feed.shelves.flatMap { it.items }.filter { it is Video && it.isShort }.map { it.id }.distinct()
    feed.shelves.forEachIndexed { s, shelf ->
        val k = "$keyPrefix$s"
        // A shelf of shorts carries on through its own; a list with shorts among its videos, through the list's.
        val shortsFeed = if (shelf.kind == ShelfKind.SHORTS || shelf.kind == ShelfKind.CAROUSEL) shelf.items.filter { it is Video && it.isShort }.map { it.id } else feedShorts
        when (shelf.kind) {
            ShelfKind.FEED, ShelfKind.LIST -> {
                if (shelf.title != null) item(key = "h$k", contentType = "header") { SectionHeader(shelf.title!!, shelf.subtitle) }
                val asRows = compact || shelf.kind == ShelfKind.LIST
                val perRow = if (asRows) 1 else columns
                // Runs of videos share rows; anything else stands alone between them.
                var run = ArrayList<Item>()
                fun flush() {
                    if (run.isEmpty()) return
                    gridItems(run, perRow, key = { "i$k:${it.id}" }, contentType = { it::class.simpleName + compact }) { item ->
                        CompositionLocalProvider(LocalShortsFeed provides shortsFeed) { Box(if (perRow > 1) Modifier else feedItem()) { if (asRows) ItemRow(item) else ItemCard(item) } }
                    }
                    run = ArrayList()
                }
                shelf.items.distinctBy { it.id }.forEach { entry ->
                    if (perRow > 1 && entry !is Video) {
                        flush()
                        item(key = "i$k:${entry.id}", contentType = entry::class.simpleName + compact) { CompositionLocalProvider(LocalShortsFeed provides shortsFeed) { Box(feedItem()) { if (asRows) ItemRow(entry) else ItemCard(entry) } } }
                    } else run.add(entry)
                }
                flush()
                if (shelf.title != null) item(key = "g$k", contentType = "gap") { Spacer(Modifier.height(12.dp)) }
            }
            ShelfKind.CAROUSEL -> item(key = "c$k", contentType = "carousel") {
                CompositionLocalProvider(LocalShortsFeed provides shortsFeed) {
                    Column(feedItem().padding(bottom = 18.dp)) {
                        shelf.title?.let { SectionHeader(it, shelf.subtitle) }
                        ShelfRow(shelf.items) { ItemCarouselCard(it) }
                    }
                }
            }
            ShelfKind.SHORTS -> item(key = "s$k", contentType = "shorts") {
                CompositionLocalProvider(LocalShortsFeed provides shortsFeed) {
                    Column(feedItem().padding(top = 6.dp, bottom = 18.dp)) {
                        SectionHeader(shelf.title ?: "Shorts", icon = OrielIcons.ShortsSelected)
                        ShelfRow(shelf.items, spacing = 12.dp) { (it as? Video)?.let { v -> ShortCard(v, width = 150.dp) } }
                    }
                }
            }
            ShelfKind.SHORTS_GRID -> {
                val rows = shelf.items.filterIsInstance<Video>().chunked(shortColumns)
                items(rows.size, key = { "r$k:$it" }, contentType = { "shortsRow" }) { r ->
                    CompositionLocalProvider(LocalShortsFeed provides shortsFeed) {
                        Row(feedItem().padding(horizontal = PAGE_GUTTER, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rows[r].forEach { v -> ShortCard(v, Modifier.weight(1f), width = null) }
                            repeat(shortColumns - rows[r].size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * List keys that survive moves and removals: the video's id, with a count for repeats
 * (a playlist can hold a video twice). Position-based keys re-key every row after an edit,
 * so nothing could animate.
 */
fun rowKeys(videos: List<Video>): List<String> {
    val seen = HashMap<String, Int>()
    return videos.map { v -> val n = seen.merge(v.id, 1, Int::plus)!!; if (n == 1) v.id else "${v.id}#$n" }
}
