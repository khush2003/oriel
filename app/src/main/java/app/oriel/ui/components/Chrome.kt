/*
 * Floating chrome adapted from BitChord (GPL-3.0): FloatingBottomBar.kt,
 * GlassNavBar.kt (standalone Search circle), FrostedTopBar.kt (glass circles,
 * topBarContentPadding) and BottomFadeScrim.kt.
 */
package app.oriel.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.oriel.ui.glass.glassContentColor
import app.oriel.ui.glass.glassSurface
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.GlassSpring
import app.oriel.ui.theme.EaseOutCubic
import app.oriel.ui.theme.LocalLiquidGlassEnabled
import app.oriel.ui.theme.fade
import app.oriel.ui.theme.motion
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- geometry ------------------------------------------------------------------------------

val PAGE_GUTTER = 16.dp
val BAR_GUTTER = 16.dp
val PILL_INSET = 6.dp
private val TAB_VERTICAL_PADDING = 9.dp
private val TAB_ICON_LABEL_GAP = 2.dp
private val GESTURE_BAR_FLOOR = 15.dp
val FLOATING_BAR_MAX_WIDTH = 440.dp

/** 6 + 9 + 25 + 2 + 13 + 9 + 6: the floating tab bar's height. */
val TAB_BAR_HEIGHT = 70.dp
val MINI_PLAYER_HEIGHT = 56.dp
val MINI_PLAYER_GAP = 8.dp

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

/** Bottom content padding that clears the floating tab bar, and the mini player above it when one shows. */
@Composable
fun bottomBarsPadding(withMiniPlayer: Boolean, withTabBar: Boolean = true): Dp {
    val inset = floatingBarInsets.asPaddingValues().calculateBottomPadding()
    return inset + 2.dp + (if (withTabBar) TAB_BAR_HEIGHT else 0.dp) + (if (withMiniPlayer) MINI_PLAYER_HEIGHT + MINI_PLAYER_GAP else 0.dp) + 24.dp
}

@Composable
fun pagePadding(withMiniPlayer: Boolean, top: Dp = topBarContentPadding(), withTabBar: Boolean = true) =
    PaddingValues(top = top, bottom = bottomBarsPadding(withMiniPlayer, withTabBar))

// ---- floating tab bar ----------------------------------------------------------------------

data class BottomTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** How far the indicator draws out along its travel at full stride, and how much of that its height gives back. */
private const val STRETCH = 0.16f
private const val SQUASH = 0.5f

/**
 * The iOS 26-style floating tab bar: a glass pill of tabs with a travelling
 * selection indicator that stretches as it moves, plus Search as a standalone
 * glass circle at the end. With liquid glass off it's BitChord's bar instead:
 * one frosted pill the width of the screen with Search as its last tab, in
 * the theme's accent. Drag across the tabs to scrub between them.
 */
