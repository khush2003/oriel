/*
 * Adapted from BitChord's ArtworkPalette.kt (GPL-3.0): page colours taken
 * from the artwork, the Apple Music trick. Here the artwork is a video's
 * thumbnail or a channel's avatar.
 */
package app.oriel.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

@Immutable
data class ArtworkPalette(
    val background: Color,
    val wash: Color,
    val elevated: Color,
    val accent: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val divider: Color,
)

/**
 * The page colours for [imageUrl]: read once per URL off the main thread,
 * then animated in over 260ms (or snapped when already known, or under
 * "reduce animation"). Until then it's the plain scheme.
 */
@Composable
fun rememberArtworkPalette(imageUrl: String?, dark: Boolean = LocalIsDark.current): ArtworkPalette {
    val scheme = MaterialTheme.colorScheme
    val seed = rememberArtworkSeed(imageUrl)
    val target = seed?.toPalette(dark) ?: ArtworkPalette(
        background = scheme.background, wash = scheme.background, elevated = scheme.surfaceVariant, accent = scheme.primary,
        onBackground = scheme.onBackground, onBackgroundVariant = scheme.onSurfaceVariant, divider = scheme.outline,
    )
    // Returned as is, not animated colour by colour: seven animated colours read in composition
    // recomposed the whole watch page every frame of the change. The page's own background
    // eases across instead, in the draw phase ([animatedBackground]); text and tints take their
    // new values at once, which against the moving background doesn't read as a jump.
    return target
}

/**
 * The page colour, eased to each new value in 260ms (snapped under reduce animation) and
 * painted in the draw phase, so a palette arriving never recomposes what's on top of it.
 */
fun Modifier.animatedBackground(color: Color): Modifier = composed {
    val spec: AnimationSpec<Color> = if (LocalReduceAnimation.current) snap() else tween(260)
    val shown = animateColorAsState(color, spec, label = "pageBackground")
    drawBehind { drawRect(shown.value) }
}

/** Average luminance of the artwork's top tenth, for how strong a status-bar scrim it needs. */
@Composable
fun rememberTopBandLuminance(imageUrl: String?): Float? = rememberArtworkSeed(imageUrl)?.topBandLuminance

fun topBandScrimAlpha(luminance: Float?): Float = 0.16f + (0.65f - 0.16f) * (luminance?.coerceIn(0f, 1f) ?: 0f)

private data class Seed(val dominant: Color, val vibrant: Color, val edge: Color, val topBandLuminance: Float)

private val seedCache = object : LinkedHashMap<String, Seed>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Seed>) = size > 128
}

