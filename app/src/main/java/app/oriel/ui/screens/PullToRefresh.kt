package app.oriel.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.goo
import app.oriel.ui.theme.gooEffect
import app.oriel.ui.theme.smoothstep
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import app.oriel.ui.components.drawActivitySpokes
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalReduceAnimation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** How far M3's pull is past its threshold turns into iOS's rubber band: it gives less and less. */
private fun pullOffset(fraction: Float, threshold: Float): Float {
    if (fraction <= 1f) return fraction * threshold
    val over = (fraction - 1f).coerceIn(0f, 2f)
    return threshold * (1f + over - over * over / 4f)
}

/** iOS's refresh: where it holds the content while it loads, and how far it has to be pulled (the mock's 64 and 80). */
private val REFRESH_HOLD = 64.dp
private val REFRESH_THRESHOLD = 80.dp

/**
 * iOS's pull to refresh rather than Material's: the content itself comes down with the finger
 * (passed to [content] as a layer offset), a tick as it catches, and the content held open while
 * [refreshing]. In the gap, the mock's drop: it hangs from the top on a neck that thins as it's
 * pulled; past the threshold the neck snaps, and the drop springs into place as a goo spinner
 * whose satellites fuse and split with it. Reduce motion shows iOS's spokes instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IosPullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, spinnerTop: Dp, content: @Composable (offset: () -> Float) -> Unit) {
    if (!LocalLiquidMotion.current) return ClassicPullToRefresh(refreshing, onRefresh, spinnerTop, content)
    val state = rememberPullToRefreshState()
    val density = LocalDensity.current
    val thresholdPx = with(density) { REFRESH_THRESHOLD.toPx() }
    val haptics = rememberHaptics()
    val loading by rememberUpdatedState(refreshing)
    val reduce = LocalReduceAnimation.current
    val drop = remember { RefreshDrop() }
    // Held open a little short of the threshold while it loads, as the mock's is.
    val hold = remember { Animatable(1f) }
    LaunchedEffect(refreshing) { hold.animateTo(if (refreshing) REFRESH_HOLD / REFRESH_THRESHOLD else 1f, Sp.Soft.unit) }
    val offset = remember(state, thresholdPx) { { pullOffset(state.distanceFraction, thresholdPx) * hold.value } }
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }.distinctUntilChanged().filter { it }.collect {
            // The pull caught; a refresh started from elsewhere opens without one.
            if (!loading) {
                haptics.play(Haptic.Tap)
                if (!reduce) drop.snap(this, offset() / density.density)
            }
        }
    }
    LaunchedEffect(refreshing) {
        if (refreshing && !reduce && !drop.snapped) drop.snap(this, offset() / density.density)
        if (!refreshing && drop.snapped) drop.finish(this)
    }
    // Pulled back without letting it refresh: the drop goes back to hanging.
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction <= 0f }.distinctUntilChanged().filter { it }.collect { if (!loading && drop.snapped && !drop.finishing) drop.reset() }
    }
    // A page that starts under the status bar still shows the indicator below it.
    val top = maxOf(spinnerTop, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp)
    Box(Modifier.fillMaxSize().pullToRefresh(refreshing, state, threshold = REFRESH_THRESHOLD, onRefresh = onRefresh)) {
        content(offset)
        if (reduce) RefreshSpinner(refreshing, { state.distanceFraction }, offset, Modifier.align(Alignment.TopCenter).padding(top = top))
        else RefreshDropCanvas(drop, refreshing, offset, Modifier.align(Alignment.TopCenter).padding(top = top))
    }
}

/** Liquid motion off: where it holds the content while it loads, and how far it has to be pulled (twice this, under the finger). */
private val CLASSIC_REFRESH_HOLD = 60.dp

/** Liquid motion off: the spokes fill in as it's pulled, the content held 60 dp down while it loads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassicPullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, spinnerTop: Dp, content: @Composable (offset: () -> Float) -> Unit) {
    val state = rememberPullToRefreshState()
    val holdPx = with(LocalDensity.current) { CLASSIC_REFRESH_HOLD.toPx() }
    val haptics = rememberHaptics()
    val loading by rememberUpdatedState(refreshing)
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }.distinctUntilChanged().filter { it }.collect {
            if (!loading) haptics.play(Haptic.Tap)
        }
    }
    val offset = remember(state, holdPx) { { pullOffset(state.distanceFraction, holdPx) } }
    val top = maxOf(spinnerTop, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp)
    Box(Modifier.fillMaxSize().pullToRefresh(refreshing, state, threshold = CLASSIC_REFRESH_HOLD, onRefresh = onRefresh)) {
        content(offset)
        RefreshSpinner(refreshing, { state.distanceFraction }, offset, Modifier.align(Alignment.TopCenter).padding(top = top))
    }
}

/** The mock's refresh drop: its springs and its state (hanging, snapped into a spinner, finishing). */
@Stable
private class RefreshDrop {
    var snapped by mutableStateOf(false)
    var finishing = false
    val neck = Animatable(0f)
    val anchor = Animatable(1f)
    val ballY = Animatable(0f)
    val ballR = Animatable(0f)
    val sat = Animatable(0f)
    /** The spinner's clock, seconds. */
    var spin by mutableFloatStateOf(0f)

