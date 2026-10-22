package app.oriel.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.Feed
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.ui.player.PIP_FLING_AWAY
import app.oriel.ui.player.PipRelease
import app.oriel.ui.player.isRise
import app.oriel.ui.player.pipRelease
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.OrielTheme
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The floating window's gestures, as YouTube's: moved anywhere and landed in a corner, put away
 * only by an explicit throw off a side or a release more than half past one, a swipe up or a tap
 * to open the watch page. Driven through the whole app with real touch events and the clock run
 * by hand. Liquid motion off unless a test says otherwise: the rules are the same either way.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class PlayerGestureTest {
    @get:Rule
    val rule = createComposeRule()

    private val graph get() = ApplicationProvider.getApplicationContext<TestApp>().graph
    private var appState: AppState? = null
    private val state get() = appState!!
    private val density = 2f // xhdpi

    private fun settle(rounds: Int = 16) = repeat(rounds) {
        ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(120)
    }

    private fun start(liquid: Boolean = false) {
        rule.mainClock.autoAdvance = false
        graph.pages.write("home-false", Feed.serializer(), Fixtures.homeFeed)
        rule.setContent { Frame(liquid) }
        settle(24)
    }

    @Composable
    private fun Frame(liquid: Boolean) {
        OrielTheme(ThemeMode.DARK, reduceAnimation = false) {
            CompositionLocalProvider(LocalLiquidMotion provides liquid) {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val activity = LocalContext.current as Activity
                val connection = remember { PlayerConnection(activity, graph) }
                val app = remember { AppState(graph, nav, connection, scope, activity) }
                appState = app
                LaunchedEffect(Unit) {
                    app.watch.preview(Fixtures.watch, Fixtures.comments, Fixtures.storyboard)
                    connection.preview(listOf(Fixtures.watch.video!!) + Fixtures.videos.drop(1).take(3), 0, playing = true, positionMs = 300_000)
                    nav.currentBackStackEntryFlow.first().lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                    app.sheet.progress.snapTo(0f)
                }
                OrielRoot(app, inPip = false)
            }
        }
    }

    /** The window's bounds in px. */
    private fun window(): Rect = rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun dp(v: Float) = v * density
    private val screenW get() = rule.onRoot().fetchSemanticsNode().size.width.toFloat()

    /**
     * A finger down at [from], moved through [path] (offsets from [from], in px) one step every
     * [stepMs], then lifted, the clock following each step so the velocity is the path's own.
     */
    private fun gesture(from: Offset, path: List<Offset>, stepMs: Long = 16) {
        rule.onRoot().performTouchInput {
            down(from)
            for (o in path) moveTo(from + o, delayMillis = stepMs)
            up()
        }
        settle()
    }

    /** [steps] even steps to [to], in px. */
    private fun line(to: Offset, steps: Int) = (1..steps).map { to * (it.toFloat() / steps) }

    private val stops get() = state.connection.stopRequests

    /**
     * Dragged across to the other side at a fair pace and let go there, it lands in that corner.
     * (It used to be put away: a sideways throw whose aim crossed the edge counted as one.)
     */
    @Test fun throwAcrossLandsInTheFarCorner() {
        start()
        val w = window()
        assertEquals("starts bottom right", 3, state.sheet.pipCorner)
        // 200 dp left in 200 ms: 1000 dp/s, ending with its left edge a few dp past the screen's.
        gesture(w.center, line(Offset(-dp(200f), 0f), 12), stepMs = 16)
        assertEquals("not put away", 0, stops)
        assertEquals("bottom-left corner", 2, state.sheet.pipCorner)
        assertTrue("came to rest at the left: ${window()}", window().left < dp(20f) && window().left > 0f)
        assertEquals("still the window", 0f, state.sheet.progress.value, 0.01f)
    }

    /** Moved slowly to anywhere in the middle and let go, it lands in the nearest corner, never put away. */
    @Test fun dragAndReleaseLands() {
        start()
        val w = window()
        // Up and to the left, more across than up: a move, not the swipe up.
        gesture(w.center, line(Offset(-dp(250f), -dp(150f)), 40), stepMs = 20)
        assertEquals("not put away", 0, stops)
        assertTrue("in a corner", state.sheet.pipCorner in 0..3)
        val r = window()
        assertTrue("on screen: $r", r.left >= 0f && r.right <= screenW)
    }

    /** A short, slow nudge toward the side it's on just lands it back. (It used to put it away.) */
    @Test fun nudgeTowardTheEdgeLands() {
        start()
        val w = window()
        gesture(w.center, line(Offset(w.width * 0.25f, 0f), 6), stepMs = 15)
        assertEquals("not put away", 0, stops)
        assertEquals("back in its corner", 3, state.sheet.pipCorner)
    }

    /** Dragged slowly until more than half of it is past the right edge and let go there, it's put away. */
    @Test fun slowDragPastTheEdgeDismisses() {
        start()
        val w = window()
        // From its corner (12 dp in) to 130 dp further right: 118 dp past, more than half of 206.
        gesture(w.center, line(Offset(dp(130f), 0f), 40), stepMs = 25)
        assertEquals("put away", 1, stops)
    }

    /** Thrown hard off the side it's on, it's put away even though it hardly moved before the release. */
    @Test fun fastFlingOffTheSideDismisses() {
        start()
        val w = window()
        // 80 dp in 36 ms, about 2200 dp/s, and nowhere near half past the edge.
        gesture(w.center, line(Offset(dp(80f), 0f), 6), stepMs = 6)
        assertEquals("put away", 1, stops)
    }

    /** Dragged into the zone past the edge and back out before letting go: it stays. */
    @Test fun intoTheZoneAndBackOutStays() {
        start()
        val w = window()
        val out = line(Offset(dp(130f), 0f), 30)
        val back = line(Offset(-dp(130f), 0f), 30).map { it + Offset(dp(130f), 0f) }
        gesture(w.center, out + back, stepMs = 25)
        assertEquals("not put away", 0, stops)
        assertEquals("back in its corner", 3, state.sheet.pipCorner)
        assertEquals("still the window", 0f, state.sheet.progress.value, 0.01f)
    }

    /** A swipe up on it opens the watch page. (It used to only move the window up a corner.) */
    @Test fun swipeUpExpands() {
        start()
        val w = window()
        gesture(w.center, line(Offset(dp(10f), -dp(120f)), 10), stepMs = 16)
        assertEquals("not put away", 0, stops)
        assertEquals("the watch page", 1f, state.sheet.progress.value, 0.01f)
    }

    /** The same swipe up, liquid. */
    @Test fun swipeUpExpandsLiquid() {
        start(liquid = true)
        val w = window()
        gesture(w.center, line(Offset(dp(10f), -dp(120f)), 10), stepMs = 16)
        assertEquals("the watch page", 1f, state.sheet.progress.value, 0.01f)
    }

    /** A swipe down on it at the bottom does nothing destructive: it lands back where it was. */
    @Test fun swipeDownAtTheBottomStays() {
        start()
        val w = window()
        gesture(w.center, line(Offset(0f, dp(90f)), 8), stepMs = 16)
        assertEquals("not put away", 0, stops)
        assertEquals("back in its corner", 3, state.sheet.pipCorner)
        assertEquals("still the window", 0f, state.sheet.progress.value, 0.01f)
    }

    /** A tap opens the watch page. */
    @Test fun tapExpands() {
        start()
        rule.onNodeWithContentDescription("Video window", useUnmergedTree = true).performClick()
        settle()
        assertEquals("the watch page", 1f, state.sheet.progress.value, 0.01f)
    }

    /** Liquid, a throw across still lands it, and a throw off the side still puts it away. */
    @Test fun liquidThrowsFollowTheSameRules() {
        start(liquid = true)
        gesture(window().center, line(Offset(-dp(200f), 0f), 12), stepMs = 16)
        assertEquals("not put away", 0, stops)
        assertEquals("bottom-left corner", 2, state.sheet.pipCorner)
        settle()
        gesture(window().center, line(Offset(-dp(80f), 0f), 6), stepMs = 6)
        assertEquals("put away", 1, stops)
    }

    /** The release rule on its own, at the numbers the app uses (px at 2x: a 412 dp screen, a 206 dp window). */
    @Test fun releaseRules() {
        val sw = dp(412f)
        val w = dp(206f)
        val fling = dp(PIP_FLING_AWAY.value)
        val right = sw - w - dp(12f)
        assertEquals("resting", PipRelease.Land, pipRelease(right, w, sw, 0f, 0f, fling, false))
        assertEquals("half past, let go", PipRelease.AwayRight, pipRelease(right + dp(116f), w, sw, 0f, 0f, fling, false))
        assertEquals("just under half past", PipRelease.Land, pipRelease(sw - w * 0.52f, w, sw, 0f, 0f, fling, false))
        assertEquals("half past, thrown back in hard", PipRelease.Land, pipRelease(right + dp(116f), w, sw, -fling * 1.2f, 0f, fling, false))
        assertEquals("flung right on the right", PipRelease.AwayRight, pipRelease(right, w, sw, fling * 1.1f, 0f, fling, false))
        assertEquals("flung left from the right: a move", PipRelease.Land, pipRelease(right, w, sw, -fling * 2f, 0f, fling, false))
        assertEquals("flung right, too slow", PipRelease.Land, pipRelease(right, w, sw, fling * 0.8f, 0f, fling, false))
        assertEquals("flung down and right: a move", PipRelease.Land, pipRelease(right, w, sw, fling * 1.2f, fling * 1.2f, fling, false))
        assertEquals("pinched past the edge", PipRelease.Land, pipRelease(right + dp(116f), w, sw, 0f, 0f, fling, true))
        assertEquals("flung left on the left", PipRelease.AwayLeft, pipRelease(dp(12f), w, sw, -fling * 1.1f, 0f, fling, false))
        assertTrue("straight up rises", isRise(Offset(0f, -10f), 1))
        assertTrue("steeply up rises", isRise(Offset(4f, -10f), 1))
        assertFalse("up toward a corner is a move", isRise(Offset(6f, -10f), 1))
        assertFalse("diagonal is a move", isRise(Offset(10f, -10f), 1))
        assertFalse("down is a move", isRise(Offset(0f, 10f), 1))
        assertFalse("two fingers is a pinch", isRise(Offset(0f, -10f), 2))
    }
}
