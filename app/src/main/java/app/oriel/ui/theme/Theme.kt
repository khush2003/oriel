/*
 * Colour scheme and type scale adapted from BitChord (GPL-3.0),
 * https://github.com/kushagrasinghx/BitChord, with Inter in place of SF Pro.
 */
package app.oriel.ui.theme

import android.app.Activity
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.oriel.R
import app.oriel.data.Accent
import app.oriel.data.ThemeMode

/*
 * Monochrome by design: primary is pure white in dark and pure black in light.
 * There is no brand colour in the scheme; colour comes from the thumbnails
 * (see ArtworkPalette). Only these slots are overridden, as in BitChord, plus the separator.
 */
private val DarkColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF0D0D0F), onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E), onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
    // iOS's separator, for alert hairlines; Material's default here is a lavender.
    outlineVariant = Color(0xFF38383A),
    surfaceContainerHigh = Color(0xFF242426),
)

private val LightColors = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    background = Color.White, onBackground = Color.Black,
    surface = Color(0xFFF7F7F9), onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7), onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFE5E5EA),
    outlineVariant = Color(0xFFC6C6C8),
    surfaceContainerHigh = Color(0xFFFFFFFF),
)

/** YouTube's red, used only where YouTube itself would: the live badge, the progress of a watched video. */
val LiveRed = Color(0xFFFF0033)

/** The creator's heart on a comment and the double-tap heart on a Short. */
val HeartRed = Color(0xFFFF3B5C)

/** iOS's system red for destructive actions, in its dark and light variants. */
val DestructiveRedDark = Color(0xFFFF453A)
val DestructiveRedLight = Color(0xFFD70015)

/** The segmented control's raised thumb in dark mode (iOS's systemGray). */
val SegmentThumbDark = Color(0xFF636366)

/** How strong the colour wash behind a page's glass is: 0 for none. */
val LocalWash = staticCompositionLocalOf { 1f }

/** Dark, a step up from black: what "True black" off shows. */
private val DimDarkColors = DarkColors.copy(background = Color(0xFF0E0E11), surface = Color(0xFF17171B), surfaceVariant = Color(0xFF232327))

/** The accent the person picked; read its colour with [accent]. */
val LocalAccent = staticCompositionLocalOf { Accent.ORIEL }

/**
 * The accent's colour on this theme: brighter on dark, deeper on light, so a tick or a switch
 * drawn in it reads on either. Buttons and chips stay primary; this is for state.
 */
@Composable
fun accent(): Color = accentColor(LocalAccent.current, LocalIsDark.current)

/** The accent over a video frame or artwork, which is dark whatever the theme. */
@Composable
fun accentOnMedia(): Color = accentColor(LocalAccent.current, dark = true)

fun accentColor(accent: Accent, dark: Boolean): Color = when (accent) {
    Accent.ORIEL -> if (dark) Color(0xFFFF7A59) else Color(0xFFF0653F)
    Accent.AMBER -> if (dark) Color(0xFFFF9F0A) else Color(0xFFE07B00)
    Accent.GREEN -> if (dark) Color(0xFF30D158) else Color(0xFF248A3D)
    Accent.BLUE -> if (dark) Color(0xFF0A84FF) else Color(0xFF0071E3)
    Accent.INDIGO -> if (dark) Color(0xFF7D7AFF) else Color(0xFF5856D6)
    Accent.PINK -> if (dark) Color(0xFFFF6482) else Color(0xFFD9336B)
    Accent.GRAPHITE -> if (dark) Color.White else Color.Black
}

/** The "reduce animation" switch: springs snap, crossfades drop. */
val LocalReduceAnimation = staticCompositionLocalOf { false }

/** The "reduce dynamic blur" switch: glass turns solid, blurs are skipped. */
val LocalReduceBlur = staticCompositionLocalOf { false }

/** Whether the floating bars draw liquid glass at all (API 31+ and the setting on). */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

val LocalIsDark = staticCompositionLocalOf { true }

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int, display: Boolean = false) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", if (display) 32f else 14f)),
)

/** Inter, whose text cut is tuned for sizes under 20sp. */
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

