/*
 * Floating chrome adapted from BitChord (GPL-3.0): FloatingBottomBar.kt,
 * GlassNavBar.kt (standalone Search circle), FrostedTopBar.kt (glass circles,
 * topBarContentPadding) and BottomFadeScrim.kt.
 */
package app.oriel.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.ui.glass.glassContentColor
import app.oriel.ui.glass.glassSurface
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.EaseOutCubic
import app.oriel.ui.theme.LocalLiquidGlassEnabled
import app.oriel.ui.theme.fade
import app.oriel.ui.theme.GlyphPress
import app.oriel.ui.theme.pressEffect
import app.oriel.ui.theme.GLASS_PRESS_SCALE

// ---- geometry ------------------------------------------------------------------------------

val PAGE_GUTTER = 16.dp
val BAR_GUTTER = 16.dp
val PILL_INSET = 6.dp
private val GESTURE_BAR_FLOOR = 15.dp
val FLOATING_BAR_MAX_WIDTH = 440.dp

/** 6 + 13 + 26 + 13 + 6: the floating tab bar's height, at its full size. */
val TAB_BAR_HEIGHT = 64.dp

/** How far the floating bars stand off the bottom: the navigation inset, never less than a gesture bar's. */
val floatingBarInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets(bottom = GESTURE_BAR_FLOOR))

val TopBarContentHeight = 52.dp
val TopBarContentGap = 4.dp

@Composable
fun topBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarContentHeight

/** What pages use as their top content padding: under the floating circles, with a 4dp breath. */
@Composable
fun topBarContentPadding(): Dp = topBarHeight() + TopBarContentGap

/** Top padding for a page whose bar has nothing in it: the large title starts right under the status bar. */
@Composable
fun bareTopPadding(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp

/** Bottom content padding that clears the floating tab bar. */
@Composable
fun bottomBarsPadding(withTabBar: Boolean = true): Dp {
    val inset = floatingBarInsets.asPaddingValues().calculateBottomPadding()
    return inset + 2.dp + (if (withTabBar) TAB_BAR_HEIGHT else 0.dp) + 24.dp
}

@Composable
fun pagePadding(top: Dp = topBarContentPadding(), withTabBar: Boolean = true, horizontal: Dp = 0.dp) =
    PaddingValues(start = horizontal, top = top, end = horizontal, bottom = bottomBarsPadding(withTabBar))

// ---- scrims --------------------------------------------------------------------------------

private const val STOPS = 16

/**
 * The page colour fading in under the floating bars, so content dissolves
 * rather than cuts behind them. Colour only, no blur: it stays on under
 * Reduce transparency.
 */
@Composable
fun BottomFadeScrim(modifier: Modifier = Modifier, withTabBar: Boolean = true, pageColor: Color = MaterialTheme.colorScheme.background) {
    val inset = floatingBarInsets.asPaddingValues().calculateBottomPadding()
    val base = if (withTabBar) 180.dp else 110.dp
    val height by animateDpAsState(inset + base, fade(220), label = "bottomScrim")
    val brush = remember(pageColor) {
        Brush.verticalGradient(colorStops = Array(STOPS) { i -> val t = i / (STOPS - 1f); t to pageColor.copy(alpha = EaseInCubic.transform(t)) })
    }
    Box(modifier.fillMaxWidth().height(height).background(brush))
}

/** A short fade under the status bar, so the clock stays legible over a scrolled thumbnail. */
@Composable
fun TopFadeScrim(modifier: Modifier = Modifier, pageColor: Color = MaterialTheme.colorScheme.background, strength: Float = 0.85f) {
    val height = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 28.dp
    val brush = remember(pageColor, strength) {
        Brush.verticalGradient(colorStops = Array(12) { i -> val t = i / 11f; t to pageColor.copy(alpha = strength * (1f - EaseOutCubic.transform(t))) })
    }
    Box(modifier.fillMaxWidth().height(height).background(brush))
}

/**
 * iOS 26's scroll edge: the page colour gathering under the floating bar once content scrolls
 * beneath it, so the bar's title and circles stay legible over whatever passes under. Strong
 * through the status bar and the bar, dissolving just below. [progress] (0 to 1) is read in the
 * layer, so following the scroll never recomposes.
 */
@Composable
fun TopScrollEdge(progress: () -> Float, modifier: Modifier = Modifier, pageColor: Color = MaterialTheme.colorScheme.background) {
    val height = topBarHeight() + 18.dp
    val brush = remember(pageColor) {
        Brush.verticalGradient(colorStops = Array(STOPS) { i -> val t = i / (STOPS - 1f); t to pageColor.copy(alpha = 0.9f * (1f - EaseInCubic.transform(t))) })
    }
    Box(modifier.fillMaxWidth().height(height).graphicsLayer { alpha = progress().coerceIn(0f, 1f) }.background(brush))
}

// ---- floating top bar ----------------------------------------------------------------------

@Composable
fun GlassCircleButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, iconSize: Dp = 22.dp) {
    val haptics = rememberHaptics()
    val tint = if (LocalLiquidGlassEnabled.current) glassContentColor() else MaterialTheme.colorScheme.onSurface
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            // The whole circle swells (or squishes), glass and all, so the press goes before the surface.
            .pressEffect(press, scale = GLASS_PRESS_SCALE)
            .glassSurface(CircleShape)
            .clickable(press, indication = null, role = Role.Button) {
                haptics.play(Haptic.Tap)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** A glass pill holding 44dp icon buttons, BitChord's ArtworkPageActions. */
@Composable
fun GlassActionsPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val tint = if (LocalLiquidGlassEnabled.current) glassContentColor() else MaterialTheme.colorScheme.onSurface
    val glass = Modifier.glassSurface(RoundedCornerShape(percent = 50))
    CompositionLocalProvider(LocalContentColor provides tint) {
        // The glass is a layer of its own under the buttons, so actions that turn out to draw
        // nothing leave no empty pill behind.
        Layout(
            contents = listOf({ Box(glass) }, { Row(Modifier.height(44.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, content = content) }),
            modifier = modifier,
        ) { (pills, rows), constraints ->
            val row = rows.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            if (row.width <= 4.dp.roundToPx()) return@Layout layout(0, 0) {}
            val pill = pills.first().measure(androidx.compose.ui.unit.Constraints.fixed(row.width, row.height))
            layout(row.width, row.height) {
                pill.place(0, 0)
                row.place(0, 0)
            }
        }
    }
}

@Composable
fun PillAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier.size(44.dp).clickable(null, GlyphPress, role = Role.Button) {
            haptics.play(Haptic.Tap)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, modifier = Modifier.size(22.dp)) }
}

