package app.oriel.ui.screens

import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.components.TextPromptDialog
import app.oriel.ui.components.SwipeToRemove
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.AppGraph
import app.oriel.core.model.PlaylistPage
import app.oriel.core.model.Video
import app.oriel.playback.QueueContext
import app.oriel.ui.AlertAction
import app.oriel.ui.AlertSpec
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.feedItem
import app.oriel.ui.components.rowKeys
import app.oriel.ui.components.ActionPill
import app.oriel.ui.components.ExpandableText
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.MoreButton
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.PillAction
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.components.SkeletonLine
import app.oriel.ui.components.shimmer
import androidx.compose.ui.draw.clip
import app.oriel.ui.components.VideoRow
import app.oriel.ui.components.VideoRowSkeleton
import app.oriel.ui.theme.ArtworkPalette
import app.oriel.ui.theme.rememberArtworkPalette
import app.oriel.util.Thumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistState(
    val page: PlaylistPage? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val error: String? = null,
    val refreshing: Boolean = false,
    /** Play all or Shuffle is fetching the rest of a long playlist first. */
    val fetchingAll: Boolean = false,
)

class PlaylistModel(private val graph: AppGraph, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(PlaylistState())
    val state: StateFlow<PlaylistState> = _state.asStateFlow()

    private var job: Job? = null
    private var moreJob: Job? = null
    private var allJob: Job? = null

    init { load(false) }

    fun load(fresh: Boolean) {
        // A reload replaces the list: a load, next page or fetch-all still on its way belonged to the old one.
        job?.cancel()
        moreJob?.cancel()
        allJob?.cancel()
        _state.update { it.copy(loading = it.page == null, refreshing = fresh && it.page != null, error = null, loadingMore = false, moreFailed = false, fetchingAll = false) }
        job = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { graph.yt.playlist(id, fresh) } }
            r.onSuccess { p -> _state.value = PlaylistState(page = p, loading = false) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    graph.notePageFailure("Loading a playlist", e)
                    _state.update { it.copy(loading = false, refreshing = false, error = friendlyError(e)) }
                }
        }
    }

    fun loadMore() {
        val s = _state.value
        val token = s.page?.continuation ?: return
        if (s.loadingMore || s.moreFailed || s.fetchingAll) return
        _state.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { graph.yt.more(token) }.getOrNull() }
            _state.update { st ->
                val p = st.page ?: return@update st.copy(loadingMore = false)
                // Reloaded meanwhile: this page followed the old list.
                if (p.continuation != token) return@update st
                // A failed page keeps its token, for the footer's Try again.
                if (next == null) return@update st.copy(loadingMore = false, moreFailed = true)
                val have = p.videos.mapTo(HashSet()) { it.id }
                val more = next.items.filterIsInstance<Video>().filterNot { it.id in have }
                st.copy(loadingMore = false, page = p.copy(videos = p.videos + more, continuation = next.continuation))
            }
        }
    }

    fun retryMore() {
        _state.update { it.copy(moreFailed = false) }
        loadMore()
    }

    /**
     * Every video, following continuations, for Play all / Shuffle on long playlists, then
     * [then] with them. A second tap while it fetches does nothing; the pills show it's busy.
     */
    fun all(then: (List<Video>) -> Unit) {
        if (_state.value.fetchingAll) return
        val start = _state.value.page ?: return
        // This walks the same continuations: a next page landing meanwhile would add them twice.
        moreJob?.cancel()
        _state.update { it.copy(fetchingAll = true, loadingMore = false) }
        allJob = viewModelScope.launch {
            var p = start
            var guard = 0
            while (p.continuation != null && guard++ < 20) {
                val next = withContext(Dispatchers.IO) { runCatching { graph.yt.more(p.continuation!!) }.getOrNull() } ?: break
                val have = p.videos.mapTo(HashSet()) { it.id }
                p = p.copy(videos = p.videos + next.items.filterIsInstance<Video>().filterNot { it.id in have }, continuation = next.continuation)
            }
            _state.update { it.copy(page = p, fetchingAll = false) }
            then(p.videos)
        }
    }
}