/** Inter Display (the same file at its largest optical size) for the big iOS titles. */
val InterDisplay = FontFamily(inter(700, display = true), inter(800, display = true))

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, weight: Int, size: Int, tracking: Float, lineHeight: Float): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    letterSpacing = if (tracking == 0f) TextUnit.Unspecified else tracking.sp,
    lineHeight = (size * lineHeight).sp,
    lineHeightStyle = Trim,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/*
 * BitChord's scale with Inter's corrections: about −0.2sp more tracking on the
 * display, headline and title styles (Inter is wider than SF at a size), and
 * explicit line heights, since Inter's native leading is looser than SF's.
 */
private val OrielTypography: Typography = Typography().let { base ->
    fun TextStyle.inter() = copy(fontFamily = Inter, lineHeightStyle = Trim, platformStyle = PlatformTextStyle(includeFontPadding = false))
    Typography(
        displayLarge = style(InterDisplay, 800, 34, -1.0f, 1.2f),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = style(InterDisplay, 800, 30, -0.9f, 1.2f),
        headlineMedium = style(Inter, 700, 22, -0.6f, 1.22f),
        headlineSmall = style(Inter, 700, 20, -0.5f, 1.25f),
        titleLarge = style(Inter, 700, 20, -0.5f, 1.25f),
        titleMedium = style(Inter, 600, 16, -0.3f, 1.3f),
        titleSmall = style(Inter, 600, 14, -0.15f, 1.3f),
        bodyLarge = style(Inter, 400, 16, -0.2f, 1.32f),
        bodyMedium = style(Inter, 400, 14, -0.1f, 1.32f),
        bodySmall = style(Inter, 400, 12, 0f, 1.33f),
        labelLarge = style(Inter, 600, 14, -0.1f, 1.3f),
        labelMedium = style(Inter, 600, 12, 0f, 1.3f),
        labelSmall = style(Inter, 600, 11, 0f, 1.3f),
    )
}

@Composable
fun OrielTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    reduceAnimation: Boolean = false,
    reduceBlur: Boolean = false,
    liquidGlass: Boolean = true,
    liquidMotion: Boolean = false,
    forceDark: Boolean = false,
    accent: Accent = Accent.ORIEL,
    wash: Float = 1f,
    trueBlack: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    SystemBarIcons(dark)
    MaterialTheme(colorScheme = if (!dark) LightColors else if (trueBlack) DarkColors else DimDarkColors, typography = OrielTypography) {
        CompositionLocalProvider(
            LocalIsDark provides dark,
            LocalAccent provides accent,
            LocalWash provides wash,
            androidx.compose.foundation.LocalIndication provides remember(dark) { PressHighlight(pressGlow(dark), strength = ROW_PRESS_STRENGTH, wash = rowWash(dark)) },
            LocalReduceAnimation provides reduceAnimation,
            // Never under Reduce motion, the app's or the system's (animations off in Developer options or Accessibility).
            LocalLiquidMotion provides (liquidMotion && !reduceAnimation && !systemAnimationsOff()),
            LocalReduceBlur provides reduceBlur,
            LocalLiquidGlassEnabled provides (liquidGlass && !reduceBlur && app.oriel.ui.glass.isGlassSupported()),
            LocalOverscrollFactory provides app.oriel.ui.glass.rememberIosOverscrollFactory(),
            // Text outside a Surface takes the page's colour, not Material's black default.
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}

/** A dark island inside a light app (the video player, Shorts): same type, dark colours. */
@Composable
fun DarkIsland(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = MaterialTheme.typography) {
        CompositionLocalProvider(
            LocalIsDark provides true,
            androidx.compose.foundation.LocalIndication provides remember { PressHighlight(pressGlow(true), strength = ROW_PRESS_STRENGTH, wash = rowWash(true)) },
            androidx.compose.material3.LocalContentColor provides DarkColors.onBackground,
            content = content,
        )
    }
}

/** Whether the system has animations turned off (animator duration scale 0). */
@Composable
private fun systemAnimationsOff(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

@Composable
private fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
