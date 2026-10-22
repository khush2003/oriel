package app.oriel.ui.theme

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whether the liquid motion is on: the "Liquid motion" setting (off by default), and never under
 * Reduce motion. Off, every interaction behaves as it did before liquid motion existed. Liquid
 * glass is a separate setting.
 */
val LocalLiquidMotion = staticCompositionLocalOf { true }
import kotlin.math.tanh

/*
 * The liquid motion's shared pieces, ported from the mock in design/liquid/index.html: its spring
 * table, its shapes (the necking capsule, the swelling rect, the page's soft edge, the metaball
 * neck), the two-edge pill, the rubber band and the goo layer. The numbers are the mock's, on
 * purpose: parity with it is the spec, so don't soften them here. Everything is meant to be read
 * in the draw or layout phase (graphicsLayer, drawBehind, layout lambdas), never to recompose.
 */

/**
 * One of the mock's springs, described as SwiftUI and the mock's `cfg(response, damping)` do:
 * [response] is the undamped period in seconds and [damping] the ratio. In Compose terms that's
 * stiffness (2π / response)² at unit mass, the same oscillator.
 */
@Immutable
class LiquidSpring(val response: Float, val damping: Float) {
    val stiffness: Float = (2f * PI.toFloat() / response).let { it * it }

    /** For a 0-to-1 value (progress, scale). */
    val unit: SpringSpec<Float> = spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = 0.0005f)

    /** For a value in pixels. */
    val px: SpringSpec<Float> = spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = 0.1f)

    fun <T> spec(threshold: T? = null): SpringSpec<T> = spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = threshold)
}

/** The mock's `SP` table, number for number. */
object Sp {
    val Snappy = LiquidSpring(0.3f, 0.82f)
    val Press = LiquidSpring(0.2f, 0.8f)
    val Bouncy = LiquidSpring(0.42f, 0.6f)
    val Jelly = LiquidSpring(0.48f, 0.3f)
    val Lead = LiquidSpring(0.26f, 0.6f)
    val Trail = LiquidSpring(0.46f, 0.75f)
    val Soft = LiquidSpring(0.5f, 0.92f)
    val Page = LiquidSpring(0.42f, 0.86f)
    val Fade = LiquidSpring(0.26f, 1f)
    val FadeOut = LiquidSpring(0.35f, 1f)
    val TabFade = LiquidSpring(0.32f, 1f)
    val SheetH = LiquidSpring(0.44f, 0.68f)
    val SheetW = LiquidSpring(0.58f, 0.74f)
    val Sheet = LiquidSpring(0.4f, 0.86f)
    val SheetClose = LiquidSpring(0.38f, 0.92f)
    val Player = LiquidSpring(0.5f, 0.72f)
    val Push = LiquidSpring(0.5f, 0.88f)
    val FastEdge = LiquidSpring(0.3f, 0.68f)
    val SlowEdge = LiquidSpring(0.6f, 0.82f)
    val Lift = LiquidSpring(0.4f, 0.55f)
    val LiftClose = LiquidSpring(0.4f, 0.9f)
    val MenuX = LiquidSpring(0.52f, 0.66f)
    val MenuY = LiquidSpring(0.4f, 0.62f)
    val MenuClose = LiquidSpring(0.3f, 0.95f)
    /** The like splash's burst out and its fall back (the mock's inline cfg(.34, .55) and cfg(.42, .9)). */
    val Splash = LiquidSpring(0.34f, 0.55f)
    val SplashBack = LiquidSpring(0.42f, 0.9f)
}

/** 0 below [from], 1 above [to], smooth between. */
fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 0 at both ends of a 0-to-1 flight, 1 at its middle: how much a drop swells on its way. */
fun midFlight(f: Float): Float = sin(PI.toFloat() * f.coerceIn(0f, 1f))

/** iOS's rubber band, as the mock's `rubber`: the further past, the less it gives, never beyond [dim]. */
fun rubber(x: Float, dim: Float, c: Float = 0.55f): Float = if (x <= 0f || dim <= 0f) 0f else (1f - 1f / (x * c / dim + 1f)) * dim

/** Where a fling at [velocity] (units per second) would come to rest, as UIScrollView projects it. */
fun project(velocity: Float, rate: Float = 0.998f): Float = velocity / 1000f * rate / (1f - rate)

/**
 * Stretch along the direction of travel for a speed in units per second, limited to ±[max] (a
 * fraction of the length).
 */
