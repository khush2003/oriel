package app.oriel.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import app.oriel.ui.theme.LocalIsDark
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import app.oriel.ui.theme.EdgeGiveSpring
import app.oriel.ui.theme.LiquidEdgeShape
import app.oriel.ui.theme.SquashSpring
import app.oriel.ui.theme.liquidEdgeCurve
import app.oriel.ui.theme.midFlight
import app.oriel.ui.theme.smoothstep
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.oriel.ui.components.AlertHost
import app.oriel.ui.components.BottomFadeScrim
import app.oriel.ui.components.BottomTab
import app.oriel.ui.components.FloatingTabBar
import app.oriel.ui.components.HeldContextMenu
import app.oriel.ui.components.ItemActions
import app.oriel.ui.components.ItemActionsSheet
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.NoticeHost
import app.oriel.ui.components.SaveToPlaylistSheet
import app.oriel.ui.components.TextPromptDialog
import app.oriel.ui.glass.LocalAppBackdrop
import app.oriel.ui.glass.backdropWanted
import app.oriel.ui.glass.backdrop.backdrops.layerBackdrop
import app.oriel.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import app.oriel.ui.navigation.ChannelRoute
import app.oriel.ui.navigation.ChannelsRoute
import app.oriel.ui.navigation.HistoryRoute
import app.oriel.ui.navigation.HomeRoute
import app.oriel.ui.navigation.LikedRoute
import app.oriel.ui.navigation.LocalPlaylistRoute
import app.oriel.ui.navigation.LoginRoute
import app.oriel.ui.navigation.NotificationsRoute
import app.oriel.ui.screens.NotificationsScreen
import app.oriel.ui.navigation.PlaylistRoute
import app.oriel.ui.navigation.PlaylistsRoute
import app.oriel.ui.navigation.SearchRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.navigation.ShortsRoute
import app.oriel.ui.navigation.SubscriptionsRoute
import app.oriel.ui.navigation.Tab
import app.oriel.ui.navigation.TopicRoute
import app.oriel.ui.navigation.WatchLaterRoute
import app.oriel.ui.navigation.YouRoute
import app.oriel.ui.navigation.tabRoot
import app.oriel.ui.player.PlayerHost
import app.oriel.ui.screens.ChannelScreen
import app.oriel.ui.screens.ChannelsScreen
import app.oriel.ui.screens.HistoryScreen
import app.oriel.ui.screens.HomeScreen
import app.oriel.ui.screens.LikedScreen
import app.oriel.ui.screens.LocalMiniPlayerVisible
import app.oriel.ui.screens.LocalPlaylistScreen
import app.oriel.ui.screens.LocalTabBarVisible
import app.oriel.ui.screens.LoginScreen
import app.oriel.ui.screens.PlaylistScreen
import app.oriel.ui.screens.PlaylistsScreen
import app.oriel.ui.screens.SearchScreen
import app.oriel.ui.screens.SettingsScreen
import app.oriel.ui.screens.ShortsScreen
import app.oriel.ui.screens.SubscriptionsScreen
import app.oriel.ui.screens.TopicScreen
import app.oriel.ui.screens.WatchLaterScreen
import app.oriel.ui.screens.YouScreen
import app.oriel.ui.theme.DarkIsland
import app.oriel.ui.theme.LocalReduceAnimation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import app.oriel.ui.screens.ReportSheet
import app.oriel.ui.screens.UpdateHost
import app.oriel.util.CrashLog
import app.oriel.util.ReportKind

private val TABS = listOf(
    BottomTab("Home", OrielIcons.Home, OrielIcons.HomeSelected),
    BottomTab("Shorts", OrielIcons.Shorts, OrielIcons.ShortsSelected),
    BottomTab("Subscriptions", OrielIcons.Subscriptions, OrielIcons.SubscriptionsSelected),
    BottomTab("You", OrielIcons.You, OrielIcons.YouSelected),
)

/** A tab's root or Search: moving between these is a tab switch. A short pushed from a feed isn't one. */
private fun NavBackStackEntry.isRoot(): Boolean = tabRoot() != null || destination.hasRoute(SearchRoute::class)

/**
 * The app's frame: pages in a NavHost recorded into the glass backdrop, the
 * floating tab bar and Search circle over them, the player sheet over that,
 * and menus, sheets, alerts and notices on top.
 */
