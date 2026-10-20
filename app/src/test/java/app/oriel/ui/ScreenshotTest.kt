package app.oriel.ui

import app.oriel.ui.navigation.NotificationsRoute
import app.oriel.core.model.InboxPage
import app.oriel.core.model.InboxNotification
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.AppGraph
import app.oriel.TestApp
import app.oriel.core.model.Feed
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.core.model.ChannelRef
import app.oriel.core.model.ShortInfo
import app.oriel.ui.navigation.SearchRoute
import app.oriel.ui.navigation.ShortsRoute
import app.oriel.ui.screens.ShortsModel
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.navigation.Tab
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.captureRoboImage
import app.oriel.core.model.ShortSound
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import app.oriel.ui.navigation.tabRoot
import app.oriel.ui.navigation.HomeRoute
import app.oriel.ui.navigation.LocalPlaylistRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The whole app frame (OrielRoot) rendered offline with fixture data: pages
 * come from the stale-while-revalidate disk cache and the local library, the
 * player from the connection's preview state. Images are drawn by FakeArt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val graph get() = app.graph
    private var glassOn = true
    private var appState: AppState? = null

    /** Runs the clock by hand (shimmers never idle) and lets image loads land. */
    private fun settle(rounds: Int = 16) {
        repeat(rounds) {
            Thread.sleep(25)
            ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeBy(120)
        }
    }

    private fun seedLibrary() {
        val lib = graph.library
        Fixtures.videos.take(8).forEach { lib.addHistory(it) }
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
        homeFeed: Feed? = Fixtures.homeFeed,
        playing: Boolean = true,
        setup: suspend (AppState) -> Unit = {},
        after: () -> Unit = {},
    ) {
        rule.mainClock.autoAdvance = false
        homeFeed?.let { graph.pages.write("home-false", Feed.serializer(), it) }
        rule.setContent { Frame(theme, playing, setup) }
        settle()
        after()
        settle(8)
        println("images for $name: ${TestApp.started.get()} started, ${TestApp.loaded.get()} loaded, ${TestApp.failed.get()} failed")
        rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Composable
    private fun Frame(theme: ThemeMode, playing: Boolean, setup: suspend (AppState) -> Unit) {
        OrielTheme(theme, reduceAnimation = true, liquidGlass = glassOn) {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val activity = LocalContext.current as Activity
            val connection = remember { PlayerConnection(activity, graph) }
            val state = remember { AppState(graph, nav, connection, scope, activity) }
            appState = state
            LaunchedEffect(Unit) {
                if (playing) {
                    state.watch.preview(Fixtures.watch, Fixtures.comments, Fixtures.storyboard)
                    connection.preview(listOf(Fixtures.watch.video!!) + Fixtures.videos.drop(1).take(3), 0, playing = true, positionMs = 300_000)
                }
                setup(state)
            }
            OrielRoot(state, inPip = false)
        }
    }

    @Test fun home() {
        seedLibrary()
        shoot("01_home")
    }

    @Test fun homeLight() {
        seedLibrary()
        shoot("02_home_light", theme = ThemeMode.LIGHT)
    }

    @Test fun watch() = shoot("03_watch", setup = { it.sheet.progress.snapTo(1f) })

    @Test fun comments() = shoot("04_comments", setup = { it.sheet.progress.snapTo(1f) }, after = {
        rule.onAllNodes(hasText("Comments")).onFirst().performClick()
    })

    @Test fun search() {
        seedLibrary()
        shoot("05_search", setup = { it.navigate(SearchRoute()) })
    }

    @Test fun you() {
        seedLibrary()
        shoot("06_you", setup = { it.selectTab(Tab.YOU) })
    }

    @Test fun subscriptions() {
        seedLibrary()
        graph.pages.write("subs-false", Feed.serializer(), Feed(shelves = Fixtures.homeFeed.shelves.drop(1)))
        shoot("07_subscriptions", setup = { it.selectTab(Tab.SUBSCRIPTIONS) })
    }

    @Test fun localPlaylistReorder() {
        seedLibrary()
        val id = graph.library.state.value.localPlaylists.first().id
        shoot("24_playlist_reorder", playing = false, setup = { it.navigate(LocalPlaylistRoute(id)) }, after = {
            settle(4)
            rule.onNodeWithContentDescription("Reorder").performClick()
        })
    }

    /** The Reorder gesture itself: hold the first row's handle, drag it two rows down, let go. */
    @Test fun localPlaylistDragReorders() {
        seedLibrary()
        val id = graph.library.state.value.localPlaylists.first().id
        val before = graph.library.state.value.localPlaylists.first().videos.map { it.id }
        shoot("25_playlist_dragging", playing = false, setup = { it.navigate(LocalPlaylistRoute(id)) }, after = {
            settle(4)
            rule.onNodeWithContentDescription("Reorder").performClick()
            settle(4)
            val handles = rule.onAllNodes(hasContentDescription("Move"), useUnmergedTree = true).fetchSemanticsNodes()
            val row = handles[1].boundsInRoot.center.y - handles[0].boundsInRoot.center.y
            val grip = handles[0].boundsInRoot.center
            rule.onRoot().performTouchInput {
                down(grip)
                // In small steps, as a finger moves, past two neighbours' middles.
                repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(0f, row * 2.1f / 20)); advanceEventTime(16) }
            }
            settle(6)
        })
        // Mid-drag: nothing written yet.
        org.junit.Assert.assertEquals(before, graph.library.state.value.localPlaylists.first().videos.map { it.id })
        rule.onRoot().performTouchInput { up() }
        settle(8)
        val after = graph.library.state.value.localPlaylists.first().videos.map { it.id }
        org.junit.Assert.assertEquals(listOf(before[1], before[2], before[0]) + before.drop(3), after)
    }

    @Test fun notificationsInbox() {
        val items = Fixtures.videos.take(6).mapIndexed { i, v ->
            InboxNotification(
                id = "n$i",
                message = if (i == 2) "${v.channel!!.name} replied to your comment: “Lovely work on the joinery.”" else "${v.channel!!.name} uploaded: ${v.title}",
                sentText = listOf("12 minutes ago", "1 hour ago", "3 hours ago", "Yesterday", "2 days ago", "4 days ago")[i],
                read = i >= 2,
                avatar = v.channel!!.avatar,
                thumbnail = v.thumbnail,
                videoId = v.id,
            )
        }
        graph.pages.write("inbox-0", InboxPage.serializer(), InboxPage(items))
        shoot("26_notifications", playing = false, setup = { it.navigate(NotificationsRoute) })
    }

    @Test fun settings() = shoot("08_settings", playing = false, setup = { it.navigate(SettingsRoute) })

    /** Mid-scrub: the storyboard frame and time ride above the thumb; the transport steps aside. */
    @Test fun scrubbing() = shoot("12_scrub", setup = { it.sheet.progress.snapTo(1f) }, after = {
        // The scrubber's centre line: 32dp up from the bottom of the 16:9 picture (xhdpi, 2px per dp).
        rule.onRoot().performTouchInput { down(Offset(200f, 431f)); moveTo(Offset(520f, 431f)) }
    })

    /** A held press on the picture plays at 2x and says so. */
    @Test fun holdForDoubleSpeed() = shoot("13_hold_2x", setup = { it.sheet.progress.snapTo(1f) }, after = {
        rule.onRoot().performTouchInput { down(Offset(70f, 320f)) }
    })

    /** Fullscreen on a phone turned sideways: the frame is wider than tall. */
    @Test @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun fullscreenLandscape() = shoot("10_fullscreen", setup = { it.sheet.progress.snapTo(1f); it.sheet.fullscreen = true })

    /** The watch page on a landscape screen (tablets and foldables rotate freely). */
    @Test @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun watchLandscape() = shoot("11_watch_landscape", setup = { it.sheet.progress.snapTo(1f) })

    /** Liquid glass off: BitChord's bar, one frosted pill with Search among the tabs. */
    @Test fun homeGlassOff() {
        seedLibrary()
        glassOn = false
        shoot("14_home_glass_off")
    }

    /** Light page from the artwork: the accent (replies, links) must still read on it. */
    @Test fun commentsLight() = shoot("15_comments_light", theme = ThemeMode.LIGHT, setup = { it.sheet.progress.snapTo(1f) }, after = {
        rule.onAllNodes(hasText("Comments")).onFirst().performClick()
    })

    /** Shorts: the picture stops above a dark tab bar; the poster stands in until a first frame. */
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
            shoot("16_shorts", playing = false, setup = { it.navigate(ShortsRoute(startId = short.id)) })
        } finally {
            ShortsModel.previewInfo = emptyMap()
        }
    }

    /** Fullscreen, then one finger down the picture: back to the watch page, as YouTube does. */
    @Test @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun swipeDownLeavesFullscreen() {
        shoot("17_fullscreen_swiped_down", setup = { it.sheet.progress.snapTo(1f); it.sheet.fullscreen = true }, after = {
            assertTrue("starts in fullscreen", appState!!.sheet.fullscreen)
            rule.onRoot().performTouchInput { swipe(Offset(width * 0.3f, height * 0.25f), Offset(width * 0.3f, height * 0.75f), 250) }
        })
        assertFalse("a swipe down leaves fullscreen", appState!!.sheet.fullscreen)
    }

    /** A short swipe isn't enough: the picture springs back and fullscreen stays. */
    @Test @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun shortSwipeKeepsFullscreen() {
        shoot("18_fullscreen_short_swipe", setup = { it.sheet.progress.snapTo(1f); it.sheet.fullscreen = true }, after = {
            rule.onRoot().performTouchInput { swipe(Offset(width * 0.3f, height * 0.4f), Offset(width * 0.3f, height * 0.4f + 40f), 400) }
        })
        assertTrue("fullscreen holds", appState!!.sheet.fullscreen)
    }

    /** Back with the comments open closes the comments, not the watch page under them. */
    @Test fun backClosesPanelFirst() {
        fun panelOpen() = rule.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes().isNotEmpty()
        shoot("19_back_closes_panel", setup = { it.sheet.progress.snapTo(1f) }, after = {
            rule.onAllNodes(hasText("Comments")).onFirst().performClick()
            settle(8)
            assertTrue("comments open", panelOpen())
            androidx.test.espresso.Espresso.pressBack()
        })
        assertFalse("back closed the comments", panelOpen())
        assertTrue("the watch page is still open", appState!!.sheet.expanded)
    }

    /**
     * Not a check, a film strip: the collapse from the watch page to the mini player, one
     * frame every 32 ms, with the sheet's progress per frame, for judging the motion by eye.
     * Opt in with -Pframes=true.
     */
    @Test fun collapseFrames() {
        if (System.getProperty("oriel.frames") != "true") return
        val log = StringBuilder()
        shoot("22_collapse_start", setup = { it.sheet.progress.snapTo(1f) }, after = {
            appState!!.sheet.collapse()
            for (i in 0 until 24) {
                ShadowLooper.idleMainLooper()
                rule.mainClock.advanceTimeBy(32)
                log.append("frame $i t=${i * 32}ms progress=${"%.4f".format(appState!!.sheet.progress.value)}\n")
                rule.onRoot().captureRoboImage("build/screenshots/frames/collapse_%02d.png".format(i))
            }
        })
        println(log)
    }

    /**
     * A short opened from Home is a page on Home's stack: the tab bar still says Home, and
     * tapping Home goes back to the feed. (It used to navigate to Home, which restored the
     * very stack it was in, short on top, so the tap did nothing.)
     */
    @Test fun homeTabLeavesAShortOpenedFromHome() {
        shoot("23_home_after_short", playing = false, after = {
            appState!!.openShort(Fixtures.videos[0].copy(isShort = true))
            settle(8)
            assertTrue("the short is open", appState!!.nav.currentBackStackEntry!!.destination.hasRoute(ShortsRoute::class))
            assertEquals("and it belongs to Home", null, appState!!.nav.currentBackStackEntry!!.tabRoot())
            appState!!.selectTab(Tab.HOME)
            settle(8)
        })
        assertTrue("back on Home", appState!!.nav.currentBackStackEntry!!.destination.hasRoute(HomeRoute::class))
    }

    /** Home's tab from Search goes Home (it used to restore Search straight back), and Search comes back as it was. */
    @Test fun homeTabLeavesSearch() {
        shoot("33_home_after_search", playing = false, after = {
            appState!!.openSearch()
            settle(8)
            assertTrue("search is open", appState!!.nav.currentBackStackEntry!!.destination.hasRoute(SearchRoute::class))
            appState!!.selectTab(Tab.HOME)
            settle(8)
            assertTrue("back on Home", appState!!.nav.currentBackStackEntry!!.destination.hasRoute(HomeRoute::class))
            appState!!.openSearch()
            settle(8)
        })
        assertTrue("search opens again", appState!!.nav.currentBackStackEntry!!.destination.hasRoute(SearchRoute::class))
    }

    /** A swipe up on the watch page's picture goes fullscreen, the picture growing with the finger. */
    @Test fun swipeUpGoesFullscreen() {
        shoot("34_swiped_up_fullscreen", setup = { it.sheet.progress.snapTo(1f) }, after = {
            rule.onRoot().performTouchInput { swipe(Offset(width * 0.22f, height * 0.16f), Offset(width * 0.22f, height * 0.02f), 220) }
            settle(16)
        })
        assertTrue("a swipe up goes fullscreen", appState!!.sheet.fullscreen)
    }

    /** A small, slow nudge up grows the picture a little and settles back: no fullscreen. */
    @Test fun shortSwipeUpStaysOnTheWatchPage() {
        shoot("35_short_swipe_up", setup = { it.sheet.progress.snapTo(1f) }, after = {
            rule.onRoot().performTouchInput { swipe(Offset(width * 0.22f, height * 0.16f), Offset(width * 0.22f, height * 0.14f), 500) }
            settle(16)
        })
        assertFalse("stays on the watch page", appState!!.sheet.fullscreen)
        assertEquals("still expanded", 1f, appState!!.sheet.progress.value, 0.01f)
    }

    /** Dragging the expanded picture down past halfway lands in the mini player, not stuck midway. */
    @Test fun dragDownMinimizes() {
        shoot("20_drag_minimized", setup = { it.sheet.progress.snapTo(1f) }, after = {
            rule.onRoot().performTouchInput { swipe(Offset(width * 0.5f, height * 0.12f), Offset(width * 0.5f, height * 0.8f), 600) }
        })
        assertEquals("settled in the mini player", 0f, appState!!.sheet.progress.value, 0.01f)
    }

    /** Collapsed, the picture floats as a window; a tap on it opens the watch page. */
    @Test fun pipWindowTapExpands() {
        shoot("21_pip_window", setup = { it.sheet.progress.snapTo(0f) })
        rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).performClick()
        settle(16)
        assertEquals("settled on the watch page", 1f, appState!!.sheet.progress.value, 0.01f)
    }

    /** Its play/pause and close are always on it, with no press needed; close stops playback. */
    @Test fun pipWindowShowsCloseAndPlay() {
        shoot("21b_pip_controls", setup = { it.sheet.progress.snapTo(0f) })
        assertTrue("play/pause showing", rule.onAllNodes(hasContentDescription("Pause").or(hasContentDescription("Play"))).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("Close", useUnmergedTree = true).performClick()
        settle(4)
        assertEquals("playback was stopped", 1, appState!!.connection.stopRequests)
    }

    /** Two fingers spread on the window make it bigger, and it stays that size in its corner. */
    @Test fun pipWindowPinchesBigger() {
        shoot("31_pip_pinched", setup = { it.sheet.progress.snapTo(0f) }, after = {
            val window = rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val c = window.center
            val d = window.width * 0.15f
            rule.onRoot().performTouchInput {
                pinch(c - Offset(d, 0f), c - Offset(d * 3, 0f), c + Offset(d, 0f), c + Offset(d * 3, 0f), 400)
            }
            settle(16)
        })
        assertTrue("bigger: ${appState!!.sheet.pipScale}", appState!!.sheet.pipScale > 1.3f)
        assertEquals("not stopped", 0, appState!!.connection.stopRequests)
        assertEquals("still collapsed", 0f, appState!!.sheet.progress.value, 0.01f)
    }

    /** A short sideways flick toward the near edge puts the window away too. */
    @Test fun pipWindowFlickedAtTheEdgeStops() {
        shoot("32_pip_flicked", setup = { it.sheet.progress.snapTo(0f) }, after = {
            val window = rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            rule.onRoot().performTouchInput { swipe(window.center, Offset(window.center.x + window.width * 0.25f, window.center.y), 90) }
            settle(16)
        })
        assertEquals("playback was stopped", 1, appState!!.connection.stopRequests)
    }

    /** A diagonal throw from the top right to the bottom left lands in that corner; it doesn't put the window away. */
    @Test fun pipWindowDiagonalThrowLandsInACorner() {
        shoot("27b_pip_diagonal", setup = { it.sheet.progress.snapTo(0f); it.sheet.pipCorner = 1 }, after = {
            val window = rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            rule.onRoot().performTouchInput { swipe(window.center, Offset(width * 0.02f, height * 0.9f), 180) }
            settle(16)
        })
        assertEquals("bottom-left corner", 2, appState!!.sheet.pipCorner)
        assertEquals("not stopped", 0, appState!!.connection.stopRequests)
    }

    /** Thrown up and to the left, the window lands in the top-left corner and stays playing there. */
    @Test fun pipWindowThrowsToACorner() {
        shoot("27_pip_thrown", setup = { it.sheet.progress.snapTo(0f) }, after = {
            val window = rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("starts bottom right", window.center.x > rule.onRoot().fetchSemanticsNode().size.width / 2)
            rule.onRoot().performTouchInput { swipe(window.center, Offset(width * 0.15f, height * 0.12f), 220) }
            settle(16)
        })
        assertEquals("top-left corner", 0, appState!!.sheet.pipCorner)
        val window = rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("came to rest at the left: $window", window.left < with(rule.density) { 20.dp.toPx() })
        assertEquals("still collapsed", 0f, appState!!.sheet.progress.value, 0.01f)
    }

    /** Pulled a good way off the side and let go, the window is put away and playback stops. */
    @Test fun pipWindowThrownOffTheSideStops() {
        shoot("30_pip_dismissed", setup = { it.sheet.progress.snapTo(0f) }, after = {
            val window = rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            rule.onRoot().performTouchInput { swipe(window.center, Offset(width * 1.05f, window.center.y), 400) }
            settle(16)
        })
        assertEquals("playback was stopped", 1, appState!!.connection.stopRequests)
    }

    @Test fun description() = shoot("28_description", setup = { it.sheet.progress.snapTo(1f) }, after = {
        rule.onAllNodes(hasText("views", substring = true)).onFirst().performClick()
    })

    /** A reply thread pushes over the comments, and Back pops it. */
    @Test fun repliesPush() = shoot("29_replies", setup = { it.sheet.progress.snapTo(1f) }, after = {
        rule.onAllNodes(hasText("Comments")).onFirst().performClick()
        settle(8)
        rule.onAllNodes(hasText("replies", substring = true)).onFirst().performClick()
    })

    @Test fun homeLoading() {
        TestApp.hang = true
        try {
            shoot("09_home_loading", homeFeed = null, playing = false)
        } finally {
            TestApp.hang = false
        }
    }
}
