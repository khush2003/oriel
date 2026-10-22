package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.oriel.ui.theme.animatedBackground
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.theme.PlayerSpring
import app.oriel.ui.theme.SheetSpring
import app.oriel.ui.theme.DropletShape
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.gooEffect
import app.oriel.ui.theme.goo
import app.oriel.ui.theme.metaballNeck
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import app.oriel.ui.theme.SmoothedVelocity
import app.oriel.ui.theme.project
import app.oriel.ui.theme.midFlight
import app.oriel.ui.theme.smoothstep
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.TransformOrigin
import app.oriel.ui.components.RemoteImage
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.LocalLiquidMotion
import androidx.compose.runtime.SideEffect
import app.oriel.ui.theme.motion
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
import app.oriel.ui.VideoHandoff
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
    /** Scaled by [k] about its centre. */
    fun scaled(k: Float) = Box4(x + w * (1f - k) / 2f, y + h * (1f - k) / 2f, w * k, h * k)
}

/**
 * A thumbnail on its way into the player. [from] is where it was tapped, in the host's
 * coordinates (null: it blooms in place, for a video that changed without a tap); [docked]
 * means the whole player is growing out of it with the sheet; [ghost] that there was a window
 * playing at the dock to be absorbed as it goes.
 */
private class Drop(val from: Box4?, val corner: Float, val art: String?, val docked: Boolean, val ghost: Boolean) {
    /** Its own flight into a page already open, 0 to 1 (a docked drop rides the sheet's progress instead). */
    val flight = androidx.compose.animation.core.Animatable(0f)
    /** Shown, then faded as the picture takes over under it. */
    val shown = androidx.compose.animation.core.Animatable(1f)
}

private val PIP_MAX_WIDTH = 232.dp
private val PIP_MARGIN = 12.dp
private val PIP_CORNER = 14.dp
/** How small a pinch can make the window, relative to its default size. */
private const val PIP_MIN_SCALE = 0.7f

/**
 * A throw this fast (per second), mostly sideways and toward the side the window is already on,
 * puts it away. Slower, or toward the far side, it's a move: it lands in a corner.
 */
internal val PIP_FLING_AWAY = 1400.dp

/** The share of the window that has to be past a side edge for letting go there to put it away. */
internal const val PIP_AWAY_OVER = 0.5f

/** The deceleration a throw is projected with to pick the corner it lands in (about 0.2 s of its speed). */
private const val PIP_THROW_RATE = 0.995f

internal enum class PipRelease { Land, AwayLeft, AwayRight }

/** Which side's put-away zone a window at [x], [w] wide, is in on a screen [screenW] wide: -1 left, 1 right, 0 neither. */
internal fun pipZoneAt(x: Float, w: Float, screenW: Float): Int = when {
    -x > w * PIP_AWAY_OVER -> -1
    x + w - screenW > w * PIP_AWAY_OVER -> 1
    else -> 0
}

/**
 * What letting go of the floating window does, as YouTube's and iOS's floating players decide it:
 * put away only on an explicit intent, either released with more than half of it past a side
 * edge (unless thrown hard back in), or thrown at [flingPx] per second or faster, mostly
 * sideways (1.5 times its vertical speed), toward the side its centre is already on. Anything
 * else, it lands in the corner the throw carries it to. A pinch is never a throw away.
 */
