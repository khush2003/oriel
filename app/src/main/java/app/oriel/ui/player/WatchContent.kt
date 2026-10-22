package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.interaction.collectIsPressedAsState

import app.oriel.core.extras.ReturnDislike
import app.oriel.core.model.Channel
import app.oriel.core.model.PlaylistPanel
import app.oriel.core.model.WatchPage
import app.oriel.ui.components.VideoCardSkeleton
import app.oriel.ui.components.GROUP_CORNER
import app.oriel.ui.components.GroupShape
import app.oriel.ui.components.ROW_CORNER
import app.oriel.ui.theme.HeartRed
import app.oriel.ui.theme.LiveRed
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.blobRectPath
import app.oriel.ui.theme.rubber
import app.oriel.ui.theme.smoothstep
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.util.lerp
import app.oriel.ui.theme.motion
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentHeight
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Chapter
import app.oriel.core.model.Comment
import app.oriel.core.model.Item
import app.oriel.core.model.LikeStatus
import app.oriel.core.model.Video
import app.oriel.playback.QueueContext
import app.oriel.ui.AppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.ExpandableText
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.components.SectionHeader
import app.oriel.ui.components.ShortCard
import app.oriel.ui.components.SkeletonLine
import app.oriel.ui.components.SubscribeButton
import app.oriel.ui.components.VerifiedBadge
import app.oriel.ui.components.VideoCard
import app.oriel.ui.components.VideoRow
import app.oriel.ui.components.ItemRow
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.ArtworkPalette
import app.oriel.ui.components.feedItem
import app.oriel.util.Format
import app.oriel.util.Thumbnails
import app.oriel.core.model.SortOption

private enum class Panel { None, Description, Comments }

/**
 * The watch page under the video: title and stats, the channel, the row of
 * actions, the comments teaser, chapters, the playlist being played, and
 * what to watch next. Description and comments open as panels over it,
 * leaving the video playing above.
 */
@Composable
fun WatchContent(app: AppState, palette: ArtworkPalette, modifier: Modifier = Modifier) {
    val state by app.watch.state.collectAsState()
    val video = state.video ?: return
    val page = state.page
    var panel by rememberSaveable(video.id) { mutableStateOf(Panel.None) }
    val listState = rememberLazyListState()
    LaunchedEffect(video.id) { listState.scrollToItem(0) }
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 4 } == true } }
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

    Box(modifier.fillMaxSize().onPlaced { morph.box = it }) {
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
            item(key = "title") { TitleBlock(video, page, palette, Modifier.onPlaced { morph.cards["title"] = it }) { open(Panel.Description, "title") } }
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
            when {
                state.loading -> items(4, key = { "rsk$it" }, contentType = { "skeleton" }) { RelatedSkeleton() }
                state.error != null && page == null -> item(key = "error") { MessageState(state.error!!, action = "Try again", onAction = { app.watch.retry() }) }
                else -> relatedItems(relatedGroups, palette)
            }
            if (state.loadingMore) item { LoadingRow() }
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
}

/**
 * The liquid panel's flight, as the mock's sheet: from the tapped card's rect and radius, the
 * height spring (Sp.SheetH) leading the width's (Sp.SheetW), the sides and top swelling
 * mid-flight, the card's fill turning into the page's; closed, it shrinks back into the card on
 * Sp.SheetClose. A drag down on it shrinks it toward the card (by up to 42%) as it follows the
 * finger; a drag up rubber-bands its top. Read only in the draw phase.
 */
@androidx.compose.runtime.Stable
internal class PanelMorph {
    val pw = androidx.compose.animation.core.Animatable(0f)
    val ph = androidx.compose.animation.core.Animatable(0f)
    /** The panel's drag, px: down positive, up negative (rubber-banded when drawn). */
    val drag = androidx.compose.animation.core.Animatable(0f)
    /** The speed it was let go at when a drag closed it, px per second. */
    var closeVelocity = 0f
    var shown by mutableStateOf(false)
    var from: androidx.compose.ui.geometry.Rect? = null
    var fromCorner = 0f
    var cardCorner = 0f
    var box: androidx.compose.ui.layout.LayoutCoordinates? = null
    val cards = HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>()
    val path = androidx.compose.ui.graphics.Path()
    var liftMax = 0f