@Composable
fun FloatingTabBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(percent = 50)
    val spec = motion(GlassSpring)
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 6.dp.toPx() }
    val glass = LocalLiquidGlassEnabled.current
    // Without glass, Search is a tab like the others rather than a circle of its own.
    val items = if (glass) tabs else tabs + BottomTab("Search", OrielIcons.Search)
    val n = items.size
    val select = { i: Int -> if (i >= tabs.size) onSearch() else onTabSelected(i) }
    val tabWidthPx = if (rowSize.width > 0 && n > 0) (rowSize.width - gapPx * (n - 1)) / n else 0f
    val tabStepPx = if (rowSize.width > 0 && n > 0) (rowSize.width + gapPx) / n else 0f
    val shownIndex = if (searchSelected) (if (glass) -1 else tabs.size) else selectedIndex
    val restIndex = shownIndex.coerceAtLeast(0)
    val restTargetPx = if (tabStepPx > 0f) restIndex * tabStepPx else 0f
    // The pill's place, read only in its layer: a tab change or a drag moves it without
    // recomposing the bar. A drag snaps it under the finger; on release it springs on from there.
    val pill = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(restTargetPx) {
        if (tabStepPx <= 0f) return@LaunchedEffect
        if (!placed) { pill.snapTo(restTargetPx); placed = true } else pill.animateTo(restTargetPx, spec)
    }
    val pillAlpha = animateFloatAsState(if (shownIndex >= 0) 1f else 0f, fade(160), label = "pillAlpha")
    var lastHapticTab by remember { mutableIntStateOf(restIndex) }
    val currentRest by rememberUpdatedState(restIndex)
    val currentShown by rememberUpdatedState(shownIndex)
    val currentStep by rememberUpdatedState(tabStepPx)
    val currentTarget by rememberUpdatedState(restTargetPx)
    val currentSelect by rememberUpdatedState(select)
    val tint = if (glass) glassContentColor() else null
    // The pill's place is kept in reading order; right to left it's drawn, and dragged, mirrored.
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

    Row(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = BAR_GUTTER)
            .padding(bottom = 2.dp)
            .widthIn(max = FLOATING_BAR_MAX_WIDTH)
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .glassSurface(pillShape)
                .padding(PILL_INSET),
        ) {
            if (tabWidthPx > 0f) {
                Box(
                    Modifier
                        .width(with(density) { tabWidthPx.toDp() })
                        .height(with(density) { rowSize.height.toDp() })
                        .graphicsLayer {
                            val lag = if (currentStep > 0f) (abs(currentTarget - pill.value) / currentStep).coerceIn(0f, 1f) else 0f
                            translationX = pill.value * sign
                            scaleX = 1f + lag * STRETCH
                            scaleY = 1f - lag * STRETCH * SQUASH
                            alpha = pillAlpha.value
                        }
                        .clip(pillShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .onSizeChanged { rowSize = it }
                    .pointerInput(n) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragCancel = { scope.launch { pill.animateTo(currentTarget, spec) } },
                            onDragEnd = {
                                val step = currentStep
                                var moved = false
                                if (step > 0f) {
                                    val newIndex = (currentRest + totalDrag / step).coerceIn(0f, (n - 1).toFloat()).roundToInt()
                                    if (newIndex != currentShown) { currentSelect(newIndex); moved = true }
                                }
                                if (!moved) scope.launch { pill.animateTo(currentTarget, spec) }
                            },
                            onHorizontalDrag = { _, delta ->
                                val step = currentStep
                                if (step <= 0f) return@detectHorizontalDragGestures
                                totalDrag += delta * sign
                                val f = (currentRest + totalDrag / step).coerceIn(0f, (n - 1).toFloat())
                                scope.launch { pill.snapTo(f * step) }
                                val approx = f.roundToInt()
                                if (approx != lastHapticTab) {
                                    haptics.play(Haptic.Tick)
                                    lastHapticTab = approx
                                }
                            },
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, tab ->
                    TabItem(
                        tab = tab,
                        selected = index == shownIndex,
                        selectedTint = tint,
                        unselectedTint = tint?.copy(alpha = 0.65f),
                        onClick = { select(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (glass) {
            // Search stands apart, as a circle the bar's height.
            val searchTint by animateColorAsState(
                if (searchSelected) tint ?: MaterialTheme.colorScheme.primary else (tint?.copy(alpha = 0.8f) ?: MaterialTheme.colorScheme.onSurface),
                fade(200), label = "searchTint",
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .glassSurface(CircleShape)
                    .selectable(searchSelected, remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                        haptics.play(Haptic.Select)
                        onSearch()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (searchSelected) Box(Modifier.padding(PILL_INSET).fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)))
                Icon(OrielIcons.Search, "Search", tint = searchTint, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTint: Color? = null,
    unselectedTint: Color? = null,
) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, motion(GlassSpring), label = "tabScale")
    val haptics = rememberHaptics()
    val tint by animateColorAsState(
        if (selected) selectedTint ?: MaterialTheme.colorScheme.primary else unselectedTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        fade(200), label = "tabTint",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // No clip: there's no ripple to bound, and a stadium clip shaves the ends off a long label.
        modifier = modifier
            .selectable(selected, remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                if (!selected) haptics.play(Haptic.Select)
                onClick()
            }
            .padding(vertical = TAB_VERTICAL_PADDING, horizontal = 2.dp),
    ) {
        // The label below names the tab; the icon would only say it twice.
        Icon(
            if (selected) tab.selectedIcon else tab.icon, null, tint = tint,
            modifier = Modifier.size(25.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        )
        Spacer(Modifier.height(TAB_ICON_LABEL_GAP))
        // The bar is a fixed TAB_BAR_HEIGHT that the mini player and page padding stand on, so its
        // label doesn't grow past the default size, as iOS's tab bar labels don't; it can shrink.
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1f))) {
            // Long labels ("Subscriptions") step down in size rather than truncate, as iOS tab bars do.
            androidx.compose.foundation.text.BasicText(
                tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = { tint },
                maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelSmall.fontSize, stepSize = 0.5.sp),
            )
        }
    }
}

// ---- scrims --------------------------------------------------------------------------------

private const val STOPS = 16

/**
 * The page colour fading in under the floating bars, so content dissolves
 * rather than cuts behind them. Colour only, no blur: it stays on under
 * "reduce dynamic blur".
 */
@Composable
fun BottomFadeScrim(modifier: Modifier = Modifier, withMiniPlayer: Boolean = false, withTabBar: Boolean = true, pageColor: Color = MaterialTheme.colorScheme.background) {
    val inset = floatingBarInsets.asPaddingValues().calculateBottomPadding()
    val base = if (withTabBar) 180.dp else 110.dp
    val height by animateDpAsState(inset + if (withMiniPlayer) base + 74.dp else base, fade(220), label = "bottomScrim")
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

// ---- floating top bar ----------------------------------------------------------------------

/** The 44dp glass back circle. */
@Composable
fun GlassCircleButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, iconSize: Dp = 22.dp) {
    val haptics = rememberHaptics()
    val tint = if (LocalLiquidGlassEnabled.current) glassContentColor() else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(size)
            .glassSurface(CircleShape)
            .clickable {
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
    CompositionLocalProvider(LocalContentColor provides tint) {
        Row(modifier.height(44.dp).glassSurface(RoundedCornerShape(percent = 50)).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
fun PillAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable {
            haptics.play(Haptic.Tap)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, modifier = Modifier.size(22.dp)) }
}

/**
 * The transparent top bar every page floats: a back circle on the left, an
 * actions pill on the right, and (optionally) a centred title that fades in
 * once the page's own large title has scrolled under the bar.
 */
@Composable
fun FloatingTopBar(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String? = null,
    showTitle: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
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
            if (title != null) {
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
