package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.oriel.ui.theme.animatedBackground
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.theme.PlayerSpring
import app.oriel.ui.theme.SheetSpring
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.oriel.core.model.Video
import app.oriel.ui.AppState
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.TopBarContentHeight
import app.oriel.ui.components.TAB_BAR_HEIGHT
import app.oriel.ui.components.floatingBarInsets
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.rememberArtworkPalette
import app.oriel.util.Thumbnails
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** A rectangle in pixels, for the flight between the mini player and the watch page. */
private data class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun lerpTo(o: Box4, t: Float) = Box4(lerp(x, o.x, t), lerp(y, o.y, t), lerp(w, o.w, t), lerp(h, o.h, t))
}

private val PIP_MAX_WIDTH = 232.dp
private val PIP_MARGIN = 12.dp
private val PIP_CORNER = 14.dp
/** How small a pinch can make the window, relative to its default size. */
private const val PIP_MIN_SCALE = 0.7f

/**
 * The player as one surface that flies: collapsed, a picture-in-picture window floating over
 * the app in a corner; the same picture grows into the top of the watch page as the sheet
 * rises, and fills the screen in fullscreen. The picture is a single TextureView the whole
 * way, so nothing reloads or flashes mid-flight.
 *
 * Drag the picture down to float it, throw the window to any corner (or off a side to stop),
 * tap it for its controls; the gesture hands over to a spring at release.
 */