fun liquidStretch(velocity: Float, perUnit: Float = 0.25f, max: Float = 0.11f): Float = max * tanh(velocity * perUnit)

/**
 * Stretches the layer by [s] (a fraction; negative squashes) along y, giving back half of it
 * across so it reads as the same drop rather than a zoom, anchored at its top or bottom edge.
 */
fun GraphicsLayerScope.stretchY(s: Float, anchorTop: Boolean) {
    if (s == 0f) return
    scaleY *= 1f + s
    scaleX *= 1f - s * 0.5f
    transformOrigin = TransformOrigin(0.5f, if (anchorTop) 0f else 1f)
}

/**
 * Finite-difference speed of a value fed as it changes, smoothed over a few samples: cheap
 * enough to run on every move of a drag or every frame of a slide.
 */
class SmoothedVelocity {
    private var lastT = -1L
    private var lastV = 0f
    var velocity = 0f
        private set

    fun add(timeMillis: Long, value: Float) {
        if (lastT >= 0 && timeMillis > lastT) {
            val v = (value - lastV) * 1000f / (timeMillis - lastT)
            velocity += (v - velocity) * 0.35f
        }
        lastT = timeMillis
        lastV = value
    }

    fun reset() {
        lastT = -1L
        velocity = 0f
    }
}

// ---- shapes ------------------------------------------------------------------------------------

private const val KAPPA = 0.5523f

/**
 * The mock's `capsule`: a capsule from [l] to [r] about [cy], [h] tall, whose long edges pinch in
 * by [neck] at the middle, so a stretched one reads as liquid rather than a box. Into [path], reset.
 */
fun capsulePath(path: Path, l0: Float, r0: Float, cy: Float, h: Float, neck: Float = 0f) {
    path.reset()
    val l = min(l0, r0)
    val r = max(l0, r0)
    val w = r - l
    if (w <= 0f || h <= 0f) return
    val rx = min(h / 2f, w / 2f)
    val ry = h / 2f
    val t = cy - ry
    val b = cy + ry
    val m = (l + r) / 2f
    val n = min(neck, ry * 0.6f).coerceAtLeast(0f)
    path.moveTo(l + rx, t)
    path.quadraticTo(m, t + n * 2f, r - rx, t)
    path.arcTo(Rect(r - 2f * rx, t, r, b), -90f, 180f, false)
    path.quadraticTo(m, b - n * 2f, l + rx, b)
    path.arcTo(Rect(l, t, l + 2f * rx, b), 90f, 180f, false)
    path.close()
}

/**
 * The mock's `blobRect`: a rounded rect whose sides swell outward, [bs] at the left and right and
 * [bt] at the top (60% of it at the bottom), as a drop does mid-growth; the bottom corners can
 * take their own radius. Into [path], reset.
 */
fun blobRectPath(path: Path, l: Float, t: Float, r: Float, b: Float, radIn: Float, bs: Float = 0f, bt: Float = 0f, radBottomIn: Float = radIn) {
    path.reset()
    if (r - l <= 0f || b - t <= 0f) return
    val rad = radIn.coerceIn(0f, min(r - l, b - t) / 2f)
    val rb = radBottomIn.coerceIn(0f, min(r - l, b - t) / 2f)
    val kr = rad * KAPPA
    val kb = rb * KAPPA
    val mx = (l + r) / 2f
    val my = (t + b) / 2f
    path.moveTo(l + rad, t)
    path.quadraticTo(mx, t - 2f * bt, r - rad, t)
    path.cubicTo(r - rad + kr, t, r, t + rad - kr, r, t + rad)
    path.quadraticTo(r + 2f * bs, my, r, b - rb)
    path.cubicTo(r, b - rb + kb, r - rb + kb, b, r - rb, b)
    path.quadraticTo(mx, b + bt * 0.6f, l + rb, b)
    path.cubicTo(l + rb - kb, b, l, b - rb + kb, l, b - rb)
    path.quadraticTo(l - 2f * bs, my, l, t + rad)
    path.cubicTo(l, t + rad - kr, l + rad - kr, t, l + rad, t)
    path.close()
}

/** A [Shape] drawn by a path builder that's read in the draw phase: the outline is rebuilt each time it's asked for. */
class PathShape(private val build: (Path, Size) -> Unit) : Shape {
    private val path = Path()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        build(path, size)
        // A copy: the outline outlives this call (the layer keeps it), the scratch path doesn't.
        return Outline.Generic(Path().apply { addPath(path) })
    }
}

