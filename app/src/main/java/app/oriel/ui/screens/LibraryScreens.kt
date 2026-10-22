package app.oriel.ui.screens

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import app.oriel.AppGraph
import app.oriel.data.WatchEntry
import app.oriel.ui.components.TextPromptDialog
import app.oriel.ui.components.VideoRowSkeleton
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.core.Topic
import app.oriel.core.YouTube
import app.oriel.core.model.Playlist
import app.oriel.core.model.Video
import app.oriel.playback.QueueContext
import app.oriel.ui.AlertAction
import app.oriel.ui.AlertSpec
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.ActionPill
import app.oriel.ui.components.ROW_CORNER
import app.oriel.ui.components.ChannelRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.MoreButton
import app.oriel.ui.components.NewPlaylistDialog
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.SegmentedControl
import app.oriel.ui.components.PillAction
import app.oriel.ui.components.PlaylistRow
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.components.SectionHeader
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SwitchRow
import app.oriel.ui.components.VideoCardSkeleton
import app.oriel.ui.components.VideoRow
import app.oriel.ui.components.SearchField
import app.oriel.ui.components.SwipeToRemove
import app.oriel.ui.components.feedItem
import app.oriel.ui.components.longPressMenuClickable
import app.oriel.ui.components.rowKeys
import app.oriel.ui.components.feedShelves
import app.oriel.ui.navigation.LocalPlaylistRoute
import app.oriel.util.Thumbnails
import java.util.Calendar

/** A plain list of videos with Play all / Shuffle, shared by Watch later and Liked. */
@Composable
private fun VideoListScreen(title: String, videos: List<Video>, empty: String, onRemove: ((Video) -> Unit)?) {
    val app = LocalAppState.current
    val context = remember(title) { QueueContext(title = title) }
    val keys = remember(videos) { rowKeys(videos) }
    PageScaffold(title = title, onBack = app::back) {
        if (videos.isEmpty()) {
            item(key = "empty", contentType = "message") { MessageState(empty) }
            return@PageScaffold
        }
        item(key = "play", contentType = "pills") {
            Row(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp)) {
                Box(Modifier.weight(1f)) { ActionPill("Play", OrielIcons.Play, { app.playPlaylist(videos, 0, context) }, Modifier.fillMaxWidth()) }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) { ActionPill("Shuffle", OrielIcons.Shuffle, { app.playPlaylist(videos, 0, context, shuffle = true) }, Modifier.fillMaxWidth()) }
            }
        }
        item(key = "count", contentType = "count") {
            Text(Plurals.count(videos.size, "video"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp))
        }
        itemsIndexed(videos, key = { i, _ -> keys[i] }, contentType = { _, _ -> "row" }) { i, v ->
            // Swipe left to remove, as iOS lists do; the rest glide up into the gap.
            SwipeToRemove(onRemove?.let { r -> { r(v) } }, feedItem()) {
                VideoRow(v, onClick = { app.playPlaylist(videos, i, context) }, trailing = {
                    MoreButton({
                        if (onRemove == null) app.sheetItem = v
                        else app.alert = AlertSpec(v.title, null, listOf(
                            AlertAction("Remove from $title", destructive = true) { onRemove(v) },
                            AlertAction("More…") { app.sheetItem = v },
                            AlertAction("Cancel", emphasized = true),
                        ))
                    })
                })
            }
        }
    }
}

@Composable
fun WatchLaterScreen() {
    val app = LocalAppState.current
    val videos by app.graph.library.state.collectSlice { it.watchLater }
    VideoListScreen("Watch later", videos, "Videos you save for later appear here.") { app.graph.actions.toggleWatchLater(it) }
}

@Composable
fun LikedScreen() {
    val app = LocalAppState.current
    val videos by app.graph.library.state.collectSlice { it.liked }
    VideoListScreen("Liked videos", videos, "Videos you like appear here.") { app.graph.actions.toggleLike(it) }
}

/**
 * History's groups, worked out off the main thread and kept for the page: coming back to
 * History used to start from nothing for a frame, and the list, too short to hold the saved
 * position, lost its place. The search text outlives the process, as the page's other state does.
 */
class HistoryModel(graph: AppGraph, private val saved: SavedStateHandle) : ViewModel() {
    val query: StateFlow<String> = saved.getStateFlow(KEY_QUERY, "")

