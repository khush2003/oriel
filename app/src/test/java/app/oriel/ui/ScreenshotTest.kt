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
import androidx.compose.ui.test.swipeUp
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
import app.oriel.core.model.Chapter
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
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import androidx.compose.ui.test.onNodeWithText
import app.oriel.util.ReportKind
import app.oriel.core.model.ShortSound
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import kotlinx.coroutines.flow.first
import org.junit.Test
import app.oriel.ui.navigation.tabRoot
import app.oriel.ui.navigation.HomeRoute
import app.oriel.ui.navigation.ChannelRoute
import app.oriel.ui.navigation.PlaylistRoute
import app.oriel.ui.navigation.YouRoute
import androidx.navigation.toRoute
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
@OptIn(ExperimentalRoborazziApi::class)
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
        /** The whole screen, sheets' windows included, rather than the app's root alone. */
        screen: Boolean = false,
    ) {
        rule.mainClock.autoAdvance = false
        homeFeed?.let { graph.pages.write("home-false", Feed.serializer(), it) }
        rule.setContent { Frame(theme, playing, setup) }
        settle()
        after()
        settle(8)
        println("images for $name: ${TestApp.started.get()} started, ${TestApp.loaded.get()} loaded, ${TestApp.failed.get()} failed")
        if (screen) captureScreenRoboImage("build/screenshots/$name.png")
        else rule.onRoot().captureRoboImage("build/screenshots/$name.png")
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
                // As a first tap would: navigate() drops pushes from a page still settling in.
                nav.currentBackStackEntryFlow.first().lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
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

    /** Comments that failed to load say so, with Try again. */
    @Test fun commentsFailed() = shoot("15b_comments_failed", setup = {
        it.sheet.progress.snapTo(1f)
        it.watch.preview(Fixtures.watch, Fixtures.comments.copy(comments = emptyList(), error = "Couldn't load comments", failed = true), Fixtures.storyboard)
    }, after = {
        rule.onAllNodes(hasText("Comments")).onFirst().performClick()
        settle(8)
        rule.onAllNodes(hasText("Try again")).onFirst().assertExists()
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

    /** Holding for 2x and then moving the finger down is still the hold, not the swipe out. */
    @Test @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun holdThenDragKeepsFullscreen() {
        shoot("18b_fullscreen_hold_drag", setup = { it.sheet.progress.snapTo(1f); it.sheet.fullscreen = true }, after = {
            rule.onRoot().performTouchInput { down(Offset(width * 0.3f, height * 0.25f)) }
            rule.mainClock.advanceTimeBy(900)
            rule.onRoot().performTouchInput {
                for (i in 1..10) moveTo(Offset(width * 0.3f, height * (0.25f + i * 0.05f)))
                up()
            }
        })
        assertTrue("the hold keeps fullscreen", appState!!.sheet.fullscreen)
    }

    /** A slow sideways drag on the picture is nobody's gesture: it doesn't sit out the timeout and turn into the 2x hold. */
    @Test fun slowSidewaysDragIsNoHold() {
        shoot("18c_slow_drag_no_hold", setup = { it.sheet.progress.snapTo(1f) }, after = {
            rule.onRoot().performTouchInput { down(Offset(70f, 320f)) }
            repeat(12) {
                rule.mainClock.advanceTimeBy(50)
                rule.onRoot().performTouchInput { moveBy(Offset(10f, 0f)) }
            }
            settle(2)
            assertEquals("no 2x", 0, rule.onAllNodes(hasText("2×")).fetchSemanticsNodes().size)
            rule.onRoot().performTouchInput { up() }
        })
    }

    /** A tap on a side and then a drag (here a short pull on the fullscreen picture) is the drag's: it doesn't seek. */
    @Test @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun tapThenDragDoesntSeek() {
        shoot("18d_tap_then_drag_no_seek", setup = { it.sheet.progress.snapTo(1f); it.sheet.fullscreen = true }, after = {
            val at = Offset(300f, 400f)
            rule.onRoot().performTouchInput { down(at); up() }
            rule.mainClock.advanceTimeBy(100)
            rule.onRoot().performTouchInput {
                down(at)
                repeat(8) { moveBy(Offset(0f, 6f)); advanceEventTime(16) }
                up()
            }
            settle(2)
            assertEquals("no seek", 0, rule.onAllNodes(hasText("seconds", substring = true)).fetchSemanticsNodes().size)
        })
        assertTrue("still fullscreen", appState!!.sheet.fullscreen)
    }

    /** A pull down out of fullscreen that a pinch takes over from springs back rather than leaving fullscreen. */
    @Test @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun pinchDuringPullKeepsFullscreen() {
        shoot("18e_pinch_during_pull", setup = { it.sheet.progress.snapTo(1f); it.sheet.fullscreen = true }, after = {
            rule.onRoot().performTouchInput {
                down(0, Offset(width * 0.4f, height * 0.2f))
                repeat(10) { moveBy(0, Offset(0f, 22f)); advanceEventTime(16) }
                down(1, Offset(width * 0.6f, height * 0.5f))
                repeat(10) { moveBy(1, Offset(20f, 0f)); moveBy(0, Offset(-20f, 0f)); advanceEventTime(16) }
                up(1)
                up(0)
            }
        })
        assertTrue("still fullscreen", appState!!.sheet.fullscreen)
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

    /** With the comments' list at its top, a pull down on the list pulls the panel down and closes it, as an iOS sheet does. */
    @Test fun pullDownOnListClosesPanel() {
        fun panelOpen() = rule.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes().isNotEmpty()
        shoot("19b_pull_closes_panel", setup = { it.sheet.progress.snapTo(1f) }, after = {
            rule.onAllNodes(hasText("Comments")).onFirst().performClick()
            settle(8)
            assertTrue("comments open", panelOpen())
            // A frame between moves, as on a phone, so the panel moves under the finger as it pulls.
            rule.onRoot().performTouchInput { down(Offset(width * 0.5f, height * 0.6f)) }
            repeat(30) {
                rule.mainClock.advanceTimeBy(16)
                rule.onRoot().performTouchInput { moveBy(Offset(0f, 15f)) }
            }
            rule.mainClock.advanceTimeBy(200)
            rule.onRoot().performTouchInput { up() }
            settle(8)
        })
        assertFalse("the pull closed the comments", panelOpen())
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

    private fun top() = appState!!.nav.currentBackStackEntry!!

    /** Channel B opened from channel A stacks on it, and back goes to A. (It used to replace A.) */
    @Test fun channelFromChannelGoesBackToTheFirst() {
        shoot("60_channel_from_channel", playing = false, after = {
            appState!!.openChannel(CHANNEL_A)
            settle(8)
            appState!!.openChannel(CHANNEL_B)
            settle(8)
            // The same channel again is no push.
            appState!!.openChannel(CHANNEL_B)
            settle(8)
            assertEquals(ChannelRoute(CHANNEL_B), top().toRoute<ChannelRoute>())
            appState!!.back()
            settle(8)
        })
        assertEquals("back on the first channel", ChannelRoute(CHANNEL_A), top().toRoute<ChannelRoute>())
        appState!!.back()
        settle(8)
        assertTrue("then Home", top().destination.hasRoute(HomeRoute::class))
    }

    /** Search, then You, then Home: Home, not Search restored over it; Search comes back from its button. */
    @Test fun searchThenYouThenHome() {
        shoot("61_search_you_home", playing = false, after = {
            appState!!.openSearch()
            settle(8)
            appState!!.selectTab(Tab.YOU)
            settle(8)
            assertTrue("on You", top().destination.hasRoute(YouRoute::class))
            appState!!.selectTab(Tab.HOME)
            settle(8)
        })
        assertTrue("on Home", top().destination.hasRoute(HomeRoute::class))
        appState!!.openSearch()
        settle(8)
        assertTrue("search opens again", top().destination.hasRoute(SearchRoute::class))
    }

    /** Home, a channel, Search, then Home: Home's tab comes back as it was left, on the channel. */
    @Test fun homeChannelSearchHome() {
        shoot("62_home_channel_search_home", playing = false, after = {
            appState!!.openChannel(CHANNEL_A)
            settle(8)
            appState!!.openSearch()
            settle(8)
            assertTrue("search is open", top().destination.hasRoute(SearchRoute::class))
            appState!!.selectTab(Tab.HOME)
            settle(8)
        })
        assertEquals("back on Home's channel", ChannelRoute(CHANNEL_A), top().toRoute<ChannelRoute>())
        appState!!.back()
        settle(8)
        assertTrue("then Home", top().destination.hasRoute(HomeRoute::class))
    }

    /** Shared text: the first YouTube link in it, without the punctuation after it; anything else isn't opened. */
    @Test fun sharedTextOpensTheFirstYouTubeLink() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(ThemeMode.DARK, playing = false) {} }
        settle()
        fun share(text: String) = appState!!.handleIntent(android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_TEXT, text))
        assertTrue(share("Look at this (https://example.com/a) and https://www.youtube.com/playlist?list=PLtest123!"))
        settle(8)
        assertEquals(PlaylistRoute("PLtest123"), top().toRoute<PlaylistRoute>())
        assertTrue(share("“https://youtube.com/channel/$CHANNEL_B”."))
        settle(8)
        assertEquals(ChannelRoute(CHANNEL_B), top().toRoute<ChannelRoute>())
        assertFalse("not a video id", share("https://youtu.be/nope"))
        assertFalse("not YouTube", share("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(share("https://www.youtube.com/hashtag/cats."))
        settle(8)
        assertEquals(SearchRoute("#cats"), top().toRoute<SearchRoute>())
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

    /** A longer, invented chapter list, so the sheet opens at its medium detent with the one playing (4:22) mid-list. */
    private val manyChapters = listOf(
        "Intro", "The plan", "Picking the spot", "Clearing the ground", "Hauling timber", "Rain, again", "Footings", "Foundations",
        "Sill and joists", "Walls and roof", "The stove", "Windows", "Finishing inside", "First night",
    ).zip(listOf(0, 30, 62, 95, 130, 170, 215, 262, 320, 410, 500, 610, 800, 980)) { title, s -> Chapter(title, s * 1000L) }

    /** The chapters sheet, opened from the chapter button on the player: the one playing marked, scrolled into view. */
    private fun chapters(name: String, theme: ThemeMode, playing: String = "Foundations", positionMs: Long = 300_000, after: () -> Unit = {}) = shoot(name, theme = theme, screen = true, setup = {
        it.watch.preview(Fixtures.watch.let { w -> w.copy(page = w.page!!.copy(chapters = manyChapters)) }, Fixtures.comments, Fixtures.storyboard)
        if (positionMs != 300_000L) it.connection.preview(listOf(Fixtures.watch.video!!), 0, playing = true, positionMs = positionMs)
        it.sheet.progress.snapTo(1f)
    }, after = {
        rule.onAllNodes(hasText("·  $playing")).onFirst().performClick()
        settle(10)
        after()
    })

    @Test fun chaptersSheet() {
        chapters("41_chapters_sheet", ThemeMode.DARK)
        assertEquals("the playing chapter is announced as selected", 1, count(hasText("Foundations").and(androidx.compose.ui.test.isSelected())))
    }

    @Test fun chaptersSheetLight() = chapters("42_chapters_sheet_light", ThemeMode.LIGHT)

    /** Dragged up from its medium detent to large. */
    @Test fun chaptersSheetLarge() = chapters("43_chapters_sheet_large", ThemeMode.DARK) {
        rule.onAllNodes(hasText("Done")).onFirst().performTouchInput { swipeUp(startY = centerY, endY = centerY - 900f, durationMillis = 400) }
        settle(12)
    }

    /** The last chapter playing: too near the end to scroll up into the medium detent, so the sheet opens tall. */
    @Test fun chaptersSheetNearTheEnd() = chapters("44_chapters_sheet_end_light", ThemeMode.LIGHT, playing = "First night", positionMs = 1_100_000)

    /** A tap on a chapter closes the sheet. */
    @Test fun chapterTapCloses() {
        chapters("45_chapters_sheet_tapped", ThemeMode.DARK) {
            // The sheet's row, not the shelf of chapters on the page under it.
            val inSheet = androidx.compose.ui.test.SemanticsMatcher("plays from here") { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "Play from here" }
            rule.onAllNodes(hasText("Sill and joists").and(inSheet)).onFirst().performClick()
            settle(12)
        }
        assertEquals("sheet closed", 0, count(hasText("Done")))
    }

    private val report = ReportRequest(ReportKind.PLAYBACK, "https://youtu.be/${Fixtures.videos[0].id}", "It stops after a few seconds and says it couldn't play the video.")

    // Counted, not fetched one-or-throw: a miss in a tree this big (the whole app under the
    // sheet) prints every node into the failure message, which takes the better part of an hour.
    private fun count(matcher: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().size

    /** The sheet scrolled by a finger, with the clock run by hand so the fling settles. */
    private fun scrollSheet() {
        repeat(2) {
            rule.onAllNodes(hasText("What kind of problem", ignoreCase = true)).onFirst().performTouchInput { swipeUp(startY = bottom + 600f, endY = top - 400f, durationMillis = 300) }
            settle(10)
        }
    }

    /** Report a problem from a playback error: the kind and the video already filled in, labelled for TalkBack. */
    @Test fun reportSheet() {
        shoot("36_report_sheet", playing = false, screen = true, setup = { it.report = report })
        assertEquals("what happened field", 1, count(hasContentDescription("What happened?")))
        assertEquals("video link field", 1, count(hasContentDescription("Video link")))
        assertEquals("playback picked", 1, count(hasText("A video won't play").and(androidx.compose.ui.test.isSelected())))
        assertEquals("diagnostics on", 1, count(hasText("Include diagnostics").and(androidx.compose.ui.test.isOn())))
    }

    @Test fun reportSheetLight() = shoot("37_report_sheet_light", theme = ThemeMode.LIGHT, playing = false, screen = true, setup = { it.report = report })

    /** Its end: the diagnostics rows and the two ways to send. */
    @Test fun reportSheetSend() {
        shoot("38_report_sheet_send", playing = false, screen = true, setup = { it.report = report }, after = ::scrollSheet)
        assertEquals("share row", 1, count(hasText("Share…")))
        assertEquals("issue row", 1, count(hasText("Open a GitHub issue")))
    }

    @Test fun reportSheetSendLight() = shoot("39_report_sheet_send_light", theme = ThemeMode.LIGHT, playing = false, screen = true, setup = { it.report = report }, after = ::scrollSheet)

    /** Preview report shows the exact text: their words, the link, then the diagnostics. */
    @Test fun reportPreview() {
        shoot("40_report_preview", playing = false, screen = true, setup = { it.report = report }, after = {
            scrollSheet()
            assertEquals("preview row", 1, count(hasText("Preview report")))
            rule.onAllNodes(hasText("Preview report")).onFirst().performClick()
            settle(8)
        })
        assertEquals("preview text", 1, count(hasText("Oriel problem report", substring = true)))
    }

    private companion object {
        const val CHANNEL_A = "UCaaaaaaaaaaaaaaaaaaaaaa"
        const val CHANNEL_B = "UCbbbbbbbbbbbbbbbbbbbbbb"
    }

    @Test fun homeLoading() {
        TestApp.hang = true
        try {
            shoot("09_home_loading", homeFeed = null, playing = false)
        } finally {
            TestApp.hang = false
        }
    }
}
