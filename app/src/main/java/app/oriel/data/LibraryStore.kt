package app.oriel.data

import app.oriel.core.model.Channel
import app.oriel.core.model.Playlist
import app.oriel.core.model.Video
import app.oriel.util.AppJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class LocalPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val videos: List<Video> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WatchEntry(val video: Video, val watchedAt: Long)

@Serializable
data class LibraryState(
    val liked: List<Video> = emptyList(),
    val dislikedIds: Set<String> = emptySet(),
    val watchLater: List<Video> = emptyList(),
    val subscriptions: List<Channel> = emptyList(),
    val savedPlaylists: List<Playlist> = emptyList(),
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val history: List<WatchEntry> = emptyList(),
    /** Where each video was left, in ms; finished videos are dropped. */
    val positions: Map<String, Long> = emptyMap(),
    val searchHistory: List<String> = emptyList(),
)

/**
 * The on-device library: likes, Watch later, subscriptions, playlists,
 * history and resume points.
 *
 * Every change lands in memory first, so the UI reflects a like the frame it
 * is tapped, and is written to disk half a second later, atomically, on IO.
 * When signed in, the same actions are mirrored to the YouTube account by
 * [LibraryActions]; this store is what makes them instant and lets the app
 * work fully signed out.
 *
 * Resume points live in a file of their own next to [file]: playback moves
 * them every few seconds, and that shouldn't rewrite the whole library
 * (thousands of history entries) each time.
 */
@OptIn(FlowPreview::class)
class LibraryStore(private val file: File, scope: CoroutineScope) {
    private val positionsFile = File(file.parentFile, "positions.json")
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /**
     * The file is read on IO, not in Application.onCreate. Until it's in,
     * changes apply to what's shown and are kept here, to be applied again on
     * top of what was read; nothing is written before then, so an early empty
     * state can never overwrite the library.
     */
    private val lock = Any()
    private val pending = ArrayList<(LibraryState) -> LibraryState>()
    private val loaded = CompletableDeferred<Unit>()
    @Volatile
    private var isLoaded = false

    /** What's on disk now, so [flush] and the debounced saves skip writes that change nothing. */
    private val writeLock = Any()
    private var savedLibrary: LibraryState? = null
    private var savedPositions: Map<String, Long>? = null