/**
 * The tinted head of a playlist page: artwork, title, owner and stats, then Play all and Shuffle.
 * With no [title] yet it's its own placeholder, laid out the same, so nothing moves when it lands.
 */
@Composable
fun CollectionHeader(
    art: String?,
    title: String?,
    subtitle: String?,
    meta: String?,
    description: String?,
    palette: ArtworkPalette,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val artFrame = Modifier.fillMaxWidth(0.78f).widthIn(max = 420.dp).aspectRatio(16f / 9f)
        if (title == null) {
            Box(artFrame.clip(RoundedCornerShape(16.dp)).shimmer())
            Spacer(Modifier.height(16.dp))
            LinePlaceholder(MaterialTheme.typography.headlineMedium, 0.56f)
            LinePlaceholder(MaterialTheme.typography.titleMedium, 0.32f, Modifier.padding(top = 4.dp))
            LinePlaceholder(MaterialTheme.typography.bodySmall, 0.44f, Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { ActionPill("Play", OrielIcons.Play, {}, Modifier.fillMaxWidth(), tint = palette.accent, enabled = false) }
                Box(Modifier.weight(1f)) { ActionPill("Shuffle", OrielIcons.Shuffle, {}, Modifier.fillMaxWidth(), tint = palette.accent, enabled = false) }
            }
            Spacer(Modifier.height(10.dp))
            return@Column
        }
        RemoteImage(art, artFrame, RoundedCornerShape(16.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = palette.onBackground, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = palette.accent, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
        meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.onBackgroundVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { ActionPill("Play", OrielIcons.Play, { onPlay?.invoke() }, Modifier.fillMaxWidth(), tint = palette.accent, enabled = onPlay != null) }
            Box(Modifier.weight(1f)) { ActionPill("Shuffle", OrielIcons.Shuffle, { onShuffle?.invoke() }, Modifier.fillMaxWidth(), tint = palette.accent, enabled = onShuffle != null) }
        }
        description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            ExpandableText(it, Modifier.padding(horizontal = PAGE_GUTTER).fillMaxWidth(), color = palette.onBackgroundVariant, collapsedLines = 2)
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** A shimmering bar one line of [style] tall, standing in for that line. */
@Composable
private fun LinePlaceholder(style: androidx.compose.ui.text.TextStyle, widthFraction: Float, modifier: Modifier = Modifier) {
    val height = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() }
    Box(modifier.height(height), contentAlignment = Alignment.Center) { SkeletonLine(widthFraction, height * 0.7f) }
}

@Composable
fun PlaylistScreen(id: String) {
    val app = LocalAppState.current
    val graph = app.graph
    val model: PlaylistModel = viewModel(key = "playlist:$id") { PlaylistModel(graph, id) }
    val state by model.state.collectAsState()
    val saved by graph.library.savedPlaylistIds.collectAsState()
    val page = state.page
    val playlist = page?.playlist
    val palette = rememberArtworkPalette(playlist?.thumbnail ?: page?.videos?.firstOrNull()?.let { Thumbnails.of(it, 320) })
    val context = { QueueContext(title = playlist?.title, playlistId = id) }
    val pageKeys = remember(page?.videos) { rowKeys(page?.videos.orEmpty()) }

    // The whole playlist, not just the part scrolled to so far.
    fun playAll(shuffle: Boolean) = model.all { vids -> app.playPlaylist(vids, 0, context(), shuffle) }

    PageScaffold(
        title = playlist?.title ?: "",
        onBack = app::back,
        edgeToEdge = true,
        largeTitle = false,
        background = palette.background,
        refreshing = state.refreshing,
        onRefresh = { model.load(true) },
        onNearEnd = model::loadMore,
        // None until the playlist is in: an empty pill drew as a sliver at the bar's end.
        actions = if (playlist == null) null else ({
            if (id != "WL" && id != "LL") {
                val isSaved = id in saved
                PillAction(if (isSaved) OrielIcons.Check else OrielIcons.Add, if (isSaved) "Remove from library" else "Save to library") { graph.actions.toggleSaved(playlist) }
            }
            PillAction(OrielIcons.Share, "Share") { app.share(playlist) }
        }),
    ) {
        item(key = "header", contentType = "header") {
            if (playlist == null) {
                CollectionHeader(null, null, null, null, null, palette, null, null)
            } else {
                CollectionHeader(
                    art = playlist.thumbnail ?: page.videos.firstOrNull()?.let { Thumbnails.of(it, 720) },
                    title = playlist.title,
                    subtitle = playlist.owner?.name,
                    meta = page.metadata.joinToString(" · ").ifBlank { playlist.countText },
                    description = page.description,
                    palette = palette,
                    onPlay = if (page.videos.isNotEmpty() && !state.fetchingAll) ({ playAll(false) }) else null,
                    onShuffle = if (page.videos.isNotEmpty() && !state.fetchingAll) ({ playAll(true) }) else null,
                )
            }
        }
        if (page != null && state.error != null && !state.refreshing) item(key = "notice") { NoticeRow(state.error!!, action = "Try again", onAction = { model.load(true) }) }
        when {
            state.loading -> items(8, key = { "sk$it" }, contentType = { "rowSkeleton" }) { VideoRowSkeleton(it) }
            state.error != null && page == null -> item(key = "error") { MessageState(state.error!!, action = "Try again", onAction = { model.load(true) }) }
            page != null && page.videos.isEmpty() -> item(key = "empty", contentType = "message") { MessageState("This playlist is empty.") }
            page != null -> itemsIndexed(page.videos, key = { i, _ -> pageKeys[i] }, contentType = { _, _ -> "row" }) { i, v ->
                VideoRow(
                    v, index = i + 1, modifier = feedItem(), titleColor = palette.onBackground, metaColor = palette.onBackgroundVariant,
                    onClick = { app.playPlaylist(page.videos, i, context()) },
                    trailing = { MoreButton({ app.sheetItem = v }, tint = palette.onBackgroundVariant) },
                )
            }
        }
        moreFooter(state.loadingMore, state.moreFailed, model::retryMore)
    }
}

@Composable
fun LocalPlaylistScreen(id: String) {
    val app = LocalAppState.current
    val graph = app.graph
    val found by graph.library.state.collectSlice { s -> s.localPlaylists.firstOrNull { it.id == id } }
    val palette = rememberArtworkPalette(found?.videos?.firstOrNull()?.let { Thumbnails.of(it, 320) })
    val playlist = found ?: run {
        PageScaffold(title = "Playlist", onBack = app::back) { item(key = "gone", contentType = "message") { MessageState("This playlist was deleted.") } }
        return
    }
    val context = remember(playlist.name) { QueueContext(title = playlist.name) }
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // While a row is held, the list is reordered here as the finger moves and written once on release.
    var working by remember(playlist.videos) { mutableStateOf(playlist.videos) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    val dragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val shown = if (dragKey != null) working else playlist.videos
    val keys = remember(shown) { rowKeys(shown) }
    PageScaffold(
        title = playlist.name,
        onBack = app::back,
        edgeToEdge = true,
        largeTitle = false,
        background = palette.background,
        actions = {
            if (playlist.videos.size > 1) PillAction(if (editing) OrielIcons.Check else OrielIcons.Reorder, if (editing) "Done" else "Reorder") { editing = !editing }
            PillAction(OrielIcons.Edit, "Rename") { renaming = true }
            PillAction(OrielIcons.Delete, "Delete playlist") { confirmDelete(app, id, playlist.name, after = app::back) }
        },
    ) {
        item(key = "header", contentType = "header") {
            CollectionHeader(
                art = playlist.videos.firstOrNull()?.let { Thumbnails.of(it, 720) },
                title = playlist.name,
                subtitle = "On this device",
                meta = Plurals.count(playlist.videos.size, "video"),
                description = null,
                palette = palette,
                onPlay = if (playlist.videos.isNotEmpty()) ({ app.playPlaylist(playlist.videos, 0, context) }) else null,
                onShuffle = if (playlist.videos.isNotEmpty()) ({ app.playPlaylist(playlist.videos, 0, context, shuffle = true) }) else null,
            )
        }
        if (playlist.videos.isEmpty()) item(key = "empty", contentType = "message") { MessageState("Save videos here from their ⋯ menu or the watch page's Save button.") }
        itemsIndexed(shown, key = { i, _ -> keys[i] }, contentType = { _, _ -> "row" }) { i, v ->
            val key = keys[i]
            val held = key == dragKey
            var rowHeight by remember { mutableIntStateOf(1) }
            val row = if (held) Modifier.zIndex(1f).graphicsLayer {
                translationY = dragOffset.value
                scaleX = 1.03f; scaleY = 1.03f
                shadowElevation = 18.dp.toPx()
                shape = RoundedCornerShape(12.dp)
                clip = true
            } else feedItem()
            SwipeToRemove(if (editing) null else ({ graph.library.removeFromLocalPlaylist(id, v.id) }), row.onSizeChanged { rowHeight = it.height }) {
                VideoRow(
                    v, index = i + 1, titleColor = palette.onBackground, metaColor = palette.onBackgroundVariant,
                    modifier = if (held) Modifier.background(palette.background) else Modifier,
                    onClick = { if (!editing) app.playPlaylist(playlist.videos, i, context) },
                    trailing = {
                        if (editing) {
                            // The handle: drag to move. The row lifts, follows the finger, and the
                            // others step aside as it passes their middles.
                            // The gesture outlives recompositions: it reads the list as saved now,
                            // not as it was when this row first appeared (a second drag used to
                            // start from the old order and move the wrong video).
                            val currentVideos by rememberUpdatedState(playlist.videos)
                            Icon(
                                OrielIcons.DragHandle, "Move", tint = palette.onBackgroundVariant,
                                modifier = Modifier.size(44.dp).padding(10.dp).pointerInput(key) {
                                    detectDragGestures(
                                        onDragStart = {
                                            working = currentVideos
                                            dragFrom = rowKeys(currentVideos).indexOf(key)
                                            dragKey = key
                                            haptics.play(Haptic.Tick)
                                        },
                                        onDragEnd = {
                                            val to = rowKeys(working).indexOf(key)
                                            if (dragFrom >= 0 && to >= 0 && to != dragFrom) graph.library.moveInLocalPlaylist(id, dragFrom, to)
                                            scope.launch { dragOffset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 600f)); dragKey = null }
                                            haptics.play(Haptic.Select)
                                        },
                                        onDragCancel = { scope.launch { dragOffset.animateTo(0f); dragKey = null } },
                                    ) { change, amount ->
                                        change.consume()
                                        var offset = dragOffset.value + amount.y
                                        val at = rowKeys(working).indexOf(key)
                                        val h = rowHeight.toFloat()
                                        if (offset > h / 2 && at < working.lastIndex) {
                                            working = working.toMutableList().apply { add(at + 1, removeAt(at)) }; offset -= h; haptics.play(Haptic.Tick)
                                        } else if (offset < -h / 2 && at > 0) {
                                            working = working.toMutableList().apply { add(at - 1, removeAt(at)) }; offset += h; haptics.play(Haptic.Tick)
                                        }
                                        scope.launch { dragOffset.snapTo(offset) }
                                    }
                                },
                            )
                        } else MoreButton({
                            app.alert = AlertSpec(v.title, null, listOf(
                                AlertAction("Remove from playlist", destructive = true) { graph.library.removeFromLocalPlaylist(id, v.id) },
                                AlertAction("Move to top") { graph.library.moveInLocalPlaylist(id, i, 0) },
                                AlertAction("More…") { app.sheetItem = v },
                                AlertAction("Cancel", emphasized = true),
                            ))
                        }, tint = palette.onBackgroundVariant)
                    },
                )
            }
        }
    }
    if (renaming) TextPromptDialog("Rename playlist", playlist.name, "Save", onDismiss = { renaming = false }) { name ->
        graph.library.renameLocalPlaylist(id, name); renaming = false
    }
}
