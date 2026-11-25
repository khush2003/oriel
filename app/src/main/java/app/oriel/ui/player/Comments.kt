package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import app.oriel.ui.theme.HeartRed
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.smoothstep
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Comment
import app.oriel.ui.AppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.ExpandableText
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.SkeletonLine
import app.oriel.ui.components.VerifiedBadge
import app.oriel.ui.theme.ArtworkPalette
import app.oriel.core.model.SortOption

/** Top / Newest as an iOS segmented control, in the page's own colours. */
@Composable
internal fun CommentSortRow(options: List<SortOption>, palette: ArtworkPalette, onSelect: (SortOption) -> Unit) {
    val selected = options.firstOrNull { it.selected } ?: options.firstOrNull() ?: return
    app.oriel.ui.components.SegmentedControl(
        options, selected, { it.title }, onSelect,
        modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(top = 4.dp, bottom = 6.dp).width((options.size * 104).dp),
        track = palette.onBackground.copy(alpha = 0.08f),
        thumb = if (app.oriel.ui.theme.LocalIsDark.current) palette.onBackground.copy(alpha = 0.24f) else Color.White,
        ink = palette.onBackground,
    )
}

/**
 * The comments, and a reply thread pushed over them the way iOS pushes a level: it slides in
 * from the trailing edge while the list drifts back under it, and slides away again on Back.
 */
@Composable
internal fun CommentsPanel(app: AppState, palette: ArtworkPalette, listState: androidx.compose.foundation.lazy.LazyListState, repliesState: androidx.compose.foundation.lazy.LazyListState) {
    val c by app.watch.comments.collectAsState()
    val reduceMotion = LocalReduceAnimation.current
    // Liquid, a reply thread grows out of the comment whose replies were tapped (as the panels do
    // out of their cards) and shrinks back into it on Back.
    val liquid = LocalLiquidMotion.current && !reduceMotion
    val rows = remember { HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>() }
    val container = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val threadFrom = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val openReplies: (Comment) -> Unit = { comment ->
        val box = container[0]?.takeIf { it.isAttached }
        val row = rows[comment.id]?.takeIf { it.isAttached }
        threadFrom[0] = if (box != null && row != null) box.localBoundingBoxOf(row, clipBounds = false) else null
        app.watch.openReplies(comment)
    }
    androidx.compose.animation.AnimatedContent(
        c.thread,
        contentKey = { it?.id },
        transitionSpec = {
            val push = targetState != null
            val slide = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold)
            val transform = if (reduceMotion) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            // The morph moves it: the content leaving is only held for as long as the shrink back takes.
            else if (liquid) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(1, delayMillis = 480))
            else (androidx.compose.animation.slideInHorizontally(slide) { if (push) it else -it / 3 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)))
                .togetherWith(androidx.compose.animation.slideOutHorizontally(slide) { if (push) -it / 3 else it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
            transform.apply { targetContentZIndex = if (push) 1f else 0f }
        },
        modifier = Modifier.fillMaxSize().onPlaced { container[0] = it }, label = "commentsPush",
    ) { thread ->
        val morph = if (liquid && thread != null) remember { PanelMorph().also { it.from = threadFrom[0] } } else null
        if (morph != null) {
            val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
            LaunchedEffect(leaving) { morph.run(!leaving) }
        }
        val state = if (thread != null) repliesState else listState
        // Held for this thread, so its replies stay put while it slides away on Back (which clears them).
        var held by remember(thread?.id) { mutableStateOf(c.replies) }
        if (thread != null && c.thread?.id == thread.id && held != c.replies) held = c.replies
        LaunchedEffect(thread?.id) { if (thread != null) repliesState.scrollToItem(0) }
        val nearEnd by remember(state) { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.layoutInfo.totalItemsCount - 5 } == true } }
        LaunchedEffect(nearEnd, c.continuation, c.repliesContinuation, thread?.id) { if (nearEnd) { if (thread != null) app.watch.loadMoreReplies() else app.watch.loadMoreComments() } }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize()
                .then(if (morph != null) Modifier.liquidPanel(morph, palette.background, palette.background).graphicsLayer { alpha = smoothstep(0.35f, 0.9f, morph.progress()) } else Modifier)
                .background(palette.background),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            if (thread != null) {
                item(key = "parent") { CommentRow(app, thread, palette, onReplies = null) }
                item(key = "divider") { Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.divider)) }
                val replies = held.distinctBy { it.id }
                itemsIndexed(replies, key = { _, r -> "r" + r.id }) { i, r -> CommentRow(app, r, palette, onReplies = null, indent = true, separator = i < replies.lastIndex) }
                if (c.loadingReplies) item { LoadingRow() }
                return@LazyColumn
            }
            if (c.sort.isNotEmpty()) item(key = "sort") { CommentSortRow(c.sort, palette, app.watch::sortComments) }
            when {
                c.loading && c.comments.isEmpty() -> items(6) { CommentSkeleton() }
                c.error != null && c.comments.isEmpty() -> item {
                    if (c.failed) MessageState(c.error!!, action = "Try again", onAction = app.watch::retryComments) else MessageState(c.error!!)
                }
                else -> {
                    val comments = c.comments.distinctBy { it.id }
                    itemsIndexed(comments, key = { _, it -> it.id }) { i, it ->
                        Box(Modifier.onPlaced { co -> rows[it.id] = co }) { CommentRow(app, it, palette, onReplies = openReplies, separator = i < comments.lastIndex) }
                    }
                }
            }
            if (c.loadingMore) item { LoadingRow() }
        }
    }
}

