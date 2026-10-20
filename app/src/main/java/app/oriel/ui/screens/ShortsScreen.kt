package app.oriel.ui.screens

import app.oriel.util.CrashLog
import app.oriel.util.ReportKind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.oriel.core.model.ShortSound
import app.oriel.core.model.SoundPage
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.runtime.derivedStateOf
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.player.CommentSortRow
import app.oriel.AppGraph
import app.oriel.core.model.Channel
import app.oriel.core.model.Comment
import app.oriel.core.model.CommentsPage
import app.oriel.core.model.LikeStatus
import app.oriel.core.model.ShortInfo
import app.oriel.core.model.Video
import app.oriel.playback.OrielMediaSourceFactory
import app.oriel.playback.MediaItems
import app.oriel.playback.WatchReporter
import app.oriel.ui.AppState
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.components.TAB_BAR_HEIGHT
import app.oriel.ui.components.floatingBarInsets
import app.oriel.ui.glass.videoGlass
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.player.CommentRow
import app.oriel.ui.player.CommentSkeleton
import app.oriel.ui.player.PanelHeader
import app.oriel.ui.theme.DarkIsland
import app.oriel.ui.theme.themePalette
import app.oriel.util.Thumbnails
import app.oriel.util.Format
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The endless Shorts sequence, with each short's overlay info fetched as it comes up. */
class ShortsModel(private val graph: AppGraph, startId: String?) : ViewModel() {
    private val _ids = MutableStateFlow(listOfNotNull(startId))
    val ids: StateFlow<List<String>> = _ids.asStateFlow()
    private val _info = MutableStateFlow<Map<String, ShortInfo>>(emptyMap())
    val info: StateFlow<Map<String, ShortInfo>> = _info.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var continuation: String? = null
    private var loading = false
    private var exhausted = false

    init {
        _info.value = previewInfo
        loadMore()
    }

    /**
     * The next batch. One that's all shorts already listed asks again, and one that fails
     * with shorts showing waits for the network and tries again: either used to end the
     * feed for good, with nothing to say so.
     */
    fun loadMore() {
        if (loading || exhausted) return
        loading = true
        viewModelScope.launch {
            var tries = 0
            while (true) {
                val batch = withContext(Dispatchers.IO) { runCatching { graph.yt.shorts(continuation) } }
                val b = batch.getOrNull()
                if (b != null) {
                    continuation = b.continuation
                    exhausted = b.continuation == null
                    val before = _ids.value.size
                    _ids.update { old -> old + b.ids.filterNot { it in old } }
                    _error.value = null
                    if (_ids.value.size > before || exhausted) break
                } else if (_ids.value.isEmpty()) {
                    _error.value = friendlyError(batch.exceptionOrNull()!!)
                    break
                }
                if (++tries >= 4) break
                if (b == null) { graph.network.online.first { it }; delay(1_500L * tries) }
            }
            loading = false
        }
    }

    fun retry() { exhausted = false; loadMore() }

    fun loadInfo(id: String) {
        if (id in _info.value) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { graph.yt.shortInfo(id) }.getOrNull() }?.let { i -> _info.update { it + (id to i) } }
        }
    }

    companion object {
        /** Screenshot tests: overlay info to start with, in place of the network. */
        @androidx.annotation.VisibleForTesting
        internal var previewInfo: Map<String, ShortInfo> = emptyMap()
    }
}

/**
 * The Shorts player: its own ExoPlayer over Oriel's pipeline, looping one short at a time,
 * with a preload manager readying its neighbours.
 *
 * Shorts play from YouTube's HLS ladder, as Auto does on the watch page: its segments start at
 * once, where a fresh progressive URL refuses reads for its first seconds, which was a slow
 * first short and later ones stalling a few seconds in, once their precached head ran out.
 * The next short is loaded two seconds deep, so a swipe starts it at once, and the one after
 * has its ladder and tracks ready. One the ladder refuses falls back to a fixed-quality stream.
 */
