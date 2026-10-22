package app.oriel.data

import app.oriel.core.model.Channel
import app.oriel.core.model.Playlist
import app.oriel.core.model.Video
import app.oriel.util.AppJson
import app.oriel.util.AtomicJson
import app.oriel.util.Schema
import app.oriel.util.SkipBadList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

// Library lists skip entries they can't read instead of losing the whole library over one.
object SkipBadVideos : SkipBadList<Video>(Video.serializer(), "videos")
object SkipBadChannels : SkipBadList<Channel>(Channel.serializer(), "channels")
object SkipBadPlaylists : SkipBadList<Playlist>(Playlist.serializer(), "playlists")
object SkipBadWatchEntries : SkipBadList<WatchEntry>(WatchEntry.serializer(), "history")
object SkipBadLocalPlaylists : SkipBadList<LocalPlaylist>(LocalPlaylist.serializer(), "local playlists")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LocalPlaylist(
    // Always written: a default left out of the file would be made anew, differently, on each read.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val id: String = UUID.randomUUID().toString(),
    val name: String,
    @Serializable(with = SkipBadVideos::class) val videos: List<Video> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WatchEntry(val video: Video, val watchedAt: Long)

@Serializable
data class LibraryState(
    @Serializable(with = SkipBadVideos::class) val liked: List<Video> = emptyList(),
    val dislikedIds: Set<String> = emptySet(),
    @Serializable(with = SkipBadVideos::class) val watchLater: List<Video> = emptyList(),
    @Serializable(with = SkipBadChannels::class) val subscriptions: List<Channel> = emptyList(),
    @Serializable(with = SkipBadPlaylists::class) val savedPlaylists: List<Playlist> = emptyList(),
    @Serializable(with = SkipBadLocalPlaylists::class) val localPlaylists: List<LocalPlaylist> = emptyList(),
    @Serializable(with = SkipBadWatchEntries::class) val history: List<WatchEntry> = emptyList(),
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
 *
 * What's on disk is never given up for an empty library: a file that can't be
 * read for IO reasons leaves the store read-only for the session, and one that
 * can't be decoded is kept aside (the last [MAX_UNREADABLE] of them) before
 * the library starts over. Either way it's reported through [report] and
 * [problems].
 */
@OptIn(FlowPreview::class)
class LibraryStore(
    private val file: File,
    scope: CoroutineScope,
    private val report: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val dir: File = file.absoluteFile.parentFile!!
    private val positionsFile = File(dir, "positions.json")
    private val io = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _problems = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    /** Messages for the person when the library couldn't be read as it was. */
    val problems: SharedFlow<String> = _problems.asSharedFlow()

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

    /** Set when what's on disk couldn't be read: writing now would replace it with less. */
    @Volatile
    var readOnly = false
        private set

    /** What's on disk now, so [flush] and the debounced saves skip writes that change nothing; null forces the next write. */
    private val writeLock = Any()
    private var savedLibrary: LibraryState? = null
    private var savedPositions: Map<String, Long>? = null

    // Each set is rebuilt only when its own list changes, not on every resume-point tick.
    val likedIds: StateFlow<Set<String>> = state.distinctUntilChangedBy { it.liked }.map { s -> s.liked.mapTo(HashSet()) { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.liked.mapTo(HashSet()) { it.id })
    val subscribedIds: StateFlow<Set<String>> = state.distinctUntilChangedBy { it.subscriptions }.map { s -> s.subscriptions.mapTo(HashSet()) { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.subscriptions.mapTo(HashSet()) { it.id })
    val dislikedIds: StateFlow<Set<String>> = state.distinctUntilChangedBy { it.dislikedIds }.map { s -> s.dislikedIds }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.dislikedIds)
    val watchLaterIds: StateFlow<Set<String>> = state.distinctUntilChangedBy { it.watchLater }.map { s -> s.watchLater.mapTo(HashSet()) { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.watchLater.mapTo(HashSet()) { it.id })
    val savedPlaylistIds: StateFlow<Set<String>> = state.distinctUntilChangedBy { it.savedPlaylists }.map { s -> s.savedPlaylists.mapTo(HashSet()) { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.savedPlaylists.mapTo(HashSet()) { it.id })

    /** Ids whose like, Watch later or subscription was changed here since [beginSync]; see [reconcile]. */
    private val touchedLikes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val touchedWatchLater: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val touchedSubs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        io.launch {
            var disk = LibraryState()
            try {
                disk = load()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                readOnly = true
                report("Library load", e)
                _problems.tryEmit(READ_ONLY_NOTICE)
            } finally {
                synchronized(lock) {
                    var s = disk
                    pending.forEach { s = it(s) }
                    pending.clear()
                    _state.value = s
                    isLoaded = true
                }
                loaded.complete(Unit)
            }
            // Ticks of the resume point leave the library itself as it was: only the small file is written.
            // The first value is what was read (skipped as unchanged) or that plus what changed meanwhile.
            state.map { it.copy(positions = emptyMap()) }.distinctUntilChanged().debounce(500).onEach { saveLibrary() }.launchIn(io)
            state.map { it.positions }.distinctUntilChanged().debounce(1_000).onEach { savePositions() }.launchIn(io)
        }
    }

    /** Suspends until the library has been read from disk. */
    suspend fun awaitLoaded() = loaded.await()

    /** Set while loading when a file was read from an older version: it's written again in the current one. */
    private var rewriteLibrary = false
    private var rewritePositions = false

    private fun load(): LibraryState {
        val library = readLibrary()
        // Before they had a file of their own, resume points were kept in the library: they move
        // over with the first save, before the library is next written without them.
        val positions = readPositions() ?: library.positions.also { if (it.isNotEmpty()) rewritePositions = true }
        synchronized(writeLock) {
            savedLibrary = if (rewriteLibrary) null else library.copy(positions = emptyMap())
            savedPositions = if (rewritePositions) null else positions
        }
        return library.copy(positions = positions)
    }

    private fun readLibrary(): LibraryState {
        val text = try {
            AtomicJson.read(file)
        } catch (e: IOException) {
            return cantRead("Library read", e)
        } ?: return LibraryState()
        return try {
            val doc = AppJson.parseToJsonElement(text).jsonObject
            val from = Schema.LIBRARY.version(doc)
            if (from < Schema.LIBRARY.current) {
                // The original as it was, in case a migration step turns out wrong.
                try {
                    file.copyTo(File(dir, "library.v$from.bak"), overwrite = true)
                } catch (e: IOException) {
                    return cantRead("Library backup before migrating", e)
                }
                rewriteLibrary = true
            }
            AppJson.decodeFromJsonElement(LibraryState.serializer(), Schema.LIBRARY.read(doc))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            report("Library decode", e)
            if (!keepUnreadable(file)) return cantRead("Library copy of unreadable file", e)
            _problems.tryEmit("Your library couldn't be read, so it starts empty. The old file was kept on the device.")
            LibraryState()
        }
    }

    /** Resume points, or null when there's no file of them yet. */
    private fun readPositions(): Map<String, Long>? {
        val text = try {
            AtomicJson.read(positionsFile)
        } catch (e: IOException) {
            cantRead("Resume points read", e)
            return emptyMap()
        } ?: return null
        return try {
            val doc = AppJson.parseToJsonElement(text).jsonObject
            if (Schema.POSITIONS.version(doc) < Schema.POSITIONS.current) rewritePositions = true
            // A value that isn't a number costs only its own entry.
            Schema.POSITIONS.read(doc).entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap(LinkedHashMap())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            report("Resume points decode", e)
            if (!keepUnreadable(positionsFile)) cantRead("Resume points copy of unreadable file", e)
            emptyMap()
        }
    }

    /** What couldn't be read stays as it is on disk: the store goes read-only for this session. */
    private fun cantRead(where: String, e: Throwable): LibraryState {
        report(where, e)
        if (!readOnly) {
            readOnly = true
            _problems.tryEmit(READ_ONLY_NOTICE)
        }
        return LibraryState()
    }

    /** Copies [f] aside as `<name>.unreadable-<ms>`, keeping the newest [MAX_UNREADABLE]; false if it couldn't. */
    private fun keepUnreadable(f: File): Boolean = try {
        f.copyTo(File(dir, "${f.name}.unreadable-${System.currentTimeMillis()}"), overwrite = true)
        val prefix = "${f.name}.unreadable"
        dir.listFiles { x -> x.name.startsWith(prefix) }.orEmpty()
            .sortedByDescending { it.name.substringAfter("$prefix-", "").toLongOrNull() ?: 0L }
            .drop(MAX_UNREADABLE).forEach { it.delete() }
        true
    } catch (e: IOException) {
        false
    }

    private fun encodeLibrary(s: LibraryState): String =
        Schema.LIBRARY.write(AppJson.encodeToJsonElement(LibraryState.serializer(), s).jsonObject).toString()

    private fun encodePositions(p: Map<String, Long>): String =
        Schema.POSITIONS.write(JsonObject(p.mapValues { JsonPrimitive(it.value) })).toString()

    private fun saveLibrary() {
        if (!readOnly) AtomicJson.persist(io, "Library save", report, ::writeLibrary)
    }

    private fun savePositions() {
        if (!readOnly) AtomicJson.persist(io, "Resume points save", report, ::writePositions)
    }

    /** Both write what's current when they get the lock, so a save that waited behind [flush] can't put back an older state. */
    private fun writeLibrary() = synchronized(writeLock) {
        val s = _state.value.copy(positions = emptyMap())
        if (s == savedLibrary) return@synchronized
        AtomicJson.write(file, encodeLibrary(s))
        savedLibrary = s
    }

    private fun writePositions() = synchronized(writeLock) {
        val positions = _state.value.positions
        if (positions == savedPositions) return@synchronized
        AtomicJson.write(positionsFile, encodePositions(positions))
        savedPositions = positions
    }

    /**
     * Writes whatever hasn't reached the disk yet, now, on the calling thread:
     * for when the process may be about to go (task removed, service destroyed).
     * Resume points first: they're what changes last before the process goes.
     */
    fun flush() {
        if (!isLoaded) return
        savePositions()
        saveLibrary()
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

    fun isLiked(id: String) = _state.value.liked.any { it.id == id }
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

    fun isInWatchLater(id: String) = _state.value.watchLater.any { it.id == id }

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

    fun isSubscribed(channelId: String) = _state.value.subscriptions.any { it.id == channelId }

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
        const val MAX_UNREADABLE = 3
        const val READ_ONLY_NOTICE = "Your library couldn't be read. Changes made now won't be saved until Oriel restarts."
    }
}