    fun type(text: String) { saved[KEY_QUERY] = text }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val groups: StateFlow<List<Pair<String, List<WatchEntry>>>?> =
        combine(graph.library.state.map { it.history }.distinctUntilChanged(), query) { h, q -> h to q.trim() }
            .mapLatest { (history, q) -> withContext(Dispatchers.Default) { group(history, q) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private companion object {
        const val KEY_QUERY = "query"

        // One calendar and one formatter for the lot: a few thousand entries made one of each apiece.
        fun group(history: List<WatchEntry>, q: String): List<Pair<String, List<WatchEntry>>> {
            val c = Calendar.getInstance()
            val today = startOfDay(c)
            val month = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
            fun label(ms: Long): String {
                c.timeInMillis = ms
                val days = ((today - startOfDay(c)) / 86_400_000L).toInt()
                return when {
                    days <= 0 -> "Today"
                    days == 1 -> "Yesterday"
                    days < 7 -> "This week"
                    else -> month.format(c.time)
                }
            }
            val matching = if (q.isEmpty()) history else history.filter { e ->
                e.video.title.contains(q, ignoreCase = true) || e.video.channel?.name?.contains(q, ignoreCase = true) == true
            }
            return matching.distinctBy { "${it.video.id}:${it.watchedAt}" }.groupBy { label(it.watchedAt) }.toList()
        }
    }
}

/** History, grouped the way Photos groups days: Today, Yesterday, This week, then by month. */
@Composable
fun HistoryScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val model: HistoryModel = viewModel { HistoryModel(graph, createSavedStateHandle()) }
    val hasHistory by graph.library.state.collectSlice { it.history.isNotEmpty() }
    // Only the resume points: the rows' progress bars.
    val positions by graph.library.state.collectSlice { it.positions }
    val pauseHistory by graph.settings.state.collectSlice { it.pauseHistory }
    val signedIn by graph.account.signedIn.collectAsState()
    val account by graph.account.account.collectAsState()
    // Signed in, YouTube's own history too: what was watched on other devices and in its apps.
    var onYouTube by rememberSaveable { mutableStateOf(false) }
    // One model per account, so another sign-in never shows the last one's history.
    val remote: FeedModel? = if (signedIn) viewModel(key = "history-remote:${accountOwner(account)?.hashCode()}") {
        FeedModel(graph, "history", load = { fresh -> graph.yt.browse(YouTube.HISTORY, fresh = fresh) }, maxAgeMs = 60_000L)
    } else null
    val remoteState = remote?.state?.collectAsState()?.value
    val query by model.query.collectAsState()
    val grouped by model.groups.collectAsState()
    PageScaffold(
        title = "History",
        onBack = app::back,
        // Clearing is this device's history only, so it's offered only while that's shown.
        actions = if (onYouTube) null else ({
            PillAction(OrielIcons.Delete, "Clear history") {
                app.alert = AlertSpec("Clear watch history?", "This clears what Oriel remembers on this device, and resume positions.", listOf(
                    AlertAction("Cancel"),
                    AlertAction("Clear", destructive = true) { graph.library.clearHistory() },
                ))
            }
        }),
        refreshing = onYouTube && remoteState?.refreshing == true,
        onRefresh = if (onYouTube && remote != null) ({ remote.reload(true) }) else null,
        onNearEnd = { if (onYouTube) remote?.loadMore() },
        onShown = { atTop -> if (onYouTube) remote?.refreshIfStale(atTop) },
    ) {
        if (remote != null) item(key = "where", contentType = "segments") {
            SegmentedControl(
                listOf(false, true), onYouTube, label = { if (it) "On YouTube" else "On this device" },
                onSelect = { onYouTube = it; if (it) remote.refreshIfStale() },
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
            )
        }
        item(key = "pause", contentType = "group") {
            SettingsGroup {
                SwitchRow("Pause watch history", pauseHistory, { v -> graph.settings.update { it.copy(pauseHistory = v) } }, OrielIcons.History)
            }
        }
        if (onYouTube && remoteState != null) {
            refreshNotice(remoteState, onRetry = { remote?.reload(true) })
            val feed = remoteState.feed
            when {
                feed == null && remoteState.loading -> items(4, key = { "sk$it" }, contentType = { "rowSkeleton" }) { VideoRowSkeleton(it) }
                feed == null && remoteState.error != null -> item(key = "error", contentType = "message") { MessageState(remoteState.error, action = "Try again", onAction = { remote?.reload() }) }
                feed != null && feed.isEmpty -> item(key = "empty-yt", contentType = "message") { MessageState(feed.message ?: "Nothing in your YouTube history yet.") }
                feed != null -> feedShelves(feed, compact = true, keyPrefix = "yth")
            }
            feedFooter(remoteState, onRetry = { remote?.retryMore() })
            return@PageScaffold
        }
        if (hasHistory) item(key = "search", contentType = "search") {
            SearchField(query, model::type, { }, Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp), placeholder = "Search history")
        }
        if (!hasHistory) item(key = "empty", contentType = "message") { MessageState("Videos you watch appear here.", icon = OrielIcons.History) }
        val groups = grouped.orEmpty()
        if (hasHistory && grouped != null && groups.isEmpty()) item(key = "no-match", contentType = "message") { MessageState("Nothing in your history matches “${query.trim()}”.", icon = OrielIcons.Search) }
        groups.forEach { (label, entries) ->
            item(key = "h:$label", contentType = "header") { SectionHeader(label) }
            items(entries, key = { "${it.video.id}:${it.watchedAt}" }, contentType = { "row" }) { e ->
                val pos = positions[e.video.id]
                val dur = e.video.durationSec ?: 0
                val v = if (pos != null && dur > 0) e.video.copy(progress = (pos / 1000f / dur).coerceIn(0f, 1f)) else e.video
                SwipeToRemove({ graph.library.removeHistory(e.video.id) }, feedItem()) {
                    VideoRow(v, onClick = { app.openVideo(e.video) }, trailing = {
                        MoreButton({ app.sheetItem = e.video })
                    })
                }
            }
        }
    }
}

