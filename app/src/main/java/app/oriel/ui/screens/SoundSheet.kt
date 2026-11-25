package app.oriel.ui.screens

import androidx.compose.runtime.mutableIntStateOf
import app.oriel.core.model.ShortSound
import app.oriel.core.model.SoundPage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.AppState
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RemoteImage
import app.oriel.util.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A sound, as YouTube's sound page shows it: the artwork and title, who made it, and a grid
 * of the shorts that use it, more loading as the grid nears its end. A tap plays one.
 */
@Composable
internal fun SoundSheet(app: AppState, sound: ShortSound, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<SoundPage?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    var moreFailed by remember { mutableStateOf(false) }
    var moreAttempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sound.token, attempt) {
        val token = sound.token ?: run { failed = true; return@LaunchedEffect }
        failed = false
        val p = withContext(Dispatchers.IO) { runCatching { app.graph.yt.soundPage(token) }.getOrNull() }
        if (p == null) failed = true else page = p
    }
    OrielSheet(onDismiss, scrollable = false, maxHeight = 720.dp) {
        val close = LocalSheetClose.current
        Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER).padding(top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(sound.image ?: page?.image, Modifier.size(84.dp), RoundedCornerShape(14.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(OrielIcons.Sound, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("SOUND", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(sound.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val from = listOfNotNull(page?.title?.takeIf { it != sound.title }, page?.subtitle).joinToString(" · ")
                if (from.isNotEmpty()) Text(from, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val p = page
        when {
            failed -> MessageState("Couldn't load this sound.", icon = OrielIcons.Offline, action = "Try again", onAction = { attempt++ })
            p == null -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { ActivityIndicator(size = 24.dp) }
            p.shorts.isEmpty() -> MessageState("No shorts use this sound yet.")
            else -> {
                val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 6 } == true } }
                LaunchedEffect(nearEnd, p.continuation, moreAttempt) {
                    val cont = p.continuation
                    if (!nearEnd || cont == null || loadingMore || moreFailed) return@LaunchedEffect
                    loadingMore = true
                    scope.launch {
                        val next = withContext(Dispatchers.IO) { runCatching { app.graph.yt.soundPage(cont) }.getOrNull() }
                        // A failed page says so at the end of the grid, with Try again.
                        if (next != null) page = p.copy(shorts = (p.shorts + next.shorts).distinctBy { it.id }, continuation = next.continuation)
                        else moreFailed = true
                        loadingMore = false
                    }
                }
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    androidx.compose.foundation.lazy.grid.GridCells.Fixed(3), state = grid,
                    contentPadding = PaddingValues(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(p.shorts.size, key = { p.shorts[it].id }) { i ->
                        val v = p.shorts[i]
                        Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).clickable { close { app.openShort(v) } }) {
                            RemoteImage(v.thumbnail ?: Thumbnails.short(v), Modifier.fillMaxSize(), RoundedCornerShape(10.dp), border = false)
                            Box(Modifier.fillMaxWidth().fillMaxHeight(0.35f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
                            v.viewsText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) }
                        }
                    }
                    if (p.continuation != null && (loadingMore || moreFailed)) {
                        item(key = "more", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            if (moreFailed) MessageState("Couldn't load more.", action = "Try again", onAction = { moreFailed = false; moreAttempt++ })
                            else LoadingRow()
                        }
                    }
                }
            }
        }
    }
}
