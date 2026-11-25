package app.oriel.ui.player

import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

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
internal suspend fun PointerInputScope.detectPictureDrag(
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

/** Reports the running zoom of a two-finger pinch, consuming only while two or more fingers are down. */
internal suspend fun PointerInputScope.detectPinch(onZoom: (Float) -> Unit) = awaitEachGesture {
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