    fun boundsOf(key: String): androidx.compose.ui.geometry.Rect? {
        val b = box?.takeIf { it.isAttached } ?: return null
        val c = cards[key]?.takeIf { it.isAttached } ?: return null
        return b.localBoundingBoxOf(c, clipBounds = false)
    }

    /** How far it's dragged down, which the content follows. */
    fun pull() = drag.value.coerceAtLeast(0f)

    /** How grown it is, 0 to 1, after the shrink a drag down gives it. */
    fun progress(): Float = (minOf(pw.value, ph.value) * shrink()).coerceIn(0f, 1f)

    var height = 1f
    fun shrink() = 1f - 0.42f * smoothstep(0f, height * 1.1f, pull())

    suspend fun run(open: Boolean) = kotlinx.coroutines.coroutineScope {
        if (open) {
            closeVelocity = 0f
            drag.snapTo(0f)
            pw.snapTo(0f)
            ph.snapTo(0f)
            shown = true
            launch { ph.animateTo(1f, Sp.SheetH.unit) }
            launch { pw.animateTo(1f, Sp.SheetW.unit) }
        } else if (shown) {
            // Flicked down, it carries the flick into the shrink, as the mock's close does.
            val v = -closeVelocity.coerceAtLeast(0f) / height * 1.2f
            val a = launch { ph.animateTo(0f, Sp.SheetClose.unit, v) }
            val b = launch { pw.animateTo(0f, Sp.SheetClose.unit, v) }
            a.join()
            b.join()
            shown = false
            drag.snapTo(0f)
            from = null
        }
    }
}

/** Clips to the morphing panel ([PanelMorph]) and fills it with [page], the card's [tint] fading as it grows. */
private fun Modifier.liquidPanel(m: PanelMorph, page: Color, tint: Color): Modifier = this
    .onPlaced { m.height = it.size.height.toFloat() }
    .drawWithContent {
        val w = size.width
        val h = size.height
        m.liftMax = 140.dp.toPx()
        val raw = m.drag.value
        val pull = raw.coerceAtLeast(0f)
        val lift = if (raw < 0f) rubber(-raw, m.liftMax, 0.5f) else 0f
        val k = m.shrink()
        val pw = m.pw.value * k
        val ph = m.ph.value * k
        val p = minOf(pw, ph)
        val pc = p.coerceIn(0f, 1f)
        // Without a card to grow from it rises out of the bottom edge, swelling the same.
        val src = m.from ?: androidx.compose.ui.geometry.Rect(0f, h, w, h)
        var l = lerp(src.left, 0f, pw)
        var r = lerp(src.right, w, pw)
        var t = lerp(src.top, pull, ph) - lift
        var b = lerp(src.bottom, h + pull, ph)
        if (p < 0f) {
            // Plops back into the card with a squash.
            val q = minOf(0.35f, -p)
            val cx = (l + r) / 2f
            val cy = (t + b) / 2f
            val hw = (r - l) / 2f * (1f + q)
            val hh = (b - t) / 2f * (1f - q * 0.8f)
            l = cx - hw; r = cx + hw; t = cy - hh; b = cy + hh
        }
        val ease = smoothstep(0f, 1f, pc)
        val radTop = minOf(lerp(m.fromCorner, 22.dp.toPx(), ease), (r - l) / 2f, (b - t) / 2f).coerceAtLeast(0f)
        val radBottom = minOf(lerp(m.fromCorner, 0f, ease), (r - l) / 2f, (b - t) / 2f).coerceAtLeast(0f)
        val sw = kotlin.math.sin(pc * kotlin.math.PI.toFloat())
        val vel = kotlin.math.abs(m.ph.velocity + m.pw.velocity) * 0.5f
        val bs = sw * (8f + (vel * 2f).coerceIn(0f, 10f)) * density
        val bt = sw * 18f * density
        blobRectPath(m.path, l, t, r, b, radTop, bs, bt, radBottom)
        clipPath(m.path) {
            drawRect(page, topLeft = Offset(-bs * 2f, t - bt * 2f), size = Size(w + bs * 4f, h - t + bt * 3f + pull))
            this@drawWithContent.drawContent()
            val a = 1f - smoothstep(0.02f, 0.45f, pc)
            if (a > 0f) drawRect(tint, topLeft = Offset(l - bs * 2f, t - bt * 2f), size = Size(r - l + bs * 4f, b - t + bt * 3f), alpha = a)
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

private fun androidx.compose.foundation.lazy.LazyListScope.relatedItems(groups: List<List<Item>>, palette: ArtworkPalette) {
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
                    if (first is Video) VideoCard(first, titleColor = palette.onBackground, metaColor = palette.onBackgroundVariant) else ItemRow(first)
                }
            }
        }
    }
}