private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun ChannelsScreen() {
    val app = LocalAppState.current
    val subscriptions by app.graph.library.state.collectSlice { it.subscriptions }
    val channels = remember(subscriptions) { subscriptions.distinctBy { it.id }.sortedBy { it.title.lowercase() } }
    PageScaffold(title = "Channels", onBack = app::back) {
        if (channels.isEmpty()) item(key = "empty", contentType = "message") { MessageState("Channels you subscribe to appear here.", icon = OrielIcons.Subscriptions) }
        items(channels, key = { it.id }, contentType = { "channel" }) { ChannelRow(it) }
    }
}

@Composable
fun PlaylistsScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val localPlaylists by graph.library.state.collectSlice { it.localPlaylists }
    val savedPlaylists by graph.library.state.collectSlice { it.savedPlaylists }
    // Drawn as playlists, once per change rather than per row per recomposition.
    val local = remember(localPlaylists) {
        localPlaylists.distinctBy { it.id }.map { p -> p.id to Playlist("local:" + p.id, p.name, thumbnail = p.videos.firstOrNull()?.let { Thumbnails.of(it, 480) }, countText = Plurals.count(p.videos.size, "video")) }
    }
    val saved = remember(savedPlaylists) { savedPlaylists.distinctBy { it.id } }
    val signedIn by graph.account.signedIn.collectAsState()
    val account by graph.account.account.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    val haptics = rememberHaptics()
    val remote: FeedModel? = if (signedIn) viewModel(key = "playlists-remote:${accountOwner(account)?.hashCode()}") { FeedModel(graph, "playlists", load = { fresh -> graph.yt.browse(YouTube.PLAYLISTS, fresh = fresh) }, maxAgeMs = 5 * 60_000L) } else null
    val remoteState = remote?.state?.collectAsState()?.value
    PageScaffold(
        title = "Playlists",
        onBack = app::back,
        actions = { PillAction(OrielIcons.Add, "New playlist") { creating = true } },
        refreshing = remoteState?.refreshing == true,
        onRefresh = remote?.let { r -> { r.reload(true) } },
        onNearEnd = { remote?.loadMore() },
        onShown = { atTop -> remote?.refreshIfStale(atTop) },
    ) {
        if (local.isNotEmpty()) {
            item(key = "local-h", contentType = "header") { SectionHeader("On this device") }
            items(local, key = { "l:" + it.first }, contentType = { "local" }) { (id, pl) ->
                LocalPlaylistRowView(pl, onClick = { haptics.play(Haptic.Tap); app.navigate(LocalPlaylistRoute(id)) }, onMore = {
                    app.alert = AlertSpec(pl.title, null, listOf(
                        AlertAction("Rename") { renaming = id to pl.title },
                        AlertAction("Delete playlist", destructive = true) { confirmDelete(app, id, pl.title) },
                        AlertAction("Cancel", emphasized = true),
                    ))
                })
            }
        }
        if (saved.isNotEmpty()) {
            item(key = "saved-h", contentType = "header") { SectionHeader("Saved") }
            items(saved, key = { "s:" + it.id }, contentType = { "playlist" }) { PlaylistRow(it) }
        }
        if (remoteState != null) {
            item(key = "remote-h", contentType = "header") { SectionHeader("On YouTube") }
            val feed = remoteState.feed
            refreshNotice(remoteState, onRetry = { remote?.reload(true) })
            when {
                // The same shape as the rows that replace them.
                feed == null && remoteState.loading -> items(3, key = { "pl-sk$it" }, contentType = { "rowSkeleton" }) { VideoRowSkeleton(it) }
                feed == null && remoteState.error != null -> item(key = "pl-error", contentType = "message") { MessageState(remoteState.error, action = "Try again", onAction = { remote?.reload() }) }
                feed != null && feed.isEmpty -> item(key = "pl-empty", contentType = "message") { MessageState(feed.message ?: "No playlists on your YouTube account yet.") }
                feed != null -> feedShelves(feed, compact = true, keyPrefix = "pl")
            }
            feedFooter(remoteState, onRetry = { remote?.retryMore() })
        }
        if (local.isEmpty() && saved.isEmpty() && remoteState == null) {
            item(key = "empty", contentType = "message") { MessageState("Make a playlist with ＋, or save one from YouTube.", icon = OrielIcons.Playlist) }
        }
    }
    if (creating) NewPlaylistDialog(signedIn, onDismiss = { creating = false }) { name, onAccount, private ->
        creating = false
        if (onAccount) graph.actions.createAccountPlaylist(name, private, null) else graph.library.createPlaylist(name)
    }
    renaming?.let { (id, name) ->
        TextPromptDialog("Rename playlist", name, "Save", onDismiss = { renaming = null }) { new ->
            graph.library.renameLocalPlaylist(id, new); renaming = null
        }
    }
}

