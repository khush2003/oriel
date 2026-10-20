/*
 * The glass rendering itself is Kyant0/backdrop (Apache-2.0), vendored at
 * [app.oriel.ui.glass.backdrop]. This integration glue is adapted from
 * BitChord's LiquidGlass.kt (GPL-3.0), itself from EchoMusicApp/Echo-Music's
 * GlassEffectConfig (GPL-3.0).
 */
package app.oriel.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.oriel.ui.glass.backdrop.Backdrop
import app.oriel.ui.glass.backdrop.drawBackdrop
import app.oriel.ui.glass.backdrop.effects.blur
import app.oriel.ui.glass.backdrop.effects.colorControls
import app.oriel.ui.glass.backdrop.effects.lens
import app.oriel.ui.glass.backdrop.highlight.Highlight
import app.oriel.ui.glass.backdrop.shadow.Shadow
import app.oriel.ui.theme.LocalLiquidGlassEnabled
import app.oriel.ui.theme.LocalReduceBlur

/** The page content (recorded once per frame) that every glass surface samples. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** RenderEffect on a RenderNode: Android 12 and up. Lens refraction needs 13 (RuntimeShader). */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

private const val BLUR_RADIUS_DP = 8f
private const val LENS_DP = 24f
private const val SURFACE_OPACITY = 0.4f

/** The backdrop is recorded and processed at a third of the resolution; the blur hides the upscale. */
private const val GLASS_RESOLUTION_SCALE = 0.33f

/** The hairline along every floating surface, and what stands in for the rim where glass isn't drawn. */
val GLASS_EDGE_WIDTH = 0.5.dp
val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/** Black or white off the theme's luminance: the only tint that holds against arbitrary content behind glass. */
@Composable
fun glassContentColor(): Color = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

@Composable
fun glassIndicatorColor(): Color = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.White else Color.Black

/** What a glass surface falls back to: the theme surface, nearly opaque, plus the edge. */
@Composable
fun glassFallbackColor(): Color = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalReduceBlur.current) 1f else 0.94f)

/** The frosted material that stands in for glass when it's off: a deep blur under the surface colour, as BitChord's bar has it. */
private const val FROST_BLUR_DP = 24f
private const val FROST_LIGHT_ALPHA = 0.73f
private const val FROST_DARK_ALPHA = 0.8f

/**
 * Whether pages record a backdrop for floating surfaces to sample: for liquid
 * glass, or for the frosted blur that stands in for it. Not under "reduce
 * transparency", nor where RenderEffect isn't available.
 */
@Composable
fun backdropWanted(): Boolean = LocalLiquidGlassEnabled.current || (!LocalReduceBlur.current && isGlassSupported())

/**
 * A floating surface: liquid glass sampling [LocalAppBackdrop] (vibrancy,
 * blur, and on API 33+ lens refraction with chromatic dispersion, under a
 * theme-matched tint); with glass off, frosted glass (the page blurred deep
 * under the surface colour, no lens, no rim light); under "reduce
 * transparency", a solid surface. Always with the 0.5dp edge. [shape] must be
 * corner-based: the lens throws for anything else.
 */
@Composable
fun Modifier.glassSurface(shape: CornerBasedShape): Modifier {
    val backdrop = LocalAppBackdrop.current
    if (backdrop == null || (!LocalLiquidGlassEnabled.current && LocalReduceBlur.current)) {
        return clip(shape).background(glassFallbackColor(), shape).border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    if (!LocalLiquidGlassEnabled.current) return frostedSurface(backdrop, shape)
    val density = LocalDensity.current
    val blurPx = with(density) { BLUR_RADIUS_DP.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensPx = with(density) { LENS_DP.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val tint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFFFAFAFA) else Color(0xFF121212)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.5f)
            blur(blurPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) lens(refractionHeight = lensPx, refractionAmount = lensPx, depthEffect = true, chromaticAberration = true)
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = { drawRect(color = tint.copy(alpha = SURFACE_OPACITY), size = size) },
        backdropScale = GLASS_RESOLUTION_SCALE,
    ).border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

@Composable
private fun Modifier.frostedSurface(backdrop: Backdrop, shape: CornerBasedShape): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val blurPx = with(LocalDensity.current) { FROST_BLUR_DP.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val tint = surface.copy(alpha = if (surface.luminance() >= 0.5f) FROST_LIGHT_ALPHA else FROST_DARK_ALPHA)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(blurPx) },
        highlight = null,
        shadow = null,
        onDrawSurface = { drawRect(color = tint, size = size) },
        backdropScale = GLASS_RESOLUTION_SCALE,
    ).border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

/**
 * Glass over content that isn't in the page backdrop (video frames, which
 * draw in their own surface): a dark translucent disc with the rim, the way
 * iOS draws controls over a playing video.
 */
@Composable
fun Modifier.videoGlass(shape: CornerBasedShape, alpha: Float = 0.38f): Modifier =
    clip(shape).background(Color.Black.copy(alpha = alpha), shape).border(GLASS_EDGE_WIDTH, Color.White.copy(alpha = 0.14f), shape)
