package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import app.oriel.core.model.PlaylistPanel
import app.oriel.ui.theme.motion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.playback.QueueContext
import app.oriel.ui.AppState
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.VideoRow
import app.oriel.ui.theme.ArtworkPalette

/**
 * The playlist being played, as a card that opens into its videos. Each row is its own lazy
 * item, so opening a long one composes only what's on screen; it shows a window from just
 * before the current video, so in a long mix the one playing is always among them.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.playlistPanelItems(
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