@OptIn(UnstableApi::class)
private class ShortsPlayback(context: android.content.Context, private val graph: AppGraph, private var maxHeight: Int) {
    private var current = 0
    private val items = HashMap<String, androidx.media3.common.MediaItem>()
    private val ranked = HashMap<String, Int>()

    private val control = object : androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
        override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus = when (index - current) {
            1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(2_000L)
            2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            -1 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
            else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    }

    private val sources = OrielMediaSourceFactory(graph.dataSources.playback, graph.dataSources.live, graph.adaptive.dataSource) { graph.settings.lowAudio(graph.network.metered.value) }
    private val builder = DefaultPreloadManager.Builder(context, control)
        .setMediaSourceFactory(sources)
        // Starts on half a second of picture: a short is a swipe away from being skipped.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 500, 1_500).build())
        .setBandwidthMeter(graph.abr.meter)
    val preload: DefaultPreloadManager = builder.build()
    val player: ExoPlayer = builder.buildExoPlayer(
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true),
    ).apply {
        repeatMode = Player.REPEAT_MODE_ONE
        trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(maxHeight, maxHeight).build()
        addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = recover(error)
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) currentMediaItem?.mediaId?.let(attempts::remove) }
        })
    }

    private val attempts = HashMap<String, Int>()

    /** The person paused this short: nothing but a tap, or a new short, plays it again. */
    var held = false

    /** The short that failed past every retry, for the page to say so. */
    val failed = MutableStateFlow<String?>(null)

    fun togglePlay(): Boolean {
        held = player.isPlaying
        if (held) player.pause() else { if (failed.value != null) retry() else player.play() }
        return !held
    }

    fun retry() {
        val id = player.currentMediaItem?.mediaId ?: return
        attempts.remove(id)
        failed.value = null
        held = false
        player.prepare()
        player.play()
    }

    fun resume() { if (!held && player.mediaItemCount > 0) player.play() }
    private val retryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    /**
     * A failed short is never left stuck. A dropped connection waits for the network and
     * tries again from where it was, backing off. A refusal or a broken ladder moves the
     * short to its progressive stream (and the preload map with it). Past three tries it
     * steps to the progressive stream regardless, then just retries in place.
     */
    private fun recover(error: androidx.media3.common.PlaybackException) {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        val n = (attempts[id] ?: 0) + 1
        attempts[id] = n
        val network = error.errorCode in 2000..2999 && error.errorCode != androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        val abr = MediaItems.kind(item) == "abr"
        retryScope.launch {
            if (network && n <= 3) {
                graph.network.online.first { it }
                delay(400L * n)
            } else if (abr) {
                val progressive = MediaItems.asProgressive(item)
                items[id] = progressive
                if (id in ranked) { preload.remove(item); preload.add(progressive, ranked.getValue(id)) }
                val at = player.currentPosition
                player.setMediaItem(progressive, at)
            } else if (n > 6) {
                failed.value = id
                CrashLog.note(graph.context, "Short $id ${error.errorCodeName} ← " + generateSequence(error.cause) { it.cause }.joinToString(" ← ") { "${it::class.simpleName}: ${it.message?.take(160)}" })
                return@launch
            }
            else delay(600L * n)
            if (player.currentMediaItem?.mediaId != id) return@launch
            player.prepare()
            if (!held) player.play()
        }
    }

    /** Vertical or not, nothing past the quality's long side. */
    fun cap(height: Int) {
        maxHeight = height
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(height, height).build()
    }

    private fun item(id: String) = items.getOrPut(id) { MediaItems.build(Video(id, "", isShort = true), maxHeight, adaptive = true) }

    /** Plays page [index], [id], from its preloaded source when there is one, and readies its neighbours. */
    fun show(ids: List<String>, index: Int) {
        val id = ids.getOrNull(index) ?: return
        current = index
        // Newly listed shorts join the manager, ranked by their place in the pager.
        ids.forEachIndexed { i, other -> if (other !in ranked && i >= index - 1) { ranked[other] = i; preload.add(item(other), i) } }
        // And ones well behind are let go, so a long session doesn't hold every short it passed.
        ranked.entries.filter { it.value < index - 3 }.toList().forEach { (other, _) ->
            preload.remove(item(other)); ranked.remove(other); items.remove(other)
        }
        if (player.currentMediaItem?.mediaId != id) {
            val item = item(id)
            val source = preload.getMediaSource(item)
            if (source != null) player.setMediaSource(source) else player.setMediaItem(item)
            player.prepare()
            held = false
            failed.value = null
        }
        // More shorts arriving re-runs this for the same page: a paused one stays paused.
        if (!held) player.play()
        preload.setCurrentPlayingIndex(index)
        preload.invalidate()
    }

    fun release() {
        retryScope.cancel()
        preload.release()
        player.release()
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun rememberShortsPlayback(graph: AppGraph, maxHeight: Int): ShortsPlayback {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playback = remember { ShortsPlayback(context, graph, maxHeight) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    return playback
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(startId: String?) {
    val app = LocalAppState.current
    val graph = app.graph
    val model: ShortsModel = viewModel(key = "shorts:$startId") { ShortsModel(graph, startId) }
    val ids by model.ids.collectAsState()
    val infos by model.info.collectAsState()
    val error by model.error.collectAsState()
    val metered by graph.network.metered.collectAsState()
    // The long side of what Auto allows on this network (1080p shorts are 1920 tall), as the main player.
    val height = (graph.settings.videoHeight(metered) * 16 + 8) / 9
    val playback = rememberShortsPlayback(graph, height)
    LaunchedEffect(height) { playback.cap(height) }
    val player = playback.player
    val pager = rememberPagerState { ids.size }
    var commentsFor by remember { mutableStateOf<ShortInfo?>(null) }
    var soundFor by remember { mutableStateOf<ShortSound?>(null) }
    // Vertical shorts fill the frame, as YouTube crops them; the odd square or wide one fits.
    var aspect by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    val reporter = remember { WatchReporter(graph) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                aspect = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) reporter.paused(player.currentPosition)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            reporter.close()
        }
    }
    // A signed-in viewing reaches YouTube's history as a Short, as the main player's do.
    LaunchedEffect(playing) {
        while (playing) {
            player.currentMediaItem?.mediaId?.let { reporter.playing(it, player.currentPosition, short = true) }
            delay(1_000)
        }
    }

    // The main player rests while Shorts play.
    LaunchedEffect(Unit) { app.connection.pause(); graph.telemetry.track("shorts_open") }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> playback.resume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pager.settledPage, ids.size) {
        val id = ids.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        playback.show(ids, pager.settledPage)
        model.loadInfo(id)
        ids.drop(pager.settledPage + 1).take(2).forEach { model.loadInfo(it) }
        if (pager.settledPage >= ids.size - 4) model.loadMore()
    }

    // Into history once the short's title is known, which can be after it starts playing.
    val shownId = ids.getOrNull(pager.settledPage)
    val shownTitle = shownId?.let { infos[it]?.title }
    LaunchedEffect(shownId, shownTitle) {
        if (shownId == null || shownTitle == null || graph.settings.current.pauseHistory) return@LaunchedEffect
        graph.library.addHistory(Video(shownId, shownTitle, channel = infos[shownId]?.channel, isShort = true))
    }

    // The picture ends above the tab bar, which sits on black here, as YouTube's does:
    // nothing of the short is hidden under it, and the bar reads as part of the frame.
    val barArea = floatingBarInsets.asPaddingValues().calculateBottomPadding() + 2.dp + TAB_BAR_HEIGHT + 8.dp
    DarkIsland {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(bottom = barArea)) {
            when {
                ids.isEmpty() && error != null -> MessageState(error!!, Modifier.align(Alignment.Center), action = "Try again", onAction = model::retry)
                ids.isEmpty() -> ActivityIndicator(Modifier.align(Alignment.Center), size = 30.dp, color = Color.White)
                else -> VerticalPager(
                    pager,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(bottomStart = SHORT_CORNER, bottomEnd = SHORT_CORNER)),
                    key = { ids[it] },
                    beyondViewportPageCount = 1,
                ) { page ->
                    val id = ids[page]
                    ShortPage(
                        app, id, infos[id], playback, active = page == pager.settledPage,
                        fit = page == pager.settledPage && aspect > 0.8f,
                        onComments = { commentsFor = it },
                        onSound = { soundFor = it },
                    )
                }
            }
        }
        commentsFor?.let { info -> ShortCommentsSheet(app, info) { commentsFor = null } }
        soundFor?.let { sound -> SoundSheet(app, sound) { soundFor = null } }
    }
}