@Composable
fun PlayerHost(app: AppState, withTabBar: Boolean, hidden: Boolean, inPip: Boolean) {
    val connection = app.connection
    val sheet = app.sheet
    val current by connection.current.collectAsState()
    val watchState by app.watch.state.collectAsState()
    // Optimistic: a tapped video opens its page at once, before a cold-starting service
    // has reported it as current; the player catches up underneath.
    val opening by remember { derivedStateOf { sheet.progress.targetValue > 0.5f } }
    val video = current ?: watchState.video?.takeIf { opening }
    val audioOnly by connection.audioOnly.collectAsState()

    if (video == null) {
        // The queue ended or was closed while the page was open. (Checked again here: on the
        // first frame the player may simply not have reported its video yet.)
        LaunchedEffect(Unit) {
            if (connection.current.value != null) return@LaunchedEffect
            // Fullscreen's own effect left with the player; don't strand the bars hidden and the screen sideways.
            if (sheet.fullscreen) {
                sheet.fullscreen = false
                showSystemChrome(app.activity)
            }
            if (sheet.progress.value > 0f) sheet.collapse()
        }
        return
    }
    if (inPip) {
        VideoSurface(connection, video, Modifier.fillMaxSize(), audioOnly = audioOnly)
        return
    }
    val showing by remember { derivedStateOf { sheet.progress.value > 0.001f || sheet.fullscreen } }
    if (hidden && !showing) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val palette = rememberArtworkPalette(Thumbnails.frame(video.id, 320))
    val videoSize by connection.videoSize.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    // Fill is per video and per visit to fullscreen: the next video, or the next time in, starts fitted.
    var zoomed by remember(video.id) { mutableStateOf(false) }
    LaunchedEffect(sheet.fullscreen) { if (!sheet.fullscreen) zoomed = false }

    FullscreenEffects(app, video)
    BackHandler(enabled = sheet.fullscreen) { sheet.fullscreen = false }
    BackHandler(enabled = !sheet.fullscreen && sheet.expanded) { sheet.collapse() }
    LaunchedEffect(video.id) { if (app.watch.state.value.video?.id != video.id) app.watch.load(video) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barsBottom = floatingBarInsets.asPaddingValues().calculateBottomPadding() + 2.dp + (if (withTabBar) TAB_BAR_HEIGHT else 0.dp)

    // Collapsed, the picture floats over the app as a picture-in-picture window: it can be
    // thrown to any corner, and thrown off the side to stop. These hold its drag, relative to
    // the corner it rests in, and the fade of a throw away.
    val pipDrag = remember { androidx.compose.animation.core.Animatable(Offset.Zero, Offset.VectorConverter) }
    val pipGone = remember { androidx.compose.animation.core.Animatable(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - pipGone.value }) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val px = { d: Dp -> with(density) { d.toPx() } }

        // Tall videos get a taller frame on the watch page, as YouTube gives them, up to 62% of the screen.
        val aspect = if (videoSize.width > 0 && videoSize.height > 0) videoSize.width.toFloat() / videoSize.height else 16f / 9f
        // In landscape (a phone turned for fullscreen, a tablet) 16:9 of the width is taller
        // than the cap, so the floor gives way to it rather than forming an empty range.
        val maxH = H * 0.62f
        val fullH = (W / aspect).coerceIn(minOf(W * 9f / 16f, maxH), maxH)
        val fullVideo = Box4(0f, px(statusTop), W, fullH)
        val screen = Box4(0f, 0f, W, H)

        // The window: about half the width for a wide video, as tall as a third of the screen
        // for a tall one, kept clear of the status bar's row of buttons and of the tab bar.
        // A pinch scales that, up to the width of the screen or 45% of its height.
        val margin = px(PIP_MARGIN)
        val baseW = minOf(W * 0.5f, px(PIP_MAX_WIDTH)).let { w -> if (w / aspect > H * 0.3f) H * 0.3f * aspect else w }
        fun pipSizeAt(scale: Float): Offset {
            var w = baseW * scale
            var h = w / aspect
            if (w > W - 2 * margin) { w = W - 2 * margin; h = w / aspect }
            if (h > H * 0.45f) { h = H * 0.45f; w = h * aspect }
            return Offset(w, h)
        }
        val pipMaxScale = pipSizeAt(10f).x / baseW
        val pipSize = pipSizeAt(sheet.pipScale)
        val pipW = pipSize.x
        val pipH = pipSize.y
        val pipTop = px(statusTop) + px(TopBarContentHeight) + margin
        fun cornerAt(i: Int, size: Offset = pipSize) =
            Offset(if (i % 2 == 0) margin else W - size.x - margin, if (i < 2) pipTop else H - px(barsBottom) - size.y - margin)
        val pipBottom = cornerAt(3).y
        val rest = cornerAt(sheet.pipCorner)
        // The window's resting place. A drag moves it in the layer (translation), not the
        // layout: relaying out the TextureView every frame of a drag is what made it lag.
        val pipRest = Box4(rest.x, rest.y, pipW, pipH)
        val pipRect = { val d = pipDrag.value; Box4(rest.x + d.x, rest.y + d.y, pipW, pipH) }
        val travel = (pipBottom - fullVideo.y).coerceAtLeast(1f)
        val contentTop = fullVideo.y + fullVideo.h

        val progress = sheet.progress
        val fullscreen = sheet.fullscreen
        val fsAnim = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(fullscreen) { fsAnim.animateTo(if (fullscreen) 1f else 0f, PlayerSpring) }

        // ---- the drag that moves the sheet ------------------------------------------------
        val tracker = remember { VelocityTracker() }
        var dragTotal by remember { mutableFloatStateOf(0f) }
        var panTotal by remember { mutableStateOf(Offset.Zero) }
        var pinched by remember { mutableStateOf(false) }
        val latestTravel by rememberUpdatedState(travel)
        fun release() {
            val v = tracker.calculateVelocity().y
            tracker.resetTracking()
            val p = progress.value
            val target = when {
                v < -900f -> 1f
                v > 900f -> 0f
                else -> if (p > 0.5f) 1f else 0f
            }
            haptics.play(if (target == 1f) Haptic.Expand else Haptic.Tap)
            scope.launch { sheet.settle(target, -v / latestTravel) }
        }
        // Follows the finger at once and in order: a snap queued behind the release would
        // cancel the settling spring and leave the sheet where the finger left it.
        fun follow(dy: Float) {
            val p = (progress.value - dy / latestTravel).coerceIn(0f, 1f)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
        }
        // The window's throw, as iOS's PiP throws: where the flick would carry it picks the
        // corner, and the spring starts at the finger's speed. Thrown past a side, it's put away.
        fun releasePip() {
            val v = tracker.calculateVelocity().let { Offset(it.x, it.y) }
            tracker.resetTracking()
            // A pinch may have left it a size its corner can't take; settle on the size it shows.
            sheet.pipScale = sheet.pipScale.coerceIn(PIP_MIN_SCALE, pipMaxScale)
            val here = rest + pipDrag.value
            val aim = here + v * 0.2f
            val centre = aim + Offset(pipW / 2, pipH / 2)
            // Put away, as YouTube's window is, when pushed a third of the way off the left or
            // right edge and let go not pulling back, or flicked sideways hard enough that it
            // would carry past the edge. A throw across or down the screen (more down than
            // sideways) still lands in a corner.
            // A pinch that grew it past an edge isn't a push.
            val sideways = !pinched && kotlin.math.abs(v.x) >= kotlin.math.abs(v.y) * 1.5f
            val eased = !pinched && (v.getDistance() < 600f || kotlin.math.abs(v.y) < kotlin.math.abs(v.x) * 1.5f)
            val pushedLeft = eased && here.x < -pipW * 0.33f && v.x < 400f
            val pushedRight = eased && here.x + pipW > W + pipW * 0.33f && v.x > -400f
            val flungLeft = sideways && centre.x < -pipW * 0.1f
            val flungRight = sideways && centre.x > W + pipW * 0.1f
            val offLeft = pushedLeft || flungLeft
            val offRight = !offLeft && (pushedRight || flungRight)
            if (offLeft || offRight) {
                haptics.play(Haptic.Tap)
                scope.launch {
                    val out = Offset(if (offLeft) -W else W, v.y * 0.15f)
                    launch { pipGone.animateTo(1f, androidx.compose.animation.core.tween(240)) }
                    pipDrag.animateTo(pipDrag.value + out, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 300f), v)
                    connection.stop()
                    // Back in place once the player is gone, for the next video.
                    withTimeoutOrNull(1_500) { connection.current.first { it == null } }
                    pipDrag.snapTo(Offset.Zero)
                    pipGone.snapTo(0f)
                }
                return
            }
            val corner = (0..3).minBy { (cornerAt(it) + Offset(pipW / 2, pipH / 2) - centre).getDistanceSquared() }
            if (corner != sheet.pipCorner) haptics.play(Haptic.Select)
            val from = here - cornerAt(corner)
            sheet.pipCorner = corner
            scope.launch {
                pipDrag.snapTo(from)
                pipDrag.animateTo(Offset.Zero, androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 260f), v)
            }
        }
        // Grows and shrinks the window about its own centre, under the fingers; its corner anchor
        // moves with its size, so this returns the drag that takes up the difference.
        fun pinchPip(zoom: Float): Offset {
            pinched = true
            val old = pipSizeAt(sheet.pipScale)
            val scale = (sheet.pipScale * zoom).coerceIn(PIP_MIN_SCALE * 0.9f, pipMaxScale * 1.05f)
            val new = pipSizeAt(scale)
            sheet.pipScale = scale
            return cornerAt(sheet.pipCorner, old) - cornerAt(sheet.pipCorner, new) + (old - new) / 2f
        }
        // The drag handler outlives compositions; these read the window's geometry as it is now.
        val latestReleasePip by rememberUpdatedState({ releasePip() })
        val latestPinchPip by rememberUpdatedState({ zoom: Float -> pinchPip(zoom) })
        // In fullscreen the picture follows a finger down, and lets go of fullscreen past a
        // threshold or on a flick; short of it, it springs back.
        val fsPull = remember { androidx.compose.animation.core.Animatable(0f) }
        fun releaseFullscreen() {
            val v = tracker.calculateVelocity().y
            tracker.resetTracking()
            val leave = dragTotal > px(72.dp) || (v > 900f && dragTotal > px(16.dp))
            if (leave) { haptics.play(Haptic.Tap); sheet.fullscreen = false }
            scope.launch { fsPull.animateTo(0f, SheetSpring) }
        }
        // Expanded, a drag up grows the picture toward fullscreen under the finger (the same
        // flight the button runs, held at the finger's place), and letting go past a quarter of
        // the way, or flicking up, finishes it; short of that it settles back. It used to do
        // nothing until the finger lifted, then jump.
        fun pushFullscreen(dragUp: Float) {
            val f = (dragUp / (H * 0.45f)).coerceIn(0f, 1.2f)
            val eased = if (f < 0.85f) f else 0.85f + (f - 0.85f) * 0.3f
            scope.launch(start = CoroutineStart.UNDISPATCHED) { fsAnim.snapTo(eased.coerceIn(0f, 0.95f)) }
        }
        fun releasePush() {
            val v = tracker.calculateVelocity().y
            tracker.resetTracking()
            val go = fsAnim.value > 0.22f || (v < -900f && dragTotal < -px(16.dp))
            if (go) { haptics.play(Haptic.Expand); sheet.fullscreen = true }
            else scope.launch { fsAnim.animateTo(0f, SheetSpring) }
        }
        // The picture's one drag, in every state: floating, it moves the window anywhere;
        // expanded, down collapses and a flick up goes fullscreen; in fullscreen, down leaves it.
        // What it does is decided as each drag starts. (It used to be two handlers swapped by
        // state, and they traded places mid-drag as the sheet crossed halfway. Compose restarts a
        // pointer handler whose code changes, so the drag died there with nothing to settle it.)
        val pictureDrag = Modifier.pointerInput(Unit) {
            var startedExpanded = false
            var startedFullscreen = false
            var floating = false
            detectPictureDrag(
                pansFreely = { progress.value < 0.01f && !sheet.fullscreen },
                onStart = { pan ->
                    dragTotal = 0f; panTotal = Offset.Zero; pinched = false; tracker.resetTracking()
                    floating = pan
                    startedExpanded = progress.value > 0.99f
                    startedFullscreen = sheet.fullscreen
                    if (pan) scope.launch { pipDrag.stop() }
                },
                onEnd = {
                    when {
                        floating -> latestReleasePip()
                        startedFullscreen -> releaseFullscreen()
                        startedExpanded && dragTotal < 0f && progress.value > 0.99f -> releasePush()
                        else -> release()
                    }
                },
                onCancel = { if (floating) latestReleasePip() else if (startedFullscreen) releaseFullscreen() else release() },
            ) { uptime, delta, zoom ->
                if (floating) {
                    panTotal += delta
                    tracker.addPosition(uptime, panTotal)
                    val moved = pipDrag.value + delta + (if (zoom != 1f) latestPinchPip(zoom) else Offset.Zero)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { pipDrag.snapTo(moved) }
                    return@detectPictureDrag
                }
                val dy = delta.y
                dragTotal += dy
                tracker.addPosition(uptime, Offset(0f, dragTotal))
                if (startedFullscreen) {
                    // Resists past the threshold, like a rubber band, and not at all upward.
                    val pull = dragTotal.coerceAtLeast(0f)
                    val limit = px(160.dp)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { fsPull.snapTo(if (pull < limit) pull else limit + (pull - limit) * 0.3f) }
                    return@detectPictureDrag
                }
                if (sheet.fullscreen) return@detectPictureDrag
                if (startedExpanded && dragTotal < 0 && progress.value > 0.99f) { pushFullscreen(-dragTotal); return@detectPictureDrag }
                if (fsAnim.value > 0f && !sheet.fullscreen) scope.launch(start = CoroutineStart.UNDISPATCHED) { fsAnim.snapTo(0f) }
                follow(dy)
            }
        }

        // ---- the sheet: grows out of the window into the page ------------------------------
        if (showing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { m, _ ->
                        val r = pipRect().lerpTo(screen, progress.value)
                        val p = m.measure(Constraints.fixed(r.w.roundToInt().coerceAtLeast(1), r.h.roundToInt().coerceAtLeast(1)))
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(r.x.roundToInt(), r.y.roundToInt()) }
                    }
                    .graphicsLayer {
                        val c = lerp(px(PIP_CORNER), 0f, (progress.value * 1.4f).coerceAtMost(1f))
                        shape = RoundedCornerShape(c)
                        clip = true
                    }
                    .animatedBackground(palette.background.copy(alpha = 1f)),
            ) {
                Box(
                    Modifier
                        .layout { m, c ->
                            val p = m.measure(Constraints.fixed(W.roundToInt(), (H - contentTop).roundToInt().coerceAtLeast(1)))
                            layout(c.maxWidth, c.maxHeight) {
                                val from = pipRect()
                                val sheetY = lerp(from.y, 0f, progress.value)
                                val sheetX = lerp(from.x, 0f, progress.value)
                                p.place((-sheetX).roundToInt(), (contentTop - sheetY + (1f - progress.value) * px(48.dp)).roundToInt())
                            }
                        }
                        .graphicsLayer { alpha = ((progress.value - 0.3f) / 0.7f).coerceIn(0f, 1f) * (1f - fsAnim.value) },
                ) {
                    WatchContent(app, palette)
                }
            }
        }

        // ---- the picture ----------------------------------------------------------------------
        val pipCorner = px(PIP_CORNER)
        val pipShadow = px(18.dp)
        Box(
            Modifier
                .fillMaxSize()
                .layout { m, _ ->
                    val docked = pipRest.lerpTo(fullVideo, progress.value)
                    val r = docked.lerpTo(screen, fsAnim.value)
                    val p = m.measure(Constraints.fixed(r.w.roundToInt().coerceAtLeast(1), r.h.roundToInt().coerceAtLeast(1)))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(r.x.roundToInt(), r.y.roundToInt()) }
                }
                .graphicsLayer {
                    val pull = fsPull.value
                    val p = progress.value.coerceIn(0f, 1f)
                    val drag = pipDrag.value * (1f - p)
                    shape = RoundedCornerShape(lerp(pipCorner, 0f, p) + (pull / 6f).coerceAtMost(px(20.dp)))
                    clip = true
                    // Floating, it casts a shadow onto the page, gone by the time it reaches the top.
                    shadowElevation = pipShadow * (1f - p * 3f).coerceIn(0f, 1f)
                    alpha = if (hidden) progress.value else 1f
                    translationX = drag.x
                    translationY = pull * 0.6f + drag.y
                    val shrink = 1f - (pull / H).coerceIn(0f, 1f) * 0.2f
                    scaleX = shrink
                    scaleY = shrink
                }
                .then(pictureDrag)
                // Pinch to fill or fit. Only two fingers count: one finger is the swipe out of
                // fullscreen, which a full transform detector would swallow as a pan.
                .then(
                    if (fullscreen) Modifier.pointerInput(Unit) {
                        detectPinch { zoom -> if (zoom > 1.08f) zoomed = true else if (zoom < 0.93f) zoomed = false }
                    } else Modifier,
                ),
        ) {
            // Fill is the fitted picture scaled until it covers the screen, sprung there and back,
            // rather than a jump between two layouts.
            val screenAspect = W / H
            val fillScale = if (aspect > 0f) maxOf(aspect / screenAspect, screenAspect / aspect) else 1f
            val zoom by androidx.compose.animation.core.animateFloatAsState(
                if (fullscreen && zoomed) fillScale else 1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 380f),
                label = "fillZoom",
            )
            VideoSurface(connection, video, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }, contentScale = ContentScale.Fit, audioOnly = audioOnly)
            val badgeShown by remember { derivedStateOf { progress.value >= 0.5f } }
            if (audioOnly && badgeShown) AudioOnlyBadge(Modifier.align(Alignment.Center))
            val controlsShown by remember { derivedStateOf { progress.value > 0.98f || sheet.fullscreen } }
            // Read through derived state: reading the progress here would rebuild the picture every frame of a flight.
            val atDock by remember { derivedStateOf { progress.value < 0.02f } }
            if (controlsShown) {
                VideoControls(
                    app,
                    mode = if (fullscreen) ControlsMode.Fullscreen else ControlsMode.Expanded,
                    onCollapse = { sheet.collapse() },
                    onFullscreen = { sheet.fullscreen = !sheet.fullscreen },
                    onSettings = { settingsOpen = true },
                    onChapters = { chaptersOpen = true },
                    sheetOpen = chaptersOpen || settingsOpen,
                )
            } else if (atDock && !hidden) {
                PipControls(app, onExpand = { haptics.play(Haptic.Expand); sheet.expand() })
            }
        }
    }

    if (chaptersOpen) ChaptersSheet(app, onDismiss = { chaptersOpen = false })
    if (settingsOpen) {
        PlayerSettingsSheet(app, onDismiss = { settingsOpen = false }, onChapters = { chaptersOpen = true }, onPip = {
            settingsOpen = false
            enterPip(app.activity, connection.videoSize.value)
        })
    }
}

