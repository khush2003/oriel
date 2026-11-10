package app.oriel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import app.oriel.core.model.Channel
import app.oriel.core.model.ChannelPage
import app.oriel.core.model.ChannelTab
import app.oriel.core.model.Chip
import app.oriel.core.model.SearchPage
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.ui.navigation.LoginRoute
import app.oriel.ui.screens.ChannelModel
import app.oriel.ui.screens.SearchModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.ChannelRef
import app.oriel.core.model.Feed
import app.oriel.core.model.ShortInfo
import app.oriel.core.model.ShortSound
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.ui.navigation.ChannelRoute
import app.oriel.ui.navigation.ChannelsRoute
import app.oriel.ui.navigation.HistoryRoute
import app.oriel.ui.navigation.LikedRoute
import app.oriel.ui.navigation.LocalPlaylistRoute
import app.oriel.ui.navigation.NotificationsRoute
import app.oriel.ui.navigation.PlaylistRoute
import app.oriel.ui.navigation.PlaylistsRoute
import app.oriel.ui.navigation.SearchRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.navigation.ShortsRoute
import app.oriel.ui.navigation.Tab
import app.oriel.ui.screens.ShortsModel
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Every page on a tablet: landscape (a 10-inch, 1280 x 800dp), portrait (800 x 1280dp), and a
 * small tablet (600 x 960dp), in the same offline frame as [ScreenshotTest]. Images land in
 * `build/screenshots/tablet_*`; the layout rules themselves are asserted in [WindowLayoutTest].
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = LANDSCAPE)
class TabletScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val graph get() = app.graph
    private var appState: AppState? = null

    private fun settle(rounds: Int = 16) {
        repeat(rounds) {
            Thread.sleep(25)
            ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeBy(120)
        }
    }

    private fun seedLibrary() {
        val lib = graph.library
        Fixtures.videos.take(12).forEach { lib.addHistory(it) }
        lib.savePosition(Fixtures.videos[0].id, 300_000, 1_260_000)
        Fixtures.videos.drop(2).take(5).forEach { lib.setLiked(it, true) }
        Fixtures.videos.drop(6).take(3).forEach { lib.setWatchLater(it, true) }
        Fixtures.subscriptions.forEach { lib.setSubscribed(it, true) }
        lib.createPlaylist("Cabin inspiration", Fixtures.videos.take(5))
        lib.setPlaylistSaved(Fixtures.savedPlaylist, true)
        listOf("bread recipe", "night train", "lighthouse").forEach { lib.addSearch(it) }
    }

    private fun shoot(
        name: String,
        theme: ThemeMode = ThemeMode.DARK,
        playing: Boolean = false,
        setup: suspend (AppState) -> Unit = {},
        after: () -> Unit = {},
        screen: Boolean = false,
    ) {
        rule.mainClock.autoAdvance = false
        graph.pages.write("home-false", Feed.serializer(), Fixtures.homeFeed)
        rule.setContent { Frame(theme, playing, setup) }
        settle()
        after()
        settle(8)
        if (screen) captureScreenRoboImage("build/screenshots/tablet_$name.png")
        else rule.onRoot().captureRoboImage("build/screenshots/tablet_$name.png")
    }

    @Composable
    private fun Frame(theme: ThemeMode, playing: Boolean, setup: suspend (AppState) -> Unit) {
        OrielTheme(theme, reduceAnimation = true, liquidGlass = true) {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val activity = androidx.activity.compose.LocalActivity.current!!
            val connection = remember { PlayerConnection(activity, graph) }
            val state = remember { AppState(graph, nav, connection, scope, activity) }
            appState = state
            LaunchedEffect(Unit) {
                if (playing) {
                    state.watch.preview(Fixtures.watch, Fixtures.comments, Fixtures.storyboard)
                    connection.preview(listOf(Fixtures.watch.video!!) + Fixtures.videos.drop(1).take(3), 0, playing = true, positionMs = 300_000)
                }
                nav.currentBackStackEntryFlow.first().lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                setup(state)
            }
            OrielRoot(state, inPip = false)
        }
    }

    // ---- landscape ---------------------------------------------------------------------------

    @Test fun home() { seedLibrary(); shoot("01_home") }
    @Test fun homeLight() { seedLibrary(); shoot("02_home_light", ThemeMode.LIGHT) }

    @Test fun subscriptions() {
        seedLibrary()
        graph.pages.write("subs-false", Feed.serializer(), Feed(shelves = Fixtures.homeFeed.shelves.drop(1)))
        shoot("03_subscriptions", setup = { it.selectTab(Tab.SUBSCRIPTIONS) })
    }

    @Test fun searchIdle() { seedLibrary(); shoot("04_search_idle", setup = { it.navigate(SearchRoute()) }) }
    @Test fun you() { seedLibrary(); shoot("05_you", setup = { it.selectTab(Tab.YOU) }) }
    @Test fun settings() = shoot("06_settings", setup = { it.navigate(SettingsRoute) })
    @Test fun history() { seedLibrary(); shoot("07_history", setup = { it.navigate(HistoryRoute) }) }
    @Test fun liked() { seedLibrary(); shoot("08_liked", setup = { it.navigate(LikedRoute) }) }
    @Test fun playlists() { seedLibrary(); shoot("09_playlists", setup = { it.navigate(PlaylistsRoute) }) }
    @Test fun channels() { seedLibrary(); shoot("10_channels", setup = { it.navigate(ChannelsRoute) }) }

    @Test fun localPlaylist() {
        seedLibrary()
        val id = graph.library.state.value.localPlaylists.first().id
        shoot("11_local_playlist", setup = { it.navigate(LocalPlaylistRoute(id)) })
    }

    @Test fun notifications() {
        shoot("12_notifications", setup = { it.navigate(NotificationsRoute) })
    }

    @Test fun channelLoading() {
        TestApp.hang = true
        try { shoot("13_channel_loading", setup = { it.navigate(ChannelRoute("UCfixture0")) }) } finally { TestApp.hang = false }
    }

    @Test fun playlistLoading() {
        TestApp.hang = true
        try { shoot("14_playlist_loading", setup = { it.navigate(PlaylistRoute("PLfixture1")) }) } finally { TestApp.hang = false }
    }

    /** The watch page in two panes: the video and its details beside what to watch next. */
    @Test fun watch() = shoot("20_watch", playing = true, setup = { it.sheet.progress.snapTo(1f) })

    @Test fun watchLight() = shoot("21_watch_light", ThemeMode.LIGHT, playing = true, setup = { it.sheet.progress.snapTo(1f) })

    @Test fun comments() = shoot("22_comments", playing = true, setup = { it.sheet.progress.snapTo(1f) }, after = {
        rule.onAllNodes(hasText("Comments")).onFirst().performClick()
    })

    @Test fun fullscreen() = shoot("23_fullscreen", playing = true, setup = { it.sheet.progress.snapTo(1f); it.sheet.fullscreen = true })

    /** The collapsed player floats over a wide page as a picture-in-picture window. */
    @Test fun miniPlayer() { seedLibrary(); shoot("24_mini_player", playing = true) }

    @Test fun shorts() {
        val short = Fixtures.shorts[0]
        ShortsModel.previewInfo = mapOf(
            short.id to ShortInfo(
                short.id, short.title + " #pasta #kitchen", ChannelRef("Harbor Workshop", "UCfixture0", "https://yt3.ggpht.com/fixture-avatar0=s88"),
                likeCount = "12K", commentsCount = "318", commentsToken = "fixture",
                sound = ShortSound("Lakeside evening (original)", "https://yt3.ggpht.com/fixture-avatar0=s88", "fixture"),
            ),
        )
        try {
            shoot("25_shorts", setup = { it.navigate(ShortsRoute(startId = short.id)) })
        } finally {
            ShortsModel.previewInfo = emptyMap()
        }
    }

    /** A video's ⋮ sheet: a centred card on a wide screen, not a sheet the width of the window. */
    @Test fun itemSheet() {
        seedLibrary()
        shoot("26_item_sheet", screen = true, after = { appState!!.sheetItem = Fixtures.videos[1] })
    }

    @After fun clearPreviews() { SearchModel.previewPage = null; ChannelModel.previewPage = null }

    // ---- pages that need a page in hand ----------------------------------------------------

    private fun seedSearchResults() {
        // Videos with a channel and a playlist among them: the grid keeps those on rows of their own.
        SearchModel.previewPage = SearchPage(Feed(
            shelves = listOf(Shelf(items = listOf(Fixtures.subscriptions[0], Fixtures.savedPlaylist) + Fixtures.videos.take(9), kind = ShelfKind.FEED)),
            chips = listOf(Chip("All", selected = true), Chip("Videos", "videos"), Chip("Shorts", "shorts"), Chip("Live", "live")),
        ))
    }

    @Test fun searchResults() { seedSearchResults(); shoot("15_search_results", setup = { it.navigate(SearchRoute("cabin")) }) }

    private fun loadedChannel() {
        val c = Fixtures.subscriptions[0]
        ChannelModel.previewPage = ChannelPage(
            Channel(c.id, c.title, c.avatar, handle = "@harborworkshop", subscribersText = c.subscribersText, videosText = "212 videos", verified = true),
            banner = "https://yt3.ggpht.com/fixture-banner0=w1060",
            tabs = listOf(ChannelTab("Home", null), ChannelTab("Videos", "videos"), ChannelTab("Shorts", "shorts"), ChannelTab("Playlists", "lists")),
            feed = Feed(shelves = listOf(Shelf(items = Fixtures.videos.take(9), kind = ShelfKind.FEED))), selectedTab = "Home",
        )
    }

    @Test fun channelLoaded() { loadedChannel(); shoot("16_channel", setup = { it.navigate(ChannelRoute("UCfixture0")) }) }

    @Test fun channelLoadedLight() { loadedChannel(); shoot("16b_channel_light", ThemeMode.LIGHT, setup = { it.navigate(ChannelRoute("UCfixture0")) }) }

    /** The sign-in page is Google's own, in a WebView; what's Oriel's is the bar and the frame around it. */
    @Test fun login() = shoot("17_login", setup = { it.navigate(LoginRoute) })

    // ---- the watch page's two panes ----------------------------------------------------------

    /** What to watch next is in the right pane, beside the video's column, not under it. */
    @Test fun splitWatchPutsRelatedBesideTheVideo() {
        shoot("27_split_watch", playing = true, setup = { it.sheet.progress.snapTo(1f) })
        // The Home page is still under the watch page, so a title can match twice: ask about the watch page's own.
        val details = rule.onNodeWithText("1.9M subscribers").fetchSemanticsNode().boundsInRoot
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val related = rule.onAllNodes(hasText("I tried cooking every recipe from a 1950s cookbook")).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("the details stay in the video's column", details.right <= width * 0.65f)
        assertTrue("related is listed past the video's column", related.any { it.left >= width * 0.63f })
        assertTrue("and starts at the top, beside the picture", related.filter { it.left >= width * 0.63f }.all { it.top < details.top })
    }

    @Test fun splitWatchControlsWork() {
        shoot("28_split_controls", playing = true, setup = { it.sheet.progress.snapTo(1f) })
        rule.onNodeWithContentDescription("Full screen").performClick()
        settle(12)
        assertTrue("full screen from the split page", appState!!.sheet.fullscreen)
        rule.runOnUiThread { appState!!.sheet.fullscreen = false }
        settle(12)
        assertFalse(appState!!.sheet.fullscreen)
        // A tap on the picture brings the controls back.
        rule.onRoot().performTouchInput { click(Offset(800f, 300f)) }
        settle(8)
        rule.onNodeWithContentDescription("Minimise").performClick()
        settle(16)
        assertFalse("Minimise collapses to the floating player", appState!!.sheet.expanded)
    }

    /** A drag down on the picture takes the page down, as on a phone, and the window floats where it did. */
    @Test fun splitWatchSwipeDownCollapses() {
        shoot("29_split_swipe", playing = true, setup = { it.sheet.progress.snapTo(1f) }, after = {
            assertTrue(appState!!.sheet.expanded)
            // Over the picture (the left 64%): xhdpi, 2px to the dp.
            rule.onRoot().performTouchInput { swipe(Offset(800f, 300f), Offset(800f, 1300f), 300) }
        })
        assertFalse("a swipe down on the picture collapses the page", appState!!.sheet.expanded)
    }

    // ---- turning and resizing live -----------------------------------------------------------

    /**
     * The window turned from landscape to portrait while the app is open (or split to half): the
     * feed re-lays itself out in place, three columns to two, with no new composition from outside.
     */
    @Test fun turningTheTabletRelaysTheFeed() {
        seedLibrary()
        // Tall, so the feed's second row of titles is on screen to be measured.
        var window by mutableStateOf(DpSize(1280.dp, 1500.dp))
        val info = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize get() = IntSize((window.width.value * 2).toInt(), (window.height.value * 2).toInt())
        }
        rule.mainClock.autoAdvance = false
        graph.pages.write("home-false", Feed.serializer(), Fixtures.homeFeed)
        rule.setContent {
            CompositionLocalProvider(LocalWindowInfo provides info) {
                Box(Modifier.requiredSize(window.width, window.height)) { Frame(ThemeMode.DARK, false) {} }
            }
        }
        settle()
        fun y(title: String) = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        val first = "Restoring a rusted bicycle back to new"
        val third = "Learning the piano in one year: progress diary"
        assertTrue("three across in landscape", y(third) == y(first))
        window = DpSize(800.dp, 1500.dp)
        settle(20)
        rule.onRoot().captureRoboImage("build/screenshots/tablet_43_turned_portrait.png")
        assertTrue("two across once turned: the third wraps", y(third) > y(first))
        window = DpSize(412.dp, 1500.dp)
        settle(20)
        assertTrue("one column in a phone-width window", rule.onNodeWithText(first).fetchSemanticsNode().boundsInRoot.width > 300f)
        rule.onRoot().captureRoboImage("build/screenshots/tablet_44_split_to_phone_width.png")
    }

    // ---- portrait ----------------------------------------------------------------------------

    @Test @Config(qualifiers = PORTRAIT)
    fun homePortrait() { seedLibrary(); shoot("30_home_portrait") }

    @Test @Config(qualifiers = PORTRAIT)
    fun searchIdlePortrait() { seedLibrary(); shoot("31_search_idle_portrait", setup = { it.navigate(SearchRoute()) }) }

    @Test @Config(qualifiers = PORTRAIT)
    fun settingsPortrait() = shoot("32_settings_portrait", setup = { it.navigate(SettingsRoute) })

    @Test @Config(qualifiers = PORTRAIT)
    fun youPortrait() { seedLibrary(); shoot("33_you_portrait", setup = { it.selectTab(Tab.YOU) }) }

    @Test @Config(qualifiers = PORTRAIT)
    fun watchPortrait() = shoot("34_watch_portrait", playing = true, setup = { it.sheet.progress.snapTo(1f) })

    @Test @Config(qualifiers = PORTRAIT)
    fun historyPortrait() { seedLibrary(); shoot("35_history_portrait", setup = { it.navigate(HistoryRoute) }) }

    // ---- a small tablet ----------------------------------------------------------------------

    @Test @Config(qualifiers = SMALL)
    fun homeSmall() { seedLibrary(); shoot("40_home_small") }

    @Test @Config(qualifiers = SMALL)
    fun watchSmall() = shoot("41_watch_small", playing = true, setup = { it.sheet.progress.snapTo(1f) })

    /** A tablet's window split to a phone's width falls back to the phone layout. */
    @Test @Config(qualifiers = "w412dp-h800dp-xhdpi")
    fun splitScreenIsPhoneLayout() {
        seedLibrary()
        shoot("42_split_phone_width")
        assertEquals(1, WindowLayout(412.dp, 800.dp, 800.dp).columns(PageWidth.Feed))
    }
}

private const val LANDSCAPE = "w1280dp-h800dp-xhdpi"
private const val PORTRAIT = "w800dp-h1280dp-xhdpi"
private const val SMALL = "w600dp-h960dp-xhdpi"