private val SHORT_CORNER = 18.dp

@OptIn(UnstableApi::class)
@Composable
private fun ShortPage(app: AppState, id: String, info: ShortInfo?, playback: ShortsPlayback, active: Boolean, fit: Boolean, onComments: (ShortInfo) -> Unit, onSound: (ShortSound) -> Unit) {
    val graph = app.graph
    val player = playback.player
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(false) }
    val failedId by playback.failed.collectAsState()
    var fast by remember { mutableStateOf(false) }
    val heart = remember { Animatable(0f) }
    var heartAt by remember { mutableStateOf(Offset.Zero) }
    val liked by graph.library.likedIds.collectAsState()
    val disliked by graph.library.dislikedIds.collectAsState()
    val subscribed by graph.library.subscribedIds.collectAsState()
    val video = remember(id, info) { Video(id, info?.title.orEmpty(), channel = info?.channel, isShort = true) }
    val status = when { id in liked -> LikeStatus.LIKE; id in disliked -> LikeStatus.DISLIKE; else -> LikeStatus.INDIFFERENT }
    // The tap detector lives across recompositions, so it reads these fresh rather than the values it started with.
    val currentStatus by rememberUpdatedState(status)
    val currentVideo by rememberUpdatedState(video)
    var progress by remember { mutableStateOf(0f) }
    // Scrubbing: the bar along the bottom follows a finger, the picture seeking as it goes.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubAt by remember { mutableStateOf(0f) }
    val overlay by animateFloatAsState(if (scrubbing) 0f else 1f, tween(160), label = "shortOverlay")
    LaunchedEffect(active) {
        paused = playback.held && active
        progress = 0f
        while (active && isActive) {
            val d = player.duration
            progress = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(100)
        }
    }
    // Polled ten times a second, glided between: the line moves on every frame, not in steps.
    // (A loop back to the start jumps rather than running backwards across the bar.)
    val shownProgress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        if (progress < shownProgress.value - 0.05f || scrubbing) shownProgress.snapTo(progress)
        else shownProgress.animateTo(progress, tween(110, easing = LinearEasing))
    }

    Box(
        Modifier.fillMaxSize().pointerInput(active) {
            detectTapGestures(
                onTap = {
                    if (!active) return@detectTapGestures
                    val playing = playback.togglePlay()
                    paused = !playing
                    haptics.play(if (playing) Haptic.Resume else Haptic.Pause)
                },
                onDoubleTap = { at ->
                    heartAt = at
                    if (currentStatus != LikeStatus.LIKE) graph.actions.toggleLike(currentVideo)
                    haptics.play(Haptic.ToggleOn)
                    scope.launch { heart.snapTo(0f); heart.animateTo(1f, tween(900, easing = LinearEasing)) }
                },
                onLongPress = { if (active) { fast = true; player.setPlaybackSpeed(2f); haptics.play(Haptic.Tick) } },
                onPress = {
                    tryAwaitRelease()
                    if (fast) { fast = false; player.setPlaybackSpeed(1f) }
                },
            )
        },
    ) {
        val poster = @Composable { RemoteImage(Thumbnails.short(Video(id, "")), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), border = false) }
        if (active) {
            // The poster is the shutter: it holds until the first frame, so a swipe never flashes black.
            ContentFrame(
                player = player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, keepContentOnReset = false,
                shutter = poster,
            )
        } else poster()
        // Legibility: a soft shade at the bottom under the text and buttons.
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.45f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))

        AnimatedVisibility(paused, Modifier.align(Alignment.Center), enter = fadeIn() + scaleIn(initialScale = 1.3f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
            Box(Modifier.size(72.dp).videoGlass(CircleShape, 0.45f), contentAlignment = Alignment.Center) {
                Icon(OrielIcons.Play, "Play", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        if (active && failedId == id) {
            MessageState(
                "Couldn't play this short.", Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                action = "Try again", onAction = { playback.retry(); paused = false },
                secondary = "Report", onSecondary = { app.reportProblem(ReportKind.PLAYBACK, id) },
            )
        }
        if (fast) {
            Row(Modifier.align(Alignment.TopCenter).padding(top = 96.dp).videoGlass(RoundedCornerShape(50), 0.5f).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("2× ▸▸", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
        // Read only in the layer, so the pop doesn't recompose the page each frame: it springs
        // up past full size, settles, then floats up and fades.
        Icon(
            OrielIcons.Heart, null, tint = Color(0xFFFF3B5C),
            modifier = Modifier.size(96.dp).graphicsLayer {
                val t = heart.value
                val pop = if (t < 0.25f) { val k = t / 0.25f; 0.4f + 0.85f * (1f - (1f - k) * (1f - k)) } else 1.25f - 0.15f * ((t - 0.25f) / 0.2f).coerceAtMost(1f)
                translationX = heartAt.x - 48.dp.toPx(); translationY = heartAt.y - 48.dp.toPx() - 60.dp.toPx() * ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
                rotationZ = -12f * (1f - t)
                scaleX = pop; scaleY = pop
                alpha = if (t <= 0f || t >= 1f) 0f else 1f - ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
            },
        )

        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 26.dp).graphicsLayer { alpha = overlay }, verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(start = PAGE_GUTTER, end = 12.dp)) {
                info?.channel?.let { ch ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = ch.id != null) { app.openChannel(ch.id!!) }) {
                        Avatar(ch.avatar, 32.dp, name = ch.name)
                        Spacer(Modifier.width(8.dp))
                        Text(ch.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        ch.id?.let { cid ->
                            val sub = cid in subscribed
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (sub) "Subscribed" else "Subscribe",
                                style = MaterialTheme.typography.labelLarge, color = if (sub) Color.White else Color.Black,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sub) Color.White.copy(alpha = 0.22f) else Color.White).clickable {
                                    haptics.play(if (sub) Haptic.ToggleOff else Haptic.ToggleOn)
                                    app.toggleSubscribe(Channel(cid, ch.name, ch.avatar, verified = ch.verified))
                                }.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                info?.title?.let { title ->
                    // Hashtags are links, as on YouTube: each opens a search for it.
                    val text = remember(title) {
                        buildAnnotatedString {
                            var last = 0
                            val style = TextLinkStyles(SpanStyle(fontWeight = FontWeight.W700))
                            HASHTAG.findAll(title).forEach { m ->
                                append(title.substring(last, m.range.first))
                                withLink(LinkAnnotation.Clickable(m.value, style) { haptics.play(Haptic.Tap); app.search(m.value) }) { append(m.value) }
                                last = m.range.last + 1
                            }
                            append(title.substring(last))
                        }
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                info?.sound?.let { sound ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f)).clickable { haptics.play(Haptic.Tap); onSound(sound) }
                            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(OrielIcons.Sound, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            sound.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1,
                            modifier = Modifier.widthIn(max = 210.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_200),
                        )
                    }
                }
            }
            Column(Modifier.padding(end = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ShortAction(if (status == LikeStatus.LIKE) OrielIcons.Liked else OrielIcons.Like, info?.likeCount ?: "Like", description = if (status == LikeStatus.LIKE) "Liked" else "Like") {
                    haptics.play(Haptic.ToggleOn); graph.actions.toggleLike(video)
                }
                ShortAction(if (status == LikeStatus.DISLIKE) OrielIcons.Disliked else OrielIcons.Dislike, "Dislike") {
                    haptics.play(Haptic.ToggleOn); graph.actions.toggleDislike(video)
                }
                ShortAction(OrielIcons.Comments, info?.commentsCount ?: "Comments", description = "Comments", enabled = info?.commentsToken != null) { info?.let(onComments) }
                ShortAction(OrielIcons.Share, "Share") { app.share(video) }
                ShortAction(OrielIcons.MoreHoriz, "More") { app.sheetItem = video }
                // The sound's artwork, as YouTube ends the column with it: a tap opens the sound.
                info?.sound?.let { sound ->
                    Box(
                        Modifier.padding(top = 2.dp).size(40.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, Color.White, RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = "Shorts with this sound") { haptics.play(Haptic.Tap); onSound(sound) },
                    ) {
                        RemoteImage(sound.image, Modifier.fillMaxSize(), RoundedCornerShape(10.dp), border = false)
                    }
                }
            }
        }
        // The progress bar along the bottom, and a scrubber: drag along it (or tap it) to seek.
        // It swells while held, the overlay steps aside, and the time shows above it.
        val thick by animateFloatAsState(if (scrubbing) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 700f), label = "scrubThick")
        if (scrubbing) {
            val d = player.duration.coerceAtLeast(0)
            Text(
                "${Format.durationMs((scrubAt * d).toLong())} / ${Format.durationMs(d)}",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.W700, shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 12f)),
                color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
                .pointerInput(active) {
                    if (!active) return@pointerInput
                    var lastSeek = -1f
                    // The bar is drawn inset by the frame's corners; touches map onto it, not the full width.
                    val inset = SHORT_CORNER.toPx()
                    val track = (size.width - inset * 2).coerceAtLeast(1f)
                    fun seek(f: Float, exact: Boolean) {
                        val d = player.duration
                        if (d <= 0) return
                        player.setSeekParameters(if (exact) androidx.media3.exoplayer.SeekParameters.EXACT else androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                        player.seekTo((f * d).toLong())
                        lastSeek = f
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { o -> scrubbing = true; scrubAt = ((o.x - inset) / track).coerceIn(0f, 1f); haptics.play(Haptic.Tick); seek(scrubAt, false) },
                        onDragEnd = { seek(scrubAt, true); progress = scrubAt; scrubbing = false },
                        onDragCancel = { scrubbing = false },
                    ) { change, dx ->
                        change.consume()
                        scrubAt = (scrubAt + dx / track).coerceIn(0f, 1f)
                        // Keyframe seeks while moving, a few per second's worth of travel; exact on release.
                        if (kotlin.math.abs(scrubAt - lastSeek) > 0.012f) seek(scrubAt, false)
                    }
                }
                .pointerInput(active) {
                    if (!active) return@pointerInput
                    val inset = SHORT_CORNER.toPx()
                    detectTapGestures { o ->
                        val f = ((o.x - inset) / (size.width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val d = player.duration
                        if (d > 0) { player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT); player.seekTo((f * d).toLong()); progress = f; haptics.play(Haptic.Tick) }
                    }
                }
                .drawBehind {
                    val f = if (scrubbing) scrubAt else shownProgress.value
                    val h = (2.dp.toPx() + 4.dp.toPx() * thick)
                    val inset = SHORT_CORNER.toPx()
                    val w = size.width - inset * 2
                    val y = size.height - h
                    val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
                    drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(inset, y), Size(w, h), r)
                    drawRoundRect(Color.White, Offset(inset, y), Size(w * f, h), r)
                    if (thick > 0f) drawCircle(Color.White, radius = 7.dp.toPx() * thick, center = Offset(inset + w * f, y + h / 2))
                },
        )
    }
}

@Composable
private fun ShortAction(icon: ImageVector, label: String, description: String = label, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.5f }.semantics(mergeDescendants = true) { contentDescription = if (description == label) label else "$description, $label" },
    ) {
        Box(Modifier.size(48.dp).videoGlass(CircleShape, 0.32f).clip(CircleShape).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W600), color = Color.White, maxLines = 1)
    }
}

