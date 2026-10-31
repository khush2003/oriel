package app.oriel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsDisplayed
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.Feed
import app.oriel.core.stream.AudioPreference
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.ui.navigation.ChannelRoute
import app.oriel.ui.navigation.ChannelsRoute
import app.oriel.ui.navigation.HistoryRoute
import app.oriel.ui.navigation.LikedRoute
import app.oriel.ui.navigation.NotificationsRoute
import app.oriel.ui.navigation.PlaylistRoute
import app.oriel.ui.navigation.PlaylistsRoute
import app.oriel.ui.navigation.SearchRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.navigation.Tab
import app.oriel.ui.navigation.WatchLaterRoute
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
 * The library and pushed pages, dark and light, and their loading states, in the same
 * offline frame as [ScreenshotTest]: invented fixtures, pages from the disk cache, no network.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class PagesScreenshotTest {
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
        Fixtures.videos.forEach { lib.addHistory(it) }
        lib.savePosition(Fixtures.videos[0].id, 300_000, 1_260_000)
        Fixtures.videos.drop(2).take(5).forEach { lib.setLiked(it, true) }
        Fixtures.videos.drop(6).take(3).forEach { lib.setWatchLater(it, true) }
        Fixtures.subscriptions.forEach { lib.setSubscribed(it, true) }
        lib.createPlaylist("Cabin inspiration", Fixtures.videos.take(5))
        lib.setPlaylistSaved(Fixtures.savedPlaylist, true)
        listOf("bread recipe", "night train", "lighthouse").forEach { lib.addSearch(it) }
    }

    private fun shoot(name: String, theme: ThemeMode = ThemeMode.DARK, screen: Boolean = false, setup: suspend (AppState) -> Unit = {}, after: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        graph.pages.write("home-false", Feed.serializer(), Fixtures.homeFeed)
        rule.setContent { Frame(theme, setup) }
        settle()
        after()
        settle(8)
        if (screen) captureScreenRoboImage("build/screenshots/$name.png")
        else rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    /** With every request left hanging, for the loading state. */
    private fun loading(name: String, theme: ThemeMode = ThemeMode.DARK, setup: suspend (AppState) -> Unit) {
        TestApp.hang = true
        try { shoot(name, theme, setup = setup) } finally { TestApp.hang = false }
    }

    @Composable
    private fun Frame(theme: ThemeMode, setup: suspend (AppState) -> Unit) {
        OrielTheme(theme, reduceAnimation = true, liquidGlass = true) {
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

    @Test fun history() { seedLibrary(); shoot("50_history", setup = { it.navigate(HistoryRoute) }) }
    @Test fun historyLight() { seedLibrary(); shoot("50b_history_light", ThemeMode.LIGHT, setup = { it.navigate(HistoryRoute) }) }
    @Test fun liked() { seedLibrary(); shoot("51_liked", setup = { it.navigate(LikedRoute) }) }
    @Test fun watchLaterLight() { seedLibrary(); shoot("52_watch_later_light", ThemeMode.LIGHT, setup = { it.navigate(WatchLaterRoute) }) }
    @Test fun watchLaterEmpty() = shoot("52b_watch_later_empty", setup = { it.navigate(WatchLaterRoute) })
    @Test fun playlists() { seedLibrary(); shoot("53_playlists", setup = { it.navigate(PlaylistsRoute) }) }
    @Test fun playlistsLight() { seedLibrary(); shoot("53b_playlists_light", ThemeMode.LIGHT, setup = { it.navigate(PlaylistsRoute) }) }

    /** A playlist on the phone has actions too: its ⋯ offers Rename and Delete. */
    @Test fun localPlaylistActions() {
        seedLibrary()
        shoot("53c_playlists_actions", screen = true, setup = { it.navigate(PlaylistsRoute) }, after = {
            rule.onAllNodes(hasContentDescription("More")).onFirst().performClick()
        })
        assertEquals("rename offered", 1, rule.onAllNodes(hasText("Rename")).fetchSemanticsNodes().size)
        rule.onNodeWithText("Delete playlist").performClick()
        settle(8)
        // Asked, not done: the playlist is still there until Delete is confirmed.
        assertEquals(1, graph.library.state.value.localPlaylists.size)
        assertEquals("confirmation", 1, rule.onAllNodes(hasText("Delete “Cabin inspiration”?")).fetchSemanticsNodes().size)
    }

    @Test fun channels() { seedLibrary(); shoot("54_channels", setup = { it.navigate(ChannelsRoute) }) }
    @Test fun youLight() { seedLibrary(); shoot("58_you_light", ThemeMode.LIGHT, setup = { it.selectTab(Tab.YOU) }) }
    @Test fun subscriptionsLight() {
        seedLibrary()
        graph.pages.write("subs-false", Feed.serializer(), Feed(shelves = Fixtures.homeFeed.shelves.drop(1)))
        shoot("59_subscriptions_light", ThemeMode.LIGHT, setup = { it.selectTab(Tab.SUBSCRIPTIONS) })
    }
    @Test fun searchLight() { seedLibrary(); shoot("60_search_light", ThemeMode.LIGHT, setup = { it.navigate(SearchRoute()) }) }
    @Test fun settingsLight() = shoot("61_settings_light", ThemeMode.LIGHT, setup = { it.navigate(SettingsRoute) })

    // Loading: placeholders in the shape of what replaces them.
    @Test fun playlistLoading() = loading("55_playlist_loading") { it.navigate(PlaylistRoute("PLfixture1")) }
    @Test fun playlistLoadingLight() = loading("55b_playlist_loading_light", ThemeMode.LIGHT) { it.navigate(PlaylistRoute("PLfixture1")) }
    @Test fun channelLoading() = loading("56_channel_loading") { it.navigate(ChannelRoute("UCfixture0")) }
    @Test fun notificationsLoading() = loading("57_notifications_loading") { it.navigate(NotificationsRoute) }

    /** Back from a page pushed over History lands where the reader was, not at the top. */
    @Test fun historyKeepsItsPlace() {
        seedLibrary()
        shoot("62_history_scrolled", setup = { it.navigate(HistoryRoute) }, after = {
            rule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(12)
        })
        rule.onNodeWithText("Pause watch history").assertDoesNotExist()
        rule.runOnUiThread { appState!!.navigate(SettingsRoute) }
        settle(12)
        rule.runOnUiThread { appState!!.back() }
        settle(12)
        rule.onNodeWithText("Pause watch history").assertDoesNotExist()
    }
    private fun originalAudioSetting(theme: ThemeMode, name: String) {
        graph.sessionAudio = AudioPreference(language = "es")
        shoot(name, theme, setup = { it.navigate(SettingsRoute) }, after = {
            rule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(4)
            rule.onNodeWithText("Prefer original audio").assertIsDisplayed()
        })
        org.junit.Assert.assertTrue(graph.settings.current.preferOriginalAudio)
        rule.onNodeWithText("Prefer original audio").performClick()
        settle(8)
        org.junit.Assert.assertFalse(graph.settings.current.preferOriginalAudio)
        org.junit.Assert.assertNull(graph.sessionAudio)
        rule.onRoot().captureRoboImage("build/screenshots/${name}_off.png")
    }

    @Test fun originalAudioSettingDark() = originalAudioSetting(ThemeMode.DARK, "87_original_audio_setting_dark")
    @Test fun originalAudioSettingLight() = originalAudioSetting(ThemeMode.LIGHT, "88_original_audio_setting_light")

}
