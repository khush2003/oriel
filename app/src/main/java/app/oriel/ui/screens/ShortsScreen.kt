package app.oriel.ui.screens

import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.DropletShape
import app.oriel.ui.theme.midFlight
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.util.lerp
import app.oriel.ui.theme.smoothstep
import app.oriel.core.model.ShortSound
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import app.oriel.ui.LocalWindowLayout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.oriel.ui.components.ActivityIndicator
import app.oriel.core.model.ShortInfo
import app.oriel.core.model.Video
import app.oriel.playback.WatchReporter
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.TAB_BAR_HEIGHT
import app.oriel.ui.components.floatingBarInsets
import app.oriel.ui.theme.DarkIsland
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalDensity

@kotlin.OptIn(ExperimentalFoundationApi::class)
@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(startId: String?, queue: List<String> = emptyList()) {
    val app = LocalAppState.current
    val graph = app.graph
    val model: ShortsModel = viewModel(key = "shorts:$startId:${graph.account.active.collectAsState().value.id}") { ShortsModel(graph, startId, queue) }
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
    // Liquid motion: the pages snap on the mock's page spring, the one arriving squashes a little
    // as it lands, and the page grows out of the Short tapped to open it.
    val liquid = LocalLiquidMotion.current && !LocalReduceAnimation.current
    val landing = remember { Animatable(0f) }
    val lastSettled = remember { intArrayOf(-1) }
    LaunchedEffect(pager.settledPage, liquid) {
        val before = lastSettled[0]
        lastSettled[0] = pager.settledPage
        if (!liquid || before < 0 || before == pager.settledPage) return@LaunchedEffect
        // A jelly kick, signed by the way it came: from below (swiped up) it squashes onto its top.
        landing.snapTo(0f)
        landing.animateTo(0f, Sp.Jelly.unit, initialVelocity = if (pager.settledPage > before) 1.6f else -1.6f)
    }
    val origin = remember { app.takeShortOrigin() }
    val grow = remember { Animatable(if (origin != null) 0f else 1f) }
    LaunchedEffect(Unit) { if (grow.value < 1f) { if (liquid) grow.animateTo(1f, Sp.Player.unit) else grow.snapTo(1f) } }
    val boxCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
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
        // The short swiped away is over: its viewing is reported before the next one's begins.
        if (playback.player.currentMediaItem?.mediaId != id) reporter.finish()
        playback.show(ids, pager.settledPage)
        model.loadInfo(id)
        ids.drop(pager.settledPage + 1).take(2).forEach { model.loadInfo(it) }
        if (pager.settledPage >= ids.size - 4) model.loadMore()
    }

    // Into history as soon as it's shown, then again with its title once that's known (which can be
    // after it starts playing, or never if it's swiped past first): a short skipped through still counts as seen.
    val shownId = ids.getOrNull(pager.settledPage)
    val shownTitle = shownId?.let { infos[it]?.title }
    LaunchedEffect(shownId, shownTitle) {
        if (shownId == null || graph.settings.current.pauseHistory) return@LaunchedEffect
        // Without a title, an entry that already has one is left as it is.
        if (shownTitle == null && graph.library.state.value.history.any { it.video.id == shownId }) return@LaunchedEffect
        graph.library.addHistory(Video(shownId, shownTitle.orEmpty(), channel = infos[shownId]?.channel, isShort = true))
    }

    // The picture ends above the tab bar, which sits on black here, as YouTube's does:
    // nothing of the short is hidden under it, and the bar reads as part of the frame.
    val barArea = floatingBarInsets.asPaddingValues().calculateBottomPadding() + 2.dp + TAB_BAR_HEIGHT + 8.dp
    DarkIsland {
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxSize()
                .onPlaced { boxCoords[0] = it }
                .then(
                    if (origin != null && liquid) Modifier.graphicsLayer {
                        // Out of the tapped poster as a drop: from its rect and corner to the page,
                        // corners rounding and sides swelling on the way, over-filling a touch.
                        val f = grow.value
                        if (f >= 0.9995f && !grow.isRunning) return@graphicsLayer
                        val b = origin.bounds
                        val w = size.width
                        val h = size.height
                        // Read live: the page may be sliding in under it, and the poster stays put.
                        val at = boxCoords[0]?.takeIf { it.isAttached }?.positionInWindow() ?: androidx.compose.ui.geometry.Offset.Zero
                        val l = lerp(b.left - at.x, 0f, f)
                        val t = lerp(b.top - at.y, 0f, f)
                        val sx = lerp(b.width / w, 1f, f).coerceAtLeast(0.01f)
                        val sy = lerp(b.height / h, 1f, f).coerceAtLeast(0.01f)
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = l
                        translationY = t
                        scaleX = sx
                        scaleY = sy
                        val swell = midFlight(f)
                        val r = lerp(origin.corner, 0f, smoothstep(0.55f, 1f, f)) + swell * with(density) { 22.dp.toPx() }
                        shape = DropletShape(r / sx, r / sy, side = swell * with(density) { 8.dp.toPx() } / sx, ends = swell * with(density) { 18.dp.toPx() } / sy)
                        clip = true
                    } else Modifier,
                )
                .background(Color.Black)
                .padding(bottom = barArea),
        ) {
            when {
                ids.isEmpty() && error != null -> MessageState(error!!, Modifier.align(Alignment.Center), action = "Try again", onAction = model::retry)
                ids.isEmpty() -> ActivityIndicator(Modifier.align(Alignment.Center), size = 30.dp, color = Color.White)
                else -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // On a wide screen the short keeps its portrait shape, centred on black, rather than stretching across it.
                    val column = if (LocalWindowLayout.current.wide) minOf(maxWidth, maxHeight * 9f / 16f) else maxWidth
                    VerticalPager(
                    pager,
                    Modifier.width(column).fillMaxHeight().clip(RoundedCornerShape(bottomStart = SHORT_CORNER, bottomEnd = SHORT_CORNER)),
                    key = { ids[it] },
                    beyondViewportPageCount = 1,
                    flingBehavior = if (liquid) PagerDefaults.flingBehavior(pager, snapAnimationSpec = Sp.Page.spec()) else PagerDefaults.flingBehavior(pager),
                ) { page ->
                    val id = ids[page]
                    val landed = Modifier.graphicsLayer {
                        if (page != pager.settledPage) return@graphicsLayer
                        val q = landing.value
                        if (q == 0f) return@graphicsLayer
                        // Squashed along the swipe and widened a little.
                        val k = (kotlin.math.abs(q) * 0.4f).coerceAtMost(0.05f)
                        scaleY = 1f - k
                        scaleX = 1f + k * 0.5f
                        transformOrigin = TransformOrigin(0.5f, if (q > 0f) 0f else 1f)
                    }
                    Box(landed) { ShortPage(
                        app, id, infos[id], playback, active = page == pager.settledPage,
                        fit = page == pager.settledPage && aspect > 0.8f,
                        onComments = { commentsFor = it },
                        onSound = { soundFor = it },
                    ) }
                    }
                }
            }
        }
        commentsFor?.let { info -> ShortCommentsSheet(app, info) { commentsFor = null } }
        soundFor?.let { sound -> SoundSheet(app, sound) { soundFor = null } }
    }
}