@Composable
private fun rememberArtworkSeed(imageUrl: String?): Seed? {
    val context = LocalContext.current
    var seed by remember(imageUrl) { mutableStateOf(imageUrl?.let { synchronized(seedCache) { seedCache[it] } }) }
    LaunchedEffect(imageUrl) {
        if (imageUrl == null || seed != null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(imageUrl).size(PALETTE_PX).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return@LaunchedEffect
        val found = withContext(Dispatchers.Default) { runCatching { seedOf(bitmap) }.getOrNull() } ?: return@LaunchedEffect
        synchronized(seedCache) { seedCache[imageUrl] = found }
        seed = found
    }
    return seed
}

private const val PALETTE_PX = 128

private fun seedOf(bitmap: Bitmap): Seed? {
    val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val all = Palette.from(soft).maximumColorCount(24).clearFilters().generate().swatches
    if (all.isEmpty()) return null
    val accentCandidates = Palette.from(soft).maximumColorCount(24).generate().swatches.ifEmpty { all }
    val dominant = all.maxBy { it.population }
    val vibrant = accentCandidates.maxBy { s -> s.hsl[1] * sqrt(s.population.toFloat()) }
    return Seed(Color(dominant.rgb), Color(vibrant.rgb), soft.bandColor(fromBottom = true, 0.18f), soft.bandLuminance(0.10f))
}

private fun Bitmap.bandColor(fromBottom: Boolean, fraction: Float): Color {
    val band = (height * fraction).toInt().coerceIn(1, height)
    val pixels = IntArray(width * band)
    getPixels(pixels, 0, width, 0, if (fromBottom) height - band else 0, width, band)
    var r = 0L; var g = 0L; var b = 0L
    pixels.forEach { p -> r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
    val n = pixels.size.coerceAtLeast(1)
    return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

private fun Bitmap.bandLuminance(fraction: Float): Float {
    val band = (height * fraction).toInt().coerceIn(1, height)
    val pixels = IntArray(width * band)
    getPixels(pixels, 0, width, 0, 0, width, band)
    if (pixels.isEmpty()) return 0f
    return (pixels.sumOf { relativeLuminance(it).toDouble() } / pixels.size).toFloat()
}

private fun relativeLuminance(argb: Int): Float {
    fun linear(c: Int): Float {
        val s = c / 255f
        return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
    return 0.2126f * linear((argb shr 16) and 0xFF) + 0.7152f * linear((argb shr 8) and 0xFF) + 0.0722f * linear(argb and 0xFF)
}

private fun Seed.toPalette(dark: Boolean): ArtworkPalette = if (dark) {
    val background = dominant.withHsl({ sat(it, 0.20f, 0.62f) }, { 0.13f })
    ArtworkPalette(
        background = background,
        wash = edge.withHsl({ sat(it, 0.18f, 0.58f) }, { it.coerceIn(0.14f, 0.24f) }),
        elevated = dominant.withHsl({ sat(it, 0.20f, 0.62f) }, { 0.22f }),
        accent = vibrant.withHsl({ sat(it, 0.55f, 1f) }, { it.coerceIn(0.62f, 0.78f) }).legibleOn(background, lighter = true),
        onBackground = Color.White,
        onBackgroundVariant = dominant.withHsl({ sat(it, 0.22f, 0.50f) }, { 0.80f }),
        divider = Color.White.copy(alpha = 0.12f),
    )
} else {
    val background = dominant.withHsl({ sat(it, 0.14f, 0.50f) }, { 0.91f })
    ArtworkPalette(
        background = background,
        wash = edge.withHsl({ sat(it, 0.12f, 0.46f) }, { it.coerceIn(0.78f, 0.90f) }),
        elevated = dominant.withHsl({ sat(it, 0.14f, 0.50f) }, { 0.83f }),
        accent = vibrant.withHsl({ sat(it, 0.55f, 1f) }, { it.coerceIn(0.30f, 0.44f) }).legibleOn(background, lighter = false),
        onBackground = Color.Black,
        onBackgroundVariant = dominant.withHsl({ sat(it, 0.22f, 0.55f) }, { 0.34f }),
        divider = Color.Black.copy(alpha = 0.10f),
    )
}

/**
 * Steps the colour's lightness away from [background] until text in it reads
 * at 4.5:1 (WCAG AA). Lightness alone isn't contrast: a yellow at 44% is
 * nearly as bright as the page it sits on, where a blue at 44% is dark.
 */
internal fun Color.legibleOn(background: Color, lighter: Boolean): Color {
    val bg = background.toArgb()
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }
    var argb = toArgb()
    while (ColorUtils.calculateContrast(argb, bg) < 4.5 && hsl[2] > 0f && hsl[2] < 1f) {
        hsl[2] = (hsl[2] + if (lighter) 0.02f else -0.02f).coerceIn(0f, 1f)
        argb = ColorUtils.HSLToColor(hsl)
    }
    return Color(argb)
}

/** Near-grey stays grey, so a black-and-white thumbnail keeps a neutral page. */
private fun sat(source: Float, minimum: Float, maximum: Float): Float {
    val s = source.coerceIn(0f, 1f)
    return if (s < 0.12f) s else s.coerceIn(minimum, maximum)
}

private fun Color.withHsl(saturation: (Float) -> Float, lightness: (Float) -> Float): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }
    hsl[1] = saturation(hsl[1]).coerceIn(0f, 1f)
    hsl[2] = lightness(hsl[2]).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** The plain scheme as a palette, for surfaces that share the tinted ones' components. */
@Composable
fun themePalette(): ArtworkPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        ArtworkPalette(
            background = scheme.background, wash = scheme.background, elevated = scheme.surfaceVariant, accent = scheme.primary,
            onBackground = scheme.onBackground, onBackgroundVariant = scheme.onSurfaceVariant, divider = scheme.outline,
        )
    }
}
