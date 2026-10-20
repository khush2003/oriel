package app.oriel.ui.screens

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
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
import app.oriel.ui.components.rowKeys
import app.oriel.ui.components.feedShelves
import app.oriel.ui.navigation.LocalPlaylistRoute
import app.oriel.util.Thumbnails
import java.util.Calendar

/** A plain list of videos with Play all / Shuffle, shared by Watch later and Liked. */
@Composable
private fun VideoListScreen(title: String, videos: List<Video>, empty: String, onRemove: ((Video) -> Unit)?) {
    val app = LocalAppState.current
    val context = QueueContext(title = title)
    PageScaffold(title = title, onBack = app::back) {
        if (videos.isEmpty()) {
            item { MessageState(empty) }
            return@PageScaffold
        }
        item(key = "play") {
            Row(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp)) {
                Box(Modifier.weight(1f)) { ActionPill("Play", OrielIcons.Play, { app.playPlaylist(videos, 0, context) }, Modifier.fillMaxWidth(), height = 46.dp) }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) { ActionPill("Shuffle", OrielIcons.Shuffle, { app.playPlaylist(videos, 0, context, shuffle = true) }, Modifier.fillMaxWidth(), height = 46.dp) }
            }
        }
        item(key = "count") {
            Text("${videos.size} videos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp))
        }
        val keys = rowKeys(videos)
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
    val library by app.graph.library.state.collectAsState()
    VideoListScreen("Watch later", library.watchLater, "Videos you save for later appear here.") { app.graph.actions.toggleWatchLater(it) }
}

@Composable
fun LikedScreen() {
    val app = LocalAppState.current
    val library by app.graph.library.state.collectAsState()
    VideoListScreen("Liked videos", library.liked, "Videos you like appear here.") { app.graph.actions.toggleLike(it) }
}

