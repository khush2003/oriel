package app.oriel

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import app.oriel.data.ThemeMode
import app.oriel.playback.MediaItems
import app.oriel.playback.PlayerConnection
import app.oriel.ui.AppState
import app.oriel.ui.OrielRoot
import app.oriel.ui.player.defaultOrientation
import app.oriel.ui.player.pipParams
import app.oriel.ui.player.supportsPip
import app.oriel.ui.theme.OrielTheme
import kotlinx.coroutines.flow.combine

class MainActivity : ComponentActivity() {
    private lateinit var connection: PlayerConnection
    private var app: AppState? = null
    private var pending: Intent? = null
    private val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        requestedOrientation = defaultOrientation(this)
        connection = PlayerConnection(this, graph)
        // A recreated activity has already handled the intent that launched it.
        if (savedInstanceState == null) pending = intent
        inPip.value = isInPictureInPictureMode

        setContent {
            val settings by graph.settings.state.collectAsState()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LaunchedEffect(settings.highRefreshRate) { applyRefreshRate(settings.highRefreshRate) }
            OrielTheme(settings.theme, settings.reduceAnimation, settings.reduceBlur, settings.liquidGlass) {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val state = remember { AppState(graph, nav, connection, scope, this) }
                LaunchedEffect(state) {
                    app = state
                    pending?.let(::route)
                    pending = null
                }
                // Android 12+ enters picture-in-picture on its own when leaving with a video playing.
                LaunchedEffect(Unit) {
                    combine(connection.isPlaying, connection.current, connection.audioOnly, connection.videoSize, graph.settings.state) { playing, current, audioOnly, size, s ->
                        Triple(autoPipWanted(playing, current != null, audioOnly, s.autoPip), size, Unit)
                    }.collect { (auto, size) ->
                        if (supportsPip(this@MainActivity)) runCatching { setPictureInPictureParams(pipParams(size, autoEnter = auto)) }
                    }
                }
                OrielRoot(state, inPip.value)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect()
        connection.setVideoVisible(true)
        graph.account.syncIfStale()
    }

    override fun onStop() {
        super.onStop()
        when {
            // A stop while in picture-in-picture is the window being closed: that ends playback, as on YouTube.
            isInPictureInPictureMode -> connection.pause()
            isChangingConfigurations -> {}
            !graph.settings.current.backgroundPlay -> connection.pause()
            // In the background only the sound plays; the picture isn't decoded.
            else -> connection.setVideoVisible(false)
        }
        // Playback lives in the service; the activity only lets go of its controller.
        connection.release()
        graph.saveSession()
    }

    override fun onDestroy() {
        connection.dispose()
        app = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 picture-in-picture has to be entered by hand on the way out.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !supportsPip(this)) return
        if (autoPipWanted()) {
            runCatching { enterPictureInPictureMode(pipParams(connection.videoSize.value, autoEnter = false)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) connection.setVideoVisible(true)
        // Entering by hand (the PiP button) set auto-enter off; back out, it follows the setting again.
        else if (supportsPip(this)) runCatching { setPictureInPictureParams(pipParams(connection.videoSize.value, autoEnter = autoPipWanted())) }
    }

    /** Whether leaving the app now should float the video: one with a picture playing, and the setting on. */
    private fun autoPipWanted(
        playing: Boolean = connection.isPlaying.value,
        hasVideo: Boolean = connection.current.value != null,
        audioOnly: Boolean = connection.audioOnly.value,
        enabled: Boolean = graph.settings.current.autoPip,
    ): Boolean = playing && hasVideo && !audioOnly && enabled && MediaItems.hasPicture(connection.currentMediaItem())

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app?.let { route(intent) } ?: run { pending = intent }
    }

    @androidx.annotation.VisibleForTesting
    internal fun route(intent: Intent) {
        val state = app ?: return
        when {
            intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false) -> state.sheet.expand()
            // A link or share it couldn't make sense of says so, rather than opening on nothing.
            !state.handleIntent(intent) && intent.action != null && intent.action != Intent.ACTION_MAIN -> state.notice("Couldn't open this link")
        }
    }

    /** Asks for the display's fastest mode at the current resolution (or lets the system choose). */
    private fun applyRefreshRate(high: Boolean) = runCatching {
        // Display-mode queries can throw on unusual displays (and do under test); never worth a crash.
        @Suppress("DEPRECATION")
        val display: Display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay) ?: return@runCatching
        val currentMode = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }
        val params = window.attributes
        params.preferredDisplayModeId = if (high) best?.modeId ?: 0 else 0
        window.attributes = params
    }.onFailure { android.util.Log.w("Oriel", "Couldn't set the refresh rate", it) }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
