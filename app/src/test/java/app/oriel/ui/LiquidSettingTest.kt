package app.oriel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.Feed
import app.oriel.data.SettingsState
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.ui.screens.LiquidMotionPrompt
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.captureScreenRoboImage
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

/** The Liquid motion setting: off by default, never under Reduce motion, and its first-run question. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class LiquidSettingTest {
    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val graph get() = app.graph
    private var appState: AppState? = null

    @Test fun offByDefaultAndNotYetAsked() {
        assertFalse(SettingsState().liquidMotion)
        assertFalse(SettingsState().liquidMotionAsked)
    }

    @Test fun outsideTheThemeItsOff() {
        var seen: Boolean? = null
        rule.setContent { seen = LocalLiquidMotion.current }
        rule.waitForIdle()
        assertEquals(false, seen)
    }

    private fun provided(liquidMotion: Boolean, reduceAnimation: Boolean): Boolean {
        var seen: Boolean? = null
        rule.setContent { OrielTheme(ThemeMode.DARK, reduceAnimation = reduceAnimation, liquidMotion = liquidMotion) { seen = LocalLiquidMotion.current } }
        rule.waitForIdle()
        return seen!!
    }

    @Test fun onWhenTurnedOn() = assertTrue(provided(liquidMotion = true, reduceAnimation = false))
    @Test fun offWhenOff() = assertFalse(provided(liquidMotion = false, reduceAnimation = false))
    @Test fun offUnderReduceMotion() = assertFalse(provided(liquidMotion = true, reduceAnimation = true))

    // ---- the first-run question ----

    private fun settle(rounds: Int) = repeat(rounds) {
        Thread.sleep(10)
        ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(120)
    }

    private fun start(asked: Boolean = false, setup: suspend (AppState) -> Unit = {}) {
        graph.settings.update { it.copy(liquidMotionAsked = asked) }
        // TestApp doesn't start the graph, which is what reports the crash check done.
        graph.crashesChecked.complete(Unit)
        rule.mainClock.autoAdvance = false
        graph.pages.write("home-false", Feed.serializer(), Fixtures.homeFeed)
        rule.setContent { Frame(setup) }
    }

    @Composable
    private fun Frame(setup: suspend (AppState) -> Unit) {
        val settings by graph.settings.state.collectAsState()
        OrielTheme(ThemeMode.DARK, reduceAnimation = true, liquidMotion = settings.liquidMotion) {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val activity = androidx.activity.compose.LocalActivity.current!!
            val connection = remember { PlayerConnection(activity, graph) }
            val state = remember { AppState(graph, nav, connection, scope, activity) }
            appState = state
            LaunchedEffect(Unit) {
                nav.currentBackStackEntryFlow.first().lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                setup(state)
            }
            OrielRoot(state, inPip = false)
        }
    }

    private fun prompt() = rule.onAllNodes(hasText("Try liquid motion?")).fetchSemanticsNodes().size

    @Test fun askedOnceTheAppHasSettled() {
        start()
        settle(8)
        assertEquals("not straight away", 0, prompt())
        settle(60)
        assertEquals("asked", 1, prompt())
        rule.onNode(hasText("Turn On")).performClick()
        settle(20)
        assertEquals("gone", 0, prompt())
        assertTrue("on", graph.settings.current.liquidMotion)
        assertTrue("answered", graph.settings.current.liquidMotionAsked)
    }

    @Test fun notNowKeepsItOffAndDoesntAskAgain() {
        start()
        settle(68)
        rule.onNode(hasText("Not Now")).performClick()
        settle(20)
        assertFalse(graph.settings.current.liquidMotion)
        assertTrue(graph.settings.current.liquidMotionAsked)
    }

    @Test fun notAskedOnceAnswered() {
        start(asked = true)
        settle(80)
        assertEquals(0, prompt())
    }

    /** Never over another alert: it waits until that one has been answered. */
    @Test fun waitsForAnotherAlert() {
        start(setup = { it.alert = AlertSpec("Something else", null, listOf(AlertAction("OK"))) })
        settle(80)
        assertEquals("waiting", 0, prompt())
        rule.onNode(hasText("OK")).performClick()
        settle(50)
        assertEquals("then asked", 1, prompt())
    }

    // ---- how it looks ----

    private fun shoot(name: String, theme: ThemeMode, extraMs: Long = 0) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(theme, reduceAnimation = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { LiquidMotionPrompt {} }
            }
        }
        rule.mainClock.advanceTimeBy(1_000 + extraMs)
        rule.waitForIdle()
        captureScreenRoboImage("build/screenshots/c_liquid_prompt_$name.png")
    }

    @Test fun promptDark() = shoot("dark", ThemeMode.DARK)
    @Test fun promptLight() = shoot("light", ThemeMode.LIGHT)

    /** Mid-flow: the drop on its way to the other pill, stretched and necked. */
    @Test fun promptFlowing() = shoot("flowing_dark", ThemeMode.DARK, extraMs = 220)

    @Test fun answersOnceGone() {
        var answer: Boolean? = null
        rule.mainClock.autoAdvance = false
        rule.setContent { OrielTheme(ThemeMode.LIGHT) { LiquidMotionPrompt { answer = it } } }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onAllNodes(isRoot()).onLast()
        rule.onNode(hasText("Turn On")).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(true, answer)
    }
}
