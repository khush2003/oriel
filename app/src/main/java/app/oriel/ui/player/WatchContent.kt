package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer

import app.oriel.ui.components.VideoCardSkeleton
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.smoothstep
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.util.lerp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Item
import app.oriel.core.model.Video
import app.oriel.ui.LocalWindowLayout
import app.oriel.ui.AppState
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.SectionHeader
import app.oriel.ui.components.ShortCard
import app.oriel.ui.components.VideoCard
import app.oriel.ui.components.VideoRow
import app.oriel.ui.components.ItemRow
import app.oriel.ui.theme.ArtworkPalette
import app.oriel.ui.components.feedItem

private enum class Panel { None, Description, Comments }

/**
 * The watch page's two panes on a wide landscape screen: the page lays out as the video's
 * column ([primaryWidth] wide, the picture [videoHeight] tall above its details) beside a column
 * of what to watch next.
 */
class WatchSplit(val videoHeight: androidx.compose.ui.unit.Dp, val primaryWidth: androidx.compose.ui.unit.Dp)

/**
 * The watch page under the video: title and stats, the channel, the row of
 * actions, the comments teaser, chapters, the playlist being played, and
 * what to watch next. Description and comments open as panels over it,
 * leaving the video playing above.
 */
@Composable
fun WatchContent(app: AppState, palette: ArtworkPalette, modifier: Modifier = Modifier, split: WatchSplit? = null) {
    val state by app.watch.state.collectAsState()
    val video = state.video ?: return
    val page = state.page
    var panel by rememberSaveable(video.id) { mutableStateOf(Panel.None) }
    val listState = rememberLazyListState()
    val relatedState = rememberLazyListState()
    LaunchedEffect(video.id) { listState.scrollToItem(0); relatedState.scrollToItem(0) }
    // The list that ends in what to watch next: the page's own, or the second pane's.
    val endList = if (split != null) relatedState else listState
    val nearEnd by remember(endList) { derivedStateOf { endList.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= endList.layoutInfo.totalItemsCount - 4 } == true } }
    // Wide screens list what's next as rows, as YouTube's tablet layout does; a full-width card would be huge.
    val relatedRows = split != null || LocalWindowLayout.current.wide
    LaunchedEffect(nearEnd, state.relatedContinuation) { if (nearEnd) app.watch.loadMoreRelated() }
    val relatedGroups = remember(state.related) { groupRelated(state.related) }
    val playlist = page?.playlist
    var playlistOpen by rememberSaveable(playlist?.id) { mutableStateOf(false) }
    val current by app.connection.current.collectAsState()
    // Liquid, a panel grows out of the card that opened it and shrinks back into it (PanelMorph).
    val liquid = LocalLiquidMotion.current && !LocalReduceAnimation.current
    val morph = remember { PanelMorph() }
    morph.cardCorner = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    fun open(p: Panel, from: String) {
        morph.from = morph.boundsOf(from)
        morph.fromCorner = if (from == "title") 0f else morph.cardCorner
        panel = p
    }

    Box(modifier.fillMaxSize()) {
    Box((if (split != null) Modifier.padding(top = split.videoHeight).width(split.primaryWidth).fillMaxHeight() else Modifier.fillMaxSize()).onPlaced { morph.box = it }) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(
                // The page under a panel gives a little as it grows over it.
                if (liquid) Modifier.graphicsLayer {
                    val k = 1f - 0.03f * morph.progress()
                    scaleX = k
                    scaleY = k
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                } else Modifier,
            ),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "title") { TitleBlock(video, palette, Modifier.onPlaced { morph.cards["title"] = it }) { open(Panel.Description, "title") } }
            item(key = "channel") { ChannelLine(app, video, page?.subscribersText, palette) }
            item(key = "actions") { ActionsRow(app, video, page?.likeCount, state.votes, palette) }
            item(key = "details") { DetailsCard(video, page, palette, Modifier.onPlaced { morph.cards["details"] = it }) { open(Panel.Description, "details") } }
            if (page != null && (page.commentsToken != null || page.commentsCount != null)) item(key = "comments") {
                CommentsTeaser(page.commentsCount, page.commentTeaser, page.commentTeaserAvatar, palette, Modifier.onPlaced { morph.cards["comments"] = it }) {
                    open(Panel.Comments, "comments")
                    if (app.watch.comments.value.page == null) app.watch.loadComments()
                }
            }
            if (playlist != null) playlistPanelItems(app, playlist, current?.id, playlistOpen, { playlistOpen = !playlistOpen }, palette)
            if (!page?.chapters.isNullOrEmpty()) item(key = "chapters") { ChaptersShelf(app, page.chapters, palette) }
            if (split == null) {
                when {
                    state.loading -> items(4, key = { "rsk$it" }, contentType = { "skeleton" }) { RelatedSkeleton(relatedRows) }
                    state.error != null && page == null -> item(key = "error") { MessageState(state.error!!, action = "Try again", onAction = { app.watch.retry() }) }
                    else -> relatedItems(relatedGroups, palette, relatedRows)
                }
                if (state.loadingMore) item { LoadingRow() }
            }
        }

        // Back steps out one level at a time, as on YouTube: a reply thread, then the panel, and
        // only then (PlayerHost's handler) the watch page itself.
        val comments by app.watch.comments.collectAsState()
        // Not in fullscreen, where the panel is hidden: there Back leaves fullscreen (PlayerHost's handler).
        BackHandler(enabled = panel != Panel.None && !app.sheet.fullscreen) {
            if (panel == Panel.Comments && comments.thread != null) app.watch.closeReplies() else panel = Panel.None
        }
        // The last panel shown, kept through the slide-out so it doesn't turn into the other one on its way down.
        var shownPanel by remember { mutableStateOf(Panel.Comments) }
        if (panel != Panel.None && panel != shownPanel) shownPanel = panel
        val panelBody: @Composable () -> Unit = {
            val inThread = shownPanel == Panel.Comments && comments.thread != null
            // Each panel's list, kept here so the header's hairline can follow its scroll, and so
            // the comments keep their place while a reply thread is pushed over them.
            val commentsList = rememberLazyListState()
            val repliesList = rememberLazyListState()
            val descriptionList = rememberLazyListState()
            val shownList = when {
                shownPanel == Panel.Description -> descriptionList
                inThread -> repliesList
                else -> commentsList
            }
            val scrolled by remember(shownList) { derivedStateOf { shownList.firstVisibleItemIndex > 0 || shownList.firstVisibleItemScrollOffset > 4 } }
            Panel(
                palette, title = when { shownPanel == Panel.Description -> "Description"; inThread -> "Replies"; else -> "Comments" },
                count = if (shownPanel == Panel.Comments && !inThread) page?.commentsCount else null,
                scrolled = scrolled,
                onBack = if (inThread) app.watch::closeReplies else null,
                onClose = { panel = Panel.None },
                morph = if (liquid) morph else null,
            ) {
                if (shownPanel == Panel.Comments) CommentsPanel(app, palette, commentsList, repliesList) else DescriptionPanel(app, video, page, palette, descriptionList)
            }
        }
        if (liquid) {
            val open = panel != Panel.None
            LaunchedEffect(open) { morph.run(open) }
            if (morph.shown) {
                // The card's own fill: the page's ink at the card's veil.
                val tint = palette.onBackground.copy(alpha = 0.065f).compositeOver(palette.background)
                Box(Modifier.fillMaxSize().liquidPanel(morph, palette.background, tint)) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val pc = morph.progress()
                            alpha = smoothstep(0.55f, 0.95f, pc)
                            val k = lerp(0.94f, 1f, smoothstep(0.4f, 1f, pc))
                            scaleX = k
                            scaleY = k
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                            translationY = morph.pull()
                        },
                    ) { panelBody() }
                }
            }
        } else {
            val slide = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold)
            AnimatedVisibility(panel != Panel.None, enter = enterMotion(slideInVertically(slide) { it }), exit = exitMotion(slideOutVertically(slide) { it }), modifier = Modifier.fillMaxSize()) {
                panelBody()
            }
        }
    }
    if (split != null) {
        LazyColumn(
            state = relatedState,
            modifier = Modifier.fillMaxSize().padding(start = split.primaryWidth),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            when {
                state.loading -> items(6, key = { "rsk$it" }, contentType = { "skeleton" }) { RelatedSkeleton(true) }
                state.error != null && page == null -> item(key = "error") { MessageState(state.error!!, action = "Try again", onAction = { app.watch.retry() }) }
                else -> relatedItems(relatedGroups, palette, rows = true)
            }
            if (state.loadingMore) item { LoadingRow() }
        }
    }
    }
}

