package app.oriel.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.media3.common.VideoSize

/** Picture-in-picture parameters for the playing video's shape, clamped to what Android allows (2.39:1 to 1:2.39). */
fun pipParams(size: VideoSize, autoEnter: Boolean, sourceRect: Rect? = null): PictureInPictureParams {
    val w = size.width.takeIf { it > 0 } ?: 16
    val h = size.height.takeIf { it > 0 } ?: 9
    // Just inside Android's 1:2.39 to 2.39:1: a Rational on the limit after rounding is rejected.
    val ratio = (w.toFloat() / h).coerceIn(0.42f, 2.38f)
    val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
    sourceRect?.let(builder::setSourceRectHint)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

fun supportsPip(activity: Activity): Boolean = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

fun enterPip(activity: Activity, size: VideoSize): Boolean =
    supportsPip(activity) && runCatching { activity.enterPictureInPictureMode(pipParams(size, autoEnter = false)) }.getOrDefault(false)