/**
 * The picture's drag: in [pansFreely] mode (the floating window) any direction past the touch
 * slop, with a second finger pinching (the zoom passed is that frame's, 1 for one finger);
 * otherwise only vertical ones, so a sideways swipe on the expanded picture is left to the
 * controls. The mode is fixed for the whole drag when it starts.
 */
private suspend fun PointerInputScope.detectPictureDrag(
    pansFreely: () -> Boolean,
    onStart: (pan: Boolean) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onDrag: (uptimeMillis: Long, delta: Offset, zoom: Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (pansFreely()) {
        // The fingers' centroid moves the window and their spread sizes it. Nothing is consumed
        // until past the slop, so a tap still reaches the window's buttons.
        var started = false
        var moved = Offset.Zero
        var spread = 1f
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (!started && event.changes.any { it.isConsumed }) return@awaitEachGesture
            val pan = event.calculatePan()
            val zoom = if (pressed >= 2) event.calculateZoom() else 1f
            if (!started) {
                moved += pan
                spread *= zoom
                if (moved.getDistance() < viewConfiguration.touchSlop && kotlin.math.abs(1f - spread) < 0.04f) continue
                started = true
                onStart(true)
                onDrag(event.changes.first().uptimeMillis, moved, spread)
            } else {
                onDrag(event.changes.first().uptimeMillis, pan, zoom)
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
        if (started) onEnd()
        return@awaitEachGesture
    }
    var slop = 0f
    val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> change.consume(); slop = over } ?: return@awaitEachGesture
    onStart(false)
    onDrag(start.uptimeMillis, Offset(0f, slop), 1f)
    val completed = verticalDrag(start.id) { val dy = it.positionChange().y; it.consume(); onDrag(it.uptimeMillis, Offset(0f, dy), 1f) }
    if (completed) onEnd() else onCancel()
}

/**
 * The floating window's controls, as YouTube's: play/pause and close always there in the top
 * corner on small dark discs, over a picture left undimmed, and a thin line of progress along
 * the bottom. A tap anywhere else opens the watch page.
 */
@Composable
private fun PipControls(app: AppState, onExpand: () -> Unit) {
    val connection = app.connection
    val playing by connection.playWhenReady.collectAsState()
    val buffering by connection.buffering.collectAsState()
    val duration by connection.duration.collectAsState()
    val position = rememberPosition(connection, intervalMs = 500)
    val haptics = rememberHaptics()
    Box(
        Modifier.fillMaxSize()
            .semantics { contentDescription = "Video window" }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Open the watch page") { onExpand() },
    ) {
        // 44dp targets, the play one tucked 4dp under the close one so the discs sit where
        // they did with 40dp targets: 22dp in from the corner, 40dp apart.
        Row(Modifier.align(Alignment.TopEnd)) {
            val nudge = Modifier.offset(x = 4.dp)
            if (buffering && playing) {
                Box(nudge.size(44.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        ActivityIndicator(size = 14.dp, color = Color.White)
                    }
                }
            } else {
                PipButton(if (playing) OrielIcons.Pause else OrielIcons.Play, if (playing) "Pause" else "Play", 15.dp, nudge) {
                    haptics.play(if (playing) Haptic.Pause else Haptic.Resume); connection.togglePlay()
                }
            }
            PipButton(OrielIcons.Xmark, "Close", 11.dp) { haptics.play(Haptic.Tap); connection.stop() }
        }
        if (duration > 0) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.5.dp).background(Color.White.copy(alpha = 0.28f)).drawWithContent {
                    val f = (position.longValue.toFloat() / duration).coerceIn(0f, 1f)
                    drawRect(Color.White, size = size.copy(width = size.width * f))
                },
            )
        }
    }
}