@Composable
private fun TitleBlock(video: Video, page: WatchPage?, palette: ArtworkPalette, modifier: Modifier = Modifier, onMore: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onMore).padding(horizontal = PAGE_GUTTER).padding(top = 16.dp, bottom = 4.dp)) {
        if (video.title.isNotEmpty()) {
            Text(video.title, style = MaterialTheme.typography.titleLarge, color = palette.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            SkeletonLine(0.8f, 20.dp)
        }
        if (video.isLive) {
            Spacer(Modifier.height(6.dp))
            Text("LIVE", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W700), color = Color.White, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(LiveRed).padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

/**
 * A grouped card on the watch page, as iOS insets a group: the page's ink at a low veil,
 * corner 16, and a press that sinks it a little rather than a ripple.
 */
@Composable
private fun Modifier.watchCard(palette: ArtworkPalette, origin: Modifier = Modifier, onClick: () -> Unit): Modifier {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.975f else 1f, androidx.compose.animation.core.spring(stiffness = 900f), label = "cardPress")
    val veil by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.11f else 0.065f, androidx.compose.animation.core.tween(120), label = "cardVeil")
    return this
        .padding(horizontal = PAGE_GUTTER, vertical = 6.dp)
        .fillMaxWidth()
        .then(origin)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(16.dp))
        .drawBehind { drawRect(palette.onBackground.copy(alpha = veil)) }
        .clickable(interaction, indication = null, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp)
}

/** Views and date in bold, then the description's first lines: tap for the whole of it. */
@Composable
private fun DetailsCard(video: Video, page: WatchPage?, palette: ArtworkPalette, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(Modifier.watchCard(palette, modifier, onClick)) {
        val meta = listOfNotNull(page?.viewCountShort ?: video.viewsText, page?.relativeDate ?: video.publishedText).joinToString("  ·  ")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(meta.ifEmpty { "Details" }, style = MaterialTheme.typography.titleSmall, color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(OrielIcons.Chevron, null, tint = palette.onBackgroundVariant, modifier = Modifier.size(15.dp))
        }
        val preview = page?.description?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.take(3)?.joinToString(" ")
        if (!preview.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(preview, style = MaterialTheme.typography.bodyMedium, color = palette.onBackgroundVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else if (page == null) {
            Spacer(Modifier.height(8.dp))
            SkeletonLine(0.9f, 12.dp)
        }
    }
}

@Composable
private fun ChannelLine(app: AppState, video: Video, subscribers: String?, palette: ArtworkPalette, inset: androidx.compose.ui.unit.Dp = PAGE_GUTTER) {
    val channel = video.channel ?: return
    val subscribed by app.graph.library.subscribedIds.collectAsState()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = channel.id != null) { app.openChannel(channel.id!!) }.padding(horizontal = inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(channel.avatar, 38.dp, name = channel.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (channel.verified) VerifiedBadge(palette.onBackgroundVariant)
            }
            subscribers?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.onBackgroundVariant, maxLines = 1) }
        }
        channel.id?.let { id -> SubscribeButton(id in subscribed) { app.toggleSubscribe(Channel(id, channel.name, channel.avatar, verified = channel.verified)) } }
    }
}

