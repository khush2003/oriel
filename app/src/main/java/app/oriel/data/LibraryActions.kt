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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

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

    /** A notice that waits until something shows notices, for ones raised before the UI is up. */
    suspend fun noticeWhenShown(text: String) {
        _notices.subscriptionCount.first { it > 0 }
        _notices.emit(text)
    }

    /** One queue per thing mirrored ("rate:<id>"), and how many calls are waiting in it. */
    private class Lane {
        val mutex = Mutex()
        val waiting = AtomicInteger()
    }

    private val lanes = ConcurrentHashMap<String, Lane>()

    /**
     * Mirrors to the account, one call at a time per [key]. [block] sends the local state as it
     * is when its turn comes, and a call with a newer one queued behind it is skipped, so a quick
     * like then unlike ends unliked on YouTube too, whatever order the requests would have raced in.
     */
    private fun mirror(what: String, key: String, block: suspend () -> Unit) {
        if (!yt.isSignedIn) return
        val lane = lanes.getOrPut(key) { Lane() }
        lane.waiting.incrementAndGet()
        graph.scope.launch(Dispatchers.IO) {
            lane.mutex.withLock {
                if (lane.waiting.decrementAndGet() > 0) return@withLock
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _notices.tryEmit("Couldn't $what on your YouTube account")
                }
            }
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
        mirror("save this rating", "rate:${video.id}") { yt.rate(video.id, rating(video.id)) }
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
        mirror("update Watch later", "wl:${video.id}") {
            if (library.isInWatchLater(video.id)) yt.addToPlaylist(YouTube.WATCH_LATER, video.id) else yt.removeFromPlaylist(YouTube.WATCH_LATER, video.id)
        }
        return add
    }

    // ---- subscriptions -------------------------------------------------------------------

    fun isSubscribed(channelId: String) = library.isSubscribed(channelId)

    fun setSubscribed(channel: Channel, subscribed: Boolean) {
        library.setSubscribed(channel, subscribed)
        mirror(if (subscribed) "subscribe" else "unsubscribe", "sub:${channel.id}") { yt.subscribe(channel.id, library.isSubscribed(channel.id)) }
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
        if (!playlist.isMix) mirror("save this playlist", "pl:${playlist.id}") { yt.savePlaylist(playlist.id, library.state.value.savedPlaylists.any { it.id == playlist.id }) }
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
