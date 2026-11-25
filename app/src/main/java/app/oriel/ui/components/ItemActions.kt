package app.oriel.ui.components

import app.oriel.ui.AlertAction
import app.oriel.util.Thumbnails
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Channel
import app.oriel.core.model.Item
import app.oriel.core.model.Playlist
import app.oriel.ui.navigation.LocalPlaylistRoute
import app.oriel.core.model.Post
import app.oriel.core.model.Video
import app.oriel.ui.AlertSpec
import app.oriel.ui.AppState

/** The header of an item's sheet: its picture and title, as iOS context menus preview what they act on. */
@Composable
private fun ItemHeader(item: Item) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item) {
            is Video -> RemoteImage(Thumbnails.of(item, 240), Modifier.width(96.dp).height(54.dp), RoundedCornerShape(8.dp))
            is Channel -> Avatar(item.avatar, 54.dp, name = item.title)
            is Playlist -> RemoteImage(item.thumbnail, Modifier.width(96.dp).height(54.dp), RoundedCornerShape(8.dp))
            is Post -> Avatar(item.author?.avatar, 54.dp, name = item.author?.name)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when (item) {
                is Video -> item.channel?.name
                is Channel -> item.subscribersText ?: item.handle
                is Playlist -> listOfNotNull(item.owner?.name, item.countText).joinToString(" · ")
                is Post -> item.author?.name
            }
            sub?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/**
 * What can be done with an item, as rows: shared by the long-press menu
 * (lifted over the blurred page) and the ⋮ sheet.
 */
@Composable
fun ColumnScope.ItemActions(app: AppState, item: Item, dismiss: () -> Unit) {
    val library by app.graph.library.state.collectAsState()
    fun run(block: () -> Unit) { dismiss(); block() }
    when (item) {
        is Video -> {
            MenuRow("Play next", OrielIcons.Queue, { run { app.connection.playNext(listOf(item)); app.notice("Playing next") } })
            MenuRow("Add to queue", OrielIcons.Playlist, { run { app.connection.addToQueue(listOf(item)); app.notice("Added to queue") } })
            MenuGroupBand()
            val later = library.watchLater.any { it.id == item.id }
            MenuRow(if (later) "Remove from Watch later" else "Save to Watch later", OrielIcons.WatchLater, { run { app.graph.actions.toggleWatchLater(item) } })
            MenuRow("Save to playlist", OrielIcons.Save, { run { app.saveTarget = item } })
            MenuRow("Listen in the background", OrielIcons.AudioOnly, {
                run {
                    app.openVideo(item, expand = false)
                    app.connection.setAudioOnly(true)
                }
            })
            MenuGroupBand()
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
            item.channel?.id?.let { id -> MenuRow("Go to channel", OrielIcons.Person, { run { app.openChannel(id) } }) }
            if (library.history.any { it.video.id == item.id }) {
                MenuRow("Remove from history", OrielIcons.History, { run { app.graph.library.removeHistory(item.id) } }, destructive = true)
            }
        }
        is Channel -> {
            val subscribed = app.graph.library.subscribedIds.collectAsState().value.contains(item.id)
            MenuRow(if (subscribed) "Unsubscribe" else "Subscribe", if (subscribed) OrielIcons.Bell else OrielIcons.BellOutline, { run { app.toggleSubscribe(item) } }, destructive = subscribed)
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
        is Playlist -> if (item.id.startsWith("local:")) {
            // A playlist kept on this device: nothing on YouTube to share or save it to.
            val id = item.id.removePrefix("local:")
            MenuRow("Open playlist", OrielIcons.Playlist, { run { app.navigate(LocalPlaylistRoute(id)) } })
            MenuRow("Rename", OrielIcons.Edit, { run { app.renameTarget = item } })
            MenuRow("Delete playlist", OrielIcons.Delete, {
                run {
                    app.alert = AlertSpec("Delete “${item.title}”?", "This removes the playlist from this device. The videos stay on YouTube.", listOf(
                        AlertAction("Cancel"),
                        AlertAction("Delete", destructive = true) { app.graph.library.deleteLocalPlaylist(id) },
                    ))
                }
            }, destructive = true)
        } else {
            if (item.isMix) {
                MenuRow("Play mix", OrielIcons.Play, { run { app.openItem(item) } })
            } else {
                MenuRow("Open playlist", OrielIcons.Playlist, { run { app.openPlaylist(item.id) } })
                val saved = item.id in app.graph.library.savedPlaylistIds.collectAsState().value
                MenuRow(if (saved) "Remove from library" else "Save to library", OrielIcons.Library, { run { app.graph.actions.toggleSaved(item) } })
            }
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
        is Post -> {
            item.video?.let { v -> MenuRow("Play video", OrielIcons.Play, { run { app.openVideo(v) } }) }
            item.author?.id?.let { id -> MenuRow("Go to channel", OrielIcons.Person, { run { app.openChannel(id) } }) }
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
    }
}

@Composable
fun ItemActionsSheet(app: AppState, item: Item, onDismiss: () -> Unit) {
    OrielSheet(onDismiss) {
        val close = LocalSheetClose.current
        ItemHeader(item)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = PAGE_GUTTER).glassPane(RoundedCornerShape(22.dp))) {
            ItemActions(app, item) { close {} }
        }
    }
}

/**
 * Save to…: Watch later, the playlists kept on the device, and, signed in,
 * the account's own playlists with a tick on those already holding it.
 */
@Composable
fun SaveToPlaylistSheet(app: AppState, video: Video, onDismiss: () -> Unit) {
    val library by app.graph.library.state.collectAsState()
    val signedIn by app.graph.account.signedIn.collectAsState()
    var remote by remember { mutableStateOf<List<Pair<Playlist, Boolean>>?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(video.id, signedIn) { if (signedIn) remote = app.graph.actions.accountPlaylists(video.id) }

    // The sheet's own close, so finishing a new playlist slides it down rather than cutting it.
    // (A plain holder, not state: it's written while composing.)
    val closeSheet = remember { arrayOfNulls<(() -> Unit) -> Unit>(1) }
    OrielSheet(onDismiss) {
        closeSheet[0] = LocalSheetClose.current
        SheetTitle("Save to…", trailing = { SheetAction("New") { creating = true } })
        SettingsGroup(header = "On this device", top = 8.dp) {
            val later = library.watchLater.any { it.id == video.id }
            ChoiceRow("Watch later", later, multi = true) { app.graph.actions.toggleWatchLater(video) }
            library.localPlaylists.forEach { p ->
                RowDivider(inset = false)
                val has = p.videos.any { it.id == video.id }
                ChoiceRow(p.name, has, detail = "${p.videos.size}", multi = true) {
                    if (has) app.graph.library.removeFromLocalPlaylist(p.id, video.id) else app.graph.actions.addToLocalPlaylist(p, listOf(video))
                }
            }
        }
        if (signedIn) {
            SettingsGroup(header = "Your YouTube playlists", footer = "Changes here are made on your YouTube account.") {
                val list = remote
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { ActivityIndicator() }
                    list.isEmpty() -> Text("No playlists yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    else -> list.forEachIndexed { i, (p, has) ->
                        if (i > 0) RowDivider(inset = false)
                        ChoiceRow(p.title, has, multi = true) {
                            remote = list.map { if (it.first.id == p.id) it.first to !has else it }
                            app.graph.actions.setInAccountPlaylist(p.id, video, !has, p.title)
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewPlaylistDialog(signedIn, onDismiss = { creating = false }) { name, onAccount, private ->
            creating = false
            if (onAccount) app.graph.actions.createAccountPlaylist(name, private, video)
            else app.graph.actions.addToLocalPlaylist(app.graph.library.createPlaylist(name), listOf(video))
            closeSheet[0]?.invoke {} ?: onDismiss()
        }
    }
}
