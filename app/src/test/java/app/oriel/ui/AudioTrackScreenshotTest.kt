package app.oriel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import app.oriel.ui.glass.LocalAppBackdrop
import app.oriel.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import app.oriel.ui.glass.backdrop.backdrops.layerBackdrop
import app.oriel.ui.components.RemoteImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.data.ThemeMode
import app.oriel.playback.AudioTrackOption
import app.oriel.playback.PlayerConnection
import app.oriel.ui.player.PlayerSettingsSheet
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Invented tracks exercise the grouped language choices without fetching a video. */
@OptIn(ExperimentalRoborazziApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class AudioTrackScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val graph get() = ApplicationProvider.getApplicationContext<TestApp>().graph

    private fun settle() {
        repeat(20) {
            Thread.sleep(25)
            ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeBy(120)
        }
    }

    private fun track(name: String, language: String, original: Boolean): AudioTrackOption {
        val format = Format.Builder().setLabel(name).setLanguage(language)
            .setSampleMimeType("audio/mp4a-latm")
            .setRoleFlags(if (original) C.ROLE_FLAG_MAIN else C.ROLE_FLAG_DUB).build()
        return AudioTrackOption(name, language, original, original, TrackGroup(name, format))
    }

    private fun shoot(theme: ThemeMode, name: String) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(theme, liquidGlass = true, reduceAnimation = true) {
                val activity = LocalActivity.current!!
                val connection = remember { PlayerConnection(activity, graph) }
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val app = remember { AppState(graph, nav, connection, scope, activity) }
                LaunchedEffect(Unit) {
                    app.watch.preview(Fixtures.watch, Fixtures.comments, Fixtures.storyboard)
                    connection.preview(listOf(Fixtures.watch.video!!), 0, playing = true, positionMs = 300_000,
                        audioTracks = listOf(track("English (original)", "en", true), track("Spanish (auto-dubbed)", "es", false)))
                }
                val backdrop = rememberLayerBackdrop()
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().layerBackdrop(backdrop).background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                        repeat(4) { RemoteImage(Fixtures.videos[it].thumbnail, androidx.compose.ui.Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                    }
                    androidx.compose.runtime.CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
                        PlayerSettingsSheet(app, onDismiss = {}, onPip = {})
                    }
                }
            }
        }
        settle()
        rule.onNodeWithText("Audio track").assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/${name}_settings.png")
        rule.onNodeWithText("Audio track").performClick()
        settle()
        rule.onNodeWithText("English (original)").assertIsDisplayed()
        rule.onNodeWithText("Spanish (auto-dubbed)").assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/${name}_choices.png")
        rule.onNodeWithContentDescription("Back").performClick()
        settle()
        rule.onNodeWithText("Playback speed").assertIsDisplayed()
        rule.onNodeWithText("Audio track").performClick()
        settle()
        rule.onNodeWithText("Spanish (auto-dubbed)").performClick()
        settle()
        assertEquals("es", graph.audioPreference().language)
        rule.onNodeWithText("Playback speed").assertIsDisplayed()
    }

    @Test fun audioTracksDark() = shoot(ThemeMode.DARK, "85_audio_dark")
    @Test fun audioTracksLight() = shoot(ThemeMode.LIGHT, "86_audio_light")
}
