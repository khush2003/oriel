package app.oriel.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import app.oriel.ui.components.GroupShape
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.ROW_CORNER
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.SheetTitle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Chapter
import app.oriel.ui.AppState
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.util.Format
import app.oriel.util.Thumbnails

/**
 * The video's chapters, from the player: each with its frame, title and start, the one
 * playing marked. A tap jumps there and closes the list. Opens scrolled to the current one.
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
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    val haptics = rememberHaptics()
    val accent = MaterialTheme.colorScheme.primary

    OrielSheet(onDismiss, scrollable = false, maxHeight = 600.dp) {
        val close = LocalSheetClose.current
        SheetTitle("Chapters")
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
            itemsIndexed(chapters, key = { _, ch -> ch.startMs }) { i, ch ->
                ChapterRow(ch, videoId, playing = i == currentIndex, accent = accent) {
                    haptics.play(Haptic.Select)
                    app.connection.seekTo(ch.startMs)
                    close {}
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, videoId: String, playing: Boolean, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(GroupShape)
            .then(if (playing) Modifier.background(accent.copy(alpha = 0.12f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(chapter.thumbnail ?: Thumbnails.frame(videoId, 320), Modifier.width(112.dp).aspectRatio(16f / 9f), RoundedCornerShape(ROW_CORNER))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chapter.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (playing) FontWeight.W700 else FontWeight.W600),
                color = if (playing) accent else MaterialTheme.colorScheme.onBackground,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(Format.durationMs(chapter.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
