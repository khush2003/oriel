/*
 * Rows, cards and shelves in the manner of BitChord's Common.kt (GPL-3.0):
 * SongRow → VideoRow, ShelfCard → VideoCard/ShortCard, ShelfRow.
 */
package app.oriel.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.unit.IntOffset
import app.oriel.ui.theme.EaseOutCubic
import app.oriel.ui.theme.LocalReduceAnimation
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Channel
import app.oriel.core.model.Feed
import app.oriel.core.model.Item
import app.oriel.core.model.Playlist
import app.oriel.core.model.Post
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import app.oriel.ui.LocalAppState
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.util.Thumbnails
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import app.oriel.ui.theme.CardPress
import app.oriel.ui.theme.GlyphPress
import app.oriel.ui.theme.TilePress

/**
 * The cards' quieter line: who and when, "Channel · 2 days ago". The view count waits for
 * the watch page, except on a live stream, where how many are watching is the news.
 */
fun Video.calmLine(withChannel: Boolean = true): String = listOfNotNull(
    channel?.name?.takeIf { withChannel },
    if (isLive || publishedText == null) viewsText else publishedText,
).joinToString(" · ")

/** "Owner · Playlist · 12 videos". A mix's count text is just "Mix" again, so it's left out. */
fun playlistMeta(p: Playlist): String {
    val kind = if (p.isMix) "Mix" else "Playlist"
    return listOfNotNull(p.owner?.name, kind, p.countText?.takeUnless { it.equals(kind, ignoreCase = true) }).joinToString(" · ")
}

/** Artwork corners on cards: rounder than YouTube's, as Apple's apps round theirs. */
val CARD_CORNER = 18.dp
val ROW_CORNER = 12.dp

