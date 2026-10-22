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
import androidx.compose.animation.scaleIn
import kotlin.math.abs
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
import androidx.compose.animation.core.spring
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.Sp
import androidx.compose.animation.core.Spring
import app.oriel.ui.theme.SmoothedVelocity
import app.oriel.ui.theme.LiquidEdgeShape
import app.oriel.ui.theme.liquidEdgeCurve
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
import app.oriel.ui.screens.LiquidMotionPrompt
import app.oriel.ui.screens.UpdateHost
import app.oriel.ui.screens.UpdatePromptGate
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
    val liquid = LocalLiquidMotion.current
    // The page underneath a push dims toward black, as UIKit's does, in light as in dark.
    val dim = when {
        liquid -> PUSH_DIM_LIQUID
        LocalIsDark.current -> PUSH_DIM_DARK
        else -> PUSH_DIM_LIGHT
    }
    val push = if (liquid) LiquidPush else ClassicPush
    val backSwipe = rememberBackSwipe(liquid)

    LaunchedEffect(app) { app.graph.actions.notices.collectLatest { app.notice(it) } }
    var askLiquid by remember { mutableStateOf(false) }
    val updateGate = remember { UpdatePromptGate() }
    // The update offer waits while the liquid question is up, as everything waits for an alert.
    updateGate.held = askLiquid
    // Usage counts are opt-in: asked once, a little after the first launch settles, and only in
    // builds that can report. Either answer is kept; Settings › Privacy changes it later.
    // Then, never at the same time, a crash since last time is offered for reporting, once.
    // Last, once (on a fresh install, and once for everyone who had the app before it), whether
    // to try liquid motion; Settings › Appearance changes it later.
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
        val crash = withContext(Dispatchers.IO) { CrashLog.unasked(graph.context) }
        if (crash != null) {
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
        if (graph.settings.current.liquidMotionAsked) return@LaunchedEffect
        // Over nothing else: no alert, sheet, menu, update offer or watch page, and the app settled a moment.
        val quiet = {
            app.alert == null && app.report == null && app.sheetItem == null && app.saveTarget == null && app.menuItem == null &&
                app.renameTarget == null && !app.sheet.expanded && !updateGate.showing
        }
        kotlinx.coroutines.delay(3_000)
        snapshotFlow { quiet() }.first { it }
        kotlinx.coroutines.delay(1_000)
        snapshotFlow { quiet() }.first { it }
        askLiquid = true
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
                    enterTransition = { if (reduce) EnterTransition.None else push(push, true, app.tabSwitchEntry, dim) },
                    exitTransition = { if (reduce) ExitTransition.None else pushAway(push, true, app.tabSwitchEntry, dim) },
                    popEnterTransition = { if (reduce) EnterTransition.None else push(push, false, app.tabSwitchEntry, dim) },
                    popExitTransition = { if (reduce) ExitTransition.None else pushAway(push, false, app.tabSwitchEntry, dim) },
                    // The edge swipe seeks these with the finger, and on release the rest plays on from there.
                    predictivePopEnterTransition = { if (reduce) EnterTransition.None else swipeBackIn(push, app.tabSwitchEntry, dim) },
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
            UpdateHost(app, updateGate)
            AlertHost(app)
            if (askLiquid) {
                LiquidMotionPrompt { on ->
                    askLiquid = false
                    app.graph.settings.update { it.copy(liquidMotion = on, liquidMotionAsked = true) }
                }
            }
            NoticeHost(app, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp))
        }
    }
}

/**
 * How pages push: the new page slides in whole from the right over the old one, which drifts
 * [drift] of the way left and dims toward the black behind it rather than fading away. Push and
 * pop share one spring, as UIKit's do; the dim rides the same one so the two finish together.
 */
private class PushStyle(val spring: androidx.compose.animation.core.FiniteAnimationSpec<IntOffset>, val dimSpring: androidx.compose.animation.core.FiniteAnimationSpec<Float>, val drift: Float, val liquid: Boolean)

/**
 * iOS push: critically damped, the page beneath drifting a third of the way. Kept here rather
 * than in Motion.kt: it's the one IntOffset spring, and only pushes use it.
 */
