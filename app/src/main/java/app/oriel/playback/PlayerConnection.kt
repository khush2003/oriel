package app.oriel.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Stable
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.oriel.AppGraph
import app.oriel.core.extras.Segment
import app.oriel.core.stream.AudioPreference
import app.oriel.core.model.Video
import app.oriel.core.stream.StreamRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One queue row: its position in the player's playlist and the video. */
data class QueueEntry(val index: Int, val video: Video)

/** One of the playing video's sound tracks, for the Audio track menu. */
class AudioTrackOption(
    val name: String,
    val language: String?,
    val isOriginal: Boolean,
    val selected: Boolean,
    internal val group: TrackGroup,
)

/** Cards that sit on screen warm at most one stream per this long; see [PlayerConnection.warmAhead]. */
private const val QUIET_WARM_INTERVAL_MS = 8_000L

/**
 * The UI's handle on playback.
 *
 * State comes from a MediaController (the same view a lock screen or a watch
 * gets); what needs more than the session protocol carries (playlists,
 * quality, audio-only, SponsorBlock) goes straight to the in-process service.
 * Everything is a StateFlow so composables observe exactly the slice they draw.
 */
@Stable
class PlayerConnection(private val context: Context, private val graph: AppGraph) {
    private val scope: CoroutineScope = MainScope()
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var serviceJob: Job? = null

    private val _current = MutableStateFlow<Video?>(null)
    val current: StateFlow<Video?> = _current.asStateFlow()
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended.asStateFlow()
    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeat: StateFlow<Int> = _repeat.asStateFlow()
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private val _duration = MutableStateFlow(C.TIME_UNSET)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)
    val videoSize: StateFlow<VideoSize> = _videoSize.asStateFlow()
    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()
    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious: StateFlow<Boolean> = _hasPrevious.asStateFlow()
    private val _isLive = MutableStateFlow(false)
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()
    private val _audioTracks = MutableStateFlow<List<AudioTrackOption>>(emptyList())
    /** The sound tracks the player can switch between now: more than one only for a dubbed video on Auto (HLS). */
    val audioTracks: StateFlow<List<AudioTrackOption>> = _audioTracks.asStateFlow()

    // Service-side state.
    private val _queueTitle = MutableStateFlow<String?>(null)
    val queueTitle: StateFlow<String?> = _queueTitle.asStateFlow()
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()
    private val _heightOverride = MutableStateFlow<Int?>(null)
    val heightOverride: StateFlow<Int?> = _heightOverride.asStateFlow()
    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()
    private val _sleepAtEnd = MutableStateFlow(false)
    val sleepAtEnd: StateFlow<Boolean> = _sleepAtEnd.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    val segments: StateFlow<List<Segment>> = _segments.asStateFlow()
    private val _skipped = MutableStateFlow<Segment?>(null)
    val skipped: StateFlow<Segment?> = _skipped.asStateFlow()
    private val _surfacePlayer = MutableStateFlow<Player?>(null)
    /** The player to draw video from: the service's own, in-process, so frames never cross a binder. */
    val surfacePlayer: StateFlow<Player?> = _surfacePlayer.asStateFlow()

    val player: Player? get() = controller

    /** The connection being made, so taps before it lands queue on it rather than each opening another (never released). */
    private var connecting: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    fun connect() {
        if (controller != null || connecting != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        connecting = future
        future.addListener({
            if (connecting === future) connecting = null
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) { pending.clear(); return@addListener }
            controller = c
            c.addListener(listener)
            applyVideoVisibility(c)
            syncAll(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
        serviceJob?.cancel()
        serviceJob = scope.launch {
            PlaybackService.instance.collectLatest { service ->
                _surfacePlayer.value = service?.exoPlayer
                service ?: return@collectLatest
                launch { service.queue.context.collect { _queueTitle.value = it.title } }
                launch { service.queue.audioOnly.collect { _audioOnly.value = it } }
                launch { service.queue.heightOverride.collect { _heightOverride.value = it } }
                launch { service.sleepAt.collect { _sleepAt.value = it } }
                launch { service.sleepAtEnd.collect { _sleepAtEnd.value = it } }
                launch { service.lastError.collect { _error.value = it } }
                launch { service.segments.collect { _segments.value = it } }
                launch { service.skipped.collect { _skipped.value = it } }
            }
        }
    }

    fun release() {
        connecting?.let { MediaController.releaseFuture(it) }
        connecting = null
        serviceJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    fun dispose() {
        release()
        scope.cancel()
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: run {
            pending += action
            connect()
        }
    }

    /** Runs [action] against the playback service, starting it first if needed. */
    private fun withService(action: (PlaybackService) -> Unit) {
        PlaybackService.instance.value?.let { return action(it) }
        withController { }
        scope.launch { action(PlaybackService.instance.filterNotNull().first()) }
    }

    // ---- state sync ----------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) syncQueue(player)
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_TIMELINE_CHANGED)) syncCurrent(player)
            if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) syncPlayback(player)
            if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) _repeat.value = player.repeatMode
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) _speed.value = player.playbackParameters.speed
            if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) _videoSize.value = player.videoSize
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) _audioTracks.value = audioOptions(player.currentTracks)
        }
    }

    private fun syncPlayback(player: Player) {
        _isPlaying.value = player.isPlaying
        _playWhenReady.value = player.playWhenReady
        _buffering.value = player.playbackState == Player.STATE_BUFFERING
        _ended.value = player.playbackState == Player.STATE_ENDED
        _duration.value = player.duration
        _isLive.value = player.isCurrentMediaItemLive
    }

    private fun syncAll(player: Player) {
        syncQueue(player)
        syncCurrent(player)
        syncPlayback(player)
        _repeat.value = player.repeatMode
        _speed.value = player.playbackParameters.speed
        _videoSize.value = player.videoSize
        _audioTracks.value = audioOptions(player.currentTracks)
    }

    private fun syncCurrent(player: Player) {
        _current.value = MediaItems.video(player.currentMediaItem)
        _currentIndex.value = player.currentMediaItemIndex
        _duration.value = player.duration
        _hasNext.value = player.hasNextMediaItem()
        _hasPrevious.value = player.hasPreviousMediaItem()
        _isLive.value = player.isCurrentMediaItemLive
    }

    /** The queue in play order. */
    private fun syncQueue(player: Player) {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) {
            _queue.value = emptyList()
            return
        }
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        val window = Timeline.Window()
        _queue.value = order.mapNotNull { i -> MediaItems.video(timeline.getWindow(i, window).mediaItem)?.let { QueueEntry(i, it) } }
        _hasNext.value = player.hasNextMediaItem()
        _hasPrevious.value = player.hasPreviousMediaItem()
    }

    // ---- reading position ---------------------------------------------------------------

    private var previewPosition = 0L

    fun position(): Long = controller?.currentPosition ?: previewPosition
    fun bufferedPosition(): Long = controller?.bufferedPosition ?: previewPosition

    /** Puts the UI-facing state into a given shape without a player, for screenshot tests. */
    @VisibleForTesting
    internal fun preview(queue: List<Video>, index: Int, playing: Boolean, positionMs: Long, title: String? = null, audioTracks: List<AudioTrackOption> = emptyList()) {
        _queue.value = queue.mapIndexed { i, v -> QueueEntry(i, v) }
        _current.value = queue.getOrNull(index)
        _currentIndex.value = index
        _isPlaying.value = playing
        _playWhenReady.value = playing
        _duration.value = (queue.getOrNull(index)?.durationSec ?: 0) * 1000L
        _queueTitle.value = title
        _audioTracks.value = audioTracks
        previewPosition = positionMs
    }

    // ---- actions -------------------------------------------------------------------------

    fun play(videos: List<Video>, startIndex: Int = 0, context: QueueContext = QueueContext(), shuffle: Boolean = false) =
        withService { it.queue.play(videos, startIndex, context, shuffle = shuffle) }

    fun playVideo(video: Video, context: QueueContext = QueueContext(), positionMs: Long? = null) =
        withService { it.queue.playVideo(video, context, positionMs) }

    /** Starts resolving [video]'s stream at the quality it will play at, so a play that follows soon starts warm. */
    fun warm(video: Video) = withService { s -> prefetch(video, s.queue.height(), graph.network.metered.value) }

    /** Fetches what a tap on [video] needs first: Auto's HLS manifest, or the fixed-height streams. */
    private fun prefetch(video: Video, height: Int?, metered: Boolean) {
        val queue = PlaybackService.instance.value?.queue
        val ladder = queue?.adaptive(video) ?: (height != null && graph.settings.isAuto(metered))
        if (ladder) graph.scope.launch(Dispatchers.IO) { runCatching { graph.yt.adaptiveManifest(video.id) } }
        else graph.resolver.prefetch(StreamRequest(video.id, graph.settings.streamQuality(metered), height))
    }

    private var lastQuietWarm = 0L

    /**
     * Resolves [video]'s stream ahead of a tap, so playback starts on a URL
     * already in hand rather than a second's extraction. [pressed] is a finger
     * on the card: warm it whatever the connection. Otherwise it's a card that
     * has sat on screen, which warms only on an unmetered connection with
     * prefetching on, at most one every eight seconds, and not while the
     * video playing is still filling its buffer, so browsing never turns into
     * a stream of player requests competing with what's on screen. Resolves
     * are cached until their URLs expire, so a card warmed twice costs nothing
     * the second time.
     */
    fun warmAhead(video: Video, pressed: Boolean) {
        if (video.isLive || video.isUpcoming || video.isShort || video.id.isBlank()) return
        val metered = graph.network.metered.value
        if (!pressed) {
            val settings = graph.settings.current
            if (metered || !settings.prefetchNext || settings.dataSaver) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastQuietWarm < QUIET_WARM_INTERVAL_MS) return
            val playing = PlaybackService.instance.value?.exoPlayer
            if (playing != null && playing.isPlaying && playing.totalBufferedDuration < 30_000) return
            lastQuietWarm = now
        }
        val height = PlaybackService.instance.value?.queue?.height() ?: if (_audioOnly.value) null else graph.settings.videoHeight(metered)
        prefetch(video, height, metered)
    }

    fun playNext(videos: List<Video>) = withService { it.queue.playNext(videos) }
    fun addToQueue(videos: List<Video>) = withService { it.queue.addToQueue(videos) }

    fun togglePlay() = withController { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
        if (c.playWhenReady) c.pause() else c.play()
    }

    fun pause() = controller?.pause()
    fun play() = withController { it.play() }

    fun next() = withController { it.seekToNextMediaItem() }

    /** Restarts the video past its first seconds, otherwise goes back one. */
    fun previous() = withController { c ->
        if (c.currentPosition > 4_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun seekBy(deltaMs: Long) = withController { c ->
        val duration = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, duration))
    }
    fun seekToLiveEdge() = withController { it.seekToDefaultPosition() }

    fun skipToIndex(index: Int) = withController { c ->
        c.seekTo(index, 0)
        c.play()
    }

    fun moveItem(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun removeItem(index: Int) = withController { it.removeMediaItem(index) }

    fun clearUpNext() = withController { c ->
        if (c.mediaItemCount > c.currentMediaItemIndex + 1) c.removeMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount)
    }

    /** Stops and empties the queue: closing the mini player. */
    fun stop() {
        stopRequests++
        withController { c ->
            c.stop()
            c.clearMediaItems()
        }
    }

    /** How many times [stop] was asked for: what a screenshot test, which has no player, can check. */
    @VisibleForTesting
    internal var stopRequests = 0
        private set

    fun toggleLoop() = withController { c ->
        c.repeatMode = if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }

    fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed) }
    fun setAudioOnly(enabled: Boolean) = withService { it.queue.setAudioOnly(enabled) }
    fun setHeight(height: Int?) = withService { it.queue.setHeight(height) }
    fun currentHeight(): Int? = PlaybackService.instance.value?.queue?.height()

    /** Whether what's playing is Auto's HLS ladder, its height moving with the connection. */
    fun currentAdaptive(): Boolean = MediaItems.kind(PlaybackService.instance.value?.exoPlayer?.currentMediaItem) == MediaItems.KIND_ABR
    fun undoSkip() = withService { it.undoSkip() }
    fun retry() = withService { it.retry() }

    /** Video is decoded only while the picture is on screen; in the background only sound plays. */
    private var videoVisible = true

    /** Whether video frames are wanted at all. Only a preference: it never starts the service. */
    fun setVideoVisible(visible: Boolean) {
        videoVisible = visible
        controller?.let(::applyVideoVisibility)
    }

    private fun applyVideoVisibility(c: MediaController) {
        val disabled = C.TRACK_TYPE_VIDEO in c.trackSelectionParameters.disabledTrackTypes
        if (disabled == !videoVisible) return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoVisible).build()
    }

    /**
     * Plays [option]'s sound from here on, in place: a track selection, so the position and
     * play state carry on. The videos after this one start the same way this session.
     */
    fun selectAudioTrack(option: AudioTrackOption) {
        graph.sessionAudio = if (option.isOriginal || option.language == null) {
            AudioPreference(preferOriginal = true)
        } else {
            AudioPreference(preferOriginal = graph.settings.current.preferOriginalAudio, language = option.language)
        }
        withController { c ->
            // The first of the group's renditions: the codec AudioRenditionOrder put first for Sound quality.
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(option.group, 0)).build()
        }
    }

    fun setSleepTimer(minutes: Int) = withService { it.setSleepTimer(minutes) }
    fun sleepAtEndOfVideo() = withService { it.sleepAtEndOfVideo() }
    fun cancelSleepTimer() = withService { it.cancelSleepTimer() }

    fun currentMediaItem(): MediaItem? = controller?.currentMediaItem
}

/** [tracks]' sound tracks a menu can offer: one per language (Media3 groups each language's codecs). */
internal fun audioOptions(tracks: Tracks): List<AudioTrackOption> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }.mapIndexed { i, g ->
        val format = g.getTrackFormat(0)
        AudioTrackOption(
            name = format.label?.takeUnless { it.isBlank() }
                ?: format.language?.let { java.util.Locale.forLanguageTag(it).displayName }?.takeUnless { it.isBlank() }
                ?: "Track ${i + 1}",
            language = format.language,
            isOriginal = format.roleFlags and C.ROLE_FLAG_MAIN != 0,
            selected = g.isSelected,
            group = g.mediaTrackGroup,
        )
    }