@Composable
fun OrielRoot(app: AppState, inPip: Boolean) {
    val nav = app.nav
    val backStack by nav.currentBackStack.collectAsState()
    val top = backStack.lastOrNull()?.destination
    val selectedTab = backStack.asReversed().firstNotNullOfOrNull { it.tabRoot() } ?: Tab.HOME
    val onSearch = top?.hasRoute(SearchRoute::class) == true
    val onShorts = top?.hasRoute(ShortsRoute::class) == true
    val onLogin = top?.hasRoute(LoginRoute::class) == true
    val showTabBar = !onLogin
    val backdrop = rememberLayerBackdrop()
    val record = backdropWanted()
    val reduce = LocalReduceAnimation.current
    // The page underneath a push dims toward black, as UIKit's does, in light as in dark.
    val dim = if (LocalIsDark.current) PUSH_DIM_DARK else PUSH_DIM_LIGHT
    val backSwipe = rememberBackSwipe()

    LaunchedEffect(app) { app.graph.actions.notices.collectLatest { app.notice(it) } }
    // Usage counts are opt-in: asked once, a little after the first launch settles, and only in
    // builds that can report. Either answer is kept; Settings › Privacy changes it later.
    // Then, never at the same time, a crash since last time is offered for reporting, once.
    LaunchedEffect(app) {
        val graph = app.graph
        if (graph.telemetry.available && graph.settings.current.telemetry == null) {
            kotlinx.coroutines.delay(4_000)
            // Never over an alert or a report already open: it waits its turn.
            snapshotFlow { app.alert == null && app.report == null }.first { it }
            app.alert = AlertSpec(
                "Help improve Oriel?",
                "Share anonymous usage counts: app opens, which features get used, crashes, and the app and Android version. Never what you watch or search, and nothing tied to you or your account. You can change this in Settings.",
                listOf(
                    AlertAction("Don't Share") { graph.settings.update { it.copy(telemetry = false) } },
                    AlertAction("Share", emphasized = true) {
                        graph.settings.update { it.copy(telemetry = true) }
                        graph.telemetry.track("app_open", mapOf("signedIn" to graph.account.signedIn.value, "firstRun" to true))
                    },
                ),
            )
        }
        graph.crashesChecked.await()
        val crash = withContext(Dispatchers.IO) { CrashLog.unasked(graph.context) } ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1_500)
        snapshotFlow { app.alert == null && app.report == null }.first { it }
        // Remembered before it's shown: if showing it went wrong too, it isn't asked again and again.
        withContext(Dispatchers.IO) { CrashLog.markAsked(graph.context, crash.time) }
        app.alert = AlertSpec(
            "Oriel closed unexpectedly",
            "A note of what went wrong is kept on this phone. Sending it helps get it fixed; you'll see everything in it first.",
            listOf(
                AlertAction("Not now"),
                AlertAction("Report…", emphasized = true) { app.reportProblem(ReportKind.CRASH) },
            ),
        )
    }
    // Where a crash happens, for its report: the page on top, and whether the watch page covers it.
    val page = top?.route?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('.')?.removeSuffix("Route")
    LaunchedEffect(page) {
        snapshotFlow { app.sheet.expanded }.collect { CrashLog.screen = if (it) "$page, watch page open" else page }
    }

    CompositionLocalProvider(
        LocalAppState provides app,
        LocalAppBackdrop provides if (record) backdrop else null,
        // The collapsed player floats over the page as a picture-in-picture window, so pages
        // no longer keep a strip clear for it at the bottom.
        LocalMiniPlayerVisible provides false,
        LocalTabBarVisible provides showTabBar,
    ) {
        if (inPip) {
            PlayerHost(app, withTabBar = false, hidden = false, inPip = true)
            return@CompositionLocalProvider
        }
        Box(Modifier.fillMaxSize()) {
            // Black behind the pages, not the page colour: the dim is the page beneath fading toward
            // it, and on light the page colour would have washed it out to white instead. Every
            // page paints its own background, and nothing fades both pages at once (see below).
            Box(Modifier.fillMaxSize().then(if (record) Modifier.layerBackdrop(backdrop) else Modifier).background(Color.Black)) {
                NavHost(
                    navController = nav,
                    startDestination = HomeRoute,
                    modifier = Modifier.fillMaxSize(),
                    // Reduce motion snaps pages in and out, as it does every other animation.
                    enterTransition = { if (reduce) EnterTransition.None else push(true, app.tabSwitchEntry, dim) },
                    exitTransition = { if (reduce) ExitTransition.None else pushAway(true, app.tabSwitchEntry, dim) },
                    popEnterTransition = { if (reduce) EnterTransition.None else push(false, app.tabSwitchEntry, dim) },
                    popExitTransition = { if (reduce) ExitTransition.None else pushAway(false, app.tabSwitchEntry, dim) },
                    // The edge swipe seeks these with the finger, and on release the rest plays on from there.
                    predictivePopEnterTransition = { if (reduce) EnterTransition.None else swipeBackIn(app.tabSwitchEntry, dim) },
                    predictivePopExitTransition = { if (reduce) ExitTransition.None else swipeBackOut(app.tabSwitchEntry) },
                ) {
                    composable<HomeRoute> { LiquidPage(backSwipe) { HomeScreen() } }
                    composable<ShortsRoute> { LiquidPage(backSwipe) { ShortsScreen(it.toRoute<ShortsRoute>().startId) } }
                    composable<SubscriptionsRoute> { LiquidPage(backSwipe) { SubscriptionsScreen() } }
                    composable<YouRoute> { LiquidPage(backSwipe) { YouScreen() } }
                    composable<SearchRoute> { LiquidPage(backSwipe) { SearchScreen(it.toRoute<SearchRoute>().query) } }
                    composable<ChannelRoute> { LiquidPage(backSwipe) { val r = it.toRoute<ChannelRoute>(); ChannelScreen(r.id, r.params) } }
                    composable<PlaylistRoute> { LiquidPage(backSwipe) { PlaylistScreen(it.toRoute<PlaylistRoute>().id) } }
                    composable<LocalPlaylistRoute> { LiquidPage(backSwipe) { LocalPlaylistScreen(it.toRoute<LocalPlaylistRoute>().id) } }
                    composable<TopicRoute> { LiquidPage(backSwipe) { TopicScreen(it.toRoute<TopicRoute>().topic) } }
                    composable<HistoryRoute> { LiquidPage(backSwipe) { HistoryScreen() } }
                    composable<WatchLaterRoute> { LiquidPage(backSwipe) { WatchLaterScreen() } }
                    composable<LikedRoute> { LiquidPage(backSwipe) { LikedScreen() } }
                    composable<ChannelsRoute> { LiquidPage(backSwipe) { ChannelsScreen() } }
                    composable<PlaylistsRoute> { LiquidPage(backSwipe) { PlaylistsScreen() } }
                    composable<SettingsRoute> { LiquidPage(backSwipe) { SettingsScreen() } }
                    composable<LoginRoute> { LiquidPage(backSwipe) { LoginScreen() } }
                    composable<NotificationsRoute> { LiquidPage(backSwipe) { NotificationsScreen() } }
                }
            }

            if (showTabBar) {
                val sheetProgress = app.sheet.progress
                val barsGone by remember { derivedStateOf { sheetProgress.value > 0.999f || app.sheet.fullscreen } }
                if (!barsGone) {
                    if (!onShorts) BottomFadeScrim(Modifier.align(Alignment.BottomCenter))
                    val bar = @Composable {
                        FloatingTabBar(
                            tabs = TABS,
                            selectedIndex = selectedTab.ordinal,
                            onTabSelected = { app.selectTab(Tab.entries[it]) },
                            searchSelected = onSearch,
                            onSearch = { app.openSearch() },
                            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer {
                                // The bar sinks away as the player rises over it.
                                val p = sheetProgress.value
                                translationY = p * 140.dp.toPx()
                                alpha = 1f - p
                            },
                        )
                    }
                    // Shorts are black edge to edge in either theme, and the bar goes dark with them.
                    if (onShorts) DarkIsland(bar) else bar()
                }
            }

            PlayerHost(app, withTabBar = showTabBar, hidden = onShorts || onLogin, inPip = false)

            HeldContextMenu(
                item = app.menuItem,
                held = app.menuHeld,
                onDismiss = app::dismissMenu,
                onPreviewClick = { item -> app.dismissMenu(); app.openItem(item) },
            ) { item -> ItemActions(app, item, app::dismissMenu) }

            app.sheetItem?.let { item -> ItemActionsSheet(app, item) { app.sheetItem = null } }
            app.saveTarget?.let { video -> SaveToPlaylistSheet(app, video) { app.saveTarget = null } }
            app.report?.let { r -> ReportSheet(app, r) { app.report = null } }
            app.renameTarget?.let { p ->
                TextPromptDialog("Rename playlist", p.title, "Save", onDismiss = { app.renameTarget = null }) { name ->
                    app.graph.library.renameLocalPlaylist(p.id.removePrefix("local:"), name)
                    app.renameTarget = null
                }
            }
            UpdateHost(app)
            AlertHost(app)
            NoticeHost(app, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp))
        }
    }
}

