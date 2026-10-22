package app.oriel.ui.theme

import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/*
 * The liquid motion's shared pieces (design/liquid/ is the mock they come from): springs for a
 * drop's landing and flight, the outlines a drop and a pushed page's soft edge are clipped to,
 * and the arithmetic that turns speed into stretch. Everything here is meant to be read in the
 * draw or layout phase (graphicsLayer, layout lambdas), never to drive recomposition. The
 * amplitudes are small on purpose: a few percent, a dozen or so dp.
 */

/**
 * A drop's landing: underdamped, so the squash rebounds once into a slight stretch and is still
 * in about half a second. New, because every spring in Motion.kt is (nearly) critically damped.
 */
val SquashSpring = spring<Float>(dampingRatio = 0.42f, stiffness = 520f, visibilityThreshold = 0.0005f)

/**
 * A thumbnail's flight into the player: a touch of overshoot, so the drop over-fills the frame
 * by a percent or two and settles back, where PlayerSpring would stop dead. New for that reason.
 */
val DropletSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 300f, visibilityThreshold = 0.0005f)

/** How quickly a pushed page's edge gives under the finger: critically damped and quick, as a press is. */
val EdgeGiveSpring = spring<Float>(dampingRatio = 1f, stiffness = 900f, visibilityThreshold = 0.001f)

/** 0 below [from], 1 above [to], smooth between. */
fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 0 at both ends of a 0-to-1 flight, 1 at its middle: how much a drop swells on its way. */
fun midFlight(f: Float): Float = sin(PI.toFloat() * f.coerceIn(0f, 1f))

/**
 * Stretch along the direction of travel for a speed in units per second, limited to ±[max] (a
 * fraction of the length): gentle for a slow drag, never more than a few percent for a flick.
 */
fun liquidStretch(velocity: Float, perUnit: Float = 0.25f, max: Float = 0.05f): Float = max * tanh(velocity * perUnit)

/**
 * Stretches the layer by [s] (a fraction; negative squashes) along y, giving back half of it
 * across so it reads as the same drop rather than a zoom, anchored at its top or bottom edge:
 * the leading edge holds and the tail stretches, and a landing squashes against what it lands on.
 */
fun GraphicsLayerScope.stretchY(s: Float, anchorTop: Boolean) {
    if (s == 0f) return
    scaleY *= 1f + s
    scaleX *= 1f - s * 0.5f
    transformOrigin = TransformOrigin(0.5f, if (anchorTop) 0f else 1f)
}

/**
 * Finite-difference speed of a value fed as it changes, smoothed over a few samples: cheap
 * enough to run on every move of a drag, where a VelocityTracker's fit would be wasted on a
 * value that only shapes a drawing.
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

/**
 * A rounded rect whose sides swell like a drop's: the corners are drawn in by [side] (left and
 * right) and [ends] (top and bottom) and each side bows back out to the frame's edge at its
 * middle, so the outline stays inside the frame, where there's picture to show. Radii are
 * separate in x and y so a layer scaled unevenly can pass radius / scale and still look round.
 * With nothing swelling it is a plain rounded rect, which clips in hardware.
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

private const val KAPPA = 0.5523f

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
 * A page's soft leading (left) edge. [depth] > 0 bulges it forward: the edge's ends are drawn in
 * by [depth] and its middle, around [focusY], stays at the frame's edge. [depth] < 0 dents it in
 * by that much around [focusY], where a finger holds it. [spread] is the bump's half-width.
 * Flat, it's a rectangle (no clip worth the name).
 */
@Immutable
class LiquidEdgeShape(val depth: Float, val focusY: Float, val spread: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (abs(depth) < 0.5f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
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

private const val EDGE_STEPS = 40

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
 * Where a video was tapped from: the thumbnail's bounds in the window, its corner radius in px,
 * and the image it was showing, for the player to grow out of it as a drop.
 */
@Immutable
data class VideoOrigin(val bounds: androidx.compose.ui.geometry.Rect, val corner: Float, val art: String?)

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

    val modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier.onPlaced { coordinates = it }

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