@Composable
private fun ActionsRow(app: AppState, video: Video, likeCount: String?, votes: ReturnDislike.Votes?, palette: ArtworkPalette) {
    val liked by app.graph.library.likedIds.collectAsState()
    val library by app.graph.library.state.collectAsState()
    val haptics = rememberHaptics()
    val status = when {
        video.id in liked -> LikeStatus.LIKE
        video.id in library.dislikedIds -> LikeStatus.DISLIKE
        else -> LikeStatus.INDIFFERENT
    }
    // Four even tiles, icon over label, as iOS lays out a contact's actions; Watch later is under Save.
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val tile = Modifier.weight(1f)
        ActionTile(
            if (status == LikeStatus.LIKE) OrielIcons.Liked else OrielIcons.Like,
            likeCount ?: votes?.likes?.let(Format::compact) ?: "Like", "Like", palette, tile, active = status == LikeStatus.LIKE,
        ) {
            haptics.play(if (status == LikeStatus.LIKE) Haptic.ToggleOff else Haptic.ToggleOn)
            app.graph.actions.toggleLike(video)
        }
        ActionTile(
            if (status == LikeStatus.DISLIKE) OrielIcons.Disliked else OrielIcons.Dislike,
            votes?.dislikes?.let(Format::compact) ?: "Dislike", "Dislike", palette, tile, active = status == LikeStatus.DISLIKE,
        ) {
            haptics.play(if (status == LikeStatus.DISLIKE) Haptic.ToggleOff else Haptic.ToggleOn)
            app.graph.actions.toggleDislike(video)
        }
        ActionTile(OrielIcons.Share, "Share", "Share", palette, tile) { haptics.play(Haptic.Tap); app.share(video) }
        ActionTile(OrielIcons.Save, "Save", "Save", palette, tile) { haptics.play(Haptic.Tap); app.saveTarget = video }
    }
}

