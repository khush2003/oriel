package app.oriel.ui.screens

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import app.oriel.ui.components.DestructiveRed
import app.oriel.ui.navigation.NotificationsRoute
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import app.oriel.core.model.Video
import app.oriel.ui.components.RemoteImage
import app.oriel.util.Thumbnails
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Playlist
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.PlaylistShelfCard
import app.oriel.ui.components.RowDivider
import app.oriel.ui.components.SectionHeader
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SettingsRow
import app.oriel.ui.components.ShelfRow
import app.oriel.ui.components.VideoShelfCard
import app.oriel.ui.navigation.ChannelsRoute
import app.oriel.ui.navigation.HistoryRoute
import app.oriel.ui.navigation.LikedRoute
import app.oriel.ui.navigation.LocalPlaylistRoute
import app.oriel.ui.navigation.LoginRoute
import app.oriel.ui.navigation.PlaylistsRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.navigation.Tab
import app.oriel.ui.navigation.WatchLaterRoute
import kotlinx.coroutines.launch

/** You: the account, what you watched, and everything you kept. */
@Composable
fun YouScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val signedIn by graph.account.signedIn.collectAsState()
    val account by graph.account.account.collectAsState()
    val syncing by graph.account.syncing.collectAsState()
    val library by graph.library.state.collectAsState()

    PageScaffold(
        title = "You",
        tab = Tab.YOU,
        refreshing = syncing,
        onRefresh = if (signedIn) ({ app.scope.launch { graph.account.sync() } }) else null,
        // In the title row, as Home's avatar is, so the page needs no bar row above its title.
        titleTrailing = {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { app.navigate(SettingsRoute) },
                contentAlignment = Alignment.Center,
            ) { Icon(OrielIcons.Settings, "Settings", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp)) }
        },
    ) {
        item(key = "account") {
            if (signedIn) {
                Row(
                    Modifier.fillMaxWidth().clickable { app.navigate(SettingsRoute) }.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(account?.avatar, 64.dp, name = account?.name)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account?.name ?: "Signed in", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        (account?.handle ?: account?.email)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                }
            } else {
                SignInCard(onSignIn = { app.navigate(LoginRoute) }, onDismiss = null, text = "Your library works on this device without an account. Sign in to sync likes, subscriptions, Watch later and playlists with YouTube.")
            }
        }
        if (library.history.isNotEmpty()) item(key = "history") {
            Column {
                SectionHeader("History", onMore = { app.navigate(HistoryRoute) }, moreLabel = "View all")
                ShelfRow(library.history.take(16).map { it.video }) { v ->
                    val video = v as Video
                    val pos = library.positions[video.id]
                    val withProgress = if (pos != null && (video.durationSec ?: 0) > 0) video.copy(progress = (pos / 1000f / video.durationSec!!).coerceIn(0f, 1f)) else video
                    VideoShelfCard(withProgress, width = 200.dp)
                }
            }
        }
        if (signedIn) item(key = "inbox") {
            // The bell's count, asked each time You comes into view; opening the inbox clears it on YouTube's side.
            var unseen by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { unseen = withContext(Dispatchers.IO) { runCatching { graph.yt.unseenNotifications() }.getOrDefault(0) } }
            SettingsGroup(top = 14.dp) {
                SettingsRow("Notifications", OrielIcons.Bell, chevron = true, onClick = { unseen = 0; app.navigate(NotificationsRoute) }, trailing = if (unseen > 0) ({ CountBadge(unseen) }) else null)
            }
        }
        item(key = "library") {
            SettingsGroup {
                SettingsRow("Watch later", OrielIcons.WatchLater, value = library.watchLater.size.takeIf { it > 0 }?.toString(), chevron = true, onClick = { app.navigate(WatchLaterRoute) })
                RowDivider()
                SettingsRow("Liked videos", OrielIcons.Like, value = library.liked.size.takeIf { it > 0 }?.toString(), chevron = true, onClick = { app.navigate(LikedRoute) })
                RowDivider()
                SettingsRow("History", OrielIcons.History, chevron = true, onClick = { app.navigate(HistoryRoute) })
            }
        }
        item(key = "library2") {
            SettingsGroup {
                SettingsRow("Playlists", OrielIcons.Playlist, value = (library.localPlaylists.size + library.savedPlaylists.size).takeIf { it > 0 }?.toString(), chevron = true, onClick = { app.navigate(PlaylistsRoute) })
                RowDivider()
                SettingsRow("Channels", OrielIcons.Subscriptions, value = library.subscriptions.size.takeIf { it > 0 }?.toString(), chevron = true, onClick = { app.navigate(ChannelsRoute) })
            }
        }
        val playlists: List<Playlist> = library.localPlaylists.map { p ->
            Playlist(id = "local:" + p.id, title = p.name, thumbnail = p.videos.firstOrNull()?.let { Thumbnails.of(it, 480) }, countText = "${p.videos.size} videos")
        } + library.savedPlaylists
        if (playlists.isNotEmpty()) item(key = "playlists") {
            Column {
                SectionHeader("Your playlists", onMore = { app.navigate(PlaylistsRoute) }, moreLabel = "View all")
                ShelfRow(playlists.take(12)) { item ->
                    val p = item as Playlist
                    if (p.id.startsWith("local:")) {
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { app.navigate(LocalPlaylistRoute(p.id.removePrefix("local:"))) }) { PlaylistShelfCardStatic(p) }
                    } else PlaylistShelfCard(p, width = 200.dp)
                }
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(CircleShape).background(DestructiveRed).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else "$count", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** A playlist card that doesn't open YouTube's page (local playlists open their own). */
@Composable
private fun PlaylistShelfCardStatic(p: Playlist) {
    Column(Modifier.width(200.dp)) {
        RemoteImage(p.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(12.dp))
        Spacer(Modifier.padding(top = 6.dp))
        Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        p.countText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