@Composable
private fun ShortCommentsSheet(app: AppState, info: ShortInfo, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<CommentsPage?>(null) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var loadingMore by remember { mutableStateOf(false) }
    var moreFailed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    // Replies open in place, under their comment, as the watch page's panel does.
    var thread by remember { mutableStateOf<Comment?>(null) }
    var replies by remember { mutableStateOf<List<Comment>?>(null) }
    var repliesFailed by remember { mutableStateOf(false) }
    // Separate tries: retrying a thread's replies used to reload the whole list under it too.
    var attempt by remember { mutableStateOf(0) }
    var repliesAttempt by remember { mutableStateOf(0) }
    var sortToken by remember { mutableStateOf<String?>(null) }
    var moreJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(info.id, attempt, sortToken) {
        // A next page of the old order (or the old try) never lands in the new one.
        moreJob?.cancel(); loadingMore = false
        val token = sortToken ?: info.commentsToken ?: run { loading = false; return@LaunchedEffect }
        failed = false; loading = true
        val p = withContext(Dispatchers.IO) { runCatching { app.graph.yt.comments(token) }.getOrNull() }
        loading = false
        if (p == null) failed = true
        else {
            // The sort chips come with the first page only; a re-sorted page keeps them, with the new one ticked.
            val sort = p.sort.ifEmpty { page?.sort.orEmpty().map { it.copy(selected = it.token == sortToken) } }
            page = p.copy(sort = sort); comments = p.comments; moreFailed = false
        }
    }
    LaunchedEffect(thread, repliesAttempt) {
        replies = null; repliesFailed = false
        val token = thread?.repliesToken ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { app.graph.yt.comments(token) }.getOrNull() }
        if (r == null) repliesFailed = true else replies = r.comments
    }
    fun loadMore() {
        val cont = page?.continuation ?: return
        if (loadingMore) return
        loadingMore = true; moreFailed = false
        moreJob = scope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { app.graph.yt.comments(cont) }.getOrNull() }
            if (page?.continuation != cont) return@launch
            if (next != null) {
                val seen = comments.mapTo(HashSet()) { it.id }
                comments = comments + next.comments.filterNot { it.id in seen }
                page = next.copy(sort = page?.sort.orEmpty())
            } else moreFailed = true
            loadingMore = false
        }
    }
    val palette = themePalette()
    OrielSheet(onDismiss, scrollable = false, maxHeight = 720.dp) {
        val close = LocalSheetClose.current
        // Back from a reply thread returns to the comments; from the comments it closes the sheet.
        BackHandler(enabled = thread != null) { thread = null }
        PanelHeader(if (thread != null) "Replies" else "Comments", if (thread != null) null else info.commentsCount, palette, onClose = { close {} })
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.divider))
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f), contentPadding = PaddingValues(bottom = 32.dp)) {
            val open = thread
            if (open != null) {
                item(key = "back") {
                    Row(Modifier.fillMaxWidth().clickable { thread = null }.padding(horizontal = PAGE_GUTTER, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(OrielIcons.Back, null, tint = palette.onBackground, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("All comments", style = MaterialTheme.typography.labelLarge, color = palette.onBackground)
                    }
                }
                item(key = "parent") { CommentRow(app, open, palette, onReplies = null) }
                val r = replies
                when {
                    repliesFailed -> item(key = "replies-failed") { MessageState("Replies couldn't be loaded.", action = "Try again", onAction = { repliesAttempt++ }) }
                    r == null -> item(key = "replies-loading") { LoadingRow() }
                    else -> items(r.distinctBy { it.id }, key = { "r" + it.id }) { CommentRow(app, it, palette, onReplies = null, indent = true) }
                }
                return@LazyColumn
            }
            val sort = page?.sort.orEmpty()
            if (sort.size > 1) item(key = "sort") { CommentSortRow(sort, palette) { option -> sortToken = option.token; page = page?.copy(sort = sort.map { it.copy(selected = it.token == option.token) }) } }
            when {
                failed -> item(key = "failed") { MessageState("Comments couldn't be loaded.", action = "Try again", onAction = { attempt++ }) }
                loading -> items(5, key = { "sk$it" }) { CommentSkeleton() }
                comments.isEmpty() -> item(key = "empty") { MessageState(page?.disabledReason ?: "No comments yet.") }
                else -> items(comments.distinctBy { it.id }, key = { it.id }) { CommentRow(app, it, palette, onReplies = { c -> thread = c }) }
            }
            val cont = page?.continuation
            if (cont != null && !failed && !loading) {
                if (moreFailed) item(key = "more-failed") { NoticeRow("Couldn't load more.", action = "Try again", onAction = ::loadMore) }
                else item(key = "more") {
                    LaunchedEffect(cont) { loadMore() }
                    LoadingRow()
                }
            }
        }
    }
}

