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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import app.oriel.ui.theme.GlyphPress
import app.oriel.ui.theme.pressEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import app.oriel.ui.glass.glassFallbackColor
import app.oriel.ui.theme.LiquidPill
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.metaballNeck
import app.oriel.ui.theme.project
import app.oriel.ui.theme.rubber
import app.oriel.ui.theme.smoothstep
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.mutableIntStateOf
import app.oriel.ui.theme.GLASS_PRESS_SCALE
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.PressHighlight

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

/** How far the pill and the search circle swell under a press (the mock's +5 px and 4 px). */
private val PILL_PRESS_GROW = 5.dp
private val SEARCH_PRESS_GIVE = 4.dp

/** Within this gap the search circle and the bar fuse with a metaball neck (the mock's goo, about its 6 px blur). */
private val FUSE_GAP = 5.dp

/**
 * The iOS 26-style floating tab bar: a glass pill of tabs with the mock's two-edge selection
 * blob (its edges on separate springs, so it stretches toward the target, necks in the middle
 * and snaps in with overshoot), plus Search as a standalone glass circle at the end that reaches
 * into the bar and fuses with it, metaball-like, when tapped or dragged toward it. With liquid
 * glass off it's BitChord's bar instead: one frosted pill the width of the screen with Search as
 * its last tab. Drag along the tabs to drag the blob, rubber-banding past the ends.
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
    if (LocalLiquidMotion.current) LiquidFloatingTabBar(tabs, selectedIndex, onTabSelected, searchSelected, onSearch, modifier)
    else ClassicFloatingTabBar(tabs, selectedIndex, onTabSelected, searchSelected, onSearch, modifier)
}

/** How far the indicator draws out along its travel at full stride, and how much of that its height gives back. */
private const val STRETCH = 0.16f
private const val SQUASH = 0.5f

/**
 * Liquid motion off: a travelling indicator that stretches as it moves (scaled along its lag
 * behind the target), and Search a circle that swells under the finger.
 */