/**
 * A rounded rect whose sides swell like a drop's: the corners are drawn in by [side] (left and
 * right) and [ends] (top and bottom) and each side bows back out to the frame's edge at its
 * middle, so the outline stays inside the frame. Radii are separate in x and y so a layer scaled
 * unevenly can pass radius / scale and still look round. Unswollen it's a plain rounded rect.
 */
@Immutable
class DropletShape(val rx: Float, val ry: Float = rx, val side: Float = 0f, val ends: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (side < 0.5f && ends < 0.5f) {
            return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(rx.coerceAtLeast(0f), ry.coerceAtLeast(0f))))
        }
        return Outline.Generic(Path().apply { droplet(size.width, size.height, rx, ry, side, ends) })
    }

    override fun equals(other: Any?) = other is DropletShape && other.rx == rx && other.ry == ry && other.side == side && other.ends == ends
    override fun hashCode() = ((rx.hashCode() * 31 + ry.hashCode()) * 31 + side.hashCode()) * 31 + ends.hashCode()
}

private fun Path.droplet(w: Float, h: Float, rxIn: Float, ryIn: Float, side: Float, ends: Float) {
    val s = side.coerceIn(0f, w / 4f)
    val e = ends.coerceIn(0f, h / 4f)
    val l = s
    val r = w - s
    val t = e
    val b = h - e
    val rx = rxIn.coerceIn(0f, (r - l) / 2f)
    val ry = ryIn.coerceIn(0f, (b - t) / 2f)
    val kx = rx * KAPPA
    val ky = ry * KAPPA
    val mx = w / 2f
    val my = h / 2f
    // A quadratic's midpoint sits halfway to its control: 2× the inset reaches the frame's edge.
    moveTo(l + rx, t)
    quadraticTo(mx, t - 2f * e, r - rx, t)
    cubicTo(r - rx + kx, t, r, t + ry - ky, r, t + ry)
    quadraticTo(r + 2f * s, my, r, b - ry)
    cubicTo(r, b - ry + ky, r - rx + kx, b, r - rx, b)
    quadraticTo(mx, b + 2f * e, l + rx, b)
    cubicTo(l + rx - kx, b, l, b - ry + ky, l, b - ry)
    quadraticTo(l - 2f * s, my, l, t + ry)
    cubicTo(l, t + ry - ky, l + rx - kx, t, l + rx, t)
    close()
}

/**
 * The mock's `edgePath`: a page's leading (left) edge. [depth] > 0 bulges it out in front around
 * [focusY] (the edge's ends are drawn in by [depth]); [depth] < 0 dents it in by that much around
 * [focusY], where a finger holds it. [spread] is the Gaussian's s (150 in the mock, in dp).
 */
@Immutable
class LiquidEdgeShape(val depth: Float, val focusY: Float, val spread: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (abs(depth) < 0.5f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        return Outline.Generic(Path().apply { liquidEdge(size.width, size.height, depth, focusY, spread) })
    }

    override fun equals(other: Any?) = other is LiquidEdgeShape && other.depth == depth && other.focusY == focusY && other.spread == spread
    override fun hashCode() = (depth.hashCode() * 31 + focusY.hashCode()) * 31 + spread.hashCode()
}

/** Where the soft edge lies at [y], as [LiquidEdgeShape] draws it. */
fun liquidEdgeX(y: Float, depth: Float, focusY: Float, spread: Float): Float {
    val d = (y - focusY) / spread
    val g = exp(-d * d)
    return if (depth >= 0f) depth * (1f - g) else -depth * g
}

/** The mock's 36 steps down the edge. */
private const val EDGE_STEPS = 36

