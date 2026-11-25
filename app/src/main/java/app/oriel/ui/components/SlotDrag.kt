/*
 * Floating chrome adapted from BitChord (GPL-3.0): FloatingBottomBar.kt,
 * GlassNavBar.kt (standalone Search circle), FrostedTopBar.kt (glass circles,
 * topBarContentPadding) and BottomFadeScrim.kt.
 */
package app.oriel.ui.components

import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.ui.haptics.Haptic
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import app.oriel.ui.theme.LiquidPill
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.project
import app.oriel.ui.theme.rubber
import kotlin.math.roundToInt

/**
 * The mock's `slotDrag`: past the slop the blob follows the finger, rubber-banding at the ends,
 * and lands where the fling projects. [throwV] hands [select] the release speed. Slot i spans
 * [i × step, i × step + width].
 */
internal suspend fun PointerInputScope.slotDrag(
    pill: LiquidPill,
    scope: kotlinx.coroutines.CoroutineScope,
    sign: Float,
    pressGrow: Float,
    step: () -> Float,
    width: () -> Float,
    rest: () -> Int,
    shown: () -> Int,
    reduce: () -> Boolean,
    n: Int,
    haptics: app.oriel.ui.haptics.Haptics,
    throwV: FloatArray,
    select: (Int) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val rowH = size.height.toFloat()
    pill.heightTo(scope, rowH + pressGrow, reduce())
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var dragging = false
    var moved = Offset.Zero
    var last = down
    var lastSlot = shown()
    // Reading order: right to left, x runs from the right edge.
    fun local(x: Float) = if (sign < 0f) size.width - x else x
    fun pick(c: Float): Int {
        val s = step()
        return if (s <= 0f) rest() else ((c - width() / 2f) / s).roundToInt().coerceIn(0, n - 1)
    }
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) { last = change; break }
        moved += change.positionChange()
        tracker.addPosition(change.uptimeMillis, change.position)
        if (!dragging && abs(moved.x) > viewConfiguration.touchSlop && abs(moved.x) > abs(moved.y) * 1.2f) dragging = true
        if (dragging) {
            change.consume()
            val s = step()
            val w = width()
            if (s > 0f) {
                val minC = w / 2f
                val maxC = (n - 1) * s + w / 2f
                var c = local(change.position.x)
                val band = 50.dp.toPx()
                c = when {
                    c < minC -> minC - rubber(minC - c, band)
                    c > maxC -> maxC + rubber(c - maxC, band)
                    else -> c
                }
                pill.moveTo(scope, c - w / 2f, c + w / 2f, reduce = reduce())
                val slot = pick(c)
                if (slot != lastSlot) { haptics.play(Haptic.Tick); lastSlot = slot }
            }
        }
        last = change
    }
    pill.heightTo(scope, rowH, reduce())
    if (dragging) {
        val v = tracker.calculateVelocity().x * sign
        val target = pick(local(last.position.x) + project(v, 0.99f) * 0.35f)
        throwV[0] = v
        if (target != shown()) select(target)
        else {
            val l = target * step()
            pill.moveTo(scope, l, l + width(), v, reduce())
            throwV[0] = 0f
        }
    }
}

/**
 * The mock's `searchDrag`: dragged far or fast enough toward the bar it opens Search; otherwise
 * wobbles back. Taps are the clickable's.
 */
internal suspend fun PointerInputScope.searchDrag(
    reachIn: Animatable<Float, *>,
    reachOut: Animatable<Float, *>,
    scope: kotlinx.coroutines.CoroutineScope,
    give: Float,
    sign: Float,
    reduce: () -> Boolean,
    stretch: Dp = 200.dp,
    onOpen: (velocity: Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (reduce()) return@awaitEachGesture
    scope.launch { reachIn.animateTo(-give, Sp.Press.px) }
    scope.launch { reachOut.animateTo(-give, Sp.Press.px) }
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var dragging = false
    var dx = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        val d = change.positionChange()
        dx += d.x
        tracker.addPosition(change.uptimeMillis, change.position)
        if (!dragging && abs(dx) > viewConfiguration.touchSlop && abs(d.x) >= abs(d.y)) dragging = true
        if (dragging) {
            change.consume()
            // Toward the bar is positive.
            val toward = -dx * sign
            val inner = if (toward > 0f) rubber(toward, stretch.toPx(), 0.8f) else -rubber(-toward, 50.dp.toPx()) * 0.4f
            val outer = if (toward < 0f) rubber(-toward, 40.dp.toPx()) * 0.4f else 0f
            scope.launch { reachIn.animateTo(inner, Sp.Lead.px) }
            scope.launch { reachOut.animateTo(outer, Sp.Lead.px) }
        }
    }
    val v = -tracker.calculateVelocity().x * sign
    if (dragging && (-dx * sign > 50.dp.toPx() || v > 600f)) onOpen(v.coerceAtLeast(0f))
    else if (dragging) {
        scope.launch { reachIn.animateTo(0f, Sp.Jelly.px, v) }
        scope.launch { reachOut.animateTo(0f, Sp.Jelly.px) }
    } else {
        // A tap: the clickable opens Search and reaches; this only lets the press go.
        scope.launch { reachOut.animateTo(0f, Sp.Jelly.px) }
        if (reachIn.targetValue < 0f) scope.launch { reachIn.animateTo(0f, Sp.Jelly.px) }
    }
}
