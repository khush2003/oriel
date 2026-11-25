package app.oriel.ui.player

import app.oriel.ui.theme.DropletShape
import app.oriel.ui.theme.midFlight
import app.oriel.ui.theme.smoothstep
import androidx.compose.ui.graphics.TransformOrigin
import app.oriel.ui.components.RemoteImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.roundToInt

/** A rectangle in pixels. */
internal data class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
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
internal class Drop(val from: Box4?, val corner: Float, val art: String?, val docked: Boolean, val ghost: Boolean) {
    /** Its own flight into a page already open, 0 to 1 (a docked drop rides the sheet's progress instead). */
    val flight = androidx.compose.animation.core.Animatable(0f)
    /** Shown, then faded as the picture takes over under it. */
    val shown = androidx.compose.animation.core.Animatable(1f)
}

/**
 * The tapped thumbnail flying into the picture's frame. Laid out once at its page size so it
 * reuses the loaded image; all motion and shape are in the layer.
 */
@Composable
internal fun HandoffDrop(
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
