package app.oriel.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.oriel.AppGraph
import app.oriel.core.model.Video
import app.oriel.util.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** Where the queue came from, and how to continue it once it runs out. */
@Serializable
data class QueueContext(
    val title: String? = null,
    /** The playlist or mix being played, if any. */
    val playlistId: String? = null,
    /** The next page of that playlist, so a long one keeps going past what was loaded. */
    val continuation: String? = null,
    /** A mix (RD…) continues from YouTube's own panel rather than a page token. */
    val isMix: Boolean = false,
)

@Serializable
private data class SavedQueue(
    val videos: List<Video>,
    val index: Int,
    val positionMs: Long,
    val context: QueueContext,
    val audioOnly: Boolean = false,
)

/**
 * Queue semantics on top of ExoPlayer's playlist, owned by the playback
 * service so they keep working with the UI gone:
 *
 * - tapping a video plays it at once; Autoplay lines up what YouTube
 *   recommends next once it's near the end, as the YouTube app does;
 * - a playlist plays in order and pages itself in as it goes; a mix extends
 *   from YouTube's own panel;
 * - quality changes and audio-only mode rebuild the items in place, keeping
 *   the position;
 * - the queue survives process death and is restored paused.
 *
 * Main thread only, like the player it drives.
 */
@OptIn(UnstableApi::class)
class QueueController(
    private val player: ExoPlayer,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val _context = MutableStateFlow(QueueContext())
    val context: StateFlow<QueueContext> = _context.asStateFlow()

    /** Sound only: no picture is fetched at all, which is what saves data and battery. */
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    /** A height picked from the quality menu; overrides the setting until the app restarts. */
    private val _heightOverride = MutableStateFlow<Int?>(null)
    val heightOverride: StateFlow<Int?> = _heightOverride.asStateFlow()

    private var extending: Job? = null
    /** The speed play() last set; the player's differing from it means the user chose another. */
    private var appliedSpeed: Float? = null
    private var saveJob: Job? = null
    private val file = File(graph.context.filesDir, "queue.json")

    fun height(): Int? = if (_audioOnly.value) null else _heightOverride.value ?: graph.settings.videoHeight(graph.network.metered.value)

    /** Videos whose HLS ladder was refused or missing this session: Auto plays them at a fixed height. */
    private val noLadder = HashSet<String>()

    /** Auto (no height picked, here or in Settings) plays YouTube's HLS ladder, adapting as it goes. */
    fun adaptive(video: Video): Boolean =
        !_audioOnly.value && _heightOverride.value == null && graph.settings.isAuto(graph.network.metered.value) && video.id !in noLadder

    fun item(video: Video): MediaItem = MediaItems.build(video, height(), adaptive(video))

    init {
        // Auto's ceiling follows the network as it changes, without rebuilding anything:
        // the data saver holds the ladder at 480p, and lifting it lets it climb again.
        scope.launch {
            combine(graph.network.metered, graph.settings.state) { metered, _ -> graph.settings.videoHeight(metered) }
                .distinctUntilChanged()
                .collect { capAdaptive(it) }
        }
    }

    /** Puts back the setting's ceiling, after a step-down that was meant for one video. */
    fun restoreCeiling() = capAdaptive(graph.settings.videoHeight(graph.network.metered.value))

    private fun capAdaptive(height: Int) {
        // Both sides at the long edge of [height]p, so a portrait video's ladder is capped the same.
        val long = (height * 16 + 8) / 9
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(long, long).build()
    }

    private fun startPosition(video: Video): Long =
        if (graph.settings.current.resumePlayback && !video.isLive) graph.library.position(video.id) ?: 0L else 0L

    // ---- starting playback --------------------------------------------------------------

    /** Plays [videos] from [startIndex]; the context says how to continue past the end. */
    fun play(videos: List<Video>, startIndex: Int = 0, context: QueueContext = QueueContext(), positionMs: Long? = null, shuffle: Boolean = false) {
        if (videos.isEmpty()) return
        // Counts only: never which video.
        graph.telemetry.track("video_play", mapOf("short" to videos[startIndex.coerceIn(videos.indices)].isShort, "queue" to (videos.size > 1), "shuffle" to shuffle))
        extending?.cancel()
        _context.value = context
        player.shuffleModeEnabled = shuffle
        val start = if (shuffle && startIndex == 0) videos.indices.random() else startIndex.coerceIn(videos.indices)
        player.setMediaItems(videos.map(::item), start, positionMs ?: startPosition(videos[start]))
        // The default speed until the user picks one; after that, their pick carries over to
        // every tapped video this session, as it does through Autoplay.
        val speed = player.playbackParameters.speed
        if (appliedSpeed == null || speed == appliedSpeed) {
            val default = graph.settings.current.defaultSpeed
            appliedSpeed = default
            player.setPlaybackSpeed(default)
        }
        player.prepare()
        player.play()
    }

    /** Plays one video, keeping the playlist it's part of when there is one. */
    fun playVideo(video: Video, context: QueueContext = QueueContext(), positionMs: Long? = null) {
        if (player.currentMediaItem?.mediaId == video.id) {
            positionMs?.let { player.seekTo(it) }
            player.play()
            return
        }
        play(listOf(video), 0, context, positionMs)
        if (context.playlistId != null) maybeExtend(force = true)
    }

    fun playNext(videos: List<Video>) {
        if (videos.isEmpty()) return
        if (player.mediaItemCount == 0) return play(videos)
        player.addMediaItems(player.currentMediaItemIndex + 1, videos.map(::item))
    }

    fun addToQueue(videos: List<Video>) {
        if (videos.isEmpty()) return
        if (player.mediaItemCount == 0) return play(videos)
        player.addMediaItems(videos.map(::item))
    }

    // ---- continuing ----------------------------------------------------------------------

    fun onTransition() = maybeExtend()

    /** Called as playback nears the end of the last item, so Autoplay is ready before it's needed. */
    fun onNearEnd() = maybeExtend()

    private fun maybeExtend(force: Boolean = false) {
        if (player.mediaItemCount == 0) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (!force && remaining > EXTEND_WHEN_REMAINING) return
        if (extending?.isActive == true) return
        val ctx = _context.value
        val last = MediaItems.video(player.getMediaItemAt(player.mediaItemCount - 1)) ?: return
        extending = scope.launch {
            when {
                ctx.continuation != null -> {
                    val page = runCatching { withContext(Dispatchers.IO) { graph.yt.more(ctx.continuation) } }.getOrNull() ?: return@launch
                    append(page.items.filterIsInstance<Video>())
                    _context.value = ctx.copy(continuation = page.continuation)
                }
                ctx.playlistId != null -> {
                    val page = runCatching { withContext(Dispatchers.IO) { graph.yt.watch(last.id, ctx.playlistId) } }.getOrNull() ?: return@launch
                    val panel = page.playlist ?: return@launch
                    val after = panel.videos.drop(panel.currentIndex + 1)
                    append(after)
                    _context.value = ctx.copy(title = ctx.title ?: panel.title, isMix = panel.isMix)
                }
                // Autoplay only ever lines up one video, and only once the current one is the last.
                remaining == 0 && graph.settings.current.autoplay -> {
                    val page = runCatching { withContext(Dispatchers.IO) { graph.yt.watch(last.id) } }.getOrNull() ?: return@launch
                    val recent = graph.library.state.value.history.take(30).mapTo(HashSet()) { it.video.id }
                    val queued = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
                    val next = page.related.filterIsInstance<Video>().firstOrNull { !it.isShort && !it.isUpcoming && it.id !in recent && it.id !in queued }
                        ?: page.related.filterIsInstance<Video>().firstOrNull { it.id !in queued && !it.isUpcoming }
                    next?.let { append(listOf(it)) }
                }
            }
        }
    }

    /** Appends what isn't queued already. */
    private fun append(videos: List<Video>) {
        val present = HashSet<String>()
        for (i in 0 until player.mediaItemCount) present += player.getMediaItemAt(i).mediaId
        val fresh = videos.filterNot { it.id in present }.distinctBy { it.id }
        if (fresh.isNotEmpty()) player.addMediaItems(fresh.map(::item))
    }

    // ---- quality & audio-only --------------------------------------------------------------

    fun setAudioOnly(enabled: Boolean) {
        if (_audioOnly.value == enabled) return
        _audioOnly.value = enabled
        rebuild()
    }

    /** Picks a height for this session; null goes back to the setting (Auto). */
    fun setHeight(height: Int?) {
        _heightOverride.value = height
        if (!_audioOnly.value) rebuild()
    }

    /** Rebuilds every queued item for the new mode, keeping the position of the current one. */
    private fun rebuild() {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        val videos = (0 until player.mediaItemCount).mapNotNull { MediaItems.video(player.getMediaItemAt(it)) }
        if (videos.size != player.mediaItemCount) return
        val current = player.currentMediaItem
        if (index + 1 < videos.size) player.replaceMediaItems(index + 1, videos.size, videos.drop(index + 1).map(::item))
        // A live stream keeps its manifest; only on-demand video switches streams.
        if (MediaItems.kind(current) != "hls") {
            player.replaceMediaItem(index, item(videos[index]))
            player.seekTo(index, position)
        }
        player.playWhenReady = wasPlaying
        player.prepare()
    }

    /** Auto's HLS ladder wasn't offered or was refused: this video plays at a fixed height from where it was. */
    fun switchToProgressive(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        val item = player.getMediaItemAt(index)
        if (MediaItems.kind(item) != "abr") return
        noLadder += item.mediaId
        graph.yt.forgetAdaptiveManifest(item.mediaId)
        val position = if (index == player.currentMediaItemIndex) player.currentPosition else 0L
        player.replaceMediaItem(index, MediaItems.asProgressive(item))
        player.seekTo(index, position)
        player.prepare()
        player.play()
    }

    /** A progressive item turned out to be live: swap it for its HLS manifest. */
    fun switchToLive(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.replaceMediaItem(index, MediaItems.asLive(player.getMediaItemAt(index)))
        player.seekToDefaultPosition(index)
        player.prepare()
        player.play()
    }

    // ---- persistence ---------------------------------------------------------------------

    fun scheduleSave() {
        if (!graph.settings.current.persistQueue) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            val saved = snapshot()
            withContext(Dispatchers.IO) { write(saved) }
        }
    }

    /** Writes the queue now, on this thread: for the service going away, when a delayed save would be cancelled with it. */
    fun saveNow() {
        if (!graph.settings.current.persistQueue) return
        saveJob?.cancel()
        write(snapshot())
    }

    private fun snapshot(): SavedQueue {
        val videos = (0 until player.mediaItemCount).mapNotNull { MediaItems.video(player.getMediaItemAt(it)) }
        return SavedQueue(videos.take(MAX_SAVED), player.currentMediaItemIndex.coerceAtMost(MAX_SAVED - 1), player.currentPosition, _context.value, _audioOnly.value)
    }

    private fun write(saved: SavedQueue) {
        runCatching {
            val tmp = File(file.parentFile, "queue.json.tmp")
            tmp.writeText(AppJson.encodeToString(SavedQueue.serializer(), saved))
            tmp.renameTo(file)
        }
    }

    private fun readSaved(): SavedQueue? =
        runCatching { AppJson.decodeFromString(SavedQueue.serializer(), file.readText()) }.getOrNull()?.takeIf { it.videos.isNotEmpty() }

    /**
     * Restores the last session's queue, paused where it was left. It isn't
     * prepared until it's played: preparing would buffer up to two minutes of
     * the old video at launch, competing with the first screen for the network
     * and spending data on a video that may never be resumed.
     */
    fun restore(): Boolean {
        if (player.mediaItemCount > 0 || !graph.settings.current.persistQueue) return false
        val saved = readSaved() ?: return false
        _context.value = saved.context
        _audioOnly.value = saved.audioOnly
        player.playWhenReady = false
        player.setMediaItems(saved.videos.map(::item), saved.index.coerceIn(saved.videos.indices), saved.positionMs)
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) return
                player.removeListener(this)
                if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 && player.playerError == null) player.prepare()
            }
        })
        return true
    }

    fun savedQueue(): Pair<List<MediaItem>, Pair<Int, Long>>? {
        val saved = readSaved() ?: return null
        return saved.videos.map(::item) to (saved.index.coerceIn(saved.videos.indices) to saved.positionMs)
    }

    private companion object {
        const val EXTEND_WHEN_REMAINING = 2
        const val MAX_SAVED = 300
    }
}