/**
 * The transparent top bar every page floats: a back circle on the left, an
 * actions pill on the right, and (optionally) a centred title that fades in
 * once the page's own large title has scrolled under the bar. With
 * [titleProgress] the title follows the scroll, as UINavigationBar's does,
 * read in the layer only; [showTitle] then just says whether it's there for
 * accessibility. Without it, the title fades in and out on [showTitle].
 */
@Composable
fun FloatingTopBar(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String? = null,
    showTitle: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    titleProgress: (() -> Float)? = null,
) {
    CenteredTitleRow(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(TopBarContentHeight)
            .padding(horizontal = BAR_GUTTER),
        start = if (onBack == null && leading == null) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) GlassCircleButton(OrielIcons.Back, "Back", onBack, iconSize = 19.dp)
                    leading?.invoke()
                }
            }
        },
        title = {
            if (title != null && titleProgress != null) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .graphicsLayer {
                            val p = titleProgress().coerceIn(0f, 1f)
                            alpha = p
                            // Rises the last few points into place as it fades in.
                            translationY = (1f - p) * 6.dp.toPx()
                        }
                        // The large title says it until then; this would only say it twice.
                        .then(if (showTitle) Modifier else Modifier.clearAndSetSemantics {}),
                )
            } else if (title != null) {
                AnimatedVisibility(showTitle, enter = fadeIn(fade(220)), exit = fadeOut(fade(220))) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        end = actions?.let { { GlassActionsPill(content = it) } },
    )
}

/**
 * A bar's three slots: [start] and [end] are measured first at their own widths, then [title]
 * is centred between equal margins in what's left, so a wide side never runs under it.
 */
@Composable
internal fun CenteredTitleRow(
    modifier: Modifier,
    start: (@Composable () -> Unit)?,
    title: @Composable () -> Unit,
    end: (@Composable () -> Unit)?,
    gap: Dp = 8.dp,
) {
    Layout(contents = listOf(start ?: {}, title, end ?: {}), modifier = modifier) { (starts, titles, ends), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val s = starts.map { it.measure(loose) }
        val startWidth = s.maxOfOrNull { it.width } ?: 0
        val e = ends.map { it.measure(loose.copy(maxWidth = (loose.maxWidth - startWidth).coerceAtLeast(0))) }
        val side = maxOf(startWidth, e.maxOfOrNull { it.width } ?: 0)
        val margin = if (side > 0) side + gap.roundToPx() else 0
        val t = titles.map { it.measure(loose.copy(maxWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0))) }
        val width = constraints.maxWidth
        val height = (listOf(s, e, t).flatten().maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            s.forEach { it.placeRelative(0, (height - it.height) / 2) }
            e.forEach { it.placeRelative(width - it.width, (height - it.height) / 2) }
            t.forEach { it.placeRelative((width - it.width) / 2, (height - it.height) / 2) }
        }
    }
}