    /** Past the threshold, at [pull] dp: the neck snaps, the drop springs to the spinner's place and grows satellites. */
    fun snap(scope: kotlinx.coroutines.CoroutineScope, pull: Float) {
        if (snapped) return
        snapped = true
        finishing = false
        scope.launch {
            neck.snapTo(maxOf(4f, 16f - pull * 0.1f)); anchor.snapTo(1f); ballY.snapTo(10f + pull * 0.55f); ballR.snapTo(12f); sat.snapTo(0f)
            launch { neck.animateTo(0f, Sp.FastEdge.unit, -80f) }
            launch { anchor.animateTo(0f, Sp.Snappy.unit) }
            launch { ballY.animateTo(34f, Sp.Bouncy.unit) }
            launch { ballR.animateTo(10f, Sp.Bouncy.unit) }
            launch { sat.animateTo(1f, Sp.Bouncy.unit) }
            var last = withFrameNanos { it }
            while (snapped) {
                withFrameNanos { now ->
                    spin += (now - last) / 1e9f
                    last = now
                }
            }
        }
    }

    /** Done: the satellites fuse back in and the drop shrinks away. */
    fun finish(scope: kotlinx.coroutines.CoroutineScope) {
        finishing = true
        scope.launch {
            launch { sat.animateTo(0f, Sp.Snappy.unit) }
            ballR.animateTo(0f, Sp.Bouncy.unit, 30f)
            reset()
        }
    }

    fun reset() {
        snapped = false
        finishing = false
    }
}

/**
 * The drop, drawn in the gap the pull opens, through the mock's small goo filter (a 4 dp blur
 * and a 20/8 threshold) on Android 12 and up; below it the shapes are drawn as they are, the
 * neck being a bar that already joins them. All in the draw phase.
 */
@Composable
private fun RefreshDropCanvas(drop: RefreshDrop, refreshing: Boolean, pull: () -> Float, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onBackground
    val fx = with(LocalDensity.current) { remember { gooEffect(4.dp.toPx(), gain = 20f, cut = 8f) } }
    Canvas(
        modifier.size(120.dp, 220.dp)
            .graphicsLayer {
                val p = pull() / density
                alpha = if (drop.snapped) 1f else smoothstep(2f, 24f, p)
            }
            .goo(fx)
            .semantics { if (refreshing) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val d = density
        val p = pull() / d
        if (!drop.snapped && p <= 0f) return@Canvas
        val nw: Float
        val ax: Float
        val by: Float
        val br: Float
        val sat: Float
        if (!drop.snapped) {
            nw = maxOf(4f, 16f - p * 0.1f); ax = 1f; by = 10f + p * 0.55f; br = 5f + minOf(p, 80f) / 80f * 7f; sat = 0f
        } else {
            nw = drop.neck.value; ax = drop.anchor.value; by = drop.ballY.value; br = drop.ballR.value; sat = drop.sat.value
        }
        val cx = size.width / 2f
        if (ax > 0f) drawOval(color, topLeft = Offset(cx - 30f * ax * d, -10f * ax * d), size = Size(60f * ax * d, 20f * ax * d))
        if (nw > 0f) drawRect(color, topLeft = Offset(cx - nw / 2f * d, 0f), size = Size(nw * d, maxOf(0f, by) * d))
        if (br > 0f) drawCircle(color, br * d, Offset(cx, by * d))
        if (sat > 0f) {
            val t = drop.spin
            for (i in 0 until 3) {
                val a = t * 5f + i * 2.094f
                val orbit = (14f + 5f * kotlin.math.sin(t * 6f + i * 1.7f)) * sat
                drawCircle(color, 4.5f * sat * d, Offset(cx + kotlin.math.cos(a) * orbit * d, (by + kotlin.math.sin(a) * orbit) * d))
            }
        }
    }
}

@Composable
private fun RefreshSpinner(refreshing: Boolean, fraction: () -> Float, offset: () -> Float, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val turn = if (refreshing && !LocalReduceAnimation.current) {
        rememberInfiniteTransition(label = "refresh").animateFloat(0f, 8f, infiniteRepeatable(tween(960, easing = LinearEasing)), label = "refreshTurn")
    } else null
    Canvas(
        modifier.size(26.dp)
            .graphicsLayer {
                // Centred in the gap the content has opened, until it's opened enough to hold it.
                translationY = ((offset() - size.height) / 2f).coerceAtLeast(0f)
                alpha = (fraction() * 2f).coerceIn(0f, 1f)
            }
            .semantics { if (refreshing) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val shown = if (refreshing) 8 else (fraction() * 8f).toInt()
        drawActivitySpokes(color, step = turn?.value?.toInt() ?: 0, shown = shown)
    }
}