/** The soft edge as an open curve from top to bottom, for the shadow along it. */
fun Path.liquidEdgeCurve(h: Float, depth: Float, focusY: Float, spread: Float) {
    for (i in 0..EDGE_STEPS) {
        val y = h * i / EDGE_STEPS
        val x = liquidEdgeX(y, depth, focusY, spread)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
}

private fun Path.liquidEdge(w: Float, h: Float, depth: Float, focusY: Float, spread: Float) {
    liquidEdgeCurve(h, depth, focusY, spread)
    lineTo(w, h)
    lineTo(w, 0f)
    close()
}

/**
 * The neck between two circles as a metaball draws it (Hiroyuki Sato's construction): the region
 * outside both, bounded by two curves that leave each circle's rim and meet the other's. Nothing
 * when they're further apart than [maxDistance] or one holds the other. [spread] (the mock-like
 * v) widens where the neck leaves the rims. For drawing a fused join where a blur-and-threshold
 * can't run: on glass, and below Android 12. Into [path], reset; false when there's no neck.
 */
fun metaballNeck(path: Path, x1: Float, y1: Float, r1: Float, x2: Float, y2: Float, r2: Float, maxDistance: Float, spread: Float = 0.5f, handleRate: Float = 2.4f): Boolean {
    path.reset()
    val d = hypot(x2 - x1, y2 - y1)
    if (r1 <= 0f || r2 <= 0f || d > maxDistance || d <= abs(r1 - r2) || d < 0.01f) return false
    val u1: Float
    val u2: Float
    if (d < r1 + r2) {
        u1 = acos(((r1 * r1 + d * d - r2 * r2) / (2f * r1 * d)).coerceIn(-1f, 1f))
        u2 = acos(((r2 * r2 + d * d - r1 * r1) / (2f * r2 * d)).coerceIn(-1f, 1f))
    } else {
        u1 = 0f
        u2 = 0f
    }
    val a = atan2(y2 - y1, x2 - x1)
    val a2 = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
    val pi = PI.toFloat()
    val a1a = a + u1 + (a2 - u1) * spread
    val a1b = a - u1 - (a2 - u1) * spread
    val a2a = a + pi - u2 - (pi - u2 - a2) * spread
    val a2b = a - pi + u2 + (pi - u2 - a2) * spread
    val p1ax = x1 + cos(a1a) * r1
    val p1ay = y1 + sin(a1a) * r1
    val p1bx = x1 + cos(a1b) * r1
    val p1by = y1 + sin(a1b) * r1
    val p2ax = x2 + cos(a2a) * r2
    val p2ay = y2 + sin(a2a) * r2
    val p2bx = x2 + cos(a2b) * r2
    val p2by = y2 + sin(a2b) * r2
    var d2 = min(spread * handleRate, hypot(p1ax - p2ax, p1ay - p2ay) / (r1 + r2))
    d2 *= min(1f, d * 2f / (r1 + r2))
    val h1 = r1 * d2
    val h2 = r2 * d2
    val hp = pi / 2f
    path.moveTo(p1ax, p1ay)
    path.cubicTo(p1ax + cos(a1a - hp) * h1, p1ay + sin(a1a - hp) * h1, p2ax + cos(a2a + hp) * h2, p2ay + sin(a2a + hp) * h2, p2ax, p2ay)
    // Around the second circle's near side rather than across it, so the neck never covers it.
    path.arcTo(Rect(x2 - r2, y2 - r2, x2 + r2, y2 + r2), deg(a2a), sweepToward(deg(a2a), deg(a2b), deg(a + pi)), false)
    path.cubicTo(p2bx + cos(a2b - hp) * h2, p2by + sin(a2b - hp) * h2, p1bx + cos(a1b + hp) * h1, p1by + sin(a1b + hp) * h1, p1bx, p1by)
    path.arcTo(Rect(x1 - r1, y1 - r1, x1 + r1, y1 + r1), deg(a1b), sweepToward(deg(a1b), deg(a1a), deg(a)), false)
    path.close()
    return true
}

private fun deg(rad: Float) = rad * 180f / PI.toFloat()

/** The sweep from [from] to [to] (degrees) that passes through [via]. */
private fun sweepToward(from: Float, to: Float, via: Float): Float {
    fun norm(x: Float) = ((x % 360f) + 360f) % 360f
    val ccw = norm(to - from)
    return if (norm(via - from) <= ccw) ccw else ccw - 360f
}

// ---- the two-edge pill ------------------------------------------------------------------------

/**
 * The mock's `Pill`: a blob whose two edges ride separate springs. The leading edge (toward the
 * target) is stiff and the trailing one soft, so it stretches toward where it's going, necks in
 * the middle ([neckK] of the stretch) and squashes in height ([squash] of the stretch), then
 * catches up with overshoot. Positions in px; [path] builds the outline in the draw phase.
 */
class LiquidPill(left: Float, right: Float, height: Float, private val neckK: Float = 0.16f, private val squash: Float = 0.1f) {
    val l = Animatable(left)
    val r = Animatable(right)
    val h = Animatable(height)
    var rest = right - left
        private set

    fun moveTo(scope: CoroutineScope, left: Float, right: Float, velocity: Float? = null, reduce: Boolean = false) {
        rest = right - left
        if (reduce) {
            scope.launch { l.snapTo(left) }
            scope.launch { r.snapTo(right) }
            return
        }
        val goingRight = (left + right) > (l.value + r.value)
        scope.launch { l.animateTo(left, (if (goingRight) Sp.Trail else Sp.Lead).px, velocity ?: l.velocity) }
        scope.launch { r.animateTo(right, (if (goingRight) Sp.Lead else Sp.Trail).px, velocity ?: r.velocity) }
    }

    fun setNow(scope: CoroutineScope, left: Float, right: Float) {
        rest = right - left
        scope.launch { l.snapTo(left) }
        scope.launch { r.snapTo(right) }
    }

    fun heightTo(scope: CoroutineScope, height: Float, reduce: Boolean = false) {
        scope.launch { if (reduce) h.snapTo(height) else h.animateTo(height, Sp.Bouncy.px) }
    }

    /** The outline about [cy], mirrored about [mirrorWidth] when it's set (right to left). */
    fun path(path: Path, cy: Float, reduce: Boolean = false, mirrorWidth: Float = Float.NaN) {
        var left = l.value
        var right = r.value
        if (!mirrorWidth.isNaN()) {
            val a = mirrorWidth - right
            right = mirrorWidth - left
            left = a
        }
        val w = abs(right - left)
        val stretch = max(0f, w - rest)
        val height = max(h.value - stretch * squash, h.value * 0.62f)
        val neck = if (reduce) 0f else min(stretch * neckK, height * 0.3f)
        capsulePath(path, left, right, cy, height, neck)
    }
}

// ---- metaballs ---------------------------------------------------------------------------------

/**
 * The mock's goo filter: a Gaussian blur of standard deviation [blurPx] and then an alpha
 * threshold (alpha × [gain] − [cut]), so shapes closer than about the blur fuse with a smooth
 * neck, as metaballs do. A RenderEffect, Android 12 and up; null below, where callers draw a
 * [metaballNeck] instead. The mock's values are 6 px, 24 and 11 (the small one: 4, 20, 8).
 */
fun gooEffect(blurPx: Float, gain: Float = 24f, cut: Float = 11f): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || blurPx <= 0f) return null
    return runCatching {
        // Android's blur takes a radius, which it turns into sigma = 0.57735 r + 0.5; the mock's is a sigma.
        val radius = ((blurPx - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
        val blur = android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.DECAL)
        // ColorMatrix offsets are on the 0-255 scale.
        val threshold = android.graphics.ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, gain, -cut * 255f,
            ),
        )
        android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(threshold), blur).asComposeRenderEffect()
    }.getOrNull()
}