private val HASHTAG = Regex("""#[\p{L}\p{N}_]+""")

/**
 * A sound, as YouTube's sound page shows it: the artwork and title, who made it, and a grid
 * of the shorts that use it, more loading as the grid nears its end. A tap plays one.
 */
@Composable
private fun SoundSheet(app: AppState, sound: ShortSound, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<SoundPage?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    var moreFailed by remember { mutableStateOf(false) }
    var moreAttempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sound.token, attempt) {
        val token = sound.token ?: run { failed = true; return@LaunchedEffect }
        failed = false
        val p = withContext(Dispatchers.IO) { runCatching { app.graph.yt.soundPage(token) }.getOrNull() }
        if (p == null) failed = true else page = p
    }
    OrielSheet(onDismiss, scrollable = false, maxHeight = 720.dp) {
        val close = LocalSheetClose.current
        Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER).padding(top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(sound.image ?: page?.image, Modifier.size(84.dp), RoundedCornerShape(14.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(OrielIcons.Sound, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("SOUND", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(sound.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W700), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val from = listOfNotNull(page?.title?.takeIf { it != sound.title }, page?.subtitle).joinToString(" · ")
                if (from.isNotEmpty()) Text(from, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val p = page
        when {
            failed -> MessageState("Couldn't load this sound.", icon = OrielIcons.Offline, action = "Try again", onAction = { attempt++ })
            p == null -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { ActivityIndicator(size = 24.dp) }
            p.shorts.isEmpty() -> MessageState("No shorts use this sound yet.")
            else -> {
                val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 6 } == true } }
                LaunchedEffect(nearEnd, p.continuation, moreAttempt) {
                    val cont = p.continuation
                    if (!nearEnd || cont == null || loadingMore || moreFailed) return@LaunchedEffect
                    loadingMore = true
                    scope.launch {
                        val next = withContext(Dispatchers.IO) { runCatching { app.graph.yt.soundPage(cont) }.getOrNull() }
                        // A failed page says so at the end of the grid, with Try again; it used to just stop.
                        if (next != null) page = p.copy(shorts = (p.shorts + next.shorts).distinctBy { it.id }, continuation = next.continuation)
                        else moreFailed = true
                        loadingMore = false
                    }
                }
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    androidx.compose.foundation.lazy.grid.GridCells.Fixed(3), state = grid,
                    contentPadding = PaddingValues(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(p.shorts.size, key = { p.shorts[it].id }) { i ->
                        val v = p.shorts[i]
                        Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).clickable { close { app.openShort(v) } }) {
                            RemoteImage(v.thumbnail ?: Thumbnails.short(v), Modifier.fillMaxSize(), RoundedCornerShape(10.dp), border = false)
                            Box(Modifier.fillMaxWidth().fillMaxHeight(0.35f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
                            v.viewsText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) }
                        }
                    }
                    if (p.continuation != null && (loadingMore || moreFailed)) {
                        item(key = "more", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            if (moreFailed) MessageState("Couldn't load more.", action = "Try again", onAction = { moreFailed = false; moreAttempt++ })
                            else LoadingRow()
                        }
                    }
                }
            }
        }
    }
}