private val ClassicPush = PushStyle(
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold),
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f),
    drift = 1f / 3f,
    liquid = false,
)

/** The mock's push: its spring (response 0.5, damping 0.88, a whisker of overshoot), the page beneath drifting 28%. */
private val LiquidPush = PushStyle(Sp.Push.spec(IntOffset.VisibilityThreshold), Sp.Push.unit, drift = 0.28f, liquid = true)

/** How opaque the page beneath stays over black: about UIKit's dimming in each mode, and the mock's 40% in both. */
private const val PUSH_DIM_DARK = 0.7f
private const val PUSH_DIM_LIGHT = 0.88f
private const val PUSH_DIM_LIQUID = 0.6f

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
private fun AnimatedContentTransitionScope<NavBackStackEntry>.push(style: PushStyle, forward: Boolean, tabSwitchEntry: String?, dim: Float): EnterTransition {
    if (isTabSwitch(tabSwitchEntry)) {
        if (!forward) return EnterTransition.None
        // Liquid, a tab switch crossfades as the mock's do, the incoming page growing from 97%.
        return if (style.liquid) fadeIn(Sp.TabFade.unit) + scaleIn(Sp.TabFade.unit, initialScale = 0.97f) else fadeIn(tween(160))
    }
    return if (forward) slideInHorizontally(style.spring) { it }
    else slideInHorizontally(style.spring) { drift(it, style) } + fadeIn(style.dimSpring, initialAlpha = dim)
}

/** The page beneath's offset at full drift, for a page [width] wide. */
private fun drift(width: Int, style: PushStyle): Int = if (style.liquid) -(width * style.drift).toInt() else -width / 3

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushAway(style: PushStyle, forward: Boolean, tabSwitchEntry: String?, dim: Float): ExitTransition {
    if (isTabSwitch(tabSwitchEntry)) return if (forward) ExitTransition.KeepUntilTransitionsFinished else fadeOut(tween(160))
    return if (forward) slideOutHorizontally(style.spring) { drift(it, style) } + fadeOut(style.dimSpring, targetAlpha = dim)
    else slideOutHorizontally(style.spring) { it }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.swipeBackIn(style: PushStyle, tabSwitchEntry: String?, dim: Float): EnterTransition {
    if (isTabSwitch(tabSwitchEntry)) return EnterTransition.None
    val spec = tween<IntOffset>(SWIPE_BACK_MS, easing = SwipeBackEasing)
    return slideInHorizontally(spec) { drift(it, style) } + fadeIn(tween(SWIPE_BACK_MS, easing = SwipeBackEasing), initialAlpha = dim)
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.swipeBackOut(tabSwitchEntry: String?): ExitTransition {
    if (isTabSwitch(tabSwitchEntry)) return fadeOut(tween(SWIPE_BACK_MS, easing = LinearEasing))
    return slideOutHorizontally(tween(SWIPE_BACK_MS, easing = SwipeBackEasing)) { it }
}


/**
 * The system back swipe as it happens: where the finger is, and the mock's `pd`, how far (px) the
 * page's edge is dented under it. Held, the dent deepens with the finger's speed (12 dp, plus
 * 2% of the speed up to 26 dp more) on the snappy spring; let go, it springs flat on the jelly
 * one, wobbling. Read only while drawing.
 */
@Stable
private class BackSwipe {
    var active = false
    var touchY by mutableFloatStateOf(0f)
    val dent = Animatable(0f)
    val speed = SmoothedVelocity()
}

@Composable
private fun rememberBackSwipe(enabled: Boolean): BackSwipe {
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
 * A page that slides like the mock's, with a soft leading edge (its `edgePath`): pushed in, the
 * edge bulges ahead by the slide's speed (1.2% of it in px/s, up to 40 dp; a pop dents it, up
 * to 30 dp) and relaxes as it lands; under a back swipe it dents where the finger holds it
 * ([BackSwipe.dent]) and springs flat with a wobble when let go. The bump is a Gaussian 150 dp
 * across. A soft shadow runs along it. Only the outline deforms (the clip is the layer's), all
 * in the draw phase. Reduce motion: none.
 */
@Composable
private fun LiquidPage(swipe: BackSwipe, content: @Composable () -> Unit) {
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