/** The ⋮ button: a 44dp target (it sits on a card, which would take a near miss), hung into the gutter so its glyph lines up with the page edge. */
@Composable
fun MoreButton(onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val haptics = rememberHaptics()
    Box(
        modifier.offset(x = 6.dp).size(44.dp).clickable(null, GlyphPress, role = Role.Button) {
            haptics.play(Haptic.Tap)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) { Icon(OrielIcons.More, "More", tint = tint, modifier = Modifier.size(20.dp)) }
}

// ---- videos ------------------------------------------------------------------------------

/**
 * The feed's card, in the manner of Apple's TV app: the frame inset in the page's gutters
 * with round corners, then the title and one quiet line of who and when. Avatars are left
 * to the channel and watch pages.
 */
@Composable
fun VideoCard(video: Video, modifier: Modifier = Modifier, showAvatar: Boolean = false, titleColor: Color = MaterialTheme.colorScheme.onSurface, metaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val app = LocalAppState.current
    val haptics = rememberHaptics()
    // A card gives under the finger rather than washing over, as Apple's TV app's do.
    CompositionLocalProvider(LocalIndication provides CardPress) {
    Column(
        modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = { app.openVideo(video) }, onLongClick = { app.showMenu(video) }, onPress = { app.connection.warmAhead(video, pressed = true) })
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
    ) {
        // A card that stays on screen gets its stream resolved, so the tap plays at once.
        LaunchedEffect(video.id) { delay(1_200); app.connection.warmAhead(video, pressed = false) }
        VideoThumbnail(video, Modifier.fillMaxWidth(), corner = CARD_CORNER, widthHint = 480.dp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.Top) {
            if (showAvatar && video.channel != null) {
                // A 44dp target around the 36dp avatar, drawn where the avatar always was: the
                // offset pulls it back over the gutter and the shorter spacer makes up the rest.
                Avatar(
                    video.channel?.avatar, 36.dp, name = video.channel?.name,
                    modifier = Modifier.offset(x = (-4).dp, y = (-2).dp).clip(CircleShape)
                        .clickable(null, GlyphPress, enabled = video.channel?.id != null, onClickLabel = "Open channel") { haptics.play(Haptic.Tap); app.openChannel(video.channel!!.id!!) }
                        .minTouchTarget(),
                )
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(video.calmLine(), style = MaterialTheme.typography.bodyMedium, color = metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MoreButton({ app.showMenu(video) }, tint = metaColor)
        }
    }
    }
}

/** A compact row: a 160dp frame beside two lines of title, channel and meta. Search results, related, playlists. */
@Composable
fun VideoRow(
    video: Video,
    modifier: Modifier = Modifier,
    index: Int? = null,
    active: Boolean = false,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    metaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    thumbWidth: Dp = 160.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val app = LocalAppState.current
    Row(
        modifier
            .fillMaxWidth()
            .background(if (active) activeTint.copy(alpha = 0.14f) else Color.Transparent)
            .longPressMenuClickable(onClick = { onClick?.invoke() ?: app.openVideo(video) }, onLongClick = { app.showMenu(video) }, onPress = { app.connection.warmAhead(video, pressed = true) })
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("$index", style = MaterialTheme.typography.labelMedium, color = metaColor, textAlign = TextAlign.Center, modifier = Modifier.width(24.dp))
            Spacer(Modifier.width(8.dp))
        }
        VideoThumbnail(video, Modifier.width(thumbWidth), corner = ROW_CORNER, widthHint = thumbWidth)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.titleMedium, color = if (active) activeTint else titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val meta = video.calmLine()
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else MoreButton({ app.showMenu(video) }, tint = metaColor)
    }
}

/** A carousel card: a 16:9 frame 260dp wide with two lines under it. */
@Composable
fun VideoShelfCard(video: Video, width: Dp = 260.dp) {
    val app = LocalAppState.current
    CompositionLocalProvider(LocalIndication provides TilePress) {
        Column(Modifier.width(width).longPressMenuClickable(onClick = { app.openVideo(video) }, onLongClick = { app.showMenu(video) })) {
            VideoThumbnail(video, Modifier.fillMaxWidth(), corner = CARD_CORNER - 2.dp, widthHint = width)
            Spacer(Modifier.height(10.dp))
            Text(video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(video.calmLine(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A Short: a clean 9:16 poster, its title beneath it rather than over a scrim. */
@Composable
fun ShortCard(video: Video, modifier: Modifier = Modifier, width: Dp? = 150.dp, titleColor: Color = MaterialTheme.colorScheme.onSurface) {
    val app = LocalAppState.current
    CompositionLocalProvider(LocalIndication provides TilePress) {
        Column(
            modifier
                .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
                .longPressMenuClickable(onClick = { app.openShort(video) }, onLongClick = { app.showMenu(video) }),
        ) {
            RemoteImage(Thumbnails.short(video), Modifier.fillMaxWidth().aspectRatio(9f / 16f), RoundedCornerShape(CARD_CORNER), border = true)
            Spacer(Modifier.height(8.dp))
            Text(video.title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

// ---- playlists, channels, posts --------------------------------------------------------

@Composable
private fun PlaylistFrame(playlist: Playlist, modifier: Modifier, corner: Dp, widthHint: Dp) {
    val video = Video(id = playlist.firstVideoId ?: "", title = "", thumbnail = playlist.thumbnail)
    Column(modifier) {
        // Two sleeves peeking above: the stack that says "more than one".
        Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))
        Spacer(Modifier.height(2.dp))
        VideoThumbnail(video.copy(durationSec = null), Modifier.fillMaxWidth(), corner = corner, widthHint = widthHint, badge = false) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (playlist.isMix) OrielIcons.Shuffle else OrielIcons.Playlist, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (playlist.isMix) "Mix" else playlist.countText?.substringBefore(' ') ?: "", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
fun PlaylistShelfCard(playlist: Playlist, width: Dp = 220.dp) {
    val app = LocalAppState.current
    CompositionLocalProvider(LocalIndication provides TilePress) {
        Column(Modifier.width(width).longPressMenuClickable(onClick = { app.openItem(playlist) }, onLongClick = { app.showMenu(playlist) })) {
            PlaylistFrame(playlist, Modifier.fillMaxWidth(), CARD_CORNER - 2.dp, width)
            Spacer(Modifier.height(10.dp))
            Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(playlist.owner?.name, playlist.countText).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PlaylistRow(playlist: Playlist, modifier: Modifier = Modifier) {
    val app = LocalAppState.current
    Row(
        modifier.fillMaxWidth().longPressMenuClickable(onClick = { app.openItem(playlist) }, onLongClick = { app.showMenu(playlist) }).padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistFrame(playlist, Modifier.width(160.dp), ROW_CORNER, 160.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(playlistMeta(playlist), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        MoreButton({ app.showMenu(playlist) })
    }
}

@Composable
fun ChannelRow(channel: Channel, modifier: Modifier = Modifier) {
    val app = LocalAppState.current
    val subscribed by app.graph.library.subscribedIds.collectAsState()
    Row(
        modifier.fillMaxWidth().longPressMenuClickable(onClick = { app.openChannel(channel.id) }, onLongClick = { app.showMenu(channel) }).padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp), contentAlignment = Alignment.Center) { Avatar(channel.avatar, 84.dp, name = channel.title) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (channel.verified) VerifiedBadge()
            }
            Text(listOfNotNull(channel.handle, channel.subscribersText).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            SubscribeButton(channel.id in subscribed) { app.toggleSubscribe(channel) }
        }
    }
}

@Composable
fun ChannelShelfCard(channel: Channel) {
    val app = LocalAppState.current
    val haptics = rememberHaptics()
    Column(Modifier.width(110.dp).clickable(null, TilePress, role = Role.Button) { haptics.play(Haptic.Tap); app.openChannel(channel.id) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(channel.avatar, 88.dp, name = channel.title)
        Spacer(Modifier.height(8.dp))
        Text(channel.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        channel.subscribersText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

@Composable
fun VerifiedBadge(tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(OrielIcons.Verified, "Verified", tint = tint, modifier = Modifier.padding(start = 4.dp).size(14.dp))
}

/** A community post's card. [inset] keeps it in the page's gutters; a carousel, which has its own, passes false. */
@Composable
fun PostCard(post: Post, modifier: Modifier = Modifier, inset: Boolean = true) {
    val app = LocalAppState.current
    Column(
        modifier.padding(horizontal = if (inset) PAGE_GUTTER else 0.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(CARD_CORNER)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(post.author?.avatar, 32.dp, name = post.author?.name)
            Spacer(Modifier.width(10.dp))
            Text(post.author?.name ?: "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            post.publishedText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(10.dp))
        ExpandableText(post.title, style = MaterialTheme.typography.bodyLarge, collapsedLines = 6)
        post.images.firstOrNull()?.let { url ->
            Spacer(Modifier.height(10.dp))
            RemoteImage(url, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(12.dp))
        }
        post.video?.let { v ->
            Spacer(Modifier.height(10.dp))
            VideoThumbnail(v, Modifier.fillMaxWidth().clickable(null, CardPress) { app.openVideo(v) }, corner = 12.dp)
        }
        if (post.pollChoices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            post.pollChoices.forEach { choice ->
                Text(choice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(listOfNotNull(post.likesText?.let { "$it likes" }, post.commentsText?.let { "$it comments" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- shelves -----------------------------------------------------------------------------

/** BitChord's ShelfRow: a snapping LazyRow with the page gutters, 14dp apart. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfRow(items: List<Item>, spacing: Dp = 14.dp, content: @Composable (Item) -> Unit) {
    val state = rememberLazyListState()
    val distinct = remember(items) { items.distinctBy { it.id } }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
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
 * Every [Feed] renders through here, one renderer per shelf kind: home,
 * subscriptions, topics, channel tabs, search. [compact] lays plain feeds out
 * as rows rather than full cards (search, channel tabs on narrow layouts).
 */
fun LazyListScope.feedShelves(feed: Feed, compact: Boolean = false, keyPrefix: String = "") {
    feed.shelves.forEachIndexed { s, shelf ->
        val k = "$keyPrefix$s"
        when (shelf.kind) {
            ShelfKind.FEED, ShelfKind.LIST -> {
                if (shelf.title != null) item(key = "h$k", contentType = "header") { SectionHeader(shelf.title!!, shelf.subtitle) }
                items(shelf.items.distinctBy { it.id }, key = { "i$k:${it.id}" }, contentType = { it::class.simpleName + compact }) { item ->
                    Box(feedItem()) { if (compact || shelf.kind == ShelfKind.LIST) ItemRow(item) else ItemCard(item) }
                }
                if (shelf.title != null) item(key = "g$k", contentType = "gap") { Spacer(Modifier.height(12.dp)) }
            }
            ShelfKind.CAROUSEL -> item(key = "c$k", contentType = "carousel") {
                Column(feedItem().padding(bottom = 18.dp)) {
                    shelf.title?.let { SectionHeader(it, shelf.subtitle) }
                    ShelfRow(shelf.items) { ItemCarouselCard(it) }
                }
            }
            ShelfKind.SHORTS -> item(key = "s$k", contentType = "shorts") {
                Column(feedItem().padding(top = 6.dp, bottom = 18.dp)) {
                    SectionHeader(shelf.title ?: "Shorts", icon = OrielIcons.ShortsSelected)
                    ShelfRow(shelf.items, spacing = 12.dp) { (it as? Video)?.let { v -> ShortCard(v, width = 150.dp) } }
                }
            }
            ShelfKind.SHORTS_GRID -> {
                val rows = shelf.items.filterIsInstance<Video>().chunked(2)
                items(rows.size, key = { "r$k:$it" }, contentType = { "shortsRow" }) { r ->
                    Row(feedItem().padding(horizontal = PAGE_GUTTER, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows[r].forEach { v -> ShortCard(v, Modifier.weight(1f), width = null) }
                        if (rows[r].size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * A feed row's arrival and moves: new pages and fresh content fade in over the skeletons
 * rather than popping, and rows shifted by an insert glide on a spring. Removals just go.
 */
@Composable
fun LazyItemScope.feedItem(): Modifier =
    if (LocalReduceAnimation.current) Modifier
    else Modifier.animateItem(
        fadeInSpec = tween(260, easing = EaseOutCubic),
        placementSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
        fadeOutSpec = null,
    )

/**
 * List keys that survive moves and removals: the video's id, with a count for repeats
 * (a playlist can hold a video twice). Position-based keys re-key every row after an edit,
 * so nothing could animate.
 */
fun rowKeys(videos: List<Video>): List<String> {
    val seen = HashMap<String, Int>()
    return videos.map { v -> val n = seen.merge(v.id, 1, Int::plus)!!; if (n == 1) v.id else "${v.id}#$n" }
}