/** Whether [gooEffect] can fuse shapes here, or the shapes have to draw their own necks. */
val gooSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** A layer whose drawing is fused by [effect] (from [gooEffect]); nothing when it's null. Keep such layers small. */
fun Modifier.goo(effect: RenderEffect?): Modifier = if (effect == null) this else graphicsLayer { renderEffect = effect }

// ---- origins -------------------------------------------------------------------------------------

/**
 * Where a video was tapped from: the thumbnail's bounds in the window, its corner radius in px,
 * and the image it was showing, for the player to grow out of it as a drop.
 */
@Immutable
data class VideoOrigin(val bounds: Rect, val corner: Float, val art: String?)

/**
 * A thumbnail's place on screen, kept as it's laid out ([OriginCapture.modifier]) and read only
 * when it's tapped. Cards record it just before they open their video ([OriginCapture.record]),
 * and AppState takes it ([TapOrigin.take]), so a row whose click is someone else's lambda
 * (a playlist's, History's) still hands its thumbnail over without the lambda knowing.
 */
class OriginCapture {
    private var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
    var corner = 0f
    var art: String? = null

    val modifier: Modifier = Modifier.onPlaced { coordinates = it }

    fun current(): VideoOrigin? {
        val c = coordinates?.takeIf { it.isAttached } ?: return null
        val r = c.boundsInWindow()
        return if (r.width > 1f && r.height > 1f) VideoOrigin(r, corner, art) else null
    }

    fun record() = TapOrigin.record(current())
}

object TapOrigin {
    private var origin: VideoOrigin? = null
    private var recordedAt = 0L

    fun record(o: VideoOrigin?) {
        origin = o
        recordedAt = System.nanoTime()
    }

    /** The origin recorded by the tap that's being handled now, once. */
    fun take(): VideoOrigin? {
        val o = origin?.takeIf { System.nanoTime() - recordedAt < 500_000_000L }
        origin = null
        return o
    }
}