/** One of the watch page's action tiles: a soft rounded square, its icon over a small label. */
@Composable
private fun ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    palette: ArtworkPalette,
    modifier: Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val reduce = app.oriel.ui.theme.LocalReduceAnimation.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.94f else 1f, androidx.compose.animation.core.spring(stiffness = 900f), label = "tilePress")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(GroupShape)
            .background(palette.onBackground.copy(alpha = if (active) 0.15f else 0.065f))
            .clickable(interactionSource = interaction, indication = null, onClickLabel = description, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Turning on pops the icon past full size and back, the way iOS answers a like.
        val pop = remember { androidx.compose.animation.core.Animatable(1f) }
        var wasActive by remember { mutableStateOf(active) }
        LaunchedEffect(active) {
            if (active && !wasActive && !reduce) {
                pop.animateTo(1.28f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 1400f))
                pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f))
            }
            wasActive = active
        }
        androidx.compose.animation.Crossfade(icon, animationSpec = androidx.compose.animation.core.tween(160), label = "tileIcon") { shown ->
            Icon(shown, description, tint = palette.onBackground, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
        }
        Spacer(Modifier.height(5.dp))
        // The count rolls: up as it rises, down as it falls.
        androidx.compose.animation.AnimatedContent(
            label,
            transitionSpec = {
                val up = (targetState.filter(Char::isDigit).toLongOrNull() ?: 0) >= (initialState.filter(Char::isDigit).toLongOrNull() ?: 0)
                (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(stiffness = 700f)) { if (up) it else -it } + androidx.compose.animation.fadeIn())
                    .togetherWith(androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.spring(stiffness = 700f)) { if (up) -it else it } + androidx.compose.animation.fadeOut())
            },
            label = "tileLabel",
        ) { text ->
            Text(text, style = MaterialTheme.typography.labelMedium, color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CommentsTeaser(count: String?, teaser: String?, avatar: String?, palette: ArtworkPalette, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(Modifier.watchCard(palette, modifier, onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.titleSmall, color = palette.onBackground)
            count?.let { Text("  $it", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W500), color = palette.onBackgroundVariant) }
            Spacer(Modifier.weight(1f))
            Icon(OrielIcons.Chevron, null, tint = palette.onBackgroundVariant, modifier = Modifier.size(15.dp))
        }
        if (teaser != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(avatar, 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(teaser, style = MaterialTheme.typography.bodyMedium, color = palette.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChaptersShelf(app: AppState, chapters: List<Chapter>, palette: ArtworkPalette) {
    Column(Modifier.padding(vertical = 6.dp)) {
        SectionHeader("Chapters", color = palette.onBackground)
        LazyRow(contentPadding = PaddingValues(horizontal = PAGE_GUTTER), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chapters.distinctBy { it.startMs }, key = { it.startMs }) { ch ->
                Column(Modifier.width(150.dp).clip(RoundedCornerShape(ROW_CORNER)).pressClickable(onClickLabel = "Play from ${ch.title}") { app.connection.seekTo(ch.startMs) }) {
                    RemoteImage(ch.thumbnail ?: Thumbnails.frame(app.watch.state.value.video?.id ?: "", 320), Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(ROW_CORNER))
                    Spacer(Modifier.height(6.dp))
                    Text(ch.title, style = MaterialTheme.typography.titleSmall, color = palette.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Format.durationMs(ch.startMs), style = MaterialTheme.typography.labelMedium, color = palette.onBackgroundVariant)
                }
            }
        }
    }
}

/**
 * The playlist being played, as a card that opens into its videos. Each row is its own lazy
 * item, so opening a long one composes only what's on screen; it shows a window from just
 * before the current video, so in a long mix the one playing is always among them.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.playlistPanelItems(
    app: AppState,
    panel: PlaylistPanel,
    currentId: String?,
    open: Boolean,
    onToggle: () -> Unit,
    palette: ArtworkPalette,
) {
    val index = panel.videos.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: panel.currentIndex
    val tint = palette.onBackground.copy(alpha = 0.07f)
    val corner = 14.dp
    item(key = "playlist") {
        val shape = if (open) RoundedCornerShape(topStart = corner, topEnd = corner) else RoundedCornerShape(corner)
        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, motion(androidx.compose.animation.core.spring(stiffness = 700f)), label = "playlistChevron")
        Row(
            Modifier
                .padding(horizontal = PAGE_GUTTER).padding(top = 8.dp, bottom = if (open) 0.dp else 8.dp)
                .fillMaxWidth().clip(shape).background(tint)
                .clickable(onClickLabel = if (open) "Hide the playlist" else "Show the playlist", onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(panel.title, style = MaterialTheme.typography.titleSmall, color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (panel.isMix) "Mix" else panel.owner, "${index + 1} / ${panel.videos.size}").joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = palette.onBackgroundVariant)
            }
            Icon(OrielIcons.Collapse, null, tint = palette.onBackground, modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = turn })
        }
    }
    if (!open) return
    val from = (index - 5).coerceIn(0, (panel.videos.size - 1).coerceAtLeast(0))
    val shown = (from until minOf(panel.videos.size, from + 60)).toList()
    items(shown, key = { i -> "pl$i:${panel.videos[i].id}" }, contentType = { "playlistRow" }) { i ->
        VideoRow(
            panel.videos[i], index = i, active = i == index, thumbWidth = 112.dp,
            titleColor = palette.onBackground, metaColor = palette.onBackgroundVariant, activeTint = palette.accent,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER).background(tint),
            onClick = { app.playPlaylist(panel.videos, i, QueueContext(title = panel.title, playlistId = panel.id, isMix = panel.isMix)) },
        )
    }
    item(key = "playlistEnd") {
        Box(Modifier.padding(horizontal = PAGE_GUTTER).padding(bottom = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(bottomStart = corner, bottomEnd = corner)).background(tint))
    }
}

@Composable
private fun RelatedSkeleton() = VideoCardSkeleton(0)

// ---- panels ------------------------------------------------------------------------------

/**
 * A panel over the page under the video, as iOS sheets are set: a grabber, the title at the
 * leading edge with its count in grey, a round grey close button, and a hairline under the
 * header only once the list has scrolled beneath it. A drag down on the header, or on the list
 * once it's at its top, closes it.
 */
@Composable
private fun Panel(palette: ArtworkPalette, title: String, count: String? = null, scrolled: Boolean, onBack: (() -> Unit)?, onClose: () -> Unit, morph: PanelMorph? = null, content: @Composable () -> Unit) {
    // The panel follows a drag down on its header, and closes past 80dp or on a flick down; short of that it springs back.
    // Liquid, the drag is the morph's (which draws it) and goes up too, rubber-banded.
    val own = remember { androidx.compose.animation.core.Animatable(0f) }
    val drag = morph?.drag ?: own
    val liquid = morph != null
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val close by androidx.compose.runtime.rememberUpdatedState(onClose)
    val haptics = rememberHaptics()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pullToClose = remember {
        object {
            val closeAt = with(density) { 80.dp.toPx() }
            val back = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.86f, stiffness = 520f)
            var armed = false

            fun moveTo(y: Float) {
                // One tick as it passes the point where letting go closes it.
                val past = y > closeAt
                if (past && !armed) haptics.play(Haptic.Tick)
                armed = past
                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { drag.snapTo(y) }
            }

            fun settle(velocity: Float) {
                armed = false
                if (drag.value > closeAt || (velocity > 1000f && drag.value > 0f)) {
                    morph?.closeVelocity = velocity
                    close()
                } else scope.launch { drag.animateTo(0f, if (liquid) Sp.Sheet.px else back, velocity) }
            }

            // The list's scroll and the panel's pull are one gesture, as in an iOS sheet: at the
            // list's top a pull down moves the panel, and pushing back up gives that back before
            // the list scrolls again. Flings never pull it: one that reaches the top just bounces.
            val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y >= 0f || drag.value <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    val used = maxOf(available.y, -drag.value)
                    moveTo(drag.value + used)
                    return androidx.compose.ui.geometry.Offset(0f, used)
                }

                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    moveTo(drag.value + available.y)
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (drag.value <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().graphicsLayer { if (!liquid) translationY = drag.value }.clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(palette.background).nestedScroll(pullToClose.connection)) {
        // The header moves with its drag, so the finger is followed on the screen, not in the header's own coordinates.
        val headerScreen = remember { ScreenPosition() }
        Column(Modifier.fillMaxWidth().screenPosition(headerScreen).pointerInput(Unit) {
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            var total = 0f
            var lastY = 0f
            detectVerticalDragGestures(
                onDragStart = { at -> total = 0f; lastY = headerScreen.of(at).y; tracker.resetTracking() },
                onDragEnd = { pullToClose.settle(tracker.calculateVelocity().y) },
                onDragCancel = { pullToClose.armed = false; scope.launch { drag.animateTo(0f, if (liquid) Sp.Sheet.px else pullToClose.back) } },
            ) { change, _ ->
                val y = headerScreen.of(change.position).y
                val dy = y - lastY
                lastY = y
                total += dy
                tracker.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, total))
                // Not above where it rests: the header's drag only pulls the panel down (liquid, up
                // it rubber-bands, as the mock's sheet does).
                pullToClose.moveTo(if (liquid) drag.value + dy else (drag.value + dy).coerceAtLeast(0f))
            }
        }) {
            Box(Modifier.padding(top = 7.dp, bottom = 2.dp).align(Alignment.CenterHorizontally).size(36.dp, 5.dp).clip(CircleShape).background(palette.onBackground.copy(alpha = 0.22f)))
            PanelHeader(title, count, palette, onClose, onBack)
            val line by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, androidx.compose.animation.core.tween(180), label = "panelLine")
            Box(Modifier.fillMaxWidth().height(0.5.dp).graphicsLayer { alpha = line }.background(palette.divider))
        }
        content()
    }
}

/**
 * A panel's title line: the name in bold with its count in grey, a back chevron when a level
 * down (a reply thread), and the close button as iOS draws it, a small grey disc with an ×.
 */
@Composable
internal fun PanelHeader(title: String, count: String?, palette: ArtworkPalette, onClose: (() -> Unit)?, onBack: (() -> Unit)? = null) {
    // End inset 7dp, not 14: the close button's 44dp target reaches 7dp past its 30dp disc.
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 6.dp else PAGE_GUTTER, end = 7.dp, top = 4.dp, bottom = 8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.animation.AnimatedVisibility(onBack != null, enter = enterMotion(androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally()), exit = exitMotion(androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally())) {
            Box(Modifier.size(44.dp).pressClickable(onClickLabel = "Back") { onBack?.invoke() }, contentAlignment = Alignment.Center) {
                Icon(OrielIcons.Back, "Back", tint = palette.accent, modifier = Modifier.size(20.dp))
            }
        }
        androidx.compose.animation.AnimatedContent(
            title to count,
            transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
            modifier = Modifier.weight(1f), label = "panelTitle",
        ) { (t, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t, style = MaterialTheme.typography.titleLarge, color = palette.onBackground, maxLines = 1)
                if (c != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(c, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W500), color = palette.onBackgroundVariant, maxLines = 1)
                }
            }
        }
        if (onClose != null) {
            // A 44dp target (overhanging the 40dp row) around the 30dp disc, which shows the press.
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier.requiredSize(44.dp).clickable(interaction, indication = null, onClickLabel = "Close", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(palette.onBackground.copy(alpha = 0.10f)).indication(interaction, androidx.compose.foundation.LocalIndication.current),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(OrielIcons.Xmark, "Close", tint = palette.onBackgroundVariant, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

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

@Composable
private fun DescriptionPanel(app: AppState, video: Video, page: WatchPage?, palette: ArtworkPalette, listState: androidx.compose.foundation.lazy.LazyListState) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Text(video.title, style = MaterialTheme.typography.titleLarge, color = palette.onBackground, modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(top = 8.dp, bottom = 14.dp))
        }
        // The App Store's information strip: small grey labels over the figures, hairlines between.
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Stat(page?.likeCount ?: "–", "Likes", palette, Modifier.weight(1f))
                StatDivider(palette)
                Stat(page?.viewCount?.substringBefore(' ') ?: video.viewsText?.substringBefore(' ') ?: "–", if (video.isLive) "Watching" else "Views", palette, Modifier.weight(1f))
                StatDivider(palette)
                Stat(page?.date ?: video.publishedText ?: "–", "Published", palette, Modifier.weight(1.3f))
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = PAGE_GUTTER).fillMaxWidth().height(0.5.dp).background(palette.divider))
        }
        page?.description?.takeIf { it.isNotBlank() }?.let { text ->
            item {
                Text(
                    linkify(text, palette.accent, onTime = { app.connection.seekTo(it) }, onLink = { url -> if (!app.openLink(url)) app.openExternally(url) }),
                    style = MaterialTheme.typography.bodyLarge, color = palette.onBackground,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(top = 14.dp).fillMaxWidth(),
                )
            }
        }
        if (!page?.chapters.isNullOrEmpty()) {
            val chapters = page.chapters.distinctBy { it.startMs }
            item { GroupLabel("Chapters", palette) }
            itemsIndexed(chapters, key = { _, ch -> "ch${ch.startMs}" }) { i, ch ->
                GroupedRow(palette, first = i == 0, last = i == chapters.lastIndex, onClick = { app.connection.seekTo(ch.startMs) }) {
                    RemoteImage(ch.thumbnail ?: Thumbnails.frame(video.id, 320), Modifier.width(88.dp).aspectRatio(16f / 9f), RoundedCornerShape(7.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.title, style = MaterialTheme.typography.bodyLarge, color = palette.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Format.durationMs(ch.startMs), style = MaterialTheme.typography.bodyMedium, color = palette.accent)
                    }
                }
            }
        }
        if (video.channel != null) {
            item { GroupLabel("Channel", palette) }
            item {
                Box(Modifier.padding(horizontal = PAGE_GUTTER).clip(GroupShape).background(palette.onBackground.copy(alpha = 0.065f))) {
                    ChannelLine(app, video, page?.subscribersText, palette, inset = 14.dp)
                }
            }
        }
    }
}

