package app.oriel.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.oriel.ui.theme.LocalIsDark
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
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
import androidx.compose.ui.platform.LocalDensity
import app.oriel.ui.theme.LocalLiquidMotion
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
import app.oriel.ui.components.SheetScrimHost
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
import app.oriel.ui.screens.AccountSwitcher
import app.oriel.ui.screens.NotificationsScreen
import app.oriel.ui.navigation.PlaylistRoute
import app.oriel.ui.navigation.PlaylistsRoute
import app.oriel.ui.navigation.SearchRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.navigation.SettingsSectionRoute
import app.oriel.ui.screens.SettingsSectionScreen
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
                app.renameTarget == null && !app.accounts && !app.sheet.expanded && !updateGate.showing
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

    // The tab bar draws in while the reader scrolls on down a page and comes back as they scroll up.
    var barCompact by remember { mutableStateOf(false) }
    LaunchedEffect(page) { barCompact = false }
    val barScroll = with(LocalDensity.current) {
        val down = 32.dp.toPx()
        val up = 12.dp.toPx()
        remember(down, up) {
            object : NestedScrollConnection {
                var run = 0f
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val dy = consumed.y
                    if (dy == 0f) return Offset.Zero
                    // A change of direction starts the count again.
                    run = if (run * dy < 0f) dy else run + dy
                    if (run < -down) barCompact = true else if (run > up) barCompact = false
                    return Offset.Zero
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalAppState provides app,
        LocalAppBackdrop provides if (record) backdrop else null,
        LocalTabBarVisible provides showTabBar,
        LocalWindowLayout provides rememberWindowLayout(),
    ) {
        if (inPip) {
            PlayerHost(app, withTabBar = false, hidden = false, inPip = true)
            return@CompositionLocalProvider
        }
        Box(Modifier.fillMaxSize()) {
            // Black behind the pages, not the page colour: the dim is the page beneath fading toward
            // it, and on light the page colour would have washed it out to white instead. Every
            // page paints its own background, and nothing fades both pages at once (see below).
            Box(Modifier.fillMaxSize().then(if (record) Modifier.layerBackdrop(backdrop) else Modifier).background(Color.Black).nestedScroll(barScroll)) {
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
                    composable<ShortsRoute> { LiquidPage(backSwipe) { it.toRoute<ShortsRoute>().let { r -> ShortsScreen(r.startId, r.queue) } } }
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
                    composable<SettingsSectionRoute> { LiquidPage(backSwipe) { SettingsSectionScreen(it.toRoute<SettingsSectionRoute>().section) } }
                    composable<LoginRoute> { LiquidPage(backSwipe) { LoginScreen(it.toRoute<LoginRoute>().reauth) } }
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
                            searchField = app.searchField,
                            compact = barCompact,
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
            AccountSwitcher(app)
            SheetScrimHost()

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