/** History, grouped the way Photos groups days: Today, Yesterday, This week, then by month. */
@Composable
fun HistoryScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val library by graph.library.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val signedIn by graph.account.signedIn.collectAsState()
    // Signed in, YouTube's own history too: what was watched on other devices and in its apps.
    var onYouTube by rememberSaveable { mutableStateOf(false) }
    val remote: FeedModel? = if (signedIn) viewModel(key = "history-remote") {
        FeedModel(graph, "history", load = { fresh -> graph.yt.browse(YouTube.HISTORY, fresh = fresh) }, maxAgeMs = 60_000L)
    } else null
    val remoteState = remote?.state?.collectAsState()?.value
    var query by rememberSaveable { mutableStateOf("") }
    // Grouped off the main thread with one calendar and one formatter: a few thousand entries
    // made a Calendar and a SimpleDateFormat each, on every letter typed into the search.
    val history = library.history
    val grouped by produceState<List<Pair<String, List<app.oriel.data.WatchEntry>>>?>(null, history, query) {
        value = withContext(Dispatchers.Default) {
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
            val q = query.trim()
            val matching = if (q.isEmpty()) history else history.filter { e ->
                e.video.title.contains(q, ignoreCase = true) || e.video.channel?.name?.contains(q, ignoreCase = true) == true
            }
            matching.groupBy { label(it.watchedAt) }.toList()
        }
    }
    PageScaffold(
        title = "History",
        onBack = app::back,
        actions = {
            PillAction(OrielIcons.Delete, "Clear history") {
                app.alert = AlertSpec("Clear watch history?", "This clears what Oriel remembers on this device, and resume positions.", listOf(
                    AlertAction("Cancel"),
                    AlertAction("Clear", destructive = true) { graph.library.clearHistory() },
                ))
            }
        },
        refreshing = onYouTube && remoteState?.refreshing == true,
        onRefresh = if (onYouTube && remote != null) ({ remote.reload(true) }) else null,
        onNearEnd = { if (onYouTube) remote?.loadMore() },
        onShown = { atTop -> if (onYouTube) remote?.refreshIfStale(atTop) },
    ) {
        if (remote != null) item(key = "where") {
            SegmentedControl(
                listOf(false, true), onYouTube, label = { if (it) "On YouTube" else "On this device" },
                onSelect = { onYouTube = it; if (it) remote.refreshIfStale() },
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
            )
        }
        item(key = "pause") {
            SettingsGroup {
                SwitchRow("Pause watch history", settings.pauseHistory, { v -> graph.settings.update { it.copy(pauseHistory = v) } }, OrielIcons.History)
            }
        }
        if (onYouTube && remoteState != null) {
            refreshNotice(remoteState, onRetry = { remote?.reload(true) })
            val feed = remoteState.feed
            when {
                feed == null && remoteState.loading -> items(4) { VideoCardSkeleton(it) }
                feed == null && remoteState.error != null -> item { MessageState(remoteState.error, action = "Try again", onAction = { remote?.reload() }) }
                feed != null && feed.isEmpty -> item { MessageState(feed.message ?: "Nothing in your YouTube history yet.") }
                feed != null -> feedShelves(feed, compact = true, keyPrefix = "yth")
            }
            feedFooter(remoteState, onRetry = { remote?.loadMore() })
            return@PageScaffold
        }
        if (library.history.isNotEmpty()) item(key = "search") {
            SearchField(query, { query = it }, { }, Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp), placeholder = "Search history")
        }
        if (library.history.isEmpty()) item(key = "empty") { MessageState("Videos you watch appear here.") }
        val groups = grouped.orEmpty()
        if (library.history.isNotEmpty() && grouped != null && groups.isEmpty()) item(key = "no-match") { MessageState("Nothing in your history matches “${query.trim()}”.", icon = OrielIcons.Search) }
        groups.forEach { (label, entries) ->
            item(key = "h:$label") { SectionHeader(label) }
            items(entries.distinctBy { "${it.video.id}:${it.watchedAt}" }, key = { "${it.video.id}:${it.watchedAt}" }) { e ->
                val pos = library.positions[e.video.id]
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
    val library by app.graph.library.state.collectAsState()
    val channels = remember(library.subscriptions) { library.subscriptions.sortedBy { it.title.lowercase() } }
    PageScaffold(title = "Channels", onBack = app::back) {
        if (channels.isEmpty()) item { MessageState("Channels you subscribe to appear here.", icon = OrielIcons.Subscriptions) }
        items(channels.distinctBy { it.id }, key = { it.id }) { ChannelRow(it) }
    }
}

@Composable
fun PlaylistsScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val library by graph.library.state.collectAsState()
    val signedIn by graph.account.signedIn.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val remote: FeedModel? = if (signedIn) viewModel(key = "playlists-remote") { FeedModel(graph, "playlists", load = { fresh -> graph.yt.browse(YouTube.PLAYLISTS, fresh = fresh) }, maxAgeMs = 5 * 60_000L) } else null
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
        if (library.localPlaylists.isNotEmpty()) {
            item(key = "local-h") { SectionHeader("On this device") }
            items(library.localPlaylists.distinctBy { it.id }, key = { "l:" + it.id }) { p ->
                val pl = Playlist("local:" + p.id, p.name, thumbnail = p.videos.firstOrNull()?.let { Thumbnails.of(it, 480) }, countText = "${p.videos.size} videos")
                Box(Modifier.clickable { app.navigate(LocalPlaylistRoute(p.id)) }) {
                    LocalPlaylistRowView(pl)
                }
            }
        }
        if (library.savedPlaylists.isNotEmpty()) {
            item(key = "saved-h") { SectionHeader("Saved") }
            items(library.savedPlaylists.distinctBy { it.id }, key = { "s:" + it.id }) { PlaylistRow(it) }
        }
        if (remoteState != null) {
            item(key = "remote-h") { SectionHeader("On YouTube") }
            val feed = remoteState.feed
            refreshNotice(remoteState, onRetry = { remote?.reload(true) })
            when {
                feed == null && remoteState.loading -> items(3, key = { "pl-sk$it" }) { VideoCardSkeleton(it) }
                feed == null && remoteState.error != null -> item(key = "pl-error") { MessageState(remoteState.error, action = "Try again", onAction = { remote?.reload() }) }
                feed != null && feed.isEmpty -> item(key = "pl-empty") { MessageState(feed.message ?: "No playlists on your YouTube account yet.") }
                feed != null -> feedShelves(feed, compact = true, keyPrefix = "pl")
            }
            feedFooter(remoteState, onRetry = { remote?.loadMore() })
        }
        if (library.localPlaylists.isEmpty() && library.savedPlaylists.isEmpty() && remoteState == null) {
            item { MessageState("Make a playlist with ＋, or save one from YouTube.", icon = OrielIcons.Playlist) }
        }
    }
    if (creating) NewPlaylistDialog(signedIn, onDismiss = { creating = false }) { name, onAccount, private ->
        creating = false
        if (onAccount) graph.actions.createAccountPlaylist(name, private, null) else graph.library.createPlaylist(name)
    }
}

@Composable
private fun LocalPlaylistRowView(p: Playlist) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (p.thumbnail != null) RemoteImage(p.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(10.dp))
            else Icon(OrielIcons.Playlist, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.countText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
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
            feed == null && state.loading -> items(4) { VideoCardSkeleton(it) }
            feed == null && state.error != null -> item { MessageState(state.error!!, action = "Try again", onAction = { model.reload() }) }
            feed != null && feed.isEmpty && !state.loading -> item { MessageState(feed.message ?: "Nothing here right now.", action = "Try again", onAction = { model.reload() }) }
            feed != null -> feedShelves(feed, keyPrefix = "topic")
        }
        feedFooter(state, onRetry = model::loadMore)
    }
}
