package app.oriel.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.goo
import app.oriel.ui.theme.gooEffect
import app.oriel.ui.theme.smoothstep
import kotlinx.coroutines.launch
import app.oriel.ui.theme.animatedBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import app.oriel.ui.components.GroupShape
import app.oriel.ui.components.TextAction
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.util.Format
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import app.oriel.ui.components.TopScrollEdge
import app.oriel.ui.components.drawActivitySpokes
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalReduceAnimation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.ui.LocalAppState
import app.oriel.ui.glass.LocalAppBackdrop
import app.oriel.ui.glass.backdropWanted
import app.oriel.ui.glass.backdrop.backdrops.layerBackdrop
import app.oriel.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import app.oriel.ui.components.FloatingTopBar
import app.oriel.ui.components.LargeTitle
import app.oriel.ui.components.TopFadeScrim
import app.oriel.ui.components.bareTopPadding
import app.oriel.ui.components.pagePadding
import app.oriel.ui.components.topBarContentPadding
import app.oriel.ui.navigation.Tab
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull

/** Whether the docked player is showing, so pages leave room for it above the tab bar. */
val LocalMiniPlayerVisible = compositionLocalOf { false }

/** Whether the floating tab bar is showing (every page but sign-in). */
val LocalTabBarVisible = compositionLocalOf { true }

@Composable
fun bottomPadding(): PaddingValues = pagePadding(LocalMiniPlayerVisible.current, top = 0.dp, withTabBar = LocalTabBarVisible.current)

/**
 * An iOS page: a large title that scrolls with the content and hands over to
 * a small centred title in the floating bar, glass circles for back and
 * actions, the fade under the status bar and the scroll edge under the bar,
 * and iOS's pull to refresh. All of it follows the scroll in the draw and
 * layer phases: scrolling never recomposes the page's chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String?,
    onBack: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    tab: Tab? = null,
    largeTitle: Boolean = true,
    /** Pages with their own header (banners, artwork) start under the status bar instead of below the bar. */
    edgeToEdge: Boolean = false,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onNearEnd: (() -> Unit)? = null,
    /** When the app is reopened with this page on screen and scrolled to its top (always true then). */
    onShown: ((atTop: Boolean) -> Unit)? = null,
    background: Color = MaterialTheme.colorScheme.background,
    titleTrailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val app = LocalAppState.current
    val density = LocalDensity.current
    val hasLargeTitle = largeTitle && title != null && !edgeToEdge
    // How far the large title (or an edge-to-edge page's header) has handed over to the bar,
    // 0 to 1. Called from layers and draw blocks only.
    val collapse: () -> Float = remember(state, hasLargeTitle, edgeToEdge, density) {
        {
            when {
                !hasLargeTitle && !edgeToEdge -> 1f
                state.firstVisibleItemIndex > 0 -> 1f
                edgeToEdge -> with(density) { ((state.firstVisibleItemScrollOffset - 200.dp.toPx()) / 40.dp.toPx()).coerceIn(0f, 1f) }
                else -> {
                    // The bar's title fades in over the second half of the large one's exit.
                    val h = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat()?.takeIf { it > 0f } ?: with(density) { 56.dp.toPx() }
                    ((state.firstVisibleItemScrollOffset - h * 0.5f) / (h * 0.4f)).coerceIn(0f, 1f)
                }
            }
        }
    }
    // The large title fades as it goes under the bar, so it never sits behind the small one.
    val largeTitleAlpha: () -> Float = remember(state, density) {
        {
            if (state.firstVisibleItemIndex > 0) 0f
            else {
                val h = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat()?.takeIf { it > 0f } ?: with(density) { 56.dp.toPx() }
                1f - (state.firstVisibleItemScrollOffset / (h * 0.75f)).coerceIn(0f, 1f)
            }
        }
    }
    val showTitle = remember(collapse) { derivedStateOf { collapse() > 0.5f } }
    val refreshAction by rememberUpdatedState(onRefresh)
    if (tab != null) {
        // Only taps from now on: the flow keeps the last one, which a page coming back would replay.
        val shownAt = remember { System.nanoTime() }
        LaunchedEffect(tab) {
            snapshotFlow { app.reselect.value }.filterNotNull().filter { it.first == tab && it.second > shownAt }.distinctUntilChanged().collect {
                // As on YouTube: a tab tapped again goes to the top, and tapped at the top it refreshes.
                if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                    refreshAction?.invoke()
                    return@collect
                }
                if (state.firstVisibleItemIndex > 12) state.scrollToItem(6)
                state.animateScrollToItem(0)
            }
        }
    }
    val nearEndAction by rememberUpdatedState(onNearEnd)
    val shownAction by rememberUpdatedState(onShown)
    // Only when the app comes back to the foreground, and only for the page on screen and
    // scrolled to its top: stepping back to a page from another one leaves it exactly as it
    // was (it used to reload under the reader). Pull to refresh does the rest.
    val pageLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(pageLifecycle) {
        val process = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        // Registering replays the current state synchronously; that isn't a return to the app.
        var replaying = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (replaying || event != androidx.lifecycle.Lifecycle.Event.ON_START) return@LifecycleEventObserver
            val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < 8
            if (atTop && pageLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) shownAction?.invoke(true)
        }
        process.addObserver(observer)
        replaying = false
        onDispose { process.removeObserver(observer) }
    }
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }.distinctUntilChanged().collectLatest { near ->
            // Asked again while the end stays in view: a page that added few rows, or arrived
            // while the model was busy, used to leave the list stalled, as the edge never re-fired.
            // (The models ignore a call while loading, after a failure, or at the real end.)
            while (near) { nearEndAction?.invoke(); delay(1_000) }
        }
    }
    // A bar with nothing in it (the tab roots) reserves no row: the large title sits right under
    // the status bar, as iOS's do; the bar still floats over it to show the title once scrolled.
    val barContent = onBack != null || leading != null || actions != null
    val top: Dp = when {
        edgeToEdge -> 0.dp
        largeTitle && title != null && !barContent -> bareTopPadding()
        else -> topBarContentPadding()
    }
    val padding = pagePadding(LocalMiniPlayerVisible.current, top = top, withTabBar = LocalTabBarVisible.current)
    // Under the bar: content passes beneath it as soon as it scrolls when the page starts below
    // the bar; on a tab root the large title sits there at rest, so not until it's gone.
    val edge: () -> Float = remember(state, barContent, hasLargeTitle, collapse, density) {
        if (hasLargeTitle && !barContent || edgeToEdge) collapse
        else {
            {
                if (state.firstVisibleItemIndex > 0) 1f
                else with(density) { (state.firstVisibleItemScrollOffset / 24.dp.toPx()).coerceIn(0f, 1f) }
            }
        }
    }

    // The bar's glass samples this page's own layer: the app-wide backdrop holds the page, bar included,
    // so sampling it from inside would draw the layer into itself.
    val glass = backdropWanted()
    val pageBackdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().animatedBackground(background)) {
        Box(Modifier.fillMaxSize().then(if (glass) Modifier.layerBackdrop(pageBackdrop) else Modifier).animatedBackground(background)) {
            val list = @Composable { listModifier: Modifier ->
                LazyColumn(state = state, modifier = listModifier.fillMaxSize(), contentPadding = padding) {
                    if (hasLargeTitle) item(key = "large-title", contentType = "title") {
                        LargeTitle(title!!, Modifier.graphicsLayer { alpha = largeTitleAlpha() }, trailing = titleTrailing)
                    }
                    content()
                }
            }
            if (onRefresh != null) {
                IosPullToRefresh(refreshing, onRefresh, spinnerTop = top) { offset -> list(Modifier.graphicsLayer { translationY = offset() }) }
            } else list(Modifier)
            overlay()
        }
        TopFadeScrim(Modifier.align(Alignment.TopCenter), pageColor = background)
        TopScrollEdge(edge, Modifier.align(Alignment.TopCenter), pageColor = background)
        // Back only from a page that's settled in: a second tap while it slides away popped the
        // page under it too, or sent the app to the background from Home.
        val back = onBack?.let { androidx.lifecycle.compose.dropUnlessResumed(block = it) }
        CompositionLocalProvider(LocalAppBackdrop provides if (glass) pageBackdrop else null) {
            ScaffoldBar(back, Modifier.align(Alignment.TopCenter), title, showTitle, collapse, leading, actions)
        }
    }
}