    val likedIds: StateFlow<Set<String>> = state.map { s -> s.liked.mapTo(HashSet()) { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.liked.mapTo(HashSet()) { it.id })
    val subscribedIds: StateFlow<Set<String>> = state.map { s -> s.subscriptions.mapTo(HashSet()) { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.subscriptions.mapTo(HashSet()) { it.id })
    val dislikedIds: StateFlow<Set<String>> = state.map { s -> s.dislikedIds }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.dislikedIds)
    val watchLaterIds: StateFlow<Set<String>> = state.map { s -> s.watchLater.mapTo(HashSet()) { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.watchLater.mapTo(HashSet()) { it.id })
    val savedPlaylistIds: StateFlow<Set<String>> = state.map { s -> s.savedPlaylists.mapTo(HashSet()) { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.savedPlaylists.mapTo(HashSet()) { it.id })

    /** Ids whose like, Watch later or subscription was changed here since [beginSync]; see [reconcile]. */
    private val touchedLikes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val touchedWatchLater: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val touchedSubs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        val io = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
        io.launch {
            val disk = load()
            synchronized(lock) {
                var s = disk
                pending.forEach { s = it(s) }
                pending.clear()
                _state.value = s
                isLoaded = true
            }
            synchronized(writeLock) {
                savedLibrary = disk.copy(positions = emptyMap())
                savedPositions = disk.positions
            }
            loaded.complete(Unit)
            // Ticks of the resume point leave the library itself as it was: only the small file is written.
            // The first value is what was read (skipped as unchanged) or that plus what changed meanwhile.
            state.map { it.copy(positions = emptyMap()) }.distinctUntilChanged().debounce(500).onEach { saveLibrary() }.launchIn(io)
            state.map { it.positions }.distinctUntilChanged().debounce(1_000).onEach { savePositions() }.launchIn(io)
        }
    }

    /** Suspends until the library has been read from disk. */
    suspend fun awaitLoaded() = loaded.await()

    private fun load(): LibraryState {
        val library = runCatching {
            if (file.isFile) AppJson.decodeFromString<LibraryState>(file.readText()) else LibraryState()
        }.getOrElse {
            // Start empty, but keep what couldn't be read: the next save would otherwise overwrite
            // the user's likes, subscriptions and playlists for good.
            runCatching { file.copyTo(File(file.parentFile, file.name + ".unreadable"), overwrite = true) }
            LibraryState()
        }
        val positions = runCatching {
            if (positionsFile.isFile) AppJson.decodeFromString(POSITIONS, positionsFile.readText()) else null
        }.getOrNull()
        if (positions != null) return library.copy(positions = positions)
        // Before they had a file of their own, resume points were kept in the library: move them
        // over now, before the library is next written without them.
        if (library.positions.isNotEmpty()) write(positionsFile, AppJson.encodeToString(POSITIONS, library.positions))
        return library
    }

    /** Both write what's current when they get the lock, so a save that waited behind [flush] can't put back an older state. */
    private fun saveLibrary() = synchronized(writeLock) {
        val s = _state.value.copy(positions = emptyMap())
        if (s == savedLibrary) return@synchronized
        if (write(file, AppJson.encodeToString(LibraryState.serializer(), s))) savedLibrary = s
    }

    private fun savePositions() = synchronized(writeLock) {
        val positions = _state.value.positions
        if (positions == savedPositions) return@synchronized
        if (write(positionsFile, AppJson.encodeToString(POSITIONS, positions))) savedPositions = positions
    }

    private fun write(target: File, text: String): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }.getOrDefault(false)

    /**
     * Writes whatever hasn't reached the disk yet, now, on the calling thread:
     * for when the process may be about to go (task removed, service destroyed).
     */
    fun flush() {
        if (!isLoaded) return
        saveLibrary()
        savePositions()
    }

    fun update(transform: (LibraryState) -> LibraryState) {
        if (!isLoaded) synchronized(lock) {
            if (!isLoaded) {
                pending += transform
                _state.update(transform)
                return
            }
        }
        _state.update(transform)
    }

    // ---- ratings -------------------------------------------------------------------------

    fun isLiked(id: String) = id in likedIds.value
    fun isDisliked(id: String) = id in _state.value.dislikedIds

    fun setLiked(video: Video, liked: Boolean) = update { s ->
        touchedLikes += video.id
        val rest = s.liked.filterNot { it.id == video.id }
        s.copy(liked = if (liked) listOf(video) + rest else rest, dislikedIds = if (liked) s.dislikedIds - video.id else s.dislikedIds)
    }

    fun setDisliked(video: Video, disliked: Boolean) = update { s ->
        if (disliked) touchedLikes += video.id
        s.copy(
            dislikedIds = if (disliked) s.dislikedIds + video.id else s.dislikedIds - video.id,
            liked = if (disliked) s.liked.filterNot { it.id == video.id } else s.liked,
        )
    }

    /**
     * Starts an account sync: from here on, likes, Watch later and follows
     * changed on this device win over what the sync brings back, since the
     * fetch began before they were made.
     */
    fun beginSync() {
        touchedLikes.clear()
        touchedWatchLater.clear()
        touchedSubs.clear()
    }

    /**
     * YouTube's liked videos in place of the local list when [complete] (so
     * unliking on another device shows here too); merged in when only part of
     * a long list came back, or with [keepLocal] (the first sync after signing
     * in, which mustn't lose what was liked here while signed out).
     */
    fun mergeRemoteLikes(remote: List<Video>, complete: Boolean, keepLocal: Boolean = false) = update { s ->
        s.copy(liked = reconcile(s.liked, remote, replace = complete && !keepLocal, touchedLikes) { it.id })
    }

    // ---- watch later ---------------------------------------------------------------------

    fun isInWatchLater(id: String) = id in watchLaterIds.value

    fun setWatchLater(video: Video, add: Boolean) = update { s ->
        touchedWatchLater += video.id
        val rest = s.watchLater.filterNot { it.id == video.id }
        s.copy(watchLater = if (add) listOf(video) + rest else rest)
    }

    /** YouTube's Watch Later, as [mergeRemoteLikes] does likes. */
    fun mergeRemoteWatchLater(remote: List<Video>, complete: Boolean, keepLocal: Boolean = false) = update { s ->
        s.copy(watchLater = reconcile(s.watchLater, remote, replace = complete && !keepLocal, touchedWatchLater) { it.id })
    }

    // ---- subscriptions -------------------------------------------------------------------

    fun isSubscribed(channelId: String) = channelId in subscribedIds.value

    fun setSubscribed(channel: Channel, subscribed: Boolean) = update { s ->
        touchedSubs += channel.id
        val rest = s.subscriptions.filterNot { it.id == channel.id }
        s.copy(subscriptions = if (subscribed) listOf(channel) + rest else rest)
    }

    /** The account's subscriptions, as [mergeRemoteLikes] does likes. */
    fun mergeRemoteSubscriptions(remote: List<Channel>, complete: Boolean, keepLocal: Boolean = false) = update { s ->
        s.copy(subscriptions = reconcile(s.subscriptions, remote, replace = complete && !keepLocal, touchedSubs) { it.id })
    }

    /**
     * [remote] with what only this device has kept on top: all of it unless
     * [replace], and either way what was [touched] here while the fetch ran,
     * which also stays out of the result if it was removed here meanwhile.
     */
    private fun <T> reconcile(local: List<T>, remote: List<T>, replace: Boolean, touched: Set<String>, id: (T) -> String): List<T> {
        val remoteIds = remote.mapTo(HashSet(), id)
        val localIds = local.mapTo(HashSet(), id)
        val kept = local.filter { id(it) !in remoteIds && (!replace || id(it) in touched) }
        return (kept + remote.filter { id(it) !in touched || id(it) in localIds }).distinctBy(id)
    }

    // ---- playlists -----------------------------------------------------------------------

    fun setPlaylistSaved(playlist: Playlist, saved: Boolean) = update { s ->
        val rest = s.savedPlaylists.filterNot { it.id == playlist.id }
        s.copy(savedPlaylists = if (saved) listOf(playlist) + rest else rest)
    }

    fun createPlaylist(name: String, videos: List<Video> = emptyList()): LocalPlaylist {
        val playlist = LocalPlaylist(name = name.trim().ifEmpty { "New playlist" }, videos = videos)
        update { it.copy(localPlaylists = listOf(playlist) + it.localPlaylists) }
        return playlist
    }

    fun addToLocalPlaylist(id: String, videos: List<Video>) = update { s ->
        s.copy(localPlaylists = s.localPlaylists.map { p ->
            if (p.id == id) p.copy(videos = p.videos + videos.filterNot { new -> p.videos.any { it.id == new.id } }) else p
        })
    }

    fun removeFromLocalPlaylist(id: String, videoId: String) = update { s ->
        s.copy(localPlaylists = s.localPlaylists.map { p -> if (p.id == id) p.copy(videos = p.videos.filterNot { it.id == videoId }) else p })
    }

    fun moveInLocalPlaylist(id: String, from: Int, to: Int) = update { s ->
        s.copy(localPlaylists = s.localPlaylists.map { p ->
            if (p.id == id && from in p.videos.indices && to in p.videos.indices) p.copy(videos = p.videos.toMutableList().apply { add(to, removeAt(from)) }) else p
        })
    }

    fun renameLocalPlaylist(id: String, name: String) = update { s ->
        s.copy(localPlaylists = s.localPlaylists.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun deleteLocalPlaylist(id: String) = update { s -> s.copy(localPlaylists = s.localPlaylists.filterNot { it.id == id }) }

    // ---- history & resume ----------------------------------------------------------------

    fun addHistory(video: Video) = update { s ->
        val now = System.currentTimeMillis()
        // Watching again moves the video to the top instead of listing it twice.
        val history = listOf(WatchEntry(video, now)) + s.history.filterNot { it.video.id == video.id }
        s.copy(history = history.take(MAX_HISTORY))
    }

    fun removeHistory(videoId: String) = update { s -> s.copy(history = s.history.filterNot { it.video.id == videoId }, positions = s.positions - videoId) }
    fun clearHistory() = update { it.copy(history = emptyList(), positions = emptyMap()) }

    fun position(videoId: String): Long? = _state.value.positions[videoId]

    /**
     * Remembers where [videoId] was left. The first seconds and the last ones
     * aren't worth resuming: those count as not started and finished.
     */
    fun savePosition(videoId: String, positionMs: Long, durationMs: Long) = update { s ->
        val finished = durationMs > 0 && positionMs > durationMs - END_MARGIN_MS
        // Removed first so the video moves to the newest end: the oldest are the ones dropped at the cap.
        val positions = if (positionMs < START_MARGIN_MS || finished) s.positions - videoId else (s.positions - videoId) + (videoId to positionMs)
        s.copy(positions = if (positions.size > MAX_POSITIONS) positions.entries.drop(positions.size - MAX_POSITIONS).associate { it.key to it.value } else positions)
    }

    fun addSearch(query: String) = update { s ->
        val q = query.trim()
        if (q.isEmpty()) s else s.copy(searchHistory = (listOf(q) + s.searchHistory.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES))
    }

    fun removeSearch(query: String) = update { s -> s.copy(searchHistory = s.searchHistory - query) }
    fun clearSearches() = update { it.copy(searchHistory = emptyList()) }

    private companion object {
        const val MAX_HISTORY = 2000
        const val MAX_SEARCHES = 30
        const val MAX_POSITIONS = 500
        const val START_MARGIN_MS = 10_000L
        const val END_MARGIN_MS = 15_000L
        val POSITIONS = MapSerializer(String.serializer(), Long.serializer())
    }
}
