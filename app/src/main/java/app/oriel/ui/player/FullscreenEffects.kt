package app.oriel.ui.player

import androidx.compose.runtime.getValue
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.oriel.ui.AppState

/** Back to the app's normal orientation with the system bars showing. */
internal fun showSystemChrome(activity: android.app.Activity) {
    activity.requestedOrientation = defaultOrientation(activity)
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
}

/**
 * Fullscreen means landscape (portrait for tall videos) with the system bars
 * hidden. Turning the phone sideways on the watch page enters it, and back
 * upright leaves it, as YouTube does, when the system lets the screen rotate.
 */
@Composable
internal fun FullscreenEffects(app: AppState) {
    val activity = app.activity
    val sheet = app.sheet
    val fullscreen = sheet.fullscreen
    val videoSize by app.connection.videoSize.collectAsState()
    val tall = videoSize.height > videoSize.width && videoSize.width > 0
    val context = LocalContext.current

    DisposableEffect(fullscreen, tall) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = if (tall) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            showSystemChrome(activity)
        }
        onDispose { }
    }

    // Rotate-to-fullscreen, debounced so a tilt while walking doesn't flip it. Only a turn of
    // the phone counts, not how it's held: the hold is taken afresh at every change of mode, so
    // Back while holding it sideways stays out, and the button's fullscreen stays in while upright,
    // until the phone is actually turned.
    val expanded = sheet.expanded
    DisposableEffect(expanded, tall, fullscreen) {
        if (!expanded || tall) return@DisposableEffect onDispose { }
        var lastDevice: Boolean? = null
        var since = 0L
        var pending: Boolean? = null
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                val autoRotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                if (!autoRotate) return
                val landscape = angle in 60..120 || angle in 240..300
                val portrait = angle < 25 || angle > 335
                // Somewhere between the two is no change either way.
                val device = when { landscape -> true; portrait -> false; else -> return }
                val held = lastDevice ?: run { lastDevice = device; return }
                if (device == held) { pending = null; return }
                val now = System.currentTimeMillis()
                if (pending != device) { pending = device; since = now; return }
                if (now - since > 350) {
                    pending = null
                    lastDevice = device
                    if (sheet.fullscreen != device) sheet.fullscreen = device
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
}

/** Phones keep the app upright outside fullscreen, as YouTube does; tablets turn freely. */
fun defaultOrientation(activity: android.app.Activity): Int =
    if (activity.resources.configuration.smallestScreenWidthDp >= 600) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
