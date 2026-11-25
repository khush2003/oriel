package app.oriel.ui.screens

import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.glass.iosPullY
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.oriel.ui.theme.animatedBackground
import androidx.compose.ui.draw.drawBehind
import app.oriel.ui.components.drawWash
import app.oriel.ui.theme.accent
import app.oriel.ui.theme.LocalWash
import app.oriel.ui.theme.LocalIsDark
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.graphicsLayer
import app.oriel.ui.components.TopScrollEdge
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
import app.oriel.ui.LocalPageInset
import app.oriel.ui.LocalWindowLayout
import app.oriel.ui.PageWidth
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

/**
 * How far the page's list is pulled down past its top right now, in px after the rubber band
 * (0 at rest), for headers that stretch with it. Liquid motion only; read in the draw phase.
 */
val LocalPagePull = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 0f } }

/** Whether the floating tab bar is showing (every page but sign-in). */
val LocalTabBarVisible = compositionLocalOf { true }

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
    /** What the page is made of, which decides how it's centred on a wide window. */
    width: PageWidth = PageWidth.Feed,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onNearEnd: (() -> Unit)? = null,
    /** When the app is reopened with this page on screen and scrolled to its top (always true then). */
    onShown: ((atTop: Boolean) -> Unit)? = null,
    /** Whether the list is at its top, now and each time that changes: a feed holds a background refresh back until it is. */
    onAtTop: ((Boolean) -> Unit)? = null,
    background: Color = MaterialTheme.colorScheme.background,
    /** A soft wash of the accent behind the page, for pages made of glass panes: glass needs colour to bend. */
    wash: Boolean = false,
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
    // was. Pull to refresh does the rest.
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
    val atTopAction by rememberUpdatedState(onAtTop)
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < 8 }.distinctUntilChanged().collect { atTopAction?.invoke(it) }
    }
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }.distinctUntilChanged().collectLatest { near ->
            // Asked again while the end stays in view: the edge never re-fires for a page that
            // added few rows or arrived while the model was busy.
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
    val inset = LocalWindowLayout.current.inset(width)
    val padding = pagePadding(top = top, withTabBar = LocalTabBarVisible.current, horizontal = inset)
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
    val liquid = LocalLiquidMotion.current
    val overscroll = rememberOverscrollEffect()
    val pull = remember(overscroll) { { iosPullY(overscroll).coerceAtLeast(0f) } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize().animatedBackground(background)) {
        val washColor = accent()
        val washStrength = if (wash) LocalWash.current else 0f
        val washDark = LocalIsDark.current
        Box(Modifier.fillMaxSize().then(if (glass) Modifier.layerBackdrop(pageBackdrop) else Modifier).animatedBackground(background).drawBehind { drawWash(washColor, washDark, washStrength) }) {
            val list = @Composable { listModifier: Modifier ->
                LazyColumn(state = state, modifier = listModifier.fillMaxSize(), contentPadding = padding, overscrollEffect = overscroll) {
                    if (hasLargeTitle) item(key = "large-title", contentType = "title") {
                        LargeTitle(
                            title!!,
                            Modifier.graphicsLayer {
                                alpha = largeTitleAlpha()
                                if (liquid) liquidTitleStretch(pull(), rtl)
                            },
                            trailing = titleTrailing,
                        )
                    }
                    content()
                }
            }
            CompositionLocalProvider(LocalPagePull provides if (liquid) pull else NO_PULL, LocalPageInset provides inset) {
                if (onRefresh != null) {
                    IosPullToRefresh(refreshing, onRefresh, spinnerTop = top) { offset -> list(Modifier.graphicsLayer { translationY = offset() }) }
                } else list(Modifier)
            }
            overlay()
        }
        TopFadeScrim(Modifier.align(Alignment.TopCenter), pageColor = background)
        TopScrollEdge(edge, Modifier.align(Alignment.TopCenter), pageColor = background)
        // Back only from a page that's settled in: a second tap while it slides away pops the
        // page under it too, or sends the app to the background from Home.
        val back = onBack?.let { androidx.lifecycle.compose.dropUnlessResumed(block = it) }
        CompositionLocalProvider(LocalAppBackdrop provides if (glass) pageBackdrop else null) {
            ScaffoldBar(back, Modifier.align(Alignment.TopCenter), title, showTitle, collapse, leading, actions)
        }
    }
}

private val NO_PULL = { 0f }

/**
 * The large title pulled down past the top, as liquid: it swells from its leading edge, giving
 * less the further it's pulled (never past 12%), and stretches a little taller than wide, so it reads as
 * something soft being drawn out rather than zoomed.
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.liquidTitleStretch(pull: Float, rtl: Boolean) {
    if (pull <= 0f) return
    val reach = 160.dp.toPx()
    val s = app.oriel.ui.theme.rubber(pull, reach) / reach * 0.12f
    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (rtl) 1f else 0f, 1f)
    scaleX = 1f + s * 0.8f
    scaleY = 1f + s * 1.25f
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