/** Consecutive Shorts gathered into a shelf, the rest one by one. Keys are ids: a video YouTube lists twice must render once, not crash the list. */
private fun groupRelated(related: List<Item>): List<List<Item>> {
    val groups = ArrayList<List<Item>>()
    related.distinctBy { it.id }.forEach { item ->
        val short = item is Video && item.isShort
        val last = groups.lastOrNull()
        if (short && last != null && (last.first() as? Video)?.isShort == true) groups[groups.lastIndex] = last + item else groups += listOf(item)
    }
    return groups
}

private fun androidx.compose.foundation.lazy.LazyListScope.relatedItems(groups: List<List<Item>>, palette: ArtworkPalette, rows: Boolean) {
    groups.forEachIndexed { i, group ->
        val first = group.first()
        if (first is Video && first.isShort) {
            item(key = "shorts$i:${first.id}", contentType = "shorts") {
                Column(feedItem().padding(vertical = 8.dp)) {
                    SectionHeader("Shorts", icon = OrielIcons.ShortsSelected, color = palette.onBackground)
                    LazyRow(contentPadding = PaddingValues(horizontal = PAGE_GUTTER), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(group, key = { it.id }) { (it as? Video)?.let { v -> ShortCard(v, width = 140.dp, titleColor = palette.onBackground) } }
                    }
                }
            }
        } else {
            item(key = "rel:${first.id}", contentType = first::class.simpleName) {
                Box(feedItem()) {
                    if (first !is Video) ItemRow(first)
                    else if (rows) VideoRow(first, titleColor = palette.onBackground, metaColor = palette.onBackgroundVariant, thumbWidth = 150.dp)
                    else VideoCard(first, titleColor = palette.onBackground, metaColor = palette.onBackgroundVariant)
                }
            }
        }
    }
}

@Composable
private fun RelatedSkeleton(rows: Boolean) = if (rows) app.oriel.ui.components.VideoRowSkeleton(0) else VideoCardSkeleton(0)