/** A small dark disc with a white glyph, a 44dp touch target around a 30dp disc that sinks and darkens while pressed. */
@Composable
private fun PipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, iconSize: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f else 0f, androidx.compose.animation.core.spring(stiffness = 900f), label = "pipPress")
    Box(modifier.size(44.dp).clickable(interaction, indication = null, onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(30.dp)
                .graphicsLayer { val k = 1f - 0.12f * press; scaleX = k; scaleY = k }
                .clip(CircleShape)
                .drawWithContent { drawRect(Color.Black.copy(alpha = 0.45f + 0.2f * press)); drawContent() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}

/** Reports the running zoom of a two-finger pinch, consuming only while two or more fingers are down. */
private suspend fun PointerInputScope.detectPinch(onZoom: (Float) -> Unit) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    var zoom = 1f
    do {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } >= 2) {
            zoom *= event.calculateZoom()
            onZoom(zoom)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    } while (event.changes.any { it.pressed })
}

@Composable
private fun AudioOnlyBadge(modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(OrielIcons.AudioOnly, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Audio only", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/**
 * Fullscreen means landscape (portrait for tall videos) with the system bars
 * hidden. Turning the phone sideways on the watch page enters it, and back
 * upright leaves it, as YouTube does, when the system lets the screen rotate.
 */
/** Back to the app's normal orientation with the system bars showing. */
private fun showSystemChrome(activity: android.app.Activity) {
    activity.requestedOrientation = defaultOrientation(activity)
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
}

@Composable
private fun FullscreenEffects(app: AppState, video: Video) {
    val activity = app.activity
    val sheet = app.sheet
    val fullscreen = sheet.fullscreen
    val videoSize by app.connection.videoSize.collectAsState()
    val tall = videoSize.height > videoSize.width && videoSize.width > 0
    val context = LocalContext.current

    DisposableEffect(fullscreen, tall) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = if (tall) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            showSystemChrome(activity)
        }
        onDispose { }
    }

    // Rotate-to-fullscreen, debounced so a tilt while walking doesn't flip it. Only a turn of
    // the phone counts, not how it's held: the hold is taken afresh at every change of mode, so
    // Back while holding it sideways stays out, and the button's fullscreen stays in while upright,
    // until the phone is actually turned.
    val expanded = sheet.expanded
    DisposableEffect(expanded, tall, fullscreen) {
        if (!expanded || tall) return@DisposableEffect onDispose { }
        var lastDevice: Boolean? = null
        var since = 0L
        var pending: Boolean? = null
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                val autoRotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                if (!autoRotate) return
                val landscape = angle in 60..120 || angle in 240..300
                val portrait = angle < 25 || angle > 335
                // Somewhere between the two is no change either way.
                val device = when { landscape -> true; portrait -> false; else -> return }
                val held = lastDevice ?: run { lastDevice = device; return }
                if (device == held) { pending = null; return }
                val now = System.currentTimeMillis()
                if (pending != device) { pending = device; since = now; return }
                if (now - since > 350) {
                    pending = null
                    lastDevice = device
                    if (sheet.fullscreen != device) sheet.fullscreen = device
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
}

/** Phones keep the app upright outside fullscreen, as YouTube does; tablets turn freely. */
fun defaultOrientation(activity: android.app.Activity): Int =
    if (activity.resources.configuration.smallestScreenWidthDp >= 600) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT

