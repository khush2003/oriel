package app.oriel.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.oriel.MainActivity
import app.oriel.R
import app.oriel.core.extras.Segment
import app.oriel.core.extras.SponsorBlock
import app.oriel.core.model.Video
import app.oriel.core.stream.StreamRequest
import app.oriel.graph
import app.oriel.util.CrashLog
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background playback: one ExoPlayer behind a media session, kept in the
 * foreground with the media notification while it plays. The UI draws its
 * picture onto this same player, so leaving the app just stops drawing it;
 * the sound carries on.
 *
 * Besides the player it owns what must outlive the UI: the queue and Autoplay
 * ([QueueController]), stream recovery when googlevideo refuses a URL, the
 * switch to HLS for live streams, resolving and pre-caching what's next,
 * SponsorBlock skipping, resume points and watch history, the sleep timer,
 * and telling YouTube what a signed-in user watched.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = MainScope()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    lateinit var queue: QueueController
        private set

    /** The player itself, for drawing video in-process. */
    val exoPlayer: ExoPlayer get() = player

    private val refusals = HashMap<String, Int>()
    /** Per video, how often playback stopped on the network after the loader's own retries. */
    private val stalls = HashMap<String, Int>()
    private var recoverJob: Job? = null
    /** The delayed move past a video that failed; a transition of any kind makes it moot. */
    private var skipJob: Job? = null
    private var precacheJob: Job? = null
    /** Whether [precacheJob] is still waiting for a lead, and so is paused with playback. */
    private var prefetchWaiting = false
    private var sleepJob: Job? = null
    private var tickJob: Job? = null
    private var segmentsJob: Job? = null
    private lateinit var reporter: WatchReporter
    private val skippedSegments = HashSet<Segment>()

    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()
    private val _sleepAtEnd = MutableStateFlow(false)
    val sleepAtEnd: StateFlow<Boolean> = _sleepAtEnd.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    /** SponsorBlock segments of the current video, for the scrubber marks. */
    val segments: StateFlow<List<Segment>> = _segments.asStateFlow()
    private val _skipped = MutableStateFlow<Segment?>(null)
    /** The segment just skipped, for the "Skipped sponsor · Undo" notice. */
    val skipped: StateFlow<Segment?> = _skipped.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val graph = applicationContext.graph
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(OrielMediaSourceFactory(graph.dataSources.playback, graph.dataSources.live, graph.adaptive.dataSource, graph::audioPreference) { graph.settings.lowAudio(graph.network.metered.value) })
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(Abr.loadControl())
            .setBandwidthMeter(graph.abr.meter)
            .setTrackSelector(graph.abr.trackSelector(this))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)

        queue = QueueController(player, graph, scope)
        reporter = WatchReporter(graph)

        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })

        scope.launch { graph.library.likedIds.collectLatest { updateButtons() } }

        queue.restore()
        updateButtons()
        instance.value = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            savePosition()
            // The process may be killed straight after; the library's debounced write would be lost.
            applicationContext.graph.library.flush()
            queue.saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance.value = null
        applicationContext.graph.let { it.abr.save(it.network.metered.value) }
        savePosition()
        applicationContext.graph.library.flush()
        queue.saveNow()
        reporter.close()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- player events -------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val graph = applicationContext.graph
            // Recovery and the automatic skip were for the video that failed.
            recoverJob?.cancel()
            skipJob?.cancel()
            // Moving off a failed video (Next, a tap in the queue) leaves the player idle with the old
            // error; the new one should load. An unprepared restored queue has no error, and stays so.
            if (player.playbackState == Player.STATE_IDLE && player.playerError != null) player.prepare()
            _lastError.value = null
            reporter.finish()
            if (_sleepAtEnd.value && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                player.pause()
                cancelSleepTimer()
            }
            // A decoder step-down was for the video that failed; the next one gets the normal ceiling.
            if (decoderCapped != null && mediaItem?.mediaId != decoderCapped) {
                decoderCapped = null
                queue.restoreCeiling()
            }
            // A track picked in the Audio track menu was for that video; the next one starts by the
            // preference, which the pick updated. A loop replays the same video, pick and all.
            val params = player.trackSelectionParameters
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && params.overrides.keys.any { it.type == C.TRACK_TYPE_AUDIO }) {
                player.trackSelectionParameters = params.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            }
            queue.onTransition()
            queue.scheduleSave()
            updateButtons()
            val video = MediaItems.video(mediaItem) ?: return
            if (!graph.settings.current.pauseHistory) graph.library.addHistory(video)
            // An automatic move to the next video picks up where it was left, as a tap would.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && graph.settings.current.resumePlayback) {
                graph.library.position(video.id)?.let { player.seekTo(it) }
            }
            loadSegments(video)
            prefetchUpcoming()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                queue.scheduleSave()
                prefetchUpcoming()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                // Playing again: earlier failures of this video were got past, and don't count toward
                // giving up; and the client that resolved it is known to work right now.
                player.currentMediaItem?.mediaId?.let {
                    stalls.remove(it)
                    refusals.remove(it)
                    applicationContext.graph.resolver.onPlaying(it)
                }
                startTicker()
                reportPlaying()
                if (prefetchWaiting && precacheJob?.isActive != true) prefetchUpcoming()
            } else {
                // Its wait for a lead can't end while paused: stop polling until playback resumes.
                if (prefetchWaiting) precacheJob?.cancel()
                reporter.paused(player.currentPosition)
                savePosition()
                queue.scheduleSave()
                // Also here, not only in onDestroy: a process stopped in the background never gets
                // there, and the next session's first pick would start from an old measurement.
                applicationContext.graph.let { it.abr.save(it.network.metered.value) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                reporter.finish()
                player.currentMediaItem?.mediaId?.let { applicationContext.graph.library.savePosition(it, 0, 0) }
            }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    /** While playing: resume points every few seconds, SponsorBlock skips, and Autoplay lined up before the end. */
    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var ticks = 0
            while (isActive && player.isPlaying) {
                skipSegments()
                if (++ticks % 4 == 0) reportPlaying()
                if (ticks % 20 == 0) savePosition()
                val duration = player.duration
                if (duration != C.TIME_UNSET && duration - player.currentPosition < 30_000 && !player.hasNextMediaItem()) queue.onNearEnd()
                delay(250)
            }
        }
    }

    private fun savePosition() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        if (MediaItems.kind(item) == MediaItems.KIND_HLS) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        applicationContext.graph.library.savePosition(item.mediaId, player.currentPosition, duration)
    }

    // ---- SponsorBlock ----------------------------------------------------------------------

    private fun loadSegments(video: Video) {
        val graph = applicationContext.graph
        segmentsJob?.cancel()
        _segments.value = emptyList()
        skippedSegments.clear()
        val settings = graph.settings.current
        if (!settings.sponsorBlock || video.isLive) return
        segmentsJob = scope.launch {
            val segments = withContext(Dispatchers.IO) { runCatching { graph.sponsorBlock.segments(video.id, settings.sponsorCategories) }.getOrDefault(emptyList()) }
            if (player.currentMediaItem?.mediaId == video.id) _segments.value = segments
        }
    }

    private fun skipSegments() {
        val segments = _segments.value
        if (segments.isEmpty()) return
        val position = player.currentPosition
        val segment = segments.firstOrNull { position >= it.startMs && position < it.endMs - 500 && it !in skippedSegments } ?: return
        skippedSegments += segment
        player.seekTo(segment.endMs)
        _skipped.value = segment
        scope.launch {
            delay(4_000)
            if (_skipped.value == segment) _skipped.value = null
        }
    }

    fun undoSkip() {
        val segment = _skipped.value ?: return
        _skipped.value = null
        player.seekTo(segment.startMs)
    }

    /** Tells YouTube a signed-in user is watching this, so it shows in their history and shapes recommendations. */
    private fun reportPlaying() {
        val item = player.currentMediaItem ?: return
        reporter.playing(item.mediaId, player.currentPosition)
    }

    // ---- errors ----------------------------------------------------------------------------

    private fun handleError(error: PlaybackException) {
        val graph = applicationContext.graph
        val mediaId = player.currentMediaItem?.mediaId
        val causes = generateSequence(error.cause) { it.cause }.toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val unresolvable = causes.filterIsInstance<StreamResolveIOException>().firstOrNull()
        val live = causes.filterIsInstance<LiveStreamIOException>().firstOrNull()
        val sabr = causes.filterIsInstance<SabrIOException>().firstOrNull()
        val unplayable = causes.filterIsInstance<UnplayableIOException>().firstOrNull()
        // A refused URL and a refused SABR stream recover the same way; a SABR read whose
        // connection kept dropping is the network's, below.
        val refusedUrl = http?.takeIf { it.responseCode in REFUSED_HTTP }?.dataSpec?.uri?.toString() ?: sabr?.takeIf { it.refused }?.url
        // googlevideo busy or briefly failing: the network's too, not the video's.
        val busy = http?.responseCode?.let { it == 408 || it == 429 || it in 500..599 } == true
        val resolveFailed = causes.any { it is ResolveNetworkIOException }
        CrashLog.note(this, "$mediaId ${error.errorCodeName}${http?.let { " HTTP ${it.responseCode} ${it.dataSpec.uri.host}" }.orEmpty()} ← " +
            causes.joinToString(" ← ") { "${it::class.simpleName}: ${it.message?.take(160)}" })
        // Auto plays YouTube's HLS ladder; when that's refused or not offered, this video drops to the fixed-quality streams.
        if (MediaItems.kind(player.currentMediaItem) == MediaItems.KIND_ABR && mediaId != null) {
            val refused = http?.responseCode in setOf(401, 403, 410)
            // Segment URLs expire (after a long pause, say): a fresh manifest is usually all it
            // takes, and keeps the ladder. Only a second refusal, or no ladder at all, drops to
            // the fixed-quality streams.
            if (refused && (refusals[mediaId] ?: 0) == 0) {
                refusals[mediaId] = 1
                graph.yt.forgetAdaptiveManifest(mediaId)
                player.prepare()
                player.play()
                return
            }
            if (refused || causes.any { it is AdaptiveUnavailableIOException } || causes.any { it is androidx.media3.common.ParserException }) {
                queue.switchToProgressive(player.currentMediaItemIndex)
                return
            }
        }
        when {
            live != null -> queue.switchToLive(player.currentMediaItemIndex)
            // googlevideo refused this URL (expired, or the minting client fell out of favour):
            // bench that client for the video and resume from the same position on a fresh URL.
            refusedUrl != null && mediaId != null -> {
                graph.resolver.onRefused(refusedUrl)
                graph.resolver.invalidate(mediaId)
                val attempts = (refusals[mediaId] ?: 0) + 1
                refusals[mediaId] = attempts
                if (attempts <= 3) {
                    player.prepare()
                    player.play()
                } else {
                    skipAfterError("This video can't be played right now.")
                }
            }
            unresolvable != null -> skipAfterError(unresolvable.message ?: "This video can't be played.")
            unplayable != null -> skipAfterError(unplayable.message ?: "This video can't be played.")
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
            }
            // The loader already retried for half a minute (OrielLoadErrorPolicy). Offline, wait for
            // the connection and carry on from the same spot; online, try again a few more times,
            // further apart, before calling the video unplayable. A failed resolve gets one more
            // round, not three: each is a full extraction that has just failed twice.
            error.errorCode in 2000..2999 && (error.errorCode !in UNRECOVERABLE_IO || busy) -> {
                val playWhenReady = player.playWhenReady
                recoverJob?.cancel()
                recoverJob = scope.launch {
                    if (!graph.network.online.value) {
                        _lastError.value = "Waiting for network…"
                        graph.network.online.first { it }
                    } else {
                        val attempts = (stalls[mediaId] ?: 0) + 1
                        if (mediaId != null) stalls[mediaId] = attempts
                        if (attempts > if (resolveFailed) 1 else 3) {
                            skipAfterError(if (resolveFailed) "Couldn't reach YouTube. Try again in a moment." else "The connection keeps dropping. Try again in a moment.")
                            return@launch
                        }
                        delay(attempts * 2_000L)
                    }
                    if (player.playbackState == Player.STATE_IDLE) {
                        _lastError.value = null
                        player.prepare()
                        player.playWhenReady = playWhenReady
                    }
                }
            }
            // A decoder that can't take this rung (4K60 VP9 on a chip that only probed 4K30, say):
            // step the ceiling down a rung and carry on from the same spot, twice at most.
            error.errorCode in DECODER_ERRORS && mediaId != null && (decoderSteps[mediaId] ?: 0) < 2 -> {
                decoderSteps[mediaId] = (decoderSteps[mediaId] ?: 0) + 1
                val item = player.currentMediaItem
                // Heights here are the short edge, as the quality menu's are, so a portrait video steps down too.
                val playing = player.videoFormat?.let { minOf(it.width, it.height) }?.takeIf { it > 0 }
                val current = minOf(playing ?: 2160, MediaItems.height(item) ?: 2160)
                val lower = listOf(1440, 1080, 720, 480).firstOrNull { it < current } ?: 360
                val video = MediaItems.video(item)
                if (MediaItems.kind(item) == MediaItems.KIND_AV && video != null) {
                    // A fixed-quality item names its one height: rebuild it a rung lower.
                    val index = player.currentMediaItemIndex
                    val position = player.currentPosition
                    player.replaceMediaItem(index, MediaItems.build(video, lower))
                    player.seekTo(index, position)
                } else if (MediaItems.kind(item) == MediaItems.KIND_ABR || MediaItems.kind(item) == MediaItems.KIND_HLS) {
                    // A ladder only needs its ceiling lowered, which the player's track selection honours.
                    val long = (lower * 16 + 8) / 9
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(long, long).build()
                    decoderCapped = mediaId
                }
                player.prepare()
                player.play()
            }
            error.errorCode in DECODER_ERRORS -> skipAfterError("This phone can't decode this video. Try a lower quality.")
            error.errorCode in 4000..4999 -> skipAfterError("This phone can't play this video's format.")
            error.errorCode in 2000..2999 -> skipAfterError("Couldn't load the video.")
            else -> skipAfterError("Playback failed.")
        }
    }

    private val decoderSteps = HashMap<String, Int>()
    /** The video a decoder step-down capped the shared player for, until it's moved off. */
    private var decoderCapped: String? = null

    /** Shows why, and moves on only when there's something to move on to; a lone video stays put with its message. */
    private fun skipAfterError(message: String) {
        _lastError.value = message
        if (!player.hasNextMediaItem()) return
        skipJob?.cancel()
        skipJob = scope.launch {
            delay(2_500)
            if (_lastError.value == message && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    fun retry() {
        _lastError.value = null
        player.currentMediaItem?.mediaId?.let { applicationContext.graph.resolver.invalidate(it) }
        player.prepare()
        player.play()
    }

    /**
     * Resolves the next video and pre-caches its opening, so Autoplay and Next
     * start from local bytes. Only once this one is playing with a good lead
     * buffered: before that, the next video's requests compete with the bytes
     * the user is waiting for. On mobile data only the resolve (a few small
     * requests) happens; the opening is fetched when it's actually played.
     */
    private fun prefetchUpcoming() {
        val graph = applicationContext.graph
        precacheJob?.cancel()
        prefetchWaiting = false
        if (!graph.settings.current.prefetchNext || player.mediaItemCount == 0) return
        prefetchWaiting = true
        precacheJob = scope.launch {
            while (!hasLead()) delay(1_000)
            prefetchWaiting = false
            val index = player.currentTimeline.takeIf { !it.isEmpty }?.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled) ?: C.INDEX_UNSET
            if (index == C.INDEX_UNSET) return@launch
            val next = player.getMediaItemAt(index)
            val kind = MediaItems.kind(next)
            if (kind == MediaItems.KIND_HLS) return@launch
            // Auto plays the HLS ladder: warm the manifest it will ask for, so Next starts at once.
            if (kind == MediaItems.KIND_ABR) {
                withContext(Dispatchers.IO) { runCatching { graph.yt.adaptiveManifest(next.mediaId) } }
                return@launch
            }
            val height = MediaItems.height(next)
            val metered = graph.network.metered.value
            graph.resolver.prefetch(StreamRequest(next.mediaId, graph.settings.streamQuality(metered), height))
            if (metered) return@launch
            withContext(Dispatchers.IO) { graph.dataSources.precache(next.mediaId, height) }
        }
    }

    /** Playing, with half a minute buffered or the rest of the video in hand. */
    private fun hasLead(): Boolean {
        if (!player.isPlaying) return false
        if (player.totalBufferedDuration >= PREFETCH_LEAD_MS) return true
        val duration = player.duration
        return duration != C.TIME_UNSET && player.bufferedPosition >= duration - 1_000
    }

    // ---- sleep timer ---------------------------------------------------------------------

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _sleepAtEnd.value = false
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.value = at
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            // Cancelled mid-fade (the timer reset or turned off), the sound comes back at full volume.
            try {
                repeat(20) { step ->
                    player.volume = (1f - (step + 1) / 20f).coerceAtLeast(0f)
                    delay(100)
                }
                player.pause()
            } finally {
                player.volume = 1f
            }
            _sleepAt.value = null
        }
    }

    fun sleepAtEndOfVideo() {
        sleepJob?.cancel()
        _sleepAt.value = null
        _sleepAtEnd.value = true
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        player.volume = 1f
        _sleepAt.value = null
        _sleepAtEnd.value = false
    }

    // ---- likes from the notification -----------------------------------------------------

    fun toggleLike() {
        val video = MediaItems.video(player.currentMediaItem) ?: return
        applicationContext.graph.actions.toggleLike(video)
    }

    private fun updateButtons() {
        if (!::session.isInitialized) return
        val liked = player.currentMediaItem?.mediaId?.let { applicationContext.graph.library.isLiked(it) } == true
        val like = CommandButton.Builder(if (liked) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED)
            .setDisplayName(if (liked) "Remove like" else "Like")
            .setSessionCommand(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
            .build()
        session.setMediaButtonPreferences(ImmutableList.of(like))
    }

    // ---- session callback ----------------------------------------------------------------

    private val callback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // Browsing lists the user's history and likes, so only the system's own surfaces get it.
            // Any other app may still control playback, but can't read the library or like for them.
            val commands = if (canBrowse(session, controller)) {
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(SessionCommand(ACTION_LIKE, Bundle.EMPTY)).build()
            } else {
                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            // Media3 already refuses commands a controller wasn't granted; liking acts on the account, so check again.
            if (customCommand.customAction == ACTION_LIKE && canBrowse(session, controller)) {
                toggleLike()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val trusted = canBrowse(mediaSession, controller)
            val items = mediaItems.mapNotNull { controllerItem(it, trusted, queue.height()) }
            // An empty answer to a non-empty request would quietly clear the queue; refuse it instead.
            if (items.isEmpty() && mediaItems.isNotEmpty()) return Futures.immediateFailedFuture(IllegalArgumentException("No playable items"))
            return Futures.immediateFuture(items.toMutableList())
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = queue.savedQueue() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(saved.first, saved.second.first, saved.second.second))
        }

        // Android Auto and other media browsers: liked videos and history, as sound.
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            if (!canBrowse(session, browser)) Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            else Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Oriel"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!canBrowse(session, browser)) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            val graph = applicationContext.graph
            val items: List<MediaItem> = when (parentId) {
                ROOT -> listOf(folder(LIKED, "Liked videos"), folder(HISTORY, "History"))
                LIKED -> graph.library.state.value.liked.map { MediaItems.build(it, null) }
                HISTORY -> graph.library.state.value.history.take(200).map { MediaItems.build(it.video, null) }
                else -> emptyList()
            }
            // Some car clients ask for pageSize Int.MAX_VALUE: multiply in Long so it can't wrap negative.
            val from = (page.toLong() * pageSize).coerceIn(0L, items.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceIn(from.toLong(), items.size.toLong()).toInt()
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params))
        }
    }

    /** Oriel itself and system controllers, the media notification, Android Auto, and a car's own system. */
    private fun canBrowse(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
        controller.isTrusted || session.isMediaNotificationController(controller) ||
            session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build())
        .build()

    companion object {
        const val ACTION_LIKE = "oriel.LIKE"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /**
         * An item a controller asked to play, rebuilt as one of Oriel's own. Anything that isn't a
         * YouTube video id is dropped, so no controller can make the player open another URI. Other
         * apps get the bare id only: their titles and extras would otherwise land in the watch
         * history as if the person had chosen them.
         */
        @androidx.annotation.VisibleForTesting
        internal fun controllerItem(item: MediaItem, trusted: Boolean, height: Int?): MediaItem? {
            if (trusted && item.localConfiguration?.uri?.scheme == "oriel") return item
            val video = (if (trusted) MediaItems.video(item) else Video(item.mediaId, "")) ?: return null
            if (!VIDEO_ID.matches(video.id)) return null
            return MediaItems.build(video, height)
        }
        private const val ROOT = "root"
        private const val LIKED = "liked"
        private const val HISTORY = "history"

        /** The running service, for in-process callers (the UI) that need more than a MediaController offers. */
        val instance = MutableStateFlow<PlaybackService?>(null)

        /** A decoder that couldn't start, failed mid-stream, or was handed more than it can take. */
        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        private const val PREFETCH_LEAD_MS = 30_000L

        /** googlevideo refusing a URL: expired, minted by a client it no longer trusts, or gone. */
        private val REFUSED_HTTP = setOf(401, 403, 404, 410)

        /** IO errors no retry fixes: a missing file, no permission, a cleartext URL, a range past the end, a refusal. */
        private val UNRECOVERABLE_IO = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
    }
}