/** The bar, in a scope of its own: the title showing or not, for accessibility, recomposes only this. */
@Composable
private fun ScaffoldBar(
    onBack: (() -> Unit)?,
    modifier: Modifier,
    title: String?,
    showTitle: State<Boolean>,
    progress: () -> Float,
    leading: (@Composable () -> Unit)?,
    actions: (@Composable RowScope.() -> Unit)?,
) {
    FloatingTopBar(onBack, modifier, title = title, showTitle = showTitle.value, leading = leading, actions = actions, titleProgress = progress)
}

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
private fun IosPullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, spinnerTop: Dp, content: @Composable (offset: () -> Float) -> Unit) {
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

/** A feed's end: a spinner while the next page loads, or a way to ask again when it didn't. */
fun LazyListScope.feedFooter(state: FeedState, onRetry: () -> Unit) = moreFooter(state.loadingMore, state.moreFailed, onRetry)

/**
 * Over a feed still showing an older copy because the refresh failed: that it
 * failed, how old what's shown is, and a way to try again. Without it a failed
 * refresh looks like YouTube having nothing new.
 */
fun LazyListScope.refreshNotice(state: FeedState, onRetry: () -> Unit) {
    val error = state.error ?: return
    if (state.feed == null || state.loading || state.refreshing) return
    item(key = "refresh-notice", contentType = "notice") {
        val age = state.updatedAt?.let { " Updated ${Format.ago(it).replaceFirstChar { c -> c.lowercase() }}." }.orEmpty()
        NoticeRow(error.substringBefore(". ").removeSuffix(".") + "." + age, action = "Try again", onAction = onRetry)
    }
}

/** A list's tail when it pages: a spinner while the next page loads, or a retry if it failed. The token is kept, so retry picks up where it stopped. */
fun LazyListScope.moreFooter(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    when {
        loading -> item(key = "more", contentType = "more") { LoadingRow() }
        failed -> item(key = "more-failed", contentType = "more") { NoticeRow("Couldn't load more.", action = "Try again", onAction = onRetry) }
    }
}

@Composable
fun NoticeRow(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 6.dp)
            .clip(GroupShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        TextAction(action, onAction)
    }
}
