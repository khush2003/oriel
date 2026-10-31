package app.oriel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.Feed
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.ui.screens.FeedModel
import app.oriel.ui.screens.HomeScreen
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Real Home layout with invented pages; a retained row mustn't hide the refreshed ranking. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class HomeRefreshTest {
    @get:Rule val rule = createComposeRule()
    private val graph get() = ApplicationProvider.getApplicationContext<TestApp>().graph
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val videos = (0..14).map { Video("fixture-$it", "Fixture video $it") }
    private var page = feed(videos)
    private fun feed(items: List<Video>) = Feed(shelves = listOf(Shelf(items = items, kind = ShelfKind.FEED)))

    private fun settle() = repeat(16) {
        Thread.sleep(10)
        ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(120)
    }

    private fun start(theme: ThemeMode): FeedModel {
        graph.settings.update { it.copy(signInBannerDismissed = true) }
        val model = FeedModel(graph, cacheKey = null, load = { page })
        ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
        })["home:false", FeedModel::class.java]
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(theme) }
        settle()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        settle()
        return model
    }

    @Composable private fun Frame(theme: ThemeMode) {
        OrielTheme(theme, reduceAnimation = true, liquidGlass = false) {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val activity = androidx.activity.compose.LocalActivity.current!!
            val connection = remember { PlayerConnection(activity, graph) }
            val app = remember { AppState(graph, nav, connection, scope, activity) }
            CompositionLocalProvider(LocalAppState provides app, LocalViewModelStoreOwner provides owner) {
                HomeScreen()
            }
        }
    }

    private fun manualRefresh(theme: ThemeMode, name: String) {
        val model = start(theme)
        rule.onRoot().captureRoboImage("build/screenshots/${name}_before.png")
        page = feed(listOf(Video("new-first", "Fresh first recommendation")) + videos.reversed())
        rule.runOnIdle { model.reload(fresh = true) }
        settle()
        rule.onNodeWithText("Fresh first recommendation").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/screenshots/${name}_after.png")
        owner.viewModelStore.clear()
    }

    @Test fun manualRefreshShowsTheNewFirstRecommendationDark() = manualRefresh(ThemeMode.DARK, "refresh_home_dark")
    @Test fun manualRefreshShowsTheNewFirstRecommendationLight() = manualRefresh(ThemeMode.LIGHT, "refresh_home_light")

    @Test fun backgroundRefreshKeepsTheReadersPosition() {
        val model = start(ThemeMode.DARK)
        val visible = rule.onNodeWithText("Fixture video 3").fetchSemanticsNode().boundsInRoot
        page = feed(listOf(Video("new-first", "Fresh first recommendation")) + videos)
        rule.runOnIdle { model.reload(fresh = true, quiet = true) }
        settle()
        val after = rule.onNodeWithText("Fixture video 3").fetchSemanticsNode().boundsInRoot
        assertTrue("the retained row stays anchored during a quiet refresh", kotlin.math.abs(visible.top - after.top) < 2f)
        owner.viewModelStore.clear()
    }
}