/**
 * iOS push: the new page slides in whole from the right over the old one, which drifts a
 * third of the way left and dims toward the black behind it rather than fading away. Push and
 * pop share one critically damped spring, as UIKit's do. Kept here rather than in Motion.kt:
 * it's the one IntOffset spring, and only pushes use it.
 */
private val PushSpring = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)

/** The page beneath's dim, on the same spring as the slide so the two finish together. */
private val PushDimSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)

/** How opaque the page beneath stays over black: about UIKit's dimming in each mode. */
private const val PUSH_DIM_DARK = 0.7f
private const val PUSH_DIM_LIGHT = 0.88f

/**
 * The edge swipe's curve. It's seeked by the gesture's progress, so near the start it has to be
 * about linear for the page to stay under the finger (EaseOutCubic ran it nearly three times
 * ahead); it eases out at the end so a release lands softly instead of stopping dead.
 */
private val SwipeBackEasing = CubicBezierEasing(0.4f, 0.4f, 0.65f, 1f)
private const val SWIPE_BACK_MS = 340

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(tabSwitchEntry: String?) =
    (initialState.isRoot() && targetState.isRoot()) || targetState.id == tabSwitchEntry

/*
 * A tab switch crossfades by fading only the page on top, in for a switch and out for a pop
 * back, with the page beneath kept opaque until it's done: fading both let the black behind
 * show through mid-way.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.push(forward: Boolean, tabSwitchEntry: String?, dim: Float): EnterTransition {
    if (isTabSwitch(tabSwitchEntry)) return if (forward) fadeIn(tween(160)) else EnterTransition.None
    return if (forward) slideInHorizontally(PushSpring) { it }
    else slideInHorizontally(PushSpring) { -it / 3 } + fadeIn(PushDimSpring, initialAlpha = dim)
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushAway(forward: Boolean, tabSwitchEntry: String?, dim: Float): ExitTransition {
    if (isTabSwitch(tabSwitchEntry)) return if (forward) ExitTransition.KeepUntilTransitionsFinished else fadeOut(tween(160))
    return if (forward) slideOutHorizontally(PushSpring) { -it / 3 } + fadeOut(PushDimSpring, targetAlpha = dim)
    else slideOutHorizontally(PushSpring) { it }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.swipeBackIn(tabSwitchEntry: String?, dim: Float): EnterTransition {
    if (isTabSwitch(tabSwitchEntry)) return EnterTransition.None
    val spec = tween<IntOffset>(SWIPE_BACK_MS, easing = SwipeBackEasing)
    return slideInHorizontally(spec) { -it / 3 } + fadeIn(tween(SWIPE_BACK_MS, easing = SwipeBackEasing), initialAlpha = dim)
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.swipeBackOut(tabSwitchEntry: String?): ExitTransition {
    if (isTabSwitch(tabSwitchEntry)) return fadeOut(tween(SWIPE_BACK_MS, easing = LinearEasing))
    return slideOutHorizontally(tween(SWIPE_BACK_MS, easing = SwipeBackEasing)) { it }
}


/**
 * The system back swipe as it happens: where the finger is, and how far the page's edge has
 * given under it (0 to 1, sprung, so it dents in as the swipe starts and springs flat, with a
 * wobble, when it's let go). Read only while drawing.
 */
