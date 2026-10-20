package app.oriel.data

import app.oriel.AppGraph
import app.oriel.core.YouTube
import app.oriel.core.model.Channel
import app.oriel.core.model.ChannelRef
import app.oriel.core.model.LikeStatus
import app.oriel.core.model.Playlist
import app.oriel.core.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library mutations. Each lands in [LibraryStore] at once, so the UI is
 * instant and everything works signed out, and is mirrored to the YouTube
 * account in the background when signed in. A failed mirror is reported, not
 * retried silently, and the local state stays; the next sync reconciles it.
 */
class LibraryActions(private val graph: AppGraph) {
    private val library get() = graph.library
    private val yt get() = graph.yt

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short confirmations and failures for the notice banner. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    fun notice(text: String) {
        _notices.tryEmit(text)
    }

    private fun mirror(what: String, block: suspend () -> Unit) {
        if (!yt.isSignedIn) return
        graph.scope.launch(Dispatchers.IO) {
            runCatching { block() }.onFailure { _notices.tryEmit("Couldn't $what on your YouTube account") }
        }
    }

    // ---- ratings -------------------------------------------------------------------------

    fun rating(id: String): LikeStatus = when {
        library.isLiked(id) -> LikeStatus.LIKE
        library.isDisliked(id) -> LikeStatus.DISLIKE
        else -> LikeStatus.INDIFFERENT
    }

    fun rate(video: Video, status: LikeStatus) {
        when (status) {
            LikeStatus.LIKE -> library.setLiked(video, true)
            LikeStatus.DISLIKE -> library.setDisliked(video, true)
            LikeStatus.INDIFFERENT -> {
                library.setLiked(video, false)
                library.setDisliked(video, false)
            }
        }
        mirror("save this rating") { yt.rate(video.id, status) }
    }

    /** Like ⇄ no rating; returns whether it's liked now. */
    fun toggleLike(video: Video): Boolean {
        val liked = rating(video.id) != LikeStatus.LIKE
        rate(video, if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT)
        return liked
    }

    fun toggleDislike(video: Video): Boolean {
        val disliked = rating(video.id) != LikeStatus.DISLIKE
        rate(video, if (disliked) LikeStatus.DISLIKE else LikeStatus.INDIFFERENT)
        return disliked
    }

    // ---- watch later ---------------------------------------------------------------------

    fun toggleWatchLater(video: Video): Boolean {
        val add = !library.isInWatchLater(video.id)
        library.setWatchLater(video, add)
        notice(if (add) "Saved to Watch later" else "Removed from Watch later")
        mirror("update Watch later") {
            if (add) yt.addToPlaylist(YouTube.WATCH_LATER, video.id) else yt.removeFromPlaylist(YouTube.WATCH_LATER, video.id)
        }
        return add
    }

    // ---- subscriptions -------------------------------------------------------------------

    fun isSubscribed(channelId: String) = library.isSubscribed(channelId)

    fun setSubscribed(channel: Channel, subscribed: Boolean) {
        library.setSubscribed(channel, subscribed)
        mirror(if (subscribed) "subscribe" else "unsubscribe") { yt.subscribe(channel.id, subscribed) }
    }

    fun toggleSubscribed(channel: Channel): Boolean {
        val subscribed = !isSubscribed(channel.id)
        setSubscribed(channel, subscribed)
        return subscribed
    }

    fun toggleSubscribed(ref: ChannelRef): Boolean {
        val id = ref.id ?: return false
        return toggleSubscribed(Channel(id = id, title = ref.name, avatar = ref.avatar, verified = ref.verified))
    }

    // ---- playlists -----------------------------------------------------------------------

    fun isSaved(playlist: Playlist) = playlist.id in library.savedPlaylistIds.value

    fun toggleSaved(playlist: Playlist): Boolean {
        val saved = !isSaved(playlist)
        library.setPlaylistSaved(playlist, saved)
        notice(if (saved) "Saved to library" else "Removed from library")
        if (!playlist.isMix) mirror("save this playlist") { yt.savePlaylist(playlist.id, saved) }
        return saved
    }

    /** The user's account playlists for the Save sheet, each with whether it holds [videoId]; empty signed out. */
    suspend fun accountPlaylists(videoId: String): List<Pair<Playlist, Boolean>> =
        if (!yt.isSignedIn) emptyList() else withContext(Dispatchers.IO) { runCatching { yt.saveToOptions(videoId) }.getOrDefault(emptyList()) }

    fun setInAccountPlaylist(playlistId: String, video: Video, add: Boolean, title: String) {
        graph.scope.launch(Dispatchers.IO) {
            val ok = runCatching { if (add) yt.addToPlaylist(playlistId, video.id) else yt.removeFromPlaylist(playlistId, video.id) }.isSuccess
            notice(
                when {
                    !ok -> "Couldn't update $title"
                    add -> "Saved to $title"
                    else -> "Removed from $title"
                },
            )
        }
    }

    fun createAccountPlaylist(title: String, private: Boolean, video: Video?) {
        graph.scope.launch(Dispatchers.IO) {
            val id = runCatching { yt.createPlaylist(title, private, video?.id) }.getOrNull()
            notice(if (id != null) "Created “$title”" else "Couldn't create the playlist")
        }
    }

    fun addToLocalPlaylist(playlist: LocalPlaylist, videos: List<Video>) {
        library.addToLocalPlaylist(playlist.id, videos)
        notice("Saved to ${playlist.name}")
    }
}