/** A grouped list's header, iOS-style: small, grey, uppercase, above the card. */
@Composable
private fun GroupLabel(text: String, palette: ArtworkPalette) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.onBackgroundVariant, modifier = Modifier.padding(start = PAGE_GUTTER + 4.dp, top = 26.dp, bottom = 8.dp))
}

/** One row of an inset group drawn row by row (so it can live in a lazy list): rounded at the group's ends, hairlines between. */
@Composable
private fun GroupedRow(palette: ArtworkPalette, first: Boolean, last: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = if (first) GROUP_CORNER else 0.dp, topEnd = if (first) GROUP_CORNER else 0.dp, bottomStart = if (last) GROUP_CORNER else 0.dp, bottomEnd = if (last) GROUP_CORNER else 0.dp)
    Column(Modifier.padding(horizontal = PAGE_GUTTER).clip(shape).background(palette.onBackground.copy(alpha = 0.065f))) {
        Row(Modifier.fillMaxWidth().pressClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        if (!last) Box(Modifier.padding(start = 112.dp).fillMaxWidth().height(0.5.dp).background(palette.divider))
    }
}

@Composable
private fun Stat(value: String, label: String, palette: ArtworkPalette, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.onBackgroundVariant, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700), color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatDivider(palette: ArtworkPalette) {
    Box(Modifier.width(0.5.dp).fillMaxHeight(0.8f).background(palette.divider))
}