internal fun pipRelease(x: Float, w: Float, screenW: Float, vx: Float, vy: Float, flingPx: Float, pinched: Boolean): PipRelease {
    if (pinched) return PipRelease.Land
    when (pipZoneAt(x, w, screenW)) {
        -1 -> if (vx < flingPx) return PipRelease.AwayLeft
        1 -> if (vx > -flingPx) return PipRelease.AwayRight
    }
    val sideways = abs(vx) > abs(vy) * 1.5f
    val centre = x + w / 2f
    return when {
        sideways && vx <= -flingPx && centre < screenW / 2f -> PipRelease.AwayLeft
        sideways && vx >= flingPx && centre > screenW / 2f -> PipRelease.AwayRight
        else -> PipRelease.Land
    }
}

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
    // Read when a release runs: the drag handler outlives the composition that set it up.
    val reduceMotion by rememberUpdatedState(LocalReduceAnimation.current)
    val pipGone = remember { androidx.compose.animation.core.Animatable(0f) }
    // 1 while the window is held where letting go puts it away (more than half off a side), 0 otherwise;
    // which side it's in now (-1 left, 1 right, 0 neither) and which it was in last, for drawing it.
    val pipZone = remember { androidx.compose.animation.core.Animatable(0f) }
    var pipZoneSide by remember { mutableIntStateOf(0) }
    var pipZoneEdge by remember { mutableIntStateOf(1) }
    // The liquid part of the flight: stretch with speed, squash on landing, a thumbnail that grows
    // into the picture. All drawn, and only with Liquid motion on; off (or under Reduce motion)
    // it's the plain flight.
    val liquid = LocalLiquidMotion.current && !LocalReduceAnimation.current
    SideEffect { sheet.liquid = liquid }
    val liquidNow by rememberUpdatedState(liquid)
    fun pipZoneSpring() = if (liquidNow) Sp.Bouncy.unit else androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = 0.001f)
    val dragSpeed = remember { SmoothedVelocity() }
    // The sheet's speed under the finger, in progress per second: read only while drawing.
    var dragVelocity by remember { mutableFloatStateOf(0f) }
    // The same for fullscreen's push and pull under the finger (per second, in the push's units
    // and in screen heights): read only while drawing.
    val fsSpeed = remember { SmoothedVelocity() }
    var fsDragVelocity by remember { mutableFloatStateOf(0f) }
    var drop by remember { mutableStateOf<Drop?>(null) }
    // Which drag has the picture, for the droplet left at the dock: the sheet's, or the window's pan.
    var sheetDragging by remember { mutableStateOf(false) }
    var panning by remember { mutableStateOf(false) }
    // The window's speed under a pan, px per second: read only while drawing.
    val panSpeedX = remember { SmoothedVelocity() }
    val panSpeedY = remember { SmoothedVelocity() }
    val hostInWindow = remember { floatArrayOf(0f, 0f) }
    // A tap's thumbnail is taken up here, as the tap is composed, rather than by an effect a frame
    // later: that frame showed the picture leaving the dock (an empty one, with nothing playing)
    // before the thumbnail appeared.
    val seenHandoff = remember { arrayOfNulls<VideoHandoff>(1) }
    sheet.handoff.let { h ->
        if (h !== seenHandoff[0]) {
            seenHandoff[0] = h
            // Only the tap that's happening: not one replayed when the player comes back later.
            if (h != null && liquid && !sheet.fullscreen && System.nanoTime() - h.at < 1_000_000_000L) {
                val from = h.origin?.bounds?.let { Box4(it.left - hostInWindow[0], it.top - hostInWindow[1], it.width, it.height) }
                // With no thumbnail to grow from (a link, a notification) it still rides the sheet,
                // blooming where the picture lands rather than rising out of the dock.
                drop = Drop(from, h.origin?.corner ?: 0f, h.origin?.art ?: Thumbnails.frame(h.videoId, 720), docked = h.fromDock, ghost = h.windowShowing)
            }
        }
    }
    val lastVideo = remember { arrayOfNulls<String>(1) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onPlaced { val o = it.positionInWindow(); hostInWindow[0] = o.x; hostInWindow[1] = o.y }
            .graphicsLayer { alpha = 1f - pipGone.value },
    ) {
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
        // Where the picture rests at the sheet's progress now: what a drop flies into.
        val pictureAt = { pipRest.lerpTo(fullVideo, sheet.progress.value) }
        // The sheet grows out of the tapped thumbnail, not the dock, while one is flying with it.
        val sheetFrom = { drop?.takeIf { it.docked }?.from ?: pipRect() }

        // ---- a new video: its thumbnail hands off into the player ------------------------------
        val handoff = sheet.handoff
        LaunchedEffect(handoff) {
            val d = drop ?: return@LaunchedEffect
            if (handoff == null || seenHandoff[0] !== handoff) return@LaunchedEffect
            if (d.docked) {
                // It flies with the sheet: done when the sheet lands, turns back, or a finger takes it.
                snapshotFlow { sheet.progress.value >= 0.999f || sheet.progress.targetValue < 0.5f || drop !== d }.first { it }
            } else {
                // Into the page already open, the picture wobbles as it's hit (the mock's jelly kick).
                sheet.jiggle()
                d.flight.animateTo(1f, Sp.Player.unit)
            }
            if (drop === d) {
                d.shown.animateTo(0f, androidx.compose.animation.core.tween(220))
                if (drop === d) drop = null
            }
        }
        // A video that changed without a tap (the queue moving on, a link): the new picture blooms
        // in place, the old one giving way under it.
        LaunchedEffect(video.id) {
            val before = lastVideo[0]
            lastVideo[0] = video.id
            if (before == null || before == video.id || !liquid || sheet.fullscreen) return@LaunchedEffect
            val h = sheet.handoff
            if (h != null && h.videoId == video.id && System.nanoTime() - h.at < 3_000_000_000L && h.origin != null) return@LaunchedEffect
            if (drop != null) return@LaunchedEffect
            val d = Drop(null, 0f, Thumbnails.frame(video.id, 720), docked = false, ghost = false)
            drop = d
            sheet.jiggle()
            d.flight.animateTo(1f, Sp.Player.unit)
            if (drop === d) {
                d.shown.animateTo(0f, androidx.compose.animation.core.tween(220))
                if (drop === d) drop = null
            }
        }

        val progress = sheet.progress
        val fullscreen = sheet.fullscreen
        val fsAnim = remember { androidx.compose.animation.core.Animatable(0f) }
        // A swipe up that finishes fullscreen hands its speed on, so the flight doesn't pause at release.
        val fsThrow = remember { floatArrayOf(0f) }
        LaunchedEffect(fullscreen) {
            val target = if (fullscreen) 1f else 0f
            val v = fsThrow[0].also { fsThrow[0] = 0f }
            // Liquid, on the mock's player spring: it carries past the screen's edges and back
            // (drawn as a stretch going in and a squash landing on the page), as the flight to the mini player does.
            if (reduceMotion) fsAnim.snapTo(target) else fsAnim.animateTo(target, if (liquidNow) Sp.Player.unit else PlayerSpring, initialVelocity = v)
        }

        // ---- the drag that moves the sheet ------------------------------------------------
        val tracker = remember { VelocityTracker() }
        var dragTotal by remember { mutableFloatStateOf(0f) }
        var panTotal by remember { mutableStateOf(Offset.Zero) }
        var pinched by remember { mutableStateOf(false) }
        // Past the point where letting go commits (out of fullscreen, into it, the window put away):
        // one tick as the finger crosses it, as iOS marks it, and none again at the release.
        var armed by remember { mutableStateOf(false) }
        fun arm(past: Boolean) {
            if (past && !armed) haptics.play(Haptic.Tick)
            armed = past
        }
        val latestTravel by rememberUpdatedState(travel)
        // The drag started as a swipe up on the floating window (as opposed to one on the page).
        var rising by remember { mutableStateOf(false) }
        fun release() {
            val v = tracker.calculateVelocity().y
            tracker.resetTracking()
            val p = progress.value
            val target = when {
                // A swipe up on the window opens the page unless it's pulled back down: it's
                // already a deliberate gesture, so it needn't be carried halfway.
                rising -> if (v > px(300.dp)) 0f else if (v < -px(150.dp) || p > 0.1f) 1f else 0f
                v < -900f -> 1f
                v > 900f -> 0f
                else -> if (p > 0.5f) 1f else 0f
            }
            rising = false
            haptics.play(if (target == 1f) Haptic.Expand else Haptic.Tap)
            dragVelocity = 0f
            scope.launch { sheet.settle(target, -v / latestTravel) }
        }
        // Follows the finger at once and in order: a snap queued behind the release would
        // cancel the settling spring and leave the sheet where the finger left it.
        fun follow(dy: Float) {
            val p = (progress.value - dy / latestTravel).coerceIn(0f, 1f)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
        }
        // The window's throw, as iOS's PiP throws: where the flick would carry it picks the
        // corner, and the spring starts at the finger's speed. Only an explicit throw away puts it
        // away (see [pipRelease]): letting go of it anywhere else on the screen just lands it.
        fun releasePip() {
            val v = tracker.calculateVelocity().let { Offset(it.x, it.y) }
            tracker.resetTracking()
            // A pinch may have left it a size its corner can't take; settle on the size it shows.
            sheet.pipScale = sheet.pipScale.coerceIn(PIP_MIN_SCALE, pipMaxScale)
            val here = rest + pipDrag.value
            val outcome = pipRelease(here.x, pipW, W, v.x, v.y, px(PIP_FLING_AWAY), pinched)
            if (outcome != PipRelease.Land) {
                val offLeft = outcome == PipRelease.AwayLeft
                if (!armed) haptics.play(Haptic.Tap)
                armed = false
                scope.launch {
                    val out = Offset(if (offLeft) -W else W, v.y * 0.15f)
                    if (reduceMotion) {
                        pipGone.snapTo(1f)
                    } else {
                        launch { pipGone.animateTo(1f, androidx.compose.animation.core.tween(240)) }
                        pipDrag.animateTo(pipDrag.value + out, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 300f), v)
                    }
                    connection.stop()
                    // Back in place once the player is gone, for the next video.
                    withTimeoutOrNull(1_500) { connection.current.first { it == null } }
                    pipDrag.snapTo(Offset.Zero)
                    pipZone.snapTo(0f)
                    pipZoneSide = 0
                    pipGone.snapTo(0f)
                }
                return
            }
            armed = false
            if (pipZoneSide != 0) {
                pipZoneSide = 0
                scope.launch { if (reduceMotion) pipZone.snapTo(0f) else pipZone.animateTo(0f, pipZoneSpring()) }
            }
            // Where the throw would carry it, as UIScrollView projects a fling at its normal rate.
            val aim = here + Offset(project(v.x, PIP_THROW_RATE), project(v.y, PIP_THROW_RATE))
            val centre = aim + Offset(pipW / 2, pipH / 2)
            val corner = (0..3).minBy { (cornerAt(it) + Offset(pipW / 2, pipH / 2) - centre).getDistanceSquared() }
            if (corner != sheet.pipCorner) haptics.play(Haptic.Select)
            val from = here - cornerAt(corner)
            sheet.pipCorner = corner
            scope.launch {
                pipDrag.snapTo(from)
                // Tuned with the throw: a little give as it lands, at the finger's speed. Liquid, it
                // lands on the mock's bouncy spring and squashes into its corner.
                if (reduceMotion) pipDrag.snapTo(Offset.Zero)
                else if (liquidNow) pipDrag.animateTo(Offset.Zero, Sp.Bouncy.spec(Offset(0.5f, 0.5f)), v)
                else pipDrag.animateTo(Offset.Zero, androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 260f), v)
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
        // More than half of it past a side, where letting go puts the window away (as pipRelease
        // judges it): it shrinks and dims there, with a tick as it enters, so the release isn't a surprise.
        val latestArmPip by rememberUpdatedState({ drag: Offset ->
            val side = pipZoneAt(rest.x + drag.x, pipW, W)
            arm(side != 0)
            if (side != pipZoneSide) {
                if (side != 0) pipZoneEdge = side
                pipZoneSide = side
                scope.launch { if (reduceMotion) pipZone.snapTo(if (side != 0) 1f else 0f) else pipZone.animateTo(if (side != 0) 1f else 0f, pipZoneSpring()) }
            }
        })
        // In fullscreen the picture follows a finger down, and lets go of fullscreen past a
        // threshold or on a flick; short of it, it springs back.
        val fsPull = remember { androidx.compose.animation.core.Animatable(0f) }
        fun releaseFullscreen(cancelled: Boolean = false) {
            val v = tracker.calculateVelocity().y
            tracker.resetTracking()
            // Cancelled (a second finger's pinch took over, the system took the touch), it only springs back.
            val leave = !cancelled && (dragTotal > px(72.dp) || (v > 900f && dragTotal > px(16.dp)))
            if (leave) { if (!armed) haptics.play(Haptic.Tap); sheet.fullscreen = false }
            armed = false
            val throwV = if (cancelled) 0f else v.coerceIn(-4000f, 4000f)
            // Liquid, the picture that stays springs back with a wobble, the mock's bouncy spring.
            scope.launch { if (reduceMotion) fsPull.snapTo(0f) else fsPull.animateTo(0f, if (liquidNow && !leave) Sp.Bouncy.px else SheetSpring, initialVelocity = throwV) }
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
        fun releasePush(cancelled: Boolean = false) {
            val v = tracker.calculateVelocity().y
            tracker.resetTracking()
            val go = !cancelled && (fsAnim.value > 0.22f || (v < -900f && dragTotal < -px(16.dp)))
            // The flight's speed in its own units: the push grows over 45% of the screen's height.
            val flight = if (cancelled) 0f else (-v / (H * 0.45f)).coerceIn(-8f, 8f)
            if (go) { if (!armed) haptics.play(Haptic.Expand); fsThrow[0] = flight.coerceAtLeast(0f); sheet.fullscreen = true }
            // PlayerSpring, not SheetSpring: this is a 0-to-1 value, and SheetSpring's half-unit
            // threshold ended the settle at once, so the picture jumped back.
            else scope.launch { if (reduceMotion) fsAnim.snapTo(0f) else fsAnim.animateTo(0f, if (liquidNow) Sp.Bouncy.unit else PlayerSpring, initialVelocity = flight) }
            armed = false
        }
        // The drag handler outlives compositions; these see the screen as it is now, after a resize.
        val latestPushFullscreen by rememberUpdatedState({ dragUp: Float -> pushFullscreen(dragUp) })
        val latestReleaseFullscreen by rememberUpdatedState({ cancelled: Boolean -> releaseFullscreen(cancelled) })
        val latestReleasePush by rememberUpdatedState({ cancelled: Boolean -> releasePush(cancelled) })
        // The picture's one drag, in every state: floating, it moves the window anywhere;
        // expanded, down collapses and a flick up goes fullscreen; in fullscreen, down leaves it.
        // What it does is decided as each drag starts. (It used to be two handlers swapped by
        // state, and they traded places mid-drag as the sheet crossed halfway. Compose restarts a
        // pointer handler whose code changes, so the drag died there with nothing to settle it.)
        val pictureScreen = remember { ScreenPosition() }
        val pictureDrag = Modifier.screenPosition(pictureScreen).pointerInput(Unit) {
            var startedExpanded = false
            var startedFullscreen = false
            var floating = false
            detectPictureDrag(
                screen = pictureScreen,
                pansFreely = { progress.value < 0.01f && !sheet.fullscreen },
                onStart = { mode ->
                    val pan = mode == PictureDrag.Pan
                    rising = mode == PictureDrag.Rise
                    dragTotal = 0f; panTotal = Offset.Zero; pinched = false; armed = false; tracker.resetTracking()
                    dragSpeed.reset(); dragVelocity = 0f
                    fsSpeed.reset(); fsDragVelocity = 0f
                    // A finger takes the player from a thumbnail's flight: it's the player's again.
                    if (drop?.docked == true) drop = null
                    floating = pan
                    panning = pan
                    sheetDragging = !pan
                    panSpeedX.reset(); panSpeedY.reset()
                    startedExpanded = progress.value > 0.99f
                    startedFullscreen = sheet.fullscreen
                    if (pan) scope.launch { pipDrag.stop() }
                },
                onEnd = {
                    panning = false
                    sheetDragging = false
                    fsDragVelocity = 0f
                    when {
                        floating -> latestReleasePip()
                        startedFullscreen -> latestReleaseFullscreen(false)
                        startedExpanded && dragTotal < 0f && progress.value > 0.99f -> latestReleasePush(false)
                        else -> release()
                    }
                },
                // A cancelled drag puts back what it was growing rather than committing it: the
                // push up used to be left halfway to fullscreen.
                onCancel = {
                    panning = false
                    sheetDragging = false
                    fsDragVelocity = 0f
                    when {
                        floating -> latestReleasePip()
                        startedFullscreen -> latestReleaseFullscreen(true)
                        startedExpanded && dragTotal < 0f && progress.value > 0.99f -> latestReleasePush(true)
                        else -> release()
                    }
                },
            ) { uptime, delta, zoom ->
                if (floating) {
                    panTotal += delta
                    tracker.addPosition(uptime, panTotal)
                    panSpeedX.add(uptime, panTotal.x)
                    panSpeedY.add(uptime, panTotal.y)
                    val moved = pipDrag.value + delta + (if (zoom != 1f) latestPinchPip(zoom) else Offset.Zero)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { pipDrag.snapTo(moved) }
                    if (!pinched) latestArmPip(moved)
                    return@detectPictureDrag
                }
                val dy = delta.y
                dragTotal += dy
                tracker.addPosition(uptime, Offset(0f, dragTotal))
                if (startedFullscreen) {
                    // Resists past the threshold, like a rubber band, and not at all upward.
                    val pull = dragTotal.coerceAtLeast(0f)
                    arm(dragTotal > px(72.dp))
                    val limit = px(160.dp)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { fsPull.snapTo(if (pull < limit) pull else limit + (pull - limit) * 0.3f) }
                    fsSpeed.add(uptime, fsPull.value / H)
                    fsDragVelocity = fsSpeed.velocity
                    return@detectPictureDrag
                }
                if (sheet.fullscreen) return@detectPictureDrag
                if (startedExpanded && dragTotal < 0 && progress.value > 0.99f) {
                    latestPushFullscreen(-dragTotal)
                    arm(fsAnim.value > 0.22f)
                    fsSpeed.add(uptime, fsAnim.value)
                    fsDragVelocity = fsSpeed.velocity
                    return@detectPictureDrag
                }
                if (fsAnim.value > 0f && !sheet.fullscreen) scope.launch(start = CoroutineStart.UNDISPATCHED) { fsAnim.snapTo(0f) }
                follow(dy)
                dragSpeed.add(uptime, progress.value)
                dragVelocity = dragSpeed.velocity
            }
        }

        // ---- the sheet: grows out of the window into the page ------------------------------
        if (showing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { m, _ ->
                        // The mock's page is the whole screen from the start, its colour fading in;
                        // without motion it grows out of the window as before.
                        val r = if (liquid) screen else sheetFrom().lerpTo(screen, progress.value)
                        val p = m.measure(Constraints.fixed(r.w.roundToInt().coerceAtLeast(1), r.h.roundToInt().coerceAtLeast(1)))
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(r.x.roundToInt(), r.y.roundToInt()) }
                    }
                    .then(
                        if (liquid) Modifier else Modifier
                            .graphicsLayer {
                                val c = lerp(px(PIP_CORNER), 0f, (progress.value * 1.4f).coerceAtMost(1f))
                                shape = RoundedCornerShape(c)
                                clip = true
                            }
                            .animatedBackground(palette.background.copy(alpha = 1f)),
                    ),
            ) {
                if (liquid) Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = smoothstep(0f, 0.55f, progress.value) }
                        .animatedBackground(palette.background.copy(alpha = 1f)),
                )
                Box(
                    Modifier
                        .layout { m, c ->
                            val p = m.measure(Constraints.fixed(W.roundToInt(), (H - contentTop).roundToInt().coerceAtLeast(1)))
                            layout(c.maxWidth, c.maxHeight) {
                                val from = if (liquid) screen else sheetFrom()
                                val sheetY = lerp(from.y, 0f, progress.value)
                                val sheetX = lerp(from.x, 0f, progress.value)
                                // Liquid, the mock's: the details rise 320 dp into place. Plain, 48.
                                val rise = px(if (liquid) 320.dp else 48.dp)
                                p.place((-sheetX).roundToInt(), (contentTop - sheetY + (1f - progress.value) * rise).roundToInt())
                            }
                        }
                        .graphicsLayer {
                            val fc = fsAnim.value.coerceIn(0f, 1f)
                            if (liquid) {
                                // The mock's: the details fade in from a third of the way, and grow
                                // from 94% as the sheet body does; going fullscreen they fall away
                                // under the picture, shrinking a little as they go.
                                alpha = smoothstep(0.35f, 0.95f, progress.value) * (1f - fc)
                                val k = lerp(0.94f, 1f, smoothstep(0.4f, 1f, progress.value)) * (1f - 0.06f * fc)
                                scaleX = k
                                scaleY = k
                                transformOrigin = TransformOrigin(0.5f, 0f)
                                translationY = fc * px(56.dp)
                            } else {
                                alpha = ((progress.value - 0.3f) / 0.7f).coerceIn(0f, 1f) * (1f - fsAnim.value)
                            }
                            // Into a page already open, the new video's details pour in under the drop.
                            val d = drop
                            if (d != null && !d.docked) {
                                val f = d.flight.value.coerceIn(0f, 1f)
                                // Gentler for a video that changed by itself than for one tapped into.
                                val floor = if (d.from == null) 0.6f else 0.35f
                                alpha *= floor + (1f - floor) * smoothstep(0.2f, 0.9f, f)
                                translationY += (1f - f) * px(if (d.from == null) 16.dp else 40.dp)
                            }
                        },
                ) {
                    WatchContent(app, palette)
                }
            }
        }

        // ---- the droplet left at the dock --------------------------------------------------------
        // The mock's dock goo: as the picture leaves the window's place (rising into the page, or
        // the window pulled away under a finger), a droplet stays behind there, shrinking, tied
        // to the picture by a tail that thins and snaps a third of the way. Fused by the goo
        // layer on Android 12 and up; below, a metaball neck joins them. Composed only meanwhile.
        val dockShowing by remember(liquid) {
            derivedStateOf {
                val e = progress.value
                liquid && drop == null && !sheet.fullscreen &&
                    ((e > 0.004f && e < 0.5f && (progress.isRunning || sheetDragging)) ||
                        (panning && pipDrag.value.getDistance() / travel in 0.004f..0.5f))
            }
        }
        if (dockShowing) {
            val gooBlur = px(6.dp)
            val fx = remember(gooBlur) { gooEffect(gooBlur) }
            // The mock paints the droplet in the video's own colours (a 135° gradient of its two).
            val dockColor = androidx.compose.ui.graphics.Brush.linearGradient(listOf(palette.accent.copy(alpha = 1f), palette.wash.copy(alpha = 1f)))
            val neck = remember { androidx.compose.ui.graphics.Path() }
            // The layer covers the dock and where the picture is until the tail has snapped, no more.
            val reach = pipRest.lerpTo(fullVideo, 0.45f)
            val pad = px(40.dp) + if (panning) travel * 0.45f else 0f
            val gl = (minOf(pipRest.x, reach.x) - pad).coerceAtLeast(0f)
            val gt = (minOf(pipRest.y, reach.y) - pad).coerceAtLeast(0f)
            val gr = (maxOf(pipRest.x + pipRest.w, reach.x + reach.w) + pad).coerceAtMost(W)
            val gb = (maxOf(pipRest.y + pipRest.h, reach.y + reach.h) + pad).coerceAtMost(H)
            Box(
                Modifier
                    .layout { m, c ->
                        val pl = m.measure(Constraints.fixed((gr - gl).roundToInt().coerceAtLeast(1), (gb - gt).roundToInt().coerceAtLeast(1)))
                        layout(c.maxWidth, c.maxHeight) { pl.place(gl.roundToInt(), gt.roundToInt()) }
                    }
                    .goo(fx)
                    .drawBehind {
                        val drag = if (panning) pipDrag.value else Offset.Zero
                        val e = if (panning) (drag.getDistance() / travel) else progress.value.coerceIn(0f, 1f)
                        val at = if (panning) Box4(pipRest.x + drag.x, pipRest.y + drag.y, pipRest.w, pipRest.h) else pipRest.lerpTo(fullVideo, e)
                        val k = 1f - smoothstep(0f, 0.28f, e)
                        val bw = pipRest.w * k
                        val bh = pipRest.h * k
                        // Shrinking about a point 85% down, as the mock's blob does.
                        val bx = pipRest.x + (pipRest.w - bw) / 2f - gl
                        val by = pipRest.y + (pipRest.h - bh) * 0.85f - gt
                        if (k > 0f) drawRoundRect(dockColor, Offset(bx, by), Size(bw, bh), CornerRadius(px(PIP_CORNER) * k))
                        val tr = px(30.dp) * (1f - smoothstep(0.08f, 0.42f, e))
                        val tx = at.x + at.w / 2f - gl
                        val ty = if (panning) at.y + at.h / 2f - gt else at.y + at.h - px(10.dp) - gt
                        if (tr > 0f) drawCircle(dockColor, tr, Offset(tx, ty))
                        // No goo below Android 12: the tail is joined to the drop by a neck instead.
                        if (fx == null && k > 0f && tr > 0f) {
                            val br = minOf(bw, bh) / 2f
                            if (metaballNeck(neck, bx + bw / 2f, by + bh / 2f, br, tx, ty, tr, maxDistance = br + tr + travel * 0.3f)) drawPath(neck, dockColor)
                        }
                    },
            )
        }

        // ---- the picture ----------------------------------------------------------------------
        val pipCorner = px(PIP_CORNER)
        Box(
            Modifier
                .fillMaxSize()
                .layout { m, _ ->
                    // Absorbed where it floats while the new video grows out of its thumbnail.
                    val absorbing = drop?.docked == true && progress.value < 0.999f
                    val docked = if (absorbing) pipRest else pipRest.lerpTo(fullVideo, progress.value)
                    // The liquid spring's carry past either end is drawn (a stretch, a squash), not laid out.
                    val r = docked.lerpTo(screen, fsAnim.value.coerceIn(0f, 1f))
                    val p = m.measure(Constraints.fixed(r.w.roundToInt().coerceAtLeast(1), r.h.roundToInt().coerceAtLeast(1)))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(r.x.roundToInt(), r.y.roundToInt()) }
                }
                .graphicsLayer {
                    val pull = fsPull.value
                    val p = progress.value.coerceIn(0f, 1f)
                    val drag = pipDrag.value * (1f - p)
                    val corner = lerp(pipCorner, 0f, p) + (pull / 6f).coerceAtMost(px(20.dp))
                    val d = drop
                    val fs = fsAnim.value
                    if (liquid && (pull != 0f || fs != 0f || fullscreen) && d?.docked != true) {
                        // Into fullscreen and out, the same drop: it stretches along the way with its
                        // speed, its corners round and its sides swell mid-way, it carries past the
                        // screen going in (over-filling it a touch) and squashes onto the page coming
                        // back. Pulled down out of fullscreen, it bulges at the sides as it gives.
                        val fc = fs.coerceIn(0f, 1f)
                        val over = fs - fc
                        val vel = if (fsAnim.isRunning) fsAnim.velocity else if (fsPull.isRunning) fsPull.velocity / H else fsDragVelocity
                        val st = abs((vel * 0.045f).coerceIn(-0.18f, 0.3f)) * 0.6f
                        val land = (-over * 2.2f).coerceIn(0f, 0.35f)
                        val stretchTop = (over * 0.4f).coerceIn(0f, 0.12f)
                        val kx = 1f - st * 0.45f + land * 0.7f - stretchTop * 0.45f
                        val ky = 1f + st - land + stretchTop
                        scaleX = kx
                        scaleY = ky
                        transformOrigin = TransformOrigin(0.5f, if (land > 0.001f) 0f else 0.5f)
                        val swell = midFlight(fc)
                        val pulled = (pull / H).coerceIn(0f, 1f)
                        val r = (corner + swell * px(22.dp)).coerceAtMost(minOf(size.width * kx, size.height * ky) / 2f)
                        val side = swell * (px(8.dp) + (abs(vel) * 2f).coerceIn(0f, 10f) * density.density) + pulled * px(28.dp)
                        shape = DropletShape(r / kx, r / ky, side = side / kx, ends = swell * px(18.dp) / ky)
                    } else if (liquid && d?.docked != true) {
                        // The mock's renderPlayer: a moving drop elongates along its travel (more
                        // near the dock), a landing one squashes on its bottom, and one that flies
                        // past the top stretches up by the overshoot. Mid-flight its corners swell.
                        val v = if (progress.isRunning) progress.velocity else if (sheetDragging) dragVelocity else 0f
                        val st = abs((v * 0.045f).coerceIn(-0.18f, 0.3f)) * (1f - p * 0.75f)
                        val o = sheet.over.value
                        val atTop = progress.targetValue > 0.5f
                        val land = (if (atTop) 0f else (-o * 2.2f).coerceIn(0f, 0.35f)) + sheet.squash.value
                        val stretchTop = if (atTop) (o * 0.8f).coerceIn(0f, 0.25f) else 0f
                        // The window thrown about stretches along its own way, in the same units.
                        val pv = if (pipDrag.isRunning) pipDrag.velocity else if (panning) Offset(panSpeedX.velocity, panSpeedY.velocity) else Offset.Zero
                        val sx = abs((pv.x / travel * 0.045f).coerceIn(-0.18f, 0.3f)) * (1f - p)
                        val sy = abs((pv.y / travel * 0.045f).coerceIn(-0.18f, 0.3f)) * (1f - p)
                        val kx = 1f - st * 0.45f + land * 0.7f + sx - sy * 0.45f
                        val ky = 1f + st - land + stretchTop + sy - sx * 0.45f
                        scaleX = kx
                        scaleY = ky
                        transformOrigin = TransformOrigin(0.5f, if (land > 0.001f) 0.9f else 0.5f)
                        val r = (lerp(pipCorner, 0f, smoothstep(0.55f, 1f, p)) + midFlight(p) * px(22.dp))
                            .coerceAtMost(minOf(size.width * kx, size.height * ky) / 2f)
                        shape = DropletShape(r / kx, r / ky)
                    } else {
                        shape = RoundedCornerShape(corner)
                    }
                    clip = true
                    alpha = if (hidden) progress.value else 1f
                    translationX = drag.x
                    translationY = pull * 0.6f + drag.y
                    val shrink = 1f - (pull / H).coerceIn(0f, 1f) * 0.2f
                    scaleX *= shrink
                    scaleY *= shrink
                    if (d != null) {
                        if (d.docked) {
                            // The window that was playing shrinks into itself and is gone a third of the way up.
                            val gone = if (progress.value >= 0.999f) 0f else if (d.ghost) smoothstep(0f, 0.3f, p) else 1f
                            alpha *= 1f - gone
                            scaleX *= 1f - 0.35f * gone
                            scaleY *= 1f - 0.35f * gone
                        } else {
                            // Gives way under the drop as it arrives, and is whole again by the time it lands.
                            val give = midFlight(d.flight.value)
                            alpha *= 1f - 0.45f * give
                            scaleX *= 1f - 0.05f * give
                            scaleY *= 1f - 0.05f * give
                        }
                    }
                    // Held where letting go puts it away: it shrinks toward the edge it's past and
                    // dims; liquid, it also flattens against that edge like a drop pressed on glass.
                    val zone = pipZone.value * (1f - p)
                    if (zone > 0.001f) {
                        val k = 1f - 0.14f * zone
                        scaleX *= k
                        scaleY *= k
                        alpha *= 1f - 0.45f * zone
                        val edgeX = if (pipZoneEdge < 0) 0f else W
                        transformOrigin = TransformOrigin(((edgeX - (pipRest.x + drag.x)) / size.width).coerceIn(0f, 1f), 0.5f)
                        if (liquid) {
                            scaleX *= 1f - 0.1f * zone
                            scaleY *= 1f + 0.06f * zone
                            shape = DropletShape(pipCorner / scaleX, pipCorner / scaleY, side = zone * px(6.dp) / scaleX)
                        }
                    }
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
                motion(androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 380f)),
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

        // ---- the drop: the tapped thumbnail on its way into the picture -----------------------
        drop?.let { d ->
            HandoffDrop(
                d,
                target = { if (d.docked) fullVideo else pictureAt() },
                targetCorner = { lerp(px(PIP_CORNER), 0f, progress.value) },
                t = { if (d.docked) progress.value else d.flight.value },
                // In the sheet's units, whichever is moving it: the sheet's own travel or the flight's.
                speed = {
                    if (d.docked) abs(if (progress.isRunning) progress.velocity else dragVelocity)
                    else {
                        val to = pictureAt()
                        val from = d.from ?: to
                        abs(d.flight.velocity) * kotlin.math.hypot(to.x - from.x, to.y - from.y) / travel
                    }
                },
                land = { sheet.squash.value + if (d.docked && progress.targetValue < 0.5f) (-sheet.over.value * 2.2f).coerceIn(0f, 0.35f) else 0f },
                overTop = { if (d.docked) (sheet.over.value * 0.8f).coerceIn(0f, 0.25f) else 0f },
                alpha = { d.shown.value },
                fallback = fullVideo,
            )
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
 * The tapped thumbnail flying into the picture's frame as a drop: laid out once at the size it
 * had on the page, so it shows the very image already loaded there, and flown and shaped by its
 * layer as the mock flies its player out of a thumbnail: it elongates along its way, squashes
 * where it lands, its corners round and its sides swell mid-flight (the sheet's blobRect swell,
 * 8 to 18 dp at the sides and 18 at the ends), and it overshoots the frame on the player spring
 * before settling. Under it, the picture plays from the start.
 */
@Composable
private fun HandoffDrop(
    d: Drop,
    target: () -> Box4,
    targetCorner: () -> Float,
    t: () -> Float,
    speed: () -> Float,
    land: () -> Float,
    overTop: () -> Float,
    alpha: () -> Float,
    fallback: Box4,
) {
    val density = LocalDensity.current
    val base = d.from ?: fallback
    val w = base.w.roundToInt().coerceAtLeast(1)
    val h = base.h.roundToInt().coerceAtLeast(1)
    val swellCorner = with(density) { 22.dp.toPx() }
    val swellSide = with(density) { 8.dp.toPx() }
    val dp1 = density.density
    val swellEnds = with(density) { 18.dp.toPx() }
    val shadow = with(density) { 14.dp.toPx() }
    Box(
        Modifier
            .layout { m, c ->
                val p = m.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
            }
            .graphicsLayer {
                val to = target()
                val f = t()
                val fc = f.coerceIn(0f, 1f)
                val from = d.from ?: to.scaled(0.82f)
                val v = speed()
                val st = abs((v * 0.045f).coerceIn(-0.18f, 0.3f)) * (1f - fc * 0.75f)
                val l = land()
                val kx = 1f - st * 0.45f + l * 0.7f
                val ky = 1f + st - l + overTop()
                // Past 1 the spring's overshoot: the drop over-fills the frame a touch, then settles.
                val at = from.lerpTo(to, f)
                val nw = at.w * kx
                val nh = at.h * ky
                val rx = at.x + (at.w - nw) / 2f
                val ry = at.y + (at.h - nh) * (if (l > 0.001f) 0.9f else 0.5f)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = rx
                translationY = ry
                scaleX = nw / w
                scaleY = nh / h
                val swell = midFlight(fc)
                val corner = (lerp(d.corner, targetCorner(), smoothstep(0.55f, 1f, fc)) + swell * swellCorner).coerceAtMost(minOf(nw, nh) / 2f)
                shape = DropletShape(
                    rx = corner / scaleX,
                    ry = corner / scaleY,
                    side = swell * (swellSide + (v * 2f).coerceIn(0f, 10f) * dp1) / scaleX,
                    ends = swell * swellEnds / scaleY,
                )
                clip = true
                shadowElevation = shadow * swell
                // Blooming in place it fades in as it grows; from a thumbnail it's there from the start.
                this.alpha = alpha() * (if (d.from == null) smoothstep(0f, 0.35f, f) else 1f)
            },
    ) {
        RemoteImage(d.art, Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, border = false)
    }
}

/** What a drag on the picture does, decided as it starts. */
internal enum class PictureDrag {
    /** The floating window, moved anywhere under the finger (and pinched). */
    Pan,
    /** A swipe up on the floating window: the sheet rises under the finger toward the watch page. */
    Rise,
    /** The expanded or fullscreen picture's vertical drag. */
    Sheet,
}

/**
 * Whether a floating window's first movement, past the slop, is the swipe up that opens the
 * watch page rather than a move: one finger, going up, and steeper than about 63° from the
 * horizontal (twice as far up as across). A diagonal throw toward a top corner stays a move.
 */
internal fun isRise(moved: Offset, fingers: Int): Boolean = fingers == 1 && moved.y < 0f && abs(moved.y) > abs(moved.x) * 2f

/**
 * The picture's drag: in [pansFreely] mode (the floating window) any direction past the touch
 * slop, with a second finger pinching (the zoom passed is that frame's, 1 for one finger), unless
 * it starts as a swipe up ([isRise]), which then reports only its vertical movement; otherwise
 * only vertical ones, so a sideways swipe on the expanded picture is left to the controls. The
 * mode is fixed for the whole drag when it starts.
 */
private suspend fun PointerInputScope.detectPictureDrag(
    screen: ScreenPosition,
    pansFreely: () -> Boolean,
    onStart: (mode: PictureDrag) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onDrag: (uptimeMillis: Long, delta: Offset, zoom: Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (pansFreely()) {
        // The fingers' centroid moves the window and their spread sizes it. Nothing is consumed
        // until past the slop, so a tap still reaches the window's buttons.
        var started = false
        var rise = false
        var moved = Offset.Zero
        var spread = 1f
        // Each finger's last place on the screen: the window moves under them as they drag it.
        val last = HashMap<androidx.compose.ui.input.pointer.PointerId, Offset>()
        last[down.id] = screen.of(down.position)
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (!started && event.changes.any { it.isConsumed }) return@awaitEachGesture
            var sum = Offset.Zero
            var n = 0
            event.changes.forEach { c ->
                val at = screen.of(c.position)
                val before = last[c.id]
                if (c.pressed) {
                    if (before != null) { sum += at - before; n++ }
                    last[c.id] = at
                } else last.remove(c.id)
            }
            val pan = if (n > 0) sum / n.toFloat() else Offset.Zero
            val zoom = if (pressed >= 2) event.calculateZoom() else 1f
            if (!started) {
                moved += pan
                spread *= zoom
                if (moved.getDistance() < viewConfiguration.touchSlop && kotlin.math.abs(1f - spread) < 0.04f) continue
                started = true
                rise = isRise(moved, pressed)
                onStart(if (rise) PictureDrag.Rise else PictureDrag.Pan)
                if (rise) onDrag(event.changes.first().uptimeMillis, Offset(0f, moved.y), 1f)
                else onDrag(event.changes.first().uptimeMillis, moved, spread)
            } else if (rise) {
                onDrag(event.changes.first().uptimeMillis, Offset(0f, pan.y), 1f)
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
    onStart(PictureDrag.Sheet)
    onDrag(start.uptimeMillis, Offset(0f, slop), 1f)
    var lastY = screen.of(start.position).y
    while (true) {
        val event = awaitPointerEvent()
        // A second finger makes it a pinch (fill or fit in fullscreen), not this drag: it lets go
        // as a cancel, which springs back rather than committing.
        if (event.changes.any { it.id != start.id && it.pressed }) { onCancel(); return@awaitEachGesture }
        val change = event.changes.firstOrNull { it.id == start.id }
        if (change == null || change.isConsumed) { onCancel(); return@awaitEachGesture }
        if (!change.pressed) { change.consume(); break }
        val y = screen.of(change.position).y
        change.consume()
        if (y != lastY) onDrag(change.uptimeMillis, Offset(0f, y - lastY), 1f)
        lastY = y
    }
    onEnd()
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
                // Still a 44dp target that pauses, where the pause button was.
                Box(
                    nudge
                        .size(44.dp)
                        .clearAndSetSemantics {
                            contentDescription = "Loading. Pause"
                            role = Role.Button
                            onClick { connection.togglePlay(); true }
                        }
                        .pressClickable(haptic = Haptic.Pause) { connection.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
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
    val press by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f else 0f, motion(androidx.compose.animation.core.spring(stiffness = 900f)), label = "pipPress")
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

