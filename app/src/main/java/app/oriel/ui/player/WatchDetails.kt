package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import app.oriel.core.extras.ReturnDislike
import app.oriel.core.model.Channel
import app.oriel.core.model.WatchPage
import app.oriel.ui.components.GroupShape
import app.oriel.ui.components.ROW_CORNER
import app.oriel.ui.theme.LiveRed
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Chapter
import app.oriel.core.model.LikeStatus
import app.oriel.core.model.Video
import app.oriel.ui.AppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.components.SectionHeader
import app.oriel.ui.components.SkeletonLine
import app.oriel.ui.components.SubscribeButton
import app.oriel.ui.components.VerifiedBadge
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.ArtworkPalette
import app.oriel.util.Format
import app.oriel.util.Thumbnails

@Composable
internal fun TitleBlock(video: Video, palette: ArtworkPalette, modifier: Modifier = Modifier, onMore: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onMore).padding(horizontal = PAGE_GUTTER).padding(top = 16.dp, bottom = 4.dp)) {
        if (video.title.isNotEmpty()) {
            Text(video.title, style = MaterialTheme.typography.titleLarge, color = palette.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            SkeletonLine(0.8f, height = 20.dp)
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
internal fun DetailsCard(video: Video, page: WatchPage?, palette: ArtworkPalette, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
            SkeletonLine(0.9f, height = 12.dp)
        }
    }
}

@Composable
internal fun ChannelLine(app: AppState, video: Video, subscribers: String?, palette: ArtworkPalette, inset: androidx.compose.ui.unit.Dp = PAGE_GUTTER) {
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
internal fun ActionsRow(app: AppState, video: Video, likeCount: String?, votes: ReturnDislike.Votes?, palette: ArtworkPalette) {
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
internal fun CommentsTeaser(count: String?, teaser: String?, avatar: String?, palette: ArtworkPalette, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
internal fun ChaptersShelf(app: AppState, chapters: List<Chapter>, palette: ArtworkPalette) {
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
