package app.oriel.ui.player

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.oriel.core.Storyboard
import app.oriel.util.Format
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlin.math.roundToInt

/**
 * The frame under the finger while scrubbing, cut from YouTube's storyboard
 * sprite sheets, with the chapter and time beneath it, as YouTube shows it.
 * Without a storyboard (live, or not loaded yet) only the time shows.
 *
 * The position and chapter are read here, as lambdas, so a drag recomposes this alone and
 * not the controls around it.
 */
@Composable
fun ScrubPreview(storyboard: Storyboard?, positionMs: () -> Long, chapter: () -> String?, width: Dp, modifier: Modifier = Modifier) {
    val ms = positionMs()
    val title = chapter()
    val frame = storyboard?.frameAt(ms)
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (storyboard != null && frame != null) {
            PrefetchSheets(storyboard)
            val sheet = rememberSheet(frame.url)
            val lastCell = remember { arrayOfNulls<Triple<ImageBitmap, IntOffset, IntSize>>(1) }
            val level = storyboard.best
            val shape = RoundedCornerShape(10.dp)
            Canvas(
                Modifier
                    .width(width)
                    .aspectRatio(frame.width.toFloat() / frame.height)
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.9f), shape),
            ) {
                // Crossing into a sheet not decoded yet, the last frame drawn holds rather than
                // the preview going blank for a moment.
                val cell = sheet?.let { image ->
                    // The sheet as decoded may not be at its native size; map the cell by scale.
                    val sx = image.width.toFloat() / (level.columns * level.width)
                    val sy = image.height.toFloat() / (level.rows * level.height)
                    Triple(image, IntOffset((frame.x * sx).roundToInt(), (frame.y * sy).roundToInt()), IntSize((frame.width * sx).roundToInt(), (frame.height * sy).roundToInt()))
                }?.also { lastCell[0] = it } ?: lastCell[0] ?: return@Canvas
                drawImage(
                    cell.first,
                    srcOffset = cell.second,
                    srcSize = cell.third,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelMedium.copy(shadow = shadow), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = width))
        }
        Text(Format.durationMs(ms), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700, shadow = shadow), color = Color.White)
    }
}

/** Decoded sheets, kept few: one is ~1.4 MB as pixels, and a scrub rarely spans more than a handful. */
private val sheets = LruCache<String, ImageBitmap>(8)

@Composable
private fun rememberSheet(url: String): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf(sheets.get(url)) }
    LaunchedEffect(url) {
        if (bitmap != null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        val image = result.image.toBitmap().asImageBitmap()
        sheets.put(url, image)
        bitmap = image
    }
    return bitmap
}

/** Fetches every sheet into the disk cache once a scrub starts (tens of KB each), so each new one decodes from disk. */
@Composable
private fun PrefetchSheets(storyboard: Storyboard) {
    val context = LocalContext.current
    LaunchedEffect(storyboard) {
        val loader = SingletonImageLoader.get(context)
        val level = storyboard.best
        for (i in 0 until level.sheetCount) {
            loader.enqueue(ImageRequest.Builder(context).data(level.sheetUrl(i)).memoryCachePolicy(CachePolicy.DISABLED).size(Size.ORIGINAL).build())
        }
    }
}