/**
 * One comment as a row of an iOS list: the avatar, the name in semibold with the time in grey,
 * the text, then quiet counts; replies as a tinted link. A hairline inset to the text separates rows.
 */
@Composable
internal fun CommentRow(app: AppState, comment: Comment, palette: ArtworkPalette, onReplies: ((Comment) -> Unit)?, indent: Boolean = false, separator: Boolean = false) {
    val avatar = if (indent) 26.dp else 34.dp
    val start = if (indent) 52.dp else PAGE_GUTTER
    // The replies chip's 44dp target makes its line 16dp taller than the chip's 28; the space
    // above and below it gives that back.
    val hasReplies = onReplies != null && comment.replyCount > 0 && comment.repliesToken != null
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = start, end = PAGE_GUTTER, top = 14.dp, bottom = if (hasReplies) 4.dp else 12.dp)) {
            // The avatar opens the channel; its 44dp target overhangs the drawn circle without moving anything.
            Box(Modifier.size(avatar), contentAlignment = Alignment.Center) {
                Avatar(comment.authorAvatar, avatar, name = comment.author, modifier = Modifier.clip(CircleShape))
                comment.authorChannelId?.let { channelId ->
                    Box(Modifier.requiredSize(44.dp).pressClickable(onClickLabel = "Open ${comment.author}'s channel") { app.openChannel(channelId) })
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (comment.pinned) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
                        Icon(OrielIcons.Pin, null, tint = palette.onBackgroundVariant, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pinned", style = MaterialTheme.typography.labelMedium, color = palette.onBackgroundVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.author, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                        color = if (comment.isCreator) palette.background else palette.onBackground,
                        modifier = (if (comment.isCreator) Modifier.clip(RoundedCornerShape(7.dp)).background(palette.onBackgroundVariant).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier).weight(1f, fill = false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (comment.verified) VerifiedBadge(palette.onBackgroundVariant)
                    comment.published?.let { Text("  $it", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W400), color = palette.onBackgroundVariant, maxLines = 1) }
                }
                Spacer(Modifier.height(3.dp))
                ExpandableText(comment.text, style = MaterialTheme.typography.bodyLarge, color = palette.onBackground, collapsedLines = 5)
                Spacer(Modifier.height(if (hasReplies) 1.dp else 9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(OrielIcons.Like, "Likes", tint = palette.onBackgroundVariant, modifier = Modifier.size(16.dp))
                    comment.likes?.let { Text(" $it", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W500), color = palette.onBackgroundVariant) }
                    Spacer(Modifier.width(20.dp))
                    Icon(OrielIcons.Dislike, null, tint = palette.onBackgroundVariant, modifier = Modifier.size(16.dp))
                    if (comment.hearted) Icon(OrielIcons.Heart, "Hearted by the creator", tint = HeartRed, modifier = Modifier.padding(start = 20.dp).size(15.dp))
                    Spacer(Modifier.weight(1f))
                    if (hasReplies && onReplies != null) {
                        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(
                            Modifier
                                .heightIn(min = 44.dp)
                                .clickable(interaction, indication = null) { onReplies(comment) }
                                .wrapContentHeight()
                                .clip(CircleShape).background(palette.accent.copy(alpha = 0.12f))
                                .indication(interaction, androidx.compose.foundation.LocalIndication.current)
                                .padding(start = 11.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${comment.replyCount} ${if (comment.replyCount == 1) "reply" else "replies"}",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600), color = palette.accent,
                            )
                            Icon(OrielIcons.Chevron, null, tint = palette.accent, modifier = Modifier.padding(start = 2.dp).size(13.dp))
                        }
                    }
                }
            }
        }
        if (separator) Box(Modifier.padding(start = start + avatar + 12.dp).fillMaxWidth().height(0.5.dp).background(palette.divider))
    }
}

@Composable
internal fun CommentSkeleton() {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 12.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkeletonLine(0.3f, height = 11.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonLine(0.9f)
            Spacer(Modifier.height(6.dp))
            SkeletonLine(0.6f)
        }
    }
}
