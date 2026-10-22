/*
 * thumbnailBorder and CoverPlaceholder adapted from BitChord (GPL-3.0).
 */
package app.oriel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Video
import app.oriel.ui.theme.LiveRed
import app.oriel.ui.theme.LocalIsDark
import app.oriel.util.Format
import app.oriel.util.Thumbnails
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/** The Apple "sticker edge" every artwork gets: 1dp of white (dark) or black (light) at 15%. */
@Composable
fun Modifier.thumbnailBorder(shape: Shape): Modifier =
    border(1.dp, (if (LocalIsDark.current) Color.White else Color.Black).copy(alpha = 0.15f), shape)

/** A remote image cropped into [shape], on a surface-coloured placeholder. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), border: Boolean = true, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val fade = !app.oriel.ui.theme.LocalReduceAnimation.current
    val request = remember(url, fade) { ImageRequest.Builder(context).data(url).crossfade(if (fade) 180 else 0).build() }
    val variant = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    // One placeholder brush per colour pair, not one per image per recomposition: feeds draw hundreds.
    val placeholder = remember(variant, ink) { Brush.linearGradient(listOf(lerp(variant, ink, 0.06f), variant)) }
    Box(
        modifier
            .clip(shape)
            .background(placeholder)
            .then(if (border) Modifier.thumbnailBorder(shape) else Modifier),
    ) {
        if (url != null) AsyncImage(request, null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
    }
}

/** A video frame at 16:9 with its duration (or LIVE) badge and the resume bar. */
@Composable
fun VideoThumbnail(video: Video, modifier: Modifier = Modifier, corner: Dp = 14.dp, widthHint: Dp = 360.dp, progress: Float? = video.progress, badge: Boolean = true, overlay: @Composable BoxScope.() -> Unit = {}) {
    val px = with(LocalDensity.current) { widthHint.roundToPx() }
    val url = remember(video.id, video.thumbnail, px) { Thumbnails.of(video, px) }
    val shape = RoundedCornerShape(corner)
    Box(modifier.aspectRatio(16f / 9f)) {
        // A square frame runs to the screen's edges: no hairline to draw against them.
        RemoteImage(url, Modifier.fillMaxSize(), shape, border = corner > 0.dp)
        val watched = progress != null && progress > 0f
        if (badge) DurationBadge(video, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = if (watched) 16.dp else 8.dp))
        // How far in: a capsule inset along the bottom, as Apple's TV app draws it.
        if (watched) {
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).height(4.dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
        }
        overlay()
    }
}

@Composable
fun DurationBadge(video: Video, modifier: Modifier = Modifier) {
    val (text, color) = when {
        video.isLive -> "LIVE" to LiveRed
        video.isUpcoming -> "SOON" to Color.Black.copy(alpha = 0.45f)
        video.isShort && video.durationSec == null -> "SHORTS" to Color.Black.copy(alpha = 0.45f)
        video.durationSec != null -> Format.duration(video.durationSec!!.toLong()) to Color.Black.copy(alpha = 0.45f)
        else -> return
    }
    // A soft capsule, not YouTube's black box.
    Box(modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** A round avatar, or the channel's initial on a tinted disc when there's no picture. */
@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier, name: String? = null) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val sized = remember(url, px) { Thumbnails.sized(url, (px * 1.5f).toInt().coerceAtLeast(48)) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (sized == null && name != null) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                // Sized off the disc, not the font setting: the disc doesn't grow with the font, so the letter mustn't.
                val letter = with(LocalDensity.current) { (size * 0.42f).toSp() }
                Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium.copy(fontSize = letter), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (sized == null) {
            // Nobody to show (signed out): a person glyph, as iOS draws an empty contact.
            Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(OrielIcons.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.62f))
            }
        } else {
            RemoteImage(sized, Modifier.fillMaxSize(), CircleShape)
        }
    }
}