private val LINK = Regex("""https?://[^\s)]+|(?<![\d:])(?:\d{1,2}:)?\d{1,2}:\d{2}(?![\d:])""")

/** Descriptions with their URLs and timestamps tappable: a timestamp seeks, a link opens in-app when it can. */
fun linkify(text: String, color: Color, onTime: (Long) -> Unit, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var last = 0
    val style = TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.W600))
    LINK.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        val value = m.value
        if (value.startsWith("http")) {
            withLink(LinkAnnotation.Clickable(value, style) { onLink(value) }) { append(value) }
        } else {
            val parts = value.split(':').map { it.toLong() }
            val ms = parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
            withLink(LinkAnnotation.Clickable(value, style) { onTime(ms) }) { append(value) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/**
 * The comments, and a reply thread pushed over them the way iOS pushes a level: it slides in
 * from the trailing edge while the list drifts back under it, and slides away again on Back.
 */
@Composable
private fun CommentsPanel(app: AppState, palette: ArtworkPalette, listState: androidx.compose.foundation.lazy.LazyListState, repliesState: androidx.compose.foundation.lazy.LazyListState) {
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
    // above and below it gives that back, so the row is the height it was.
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
            SkeletonLine(0.3f, 11.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonLine(0.9f)
            Spacer(Modifier.height(6.dp))
            SkeletonLine(0.6f)
        }
    }
}