/** Deleting a playlist kept only on this phone can't be undone: asked first, as iOS does. */
fun confirmDelete(app: app.oriel.ui.AppState, id: String, name: String, after: () -> Unit = {}) {
    app.alert = AlertSpec("Delete “$name”?", "This removes the playlist from this device. The videos stay on YouTube.", listOf(
        AlertAction("Cancel"),
        AlertAction("Delete", destructive = true) { app.graph.library.deleteLocalPlaylist(id); after() },
    ))
}

/** A playlist on this phone: opens on tap; held or ⋯, Rename and Delete. */
@Composable
private fun LocalPlaylistRowView(p: Playlist, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().longPressMenuClickable(onClick = onClick, onLongClick = onMore).padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(ROW_CORNER)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (p.thumbnail != null) RemoteImage(p.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(ROW_CORNER))
            else Icon(OrielIcons.Playlist, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.countText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        MoreButton(onMore)
    }
}

@Composable
fun TopicScreen(topicName: String) {
    val app = LocalAppState.current
    val graph = app.graph
    val topic = Topic.entries.firstOrNull { it.name == topicName } ?: Topic.MUSIC
    val model: FeedModel = viewModel(key = "topic:$topicName") { FeedModel(graph, "topic-$topicName", load = { fresh -> graph.yt.topic(topic, fresh) }, maxAgeMs = 30 * 60_000L) }
    val state by model.state.collectAsState()
    PageScaffold(title = topic.title, onBack = app::back, refreshing = state.refreshing, onRefresh = { model.reload(true) }, onNearEnd = model::loadMore, onShown = model::refreshIfStale) {
        val feed = state.feed
        refreshNotice(state, onRetry = { model.reload(true) })
        when {
            feed == null && state.loading -> items(4, key = { "sk$it" }, contentType = { "skeleton" }) { VideoCardSkeleton(it) }
            feed == null && state.error != null -> item(key = "error", contentType = "message") { MessageState(state.error!!, action = "Try again", onAction = { model.reload() }) }
            feed != null && feed.isEmpty && !state.loading -> item(key = "empty", contentType = "message") { MessageState(feed.message ?: "Nothing here right now.", action = "Try again", onAction = { model.reload() }) }
            feed != null -> feedShelves(feed, keyPrefix = "topic")
        }
        feedFooter(state, onRetry = model::retryMore)
    }
}