@Stable
private class BackSwipe {
    var active = false
    var touchY by mutableFloatStateOf(0f)
    val give = Animatable(0f)
}

@Composable
private fun rememberBackSwipe(): BackSwipe {
    val swipe = remember { BackSwipe() }
    val owner = LocalNavigationEventDispatcherOwner.current
    LaunchedEffect(owner) {
        val dispatcher = owner?.navigationEventDispatcher ?: return@LaunchedEffect
        coroutineScope {
            dispatcher.transitionState.collect { state ->
                if (state is NavigationEventTransitionState.InProgress) {
                    swipe.touchY = state.latestEvent.touchY
                    if (!swipe.active) {
                        swipe.active = true
                        launch { swipe.give.animateTo(1f, EdgeGiveSpring) }
                    }
                } else if (swipe.active) {
                    swipe.active = false
                    launch { swipe.give.animateTo(0f, SquashSpring) }
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

    fun moved(newX: Float, newTop: Float, width: Float, swiping: Boolean) {
        if (newX < x - 0.5f) forward = true else if (newX > x + 0.5f) forward = false
        top = newTop
        if (swiping && newX > 0f) byFinger = true
        // At rest (on screen, or off it to the right) the next slide starts afresh.
        if (newX <= 0.5f || newX >= width - 0.5f) byFinger = swiping && newX > 0f
        x = newX
    }
}

/**
 * A page that slides like iOS's, with a softer leading edge: pushed in, its edge bulges forward
 * a little and relaxes as it lands; under a back swipe it dents in where the finger holds it and
 * springs flat when let go; popped by the button it dips gently mid-page. A soft shadow runs
 * along it. The page beneath shrinks a touch as it's covered (in dark mode). Only the outline deforms (the clip
 * is the layer's, the content isn't redrawn for it), all in the draw phase. Reduce motion: none.
 */
@Composable
private fun LiquidPage(swipe: BackSwipe, content: @Composable () -> Unit) {
    if (LocalReduceAnimation.current) return content()
    val edge = remember { PageEdge() }
    val density = LocalDensity.current
    // Shrinking shows the black behind the pages at its edges: unseen on dark pages, a hard line on light ones.
    val shrink = if (LocalIsDark.current) 0.03f else 0f
    val bulge = with(density) { 14.dp.toPx() }
    val dent = with(density) { 18.dp.toPx() }
    val shadowWidth = with(density) { 10.dp.toPx() }
    val shade = remember { Path() }
    // The edge's shape at this point of the slide: positive bulges, negative dents (LiquidEdgeShape).
    fun depth(width: Float): Float {
        val f = (edge.x / width).coerceIn(0f, 1f)
        val give = swipe.give.value
        return when {
            edge.byFinger -> -dent * give
            edge.forward -> bulge * midFlight(f)
            else -> -dent * 0.5f * midFlight(f)
        }
    }
    fun focus(height: Float) = if (edge.byFinger) (swipe.touchY - edge.top).coerceIn(0f, height) else height * 0.45f
    fun spread(height: Float, d: Float) = height * if (d >= 0f) 0.34f else 0.15f
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { val at = it.positionInRoot(); edge.moved(at.x, at.y, it.size.width.toFloat(), swipe.active) }
            .drawBehind {
                if (edge.x <= 0.5f) return@drawBehind
                val d = depth(size.width)
                shade.reset()
                shade.liquidEdgeCurve(size.height, d, focus(size.height), spread(size.height, d))
                // Three soft strokes centred on the edge; the page covers their inner half.
                for (i in 1..3) drawPath(shade, Color.Black.copy(alpha = 0.045f), style = Stroke(width = shadowWidth * i * 0.8f))
            }
            .graphicsLayer {
                val w = size.width
                if (w <= 0f) return@graphicsLayer
                val f = edge.x / w
                if (f < -0.001f) {
                    // Beneath a push: a touch smaller as it's covered, as the dim deepens.
                    val k = 1f - shrink * smoothstep(0f, 1f, -f * 3f)
                    scaleX = k
                    scaleY = k
                } else if (f > 0.001f) {
                    val d = depth(w)
                    shape = LiquidEdgeShape(d, focus(size.height), spread(size.height, d))
                    clip = true
                }
            },
    ) { content() }
}
