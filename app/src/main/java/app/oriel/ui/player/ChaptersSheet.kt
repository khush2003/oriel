package app.oriel.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Chapter
import app.oriel.ui.AppState
import app.oriel.ui.components.GroupedItem
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.LocalSheetExpand
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.first
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.components.SheetAction
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.util.Format
import app.oriel.util.Thumbnails

private val THUMB_WIDTH = 88.dp
private val ROW_PAD = 12.dp

/**
 * The video's chapters, from the player, as an iOS sheet: the title and Done, then one inset
 * group of rows, each with its frame, title and start. The one playing has the now-playing bars
 * over its frame and a heavier title, and is announced as selected. A long list opens at the
 * medium detent, scrolled so the one playing is in view. A tap jumps there and closes the sheet.
 */
@Composable
fun ChaptersSheet(app: AppState, onDismiss: () -> Unit) {
    val watch by app.watch.state.collectAsState()
    val videoId = watch.video?.id.orEmpty()
    // Per video: Autoplay can move on while the list is open.
    val pageChapters = watch.page?.chapters
    val chapters = remember(videoId, pageChapters) { pageChapters.orEmpty().distinctBy { it.startMs } }
    val position = rememberPosition(app.connection, 500)
    // Derived, so the list recomposes when the chapter changes, not on every position tick.
    val currentIndex by remember(chapters) { derivedStateOf { chapters.indexOfLast { it.startMs <= position.longValue } } }
    // One row above the playing one, so it shows with what came before it.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    val isPlaying by app.connection.isPlaying.collectAsState()
    val haptics = rememberHaptics()

    OrielSheet(onDismiss, scrollable = false, maxHeight = 760.dp, medium = true) {
        val close = LocalSheetClose.current
        val expand = LocalSheetExpand.current
        val windowHeight = LocalWindowInfo.current.containerSize.height
        // The list's top within the sheet: below the grabber (the parent's offset) and the title.
        val listTop = remember { floatArrayOf(0f) }
        // Near the end, the list can't scroll the playing row up to the top, which leaves it in
        // the half of the sheet the medium detent hides; then the sheet opens at full height.
        LaunchedEffect(list) {
            if (currentIndex < 0) return@LaunchedEffect
            val info = snapshotFlow { list.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
            val row = info.visibleItemsInfo.firstOrNull { it.index == currentIndex }
            if (row == null || listTop[0] + row.offset + row.size > windowHeight / 2f) expand()
        }
        SheetTitle("Chapters", trailing = { SheetAction("Done") { close {} } })
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().onPlaced { c ->
            listTop[0] = c.positionInParent().y + (c.parentLayoutCoordinates?.positionInParent()?.y ?: 0f)
        }, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            itemsIndexed(chapters, key = { _, ch -> ch.startMs }) { i, ch ->
                GroupedItem(first = i == 0, last = i == chapters.lastIndex, dividerStart = ROW_PAD * 2 + THUMB_WIDTH) {
                    ChapterRow(ch, videoId, playing = i == currentIndex, animate = isPlaying) {
                        haptics.play(Haptic.Select)
                        app.connection.seekTo(ch.startMs)
                        close {}
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, videoId: String, playing: Boolean, animate: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics { selected = playing }
            .clickable(onClickLabel = "Play from here", role = Role.Button, onClick = onClick)
            .padding(horizontal = ROW_PAD, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(THUMB_WIDTH).aspectRatio(16f / 9f)) {
            RemoteImage(chapter.thumbnail ?: Thumbnails.frame(videoId, 320), Modifier.matchParentSize(), RoundedCornerShape(7.dp))
            if (playing) {
                Box(Modifier.matchParentSize().clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = 0.4f)))
                NowPlayingBars(animate, Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.width(ROW_PAD))
        Column(Modifier.weight(1f)) {
            Text(
                chapter.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (playing) FontWeight.W600 else MaterialTheme.typography.bodyLarge.fontWeight),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(Format.durationMs(chapter.startMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Music's now-playing mark: three white bars over the frame, rising and falling while the
 * video plays and still when it's paused or Reduce motion is on. Drawn only, so the bars move
 * without recomposing the row.
 */
@Composable
private fun NowPlayingBars(animate: Boolean, modifier: Modifier = Modifier) {
    val moving = animate && !LocalReduceAnimation.current
    val phase: State<Float> = if (moving) {
        rememberInfiniteTransition(label = "nowPlaying").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "bars")
    } else remember { mutableFloatStateOf(0f) }
    Box(
        modifier.size(18.dp, 16.dp).drawBehind {
            val bar = 3.dp.toPx()
            val gap = (size.width - bar * 3) / 2
            val t = phase.value * 2f * Math.PI.toFloat()
            for (i in 0 until 3) {
                // Each bar on its own beat; at rest they stand at a short, tall, middling shape.
                val rest = floatArrayOf(0.55f, 1f, 0.75f)[i]
                val h = if (!moving) rest else 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(t * (i + 1) + i * 1.7f))
                val height = size.height * h
                drawRoundRect(Color.White, Offset(i * (bar + gap), size.height - height), Size(bar, height), CornerRadius(bar / 2))
            }
        },
    )
}
