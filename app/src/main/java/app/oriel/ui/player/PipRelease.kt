package app.oriel.ui.player

import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A throw this fast (per second), mostly sideways and toward the side the window is already on,
 * or mostly down from the bottom half onto the tab bar, puts it away. Slower, or toward the far
 * side, it's a move: it lands in a corner.
 */
internal val PIP_FLING_AWAY = 1400.dp

/** The share of the window that has to be past a side edge (or down over the tab bar) for letting go there to put it away. */
internal const val PIP_AWAY_OVER = 0.5f

internal enum class PipRelease { Land, AwayLeft, AwayRight, AwayDown }

/** Down: the put-away zone over the tab bar, as [pipZoneAt] reports it. */
internal const val PIP_ZONE_DOWN = 2

/**
 * Which put-away zone a window at ([x], [y]), [w] by [h], is in on a screen [screenW] wide whose
 * tab bar starts at [bottomEdge]: -1 left, 1 right, [PIP_ZONE_DOWN] down, 0 none. Past two at
 * once, the one it's further into.
 */
internal fun pipZoneAt(x: Float, y: Float, w: Float, h: Float, screenW: Float, bottomEdge: Float): Int {
    val left = -x / w
    val right = (x + w - screenW) / w
    val down = (y + h - bottomEdge) / h
    val most = maxOf(left, right, down)
    return when {
        most <= PIP_AWAY_OVER -> 0
        most == down -> PIP_ZONE_DOWN
        most == left -> -1
        else -> 1
    }
}

/**
 * Put away only on clear intent: released past a zone (unless thrown back in), or thrown at
 * [flingPx] toward the side it's on, or down from the bottom half. Otherwise it lands in the
 * corner the throw carries it to. A pinch never puts it away.
 */
internal fun pipRelease(
    x: Float, y: Float, w: Float, h: Float, screenW: Float, screenH: Float, bottomEdge: Float,
    vx: Float, vy: Float, flingPx: Float, pinched: Boolean,
): PipRelease {
    if (pinched) return PipRelease.Land
    when (pipZoneAt(x, y, w, h, screenW, bottomEdge)) {
        -1 -> if (vx < flingPx) return PipRelease.AwayLeft
        1 -> if (vx > -flingPx) return PipRelease.AwayRight
        PIP_ZONE_DOWN -> if (vy > -flingPx) return PipRelease.AwayDown
    }
    val sideways = abs(vx) > abs(vy) * 1.5f
    val downward = vy > abs(vx) * 1.5f
    val centre = x + w / 2f
    return when {
        sideways && vx <= -flingPx && centre < screenW / 2f -> PipRelease.AwayLeft
        sideways && vx >= flingPx && centre > screenW / 2f -> PipRelease.AwayRight
        downward && vy >= flingPx && y + h / 2f > screenH / 2f -> PipRelease.AwayDown
        else -> PipRelease.Land
    }
}
