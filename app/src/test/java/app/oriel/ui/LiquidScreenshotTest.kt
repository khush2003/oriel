package app.oriel.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.Feed
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.ui.navigation.HistoryRoute
import app.oriel.ui.navigation.HomeRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The liquid transitions, caught mid-flight with the clock held: the picture stretching as it's
 * dragged and flown, a tapped thumbnail on its way into the player, a page's soft edge as it's
 * pushed and as a back swipe holds it. Motion is on here (the other screenshot tests reduce it),
 * and each test ends by letting the motion finish and checking where it came to rest.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class LiquidScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val graph get() = app.graph
    private var appState: AppState? = null
    private val state get() = appState!!

    private fun settle(rounds: Int = 16) {
        repeat(rounds) {
            Thread.sleep(25)
            // The system clock too: images cross-fade in by it, not by the compose clock.
            ShadowLooper.idleMainLooper(120, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.mainClock.advanceTimeBy(120)
        }
    }

    /** Frame by frame, as a phone would draw them. */
    private fun frames(ms: Long) {
        var t = 0L
        while (t < ms) {
            Thread.sleep(6) // for images being drawn off the main thread
            ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.mainClock.advanceTimeBy(16)
            t += 16
        }
    }

    private fun capture(name: String) = rule.onRoot().captureRoboImage("build/screenshots/$name.png")

    private fun start(theme: ThemeMode = ThemeMode.DARK, playing: Boolean = true, setup: suspend (AppState) -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        graph.pages.write("home-false", Feed.serializer(), Fixtures.homeFeed)
        rule.setContent { Frame(theme, playing, setup) }
        settle(28)
    }

    @Composable
    private fun Frame(theme: ThemeMode, playing: Boolean, setup: suspend (AppState) -> Unit) {
        OrielTheme(theme, reduceAnimation = false, liquidGlass = true) {
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

    /** Dragging the watch page's picture down: it leans long along the way it's pulled, its corners rounding. */
    @Test fun collapseMidDrag() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        rule.onRoot().performTouchInput { down(Offset(width * 0.5f, height * 0.12f)) }
        repeat(14) {
            rule.onRoot().performTouchInput { moveBy(Offset(0f, height * 0.022f), delayMillis = 16) }
            frames(16)
        }
        capture("70_liquid_collapse_mid_drag")
        rule.onRoot().performTouchInput { up() }
        settle()
        assertEquals("let go moving down, it lands in the window", 0f, state.sheet.progress.value, 0.01f)
    }

    /** Then back up under the same finger, quicker: it stretches the other way. */
    @Test fun expandMidDrag() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        rule.onRoot().performTouchInput { down(Offset(width * 0.5f, height * 0.12f)) }
        repeat(20) {
            rule.onRoot().performTouchInput { moveBy(Offset(0f, height * 0.03f), delayMillis = 16) }
            frames(16)
        }
        repeat(8) {
            rule.onRoot().performTouchInput { moveBy(Offset(0f, -height * 0.035f), delayMillis = 16) }
            frames(16)
        }
        capture("71_liquid_expand_mid_drag")
        rule.onRoot().performTouchInput { up() }
        settle()
        assertEquals("thrown up, it lands on the watch page", 1f, state.sheet.progress.value, 0.01f)
    }

    /** A tap on the window: mid-flight it's a drop, longer than it is wide and round at the corners. */
    @Test fun tapExpandMidFlight() {
        start(setup = { it.sheet.progress.snapTo(0f) })
        rule.runOnUiThread { state.sheet.expand() }
        frames(96)
        capture("72_liquid_tap_expand_mid_flight")
        frames(240)
        capture("73_liquid_tap_expand_landing")
        settle()
        assertEquals(1f, state.sheet.progress.value, 0.001f)
        assertEquals("the landing's squash has settled", 0f, state.sheet.squash.value, 0.001f)
    }

    /** The collapse lands in the corner with a squash. */
    @Test fun collapseLanding() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        rule.runOnUiThread { state.sheet.collapse() }
        frames(144)
        capture("74_liquid_collapse_mid_flight")
        frames(256)
        capture("75_liquid_collapse_landing")
        settle()
        assertEquals(0f, state.sheet.progress.value, 0.001f)
    }

    /** A card tapped on Home while a video plays in the window: the card's frame grows into the player as the window is absorbed. */
    @Test fun handoffFromFeedCard() {
        // The window up in the top corner, clear of the card.
        start(setup = { it.sheet.progress.snapTo(0f); it.sheet.pipCorner = 0 })
        rule.onAllNodes(hasText(Fixtures.videos[4].title)).onFirst().performClick()
        frames(112)
        capture("76_liquid_handoff_feed_mid_flight")
        settle()
        assertEquals("the watch page is open", 1f, state.sheet.progress.value, 0.001f)
        assertEquals(Fixtures.videos[4].id, state.watch.state.value.video?.id)
    }

    /** Nothing playing yet: the first video grows out of its card, not out of a corner. */
    @Test fun firstVideoFromFeedCard() {
        start(playing = false)
        rule.onAllNodes(hasText(Fixtures.videos[4].title)).onFirst().performClick()
        frames(112)
        capture("77_liquid_first_open_mid_flight")
        settle()
        assertEquals(1f, state.sheet.progress.value, 0.001f)
    }

    /** Up next on the watch page: the row's frame flies up into the picture, the old one giving way under it. */
    @Test fun handoffFromUpNext() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        // Up the page to the first of Up next.
        rule.onRoot().performTouchInput { swipe(Offset(width * 0.5f, height * 0.85f), Offset(width * 0.5f, height * 0.45f), 400) }
        settle(6)
        rule.onAllNodes(hasText(Fixtures.videos[1].title)).onFirst().performClick()
        frames(128)
        capture("78_liquid_handoff_up_next_mid_flight")
        settle()
        assertEquals(Fixtures.videos[1].id, state.watch.state.value.video?.id)
        assertEquals(1f, state.sheet.progress.value, 0.001f)
    }

    /** The queue moving on by itself: the next picture blooms in place over the last. */
    @Test fun queueNextBlooms() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        rule.runOnUiThread { state.connection.preview(Fixtures.videos.take(4), 2, playing = true, positionMs = 0) }
        frames(112)
        capture("79_liquid_queue_bloom_mid_flight")
        settle()
    }

    /** A push: the incoming page's edge bulges forward on its way in. */
    @Test fun pushMidFlight() {
        start(playing = false)
        rule.runOnUiThread { state.navigate(SettingsRoute) }
        frames(80)
        capture("80_liquid_push_mid_flight")
        settle()
        assertTrue(state.nav.currentBackStackEntry!!.destination.hasRoute(SettingsRoute::class))
    }

    @Test fun pushMidFlightLight() {
        start(ThemeMode.LIGHT, playing = false)
        rule.runOnUiThread { state.navigate(SettingsRoute) }
        frames(80)
        capture("80b_liquid_push_mid_flight_light")
        settle()
    }

    /** A back swipe held part way: the page dents in where the finger holds it. Let go short, it springs back flat. */
    @Test fun backSwipeMidDrag() {
        start(playing = false, setup = { it.navigate(HistoryRoute) })
        settle(8)
        val dispatcher = (state.activity as ComponentActivity).onBackPressedDispatcher
        val y = 1100f
        rule.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(4f, y, 0f, BackEventCompat.EDGE_LEFT)) }
        for (i in 1..12) {
            rule.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(4f + i * 40f, y + i * 6f, i * 0.03f, BackEventCompat.EDGE_LEFT)) }
            frames(16)
        }
        frames(64)
        capture("81_liquid_back_swipe_mid_drag")
        rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        frames(96)
        capture("82_liquid_back_swipe_released")
        settle()
        assertTrue("cancelled, History stays", state.nav.currentBackStackEntry!!.destination.hasRoute(HistoryRoute::class))
    }

    /** And let go past the point of no return, it pops. */
    @Test fun backSwipeCommits() {
        start(playing = false, setup = { it.navigate(HistoryRoute) })
        settle(8)
        val dispatcher = (state.activity as ComponentActivity).onBackPressedDispatcher
        rule.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(4f, 900f, 0f, BackEventCompat.EDGE_LEFT)) }
        for (i in 1..10) {
            rule.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(4f + i * 60f, 900f, i * 0.05f, BackEventCompat.EDGE_LEFT)) }
            frames(16)
        }
        rule.runOnUiThread { dispatcher.onBackPressed() }
        frames(64)
        capture("83_liquid_back_swipe_committing")
        settle()
        assertTrue("popped to Home", state.nav.currentBackStackEntry!!.destination.hasRoute(HomeRoute::class))
    }
}