@Composable
private fun ClassicFloatingTabBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier,
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
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .pressEffect(press, scale = GLASS_PRESS_SCALE)
                    .glassSurface(CircleShape)
                    .selectable(searchSelected, press, indication = null, role = Role.Tab) {
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

/** A tab's icon and label give under the finger, the stretch of the indicator aside (liquid motion off). */
private val TabPress = PressHighlight(Color.Transparent, strength = 0f, scale = 0.92f)

@Composable
private fun LiquidFloatingTabBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier,
) {
    val pillShape = RoundedCornerShape(percent = 50)
    val reduce = LocalReduceAnimation.current
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
    val rowH = rowSize.height.toFloat()
    val scope = rememberCoroutineScope()
    // The blob, read only where it's drawn: a tab change or a drag moves it without recomposing the bar.
    val pill = remember { LiquidPill(0f, 0f, 0f) }
    val placed = remember { booleanArrayOf(false) }
    // A drag's release speed, handed to the spring that carries the blob on to the chosen tab.
    val throwV = remember { floatArrayOf(0f) }
    LaunchedEffect(restIndex, tabStepPx, tabWidthPx, rowH) {
        if (tabStepPx <= 0f) return@LaunchedEffect
        val l = restIndex * tabStepPx
        if (!placed[0]) {
            pill.setNow(this, l, l + tabWidthPx)
            pill.h.snapTo(rowH)
            placed[0] = true
        } else {
            pill.moveTo(scope, l, l + tabWidthPx, throwV[0].takeIf { it != 0f }, reduce)
            throwV[0] = 0f
        }
    }
    val pillAlpha = animateFloatAsState(if (shownIndex >= 0) 1f else 0f, fade(160), label = "pillAlpha")
    val currentRest by rememberUpdatedState(restIndex)
    val currentShown by rememberUpdatedState(shownIndex)
    val currentStep by rememberUpdatedState(tabStepPx)
    val currentWidth by rememberUpdatedState(tabWidthPx)
    val currentSelect by rememberUpdatedState(select)
    val currentReduce by rememberUpdatedState(reduce)
    val tint = if (glass) glassContentColor() else null
    val dark = LocalIsDark.current
    val pillColor = tint?.copy(alpha = if (dark) 0.17f else 0.08f) ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    // The blob's place is kept in reading order; right to left it's drawn, and dragged, mirrored.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sign = if (rtl) -1f else 1f
    val pillPath = remember { Path() }
    // The search circle's reach toward the bar (its near edge), its far edge, and the bar's near edge, in px.
    val reachIn = remember { Animatable(0f) }
    val reachOut = remember { Animatable(0f) }
    val barReach = remember { Animatable(0f) }
    val neck = remember { Path() }
    val bridge = glassFallbackColor()
    val pressGrow = with(density) { PILL_PRESS_GROW.toPx() }

    Row(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = BAR_GUTTER)
            .padding(bottom = 2.dp)
            .widthIn(max = FLOATING_BAR_MAX_WIDTH)
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .then(
                if (!glass || reduce) Modifier else Modifier.drawBehind {
                    // The metaball neck between the bar's end and the search circle, under both glasses.
                    val h = size.height
                    val r = h / 2f
                    val barEnd = size.width - h - BAR_SEARCH_GAP.toPx() + barReach.value
                    val searchStart = size.width - h - reachIn.value
                    var x1 = barEnd - r
                    var x2 = searchStart + r
                    if (rtl) { x1 = size.width - x1; x2 = size.width - x2 }
                    val maxD = 2f * r + FUSE_GAP.toPx()
                    val d = abs(x2 - x1)
                    val closeness = smoothstep(maxD, 2f * r, d)
                    if (closeness > 0f && metaballNeck(neck, x1, r, r, x2, r, r, maxD, spread = 0.15f + 0.45f * closeness)) drawPath(neck, bridge)
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(BAR_SEARCH_GAP),
    ) {
        Box(
            Modifier
                .weight(1f)
                .then(
                    if (!glass) Modifier else Modifier.graphicsLayer {
                        // The bar's near end reaching for the search circle.
                        val w = size.width
                        if (w > 0f && barReach.value != 0f) {
                            transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
                            scaleX = (w + barReach.value) / w
                        }
                    },
                )
                .glassSurface(pillShape)
                .padding(PILL_INSET),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .onSizeChanged { rowSize = it }
                    .drawBehind {
                        val a = pillAlpha.value
                        if (a <= 0f || pill.h.value <= 0f) return@drawBehind
                        pill.path(pillPath, size.height / 2f, reduce, if (rtl) size.width else Float.NaN)
                        drawPath(pillPath, pillColor.copy(alpha = pillColor.alpha * a))
                    }
                    .pointerInput(n) { slotDrag(pill, scope, sign, pressGrow, { currentStep }, { currentWidth }, { currentRest }, { currentShown }, { currentReduce }, n, haptics, throwV) { currentSelect(it) } },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, tab ->
                    TabItem(
                        tab = tab,
                        selected = index == shownIndex,
                        selectedTint = tint,
                        unselectedTint = tint?.copy(alpha = 0.65f),
                        onClick = {
                            // The blob sets off on the tap itself, not frames later when navigation reports the new tab.
                            if (index < tabs.size && tabStepPx > 0f) pill.moveTo(scope, index * tabStepPx, index * tabStepPx + tabWidthPx, reduce = reduce)
                            select(index)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (glass) {
            // Search stands apart, as a circle the bar's height: a droplet that reaches into the bar.
            val searchTint by animateColorAsState(
                if (searchSelected) tint ?: MaterialTheme.colorScheme.primary else (tint?.copy(alpha = 0.8f) ?: MaterialTheme.colorScheme.onSurface),
                fade(200), label = "searchTint",
            )
            val currentOnSearch by rememberUpdatedState(onSearch)
            val give = with(density) { SEARCH_PRESS_GIVE.toPx() }
            val gap = with(density) { BAR_SEARCH_GAP.toPx() }
            val reachFor = { v: Float ->
                // The mock's setSearch: the circle's near edge rushes in on the fast edge spring, the
                // bar's on the slow one, they fuse; then the bar lets go fast and the circle slow.
                scope.launch {
                    launch { barReach.animateTo(gap * 0.75f, Sp.SlowEdge.px) }
                    launch { reachOut.animateTo(0f, Sp.Snappy.px) }
                    reachIn.animateTo(gap * 1.6f, Sp.FastEdge.px, v)
                    launch { barReach.animateTo(0f, Sp.FastEdge.px) }
                    reachIn.animateTo(0f, Sp.SlowEdge.px)
                }
            }
            val searchAt = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
            val recordOrigin = { SearchOrigin.record(searchAt[0]?.takeIf { it.isAttached }?.boundsInWindow()) }
            // Left for a tab: the bar reaches back for the circle and lets it go, a drop splitting off.
            val wasSearching = remember { booleanArrayOf(searchSelected) }
            LaunchedEffect(searchSelected) {
                val was = wasSearching[0]
                wasSearching[0] = searchSelected
                if (was && !searchSelected && !reduce) {
                    launch { reachIn.animateTo(gap * 0.6f, Sp.FastEdge.px) }
                    barReach.animateTo(gap * 0.75f, Sp.FastEdge.px)
                    launch { reachIn.animateTo(0f, Sp.Jelly.px) }
                    barReach.animateTo(0f, Sp.SlowEdge.px)
                }
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .onPlaced { searchAt[0] = it }
                    .graphicsLayer {
                        val w = size.width
                        if (w <= 0f) return@graphicsLayer
                        val inner = reachIn.value
                        val outer = reachOut.value
                        if (inner == 0f && outer == 0f) return@graphicsLayer
                        // Away from the bar is +x left to right.
                        val away = if (rtl) -1f else 1f
                        scaleX = (w + inner + outer) / w
                        translationX = away * (outer - inner) / 2f
                    }
                    .glassSurface(CircleShape)
                    .selectable(searchSelected, remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                        haptics.play(Haptic.Select)
                        if (!reduce) reachFor(0f)
                        recordOrigin()
                        onSearch()
                    }
                    .pointerInput(Unit) {
                        searchDrag(reachIn, reachOut, scope, give, sign, { currentReduce }) { v ->
                            haptics.play(Haptic.Select)
                            reachFor(v)
                            recordOrigin()
                            currentOnSearch()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (searchSelected) Box(Modifier.padding(PILL_INSET).fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(pillColor))
                Icon(OrielIcons.Search, "Search", tint = searchTint, modifier = Modifier.size(26.dp))
            }
        }
    }
}

private val BAR_SEARCH_GAP = 8.dp

/**
 * Where the Search droplet was, in the window, when it was tapped or flicked into the bar: the
 * Search page's field grows out of it (liquid motion). Taken once, and only just after.
 */
object SearchOrigin {
    private var at: androidx.compose.ui.geometry.Rect? = null
    private var recordedAt = 0L

    fun record(r: androidx.compose.ui.geometry.Rect?) {
        at = r
        recordedAt = System.nanoTime()
    }

    fun take(): androidx.compose.ui.geometry.Rect? {
        val r = at?.takeIf { System.nanoTime() - recordedAt < 800_000_000L }
        at = null
        return r
    }
}

/**
 * The mock's `slotDrag` (tab bar, segmented controls): a press grows the blob a little; past the
 * slop a sideways drag takes the finger and the blob follows it, centred under it and
 * rubber-banding past the first and last slot; let go, it picks the slot where the fling would
 * carry it and springs there at the finger's speed ([throwV] hands that speed to whoever moves
 * it on [select]). Slot i spans [i × step, i × step + width]. A tap is left to what's under it.
 */
internal suspend fun PointerInputScope.slotDrag(
    pill: LiquidPill,
    scope: kotlinx.coroutines.CoroutineScope,
    sign: Float,
    pressGrow: Float,
    step: () -> Float,
    width: () -> Float,
    rest: () -> Int,
    shown: () -> Int,
    reduce: () -> Boolean,
    n: Int,
    haptics: app.oriel.ui.haptics.Haptics,
    throwV: FloatArray,
    select: (Int) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val rowH = size.height.toFloat()
    pill.heightTo(scope, rowH + pressGrow, reduce())
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var dragging = false
    var moved = Offset.Zero
    var last = down
    var lastSlot = shown()
    // Reading order: right to left, x runs from the right edge.
    fun local(x: Float) = if (sign < 0f) size.width - x else x
    fun pick(c: Float): Int {
        val s = step()
        return if (s <= 0f) rest() else ((c - width() / 2f) / s).roundToInt().coerceIn(0, n - 1)
    }
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) { last = change; break }
        moved += change.positionChange()
        tracker.addPosition(change.uptimeMillis, change.position)
        if (!dragging && abs(moved.x) > viewConfiguration.touchSlop && abs(moved.x) > abs(moved.y) * 1.2f) dragging = true
        if (dragging) {
            change.consume()
            val s = step()
            val w = width()
            if (s > 0f) {
                val minC = w / 2f
                val maxC = (n - 1) * s + w / 2f
                var c = local(change.position.x)
                val band = 50.dp.toPx()
                c = when {
                    c < minC -> minC - rubber(minC - c, band)
                    c > maxC -> maxC + rubber(c - maxC, band)
                    else -> c
                }
                pill.moveTo(scope, c - w / 2f, c + w / 2f, reduce = reduce())
                val slot = pick(c)
                if (slot != lastSlot) { haptics.play(Haptic.Tick); lastSlot = slot }
            }
        }
        last = change
    }
    pill.heightTo(scope, rowH, reduce())
    if (dragging) {
        val v = tracker.calculateVelocity().x * sign
        val target = pick(local(last.position.x) + project(v, 0.99f) * 0.35f)
        throwV[0] = v
        if (target != shown()) select(target)
        else {
            val l = target * step()
            pill.moveTo(scope, l, l + width(), v, reduce())
            throwV[0] = 0f
        }
    }
}

/**
 * The mock's `searchDrag`: a press draws the circle in a little; dragged toward the bar it
 * stretches into it (and resists the other way); let go far or fast enough toward the bar and it
 * opens Search ([onOpen], with the release speed), otherwise it wobbles back. A tap is the clickable's.
 */
private suspend fun PointerInputScope.searchDrag(
    reachIn: Animatable<Float, *>,
    reachOut: Animatable<Float, *>,
    scope: kotlinx.coroutines.CoroutineScope,
    give: Float,
    sign: Float,
    reduce: () -> Boolean,
    onOpen: (velocity: Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (reduce()) return@awaitEachGesture
    scope.launch { reachIn.animateTo(-give, Sp.Press.px) }
    scope.launch { reachOut.animateTo(-give, Sp.Press.px) }
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var dragging = false
    var dx = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        val d = change.positionChange()
        dx += d.x
        tracker.addPosition(change.uptimeMillis, change.position)
        if (!dragging && abs(dx) > viewConfiguration.touchSlop && abs(d.x) >= abs(d.y)) dragging = true
        if (dragging) {
            change.consume()
            // Toward the bar is positive.
            val toward = -dx * sign
            val inner = if (toward > 0f) rubber(toward, 200.dp.toPx(), 0.8f) else -rubber(-toward, 50.dp.toPx()) * 0.4f
            val outer = if (toward < 0f) rubber(-toward, 40.dp.toPx()) * 0.4f else 0f
            scope.launch { reachIn.animateTo(inner, Sp.Lead.px) }
            scope.launch { reachOut.animateTo(outer, Sp.Lead.px) }
        }
    }
    val v = -tracker.calculateVelocity().x * sign
    if (dragging && (-dx * sign > 50.dp.toPx() || v > 600f)) onOpen(v.coerceAtLeast(0f))
    else if (dragging) {
        scope.launch { reachIn.animateTo(0f, Sp.Jelly.px, v) }
        scope.launch { reachOut.animateTo(0f, Sp.Jelly.px) }
    } else {
        // A tap: the clickable opens Search and reaches; this only lets the press go.
        scope.launch { reachOut.animateTo(0f, Sp.Jelly.px) }
        if (reachIn.targetValue < 0f) scope.launch { reachIn.animateTo(0f, Sp.Jelly.px) }
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
    val scale = animateFloatAsState(if (selected) 1.08f else 1f, motion(GlassSpring), label = "tabScale")
    val haptics = rememberHaptics()
    // Read only where it's drawn, so a tab change's fade doesn't recompose the bar every frame.
    val tint = animateColorAsState(
        if (selected) selectedTint ?: MaterialTheme.colorScheme.primary else unselectedTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        fade(200), label = "tabTint",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // No clip: there's no ripple to bound, and a stadium clip shaves the ends off a long label.
        modifier = modifier
            // Liquid, the blob under it answers the finger instead.
            .then(
                if (LocalLiquidMotion.current) Modifier.selectable(selected, remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                    if (!selected) haptics.play(Haptic.Select)
                    onClick()
                } else Modifier.selectable(selected, null, TabPress, role = Role.Tab) {
                    if (!selected) haptics.play(Haptic.Select)
                    onClick()
                },
            )
            .padding(vertical = TAB_VERTICAL_PADDING, horizontal = 2.dp),
    ) {
        // The label below names the tab; the icon would only say it twice.
        val glyph = rememberVectorPainter(if (selected) tab.selectedIcon else tab.icon)
        Box(
            Modifier.size(25.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .drawBehind { with(glyph) { draw(size, colorFilter = ColorFilter.tint(tint.value)) } },
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
                color = { tint.value },
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

/** The 44dp glass back circle. */
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
