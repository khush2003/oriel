package app.oriel.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.SmoothedVelocity
import app.oriel.ui.theme.LiquidEdgeShape
import app.oriel.ui.theme.liquidEdgeCurve
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The system back swipe as it happens: where the finger is, and the mock's `pd`, how far (px) the
 * page's edge is dented under it. Read only while drawing.
 */
@Stable
internal class BackSwipe {
    var active = false
    var touchY by mutableFloatStateOf(0f)
    val dent = Animatable(0f)
    val speed = SmoothedVelocity()
}

@Composable
internal fun rememberBackSwipe(enabled: Boolean): BackSwipe {
    val swipe = remember { BackSwipe() }
    val owner = LocalNavigationEventDispatcherOwner.current
    val density = LocalDensity.current.density
    LaunchedEffect(owner, enabled) {
        if (!enabled) return@LaunchedEffect
        val dispatcher = owner?.navigationEventDispatcher ?: return@LaunchedEffect
        coroutineScope {
            dispatcher.transitionState.collect { state ->
                if (state is NavigationEventTransitionState.InProgress) {
                    val e = state.latestEvent
                    swipe.touchY = e.touchY
                    if (!swipe.active) {
                        swipe.active = true
                        swipe.speed.reset()
                    }
                    swipe.speed.add(android.os.SystemClock.uptimeMillis(), e.touchX / density)
                    val depth = (12f + (abs(swipe.speed.velocity) * 0.02f).coerceIn(0f, 26f)) * density
                    launch { swipe.dent.animateTo(depth, Sp.Snappy.px) }
                } else if (swipe.active) {
                    swipe.active = false
                    launch { swipe.dent.animateTo(0f, Sp.Jelly.px) }
                }
            }
        }
    }
    return swipe
}

/** Where a page is in its slide, from its place on the screen, and which way it's going. */
private class PageEdge {
    var x by mutableFloatStateOf(0f)
    var top = 0f
    var forward = false
    /** This slide is (or was) under a back swipe's finger: its edge dents there, not mid-page. */
    var byFinger = false
    /** The slide's speed, px per second (negative moving left, as a push does). */
    val speed = SmoothedVelocity()

    fun moved(newX: Float, newTop: Float, width: Float, swiping: Boolean) {
        speed.add(android.os.SystemClock.uptimeMillis(), newX)
        if (newX < x - 0.5f) forward = true else if (newX > x + 0.5f) forward = false
        top = newTop
        if (swiping && newX > 0f) byFinger = true
        // At rest (on screen, or off it to the right) the next slide starts afresh.
        if (newX <= 0.5f || newX >= width - 0.5f) byFinger = swiping && newX > 0f
        x = newX
    }
}

/**
 * A page with the mock's soft `edgePath` edge: bulges with push speed, dents under a back swipe.
 * Only the layer's outline deforms, in the draw phase.
 */
@Composable
internal fun LiquidPage(swipe: BackSwipe, content: @Composable () -> Unit) {
    if (!LocalLiquidMotion.current) return content()
    val edge = remember { PageEdge() }
    val density = LocalDensity.current
    val maxBulge = with(density) { 40.dp.toPx() }
    val maxDent = with(density) { 30.dp.toPx() }
    val spread = with(density) { 150.dp.toPx() }
    val shadowWidth = with(density) { 10.dp.toPx() }
    val shade = remember { Path() }
    // The edge's depth now: positive bulges, negative dents (LiquidEdgeShape).
    fun depth(): Float {
        val d = if (edge.byFinger) -swipe.dent.value else 0f
        // Held by the finger, the mock's page has no speed of its own; let go, it does.
        val v = if (swipe.active && edge.byFinger) 0f else (-edge.speed.velocity * 0.012f).coerceIn(-maxDent, maxBulge)
        return d + v
    }
    fun focus(height: Float) = if (edge.byFinger) (swipe.touchY - edge.top).coerceIn(0f, height) else height * 0.45f
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { val at = it.positionInRoot(); edge.moved(at.x, at.y, it.size.width.toFloat(), swipe.active) }
            .drawBehind {
                if (edge.x <= 0.5f) return@drawBehind
                val d = depth()
                shade.reset()
                shade.liquidEdgeCurve(size.height, d, focus(size.height), spread)
                // Three soft strokes centred on the edge; the page covers their inner half.
                for (i in 1..3) drawPath(shade, Color.Black.copy(alpha = 0.045f), style = Stroke(width = shadowWidth * i * 0.8f))
            }
            .graphicsLayer {
                val w = size.width
                if (w <= 0f) return@graphicsLayer
                // The mock's page beneath doesn't shrink, so no black shows at its edges in either mode.
                if (edge.x / w > 0.001f) {
                    shape = LiquidEdgeShape(depth(), focus(size.height), spread)
                    clip = true
                }
            },
    ) { content() }
}
