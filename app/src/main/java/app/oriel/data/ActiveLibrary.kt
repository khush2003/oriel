package app.oriel.data

import app.oriel.core.model.Channel
import app.oriel.core.model.Playlist
import app.oriel.core.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import java.io.File

/**
 * The library of the profile in use, as the rest of the app sees it: the same calls as
 * [LibraryStore], made on whichever profile's store is active, and flows that follow the switch.
 *
 * Each profile has a store of its own, built when the profile is first used and closed when it's
 * left, so a store never has to start over with another profile's file. What outlives a frame
 * (syncs, mirrors to the account) takes the concrete store with [store] once, at its start, and
 * keeps it: finishing after a switch, it writes to the profile it began in, or not at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveLibrary(
    private val scope: CoroutineScope,
    private val dir: (profileId: String) -> File,
    private val report: (String, Throwable) -> Unit = { _, _ -> },
    /** Runs before any profile's files are first read; see [LibraryStore]. */
    private val prepare: () -> Unit = {},
    initial: String,
) {
    private class Open(val profileId: String, val store: LibraryStore, val scope: CoroutineScope)

    private val lock = Any()
    private val active = MutableStateFlow(build(initial))

    private fun build(profileId: String): Open {
        val own = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        val store = LibraryStore(File(dir(profileId), "library.json"), own, prepare, report)
        return Open(profileId, store, own)
    }

    /** The active profile's store, for work that must stay with the profile it started in. */
    val store: LibraryStore get() = active.value.store

    val profileId: String get() = active.value.profileId

    /**
     * Makes [profileId]'s library the active one; the one left is written out and closed. Returns
     * once the new one has been read, so what's shown next is its library and not an empty one.
     */
    suspend fun use(profileId: String) {
        val next = synchronized(lock) {
            if (active.value.profileId == profileId) return
            build(profileId)
        }
        next.store.awaitLoaded()
        val left = synchronized(lock) { active.value.also { active.value = next } }
        left.store.close()
        left.scope.cancel()
    }

    // Each follows the active store: collectors move to the next one's on a switch, and [StateFlow.value]
    // reads the active store itself, so a change is seen the moment it's made, as with the store.
    val state: StateFlow<LibraryState> = following { it.state }
    val likedIds: StateFlow<Set<String>> = following { it.likedIds }
    val subscribedIds: StateFlow<Set<String>> = following { it.subscribedIds }
    val dislikedIds: StateFlow<Set<String>> = following { it.dislikedIds }
    val watchLaterIds: StateFlow<Set<String>> = following { it.watchLaterIds }
    val savedPlaylistIds: StateFlow<Set<String>> = following { it.savedPlaylistIds }

    private fun <T> following(of: (LibraryStore) -> StateFlow<T>): StateFlow<T> =
        Following({ of(store) }, active.flatMapLatest { of(it.store) }.stateIn(scope, SharingStarted.Eagerly, of(store).value))

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class Following<T>(private val current: () -> StateFlow<T>, private val flow: StateFlow<T>) : StateFlow<T> by flow {
        override val value: T get() = current().value
    }

    val problems: SharedFlow<String> = active.flatMapLatest { it.store.problems }.shareIn(scope, SharingStarted.Eagerly)

    val readOnly: Boolean get() = store.readOnly

    suspend fun awaitLoaded() = store.awaitLoaded()
    fun flush() = store.flush()
    fun update(transform: (LibraryState) -> LibraryState) = store.update(transform)

    fun isLiked(id: String) = store.isLiked(id)
    fun isDisliked(id: String) = store.isDisliked(id)
    fun setLiked(video: Video, liked: Boolean) = store.setLiked(video, liked)
    fun setDisliked(video: Video, disliked: Boolean) = store.setDisliked(video, disliked)

    fun isInWatchLater(id: String) = store.isInWatchLater(id)
    fun setWatchLater(video: Video, add: Boolean) = store.setWatchLater(video, add)

    fun isSubscribed(channelId: String) = store.isSubscribed(channelId)
    fun setSubscribed(channel: Channel, subscribed: Boolean) = store.setSubscribed(channel, subscribed)

    fun setPlaylistSaved(playlist: Playlist, saved: Boolean) = store.setPlaylistSaved(playlist, saved)
    fun createPlaylist(name: String, videos: List<Video> = emptyList()): LocalPlaylist = store.createPlaylist(name, videos)
    fun addToLocalPlaylist(id: String, videos: List<Video>) = store.addToLocalPlaylist(id, videos)
    fun removeFromLocalPlaylist(id: String, videoId: String) = store.removeFromLocalPlaylist(id, videoId)
    fun moveInLocalPlaylist(id: String, from: Int, to: Int) = store.moveInLocalPlaylist(id, from, to)
    fun renameLocalPlaylist(id: String, name: String) = store.renameLocalPlaylist(id, name)
    fun deleteLocalPlaylist(id: String) = store.deleteLocalPlaylist(id)

    fun addHistory(video: Video) = store.addHistory(video)
    fun removeHistory(videoId: String) = store.removeHistory(videoId)
    fun clearHistory() = store.clearHistory()
    fun position(videoId: String): Long? = store.position(videoId)
    fun savePosition(videoId: String, positionMs: Long, durationMs: Long) = store.savePosition(videoId, positionMs, durationMs)

    fun addSearch(query: String) = store.addSearch(query)
    fun removeSearch(query: String) = store.removeSearch(query)
    fun clearSearches() = store.clearSearches()
}
