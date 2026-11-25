/*
 * Section headers, chips, buttons, the search field, the segmented control,
 * message states and skeletons, adapted from BitChord (GPL-3.0): Common.kt,
 * SearchField.kt, Skeletons.kt, DetailScreen.kt (ActionPill, CircleIconButton)
 * and SettingsSheet.kt (SegmentedControl).
 */
package app.oriel.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import app.oriel.ui.theme.fade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableStateListOf
import app.oriel.ui.theme.motion
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Chip
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.GlyphPress
import app.oriel.ui.theme.TilePress
import app.oriel.ui.theme.LiquidPill
import app.oriel.ui.theme.LocalLiquidMotion
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced

// ---- chips ---------------------------------------------------------------------------------

/**
 * A row's travelling selection, as the mock's chips: one [LiquidPill] (neck 0.12) that flows
 * between the chosen items' capsules, which each item reports as it's laid out ([bounds], in
 * the row's coordinates). Drawn by the row under the items and by each item over its own fill,
 * with the item's ink recoloured where the blob covers it.
 */
@Stable
internal class SelectionBlob(n: Int) {
    val pill = LiquidPill(0f, 0f, 0f, neckK = 0.12f)
    val path = Path()
    var row: LayoutCoordinates? = null
    val bounds = mutableStateListOf<Rect>().apply { repeat(n) { add(Rect.Zero) } }
    var placed = false
    var cy = 0f

    fun report(i: Int, item: LayoutCoordinates) {
        val r = row?.takeIf { it.isAttached } ?: return
        if (!item.isAttached) return
        val rect = Rect(r.localPositionOf(item, Offset.Zero), item.size.toSize())
        if (i < bounds.size && bounds[i] != rect) bounds[i] = rect
    }

    /** The outline now, in the row's coordinates (read in the draw phase). */
    fun outline(reduce: Boolean): Path {
        pill.path(path, cy, reduce)
        return path
    }

    var scope: kotlinx.coroutines.CoroutineScope? = null
    var reduce = false

    /** Sets off for item [i] on the tap itself, not a frame or two later when the new selection comes back down. */
    fun go(i: Int) {
        val target = bounds.getOrNull(i) ?: return
        val s = scope ?: return
        if (!placed || target.width <= 0f) return
        cy = target.center.y
        pill.moveTo(s, target.left, target.right, reduce = reduce)
    }

    fun covers(left: Float, right: Float): Boolean {
        val a = minOf(pill.l.value, pill.r.value)
        val b = maxOf(pill.l.value, pill.r.value)
        return placed && b > left && a < right
    }
}

@Composable
internal fun rememberSelectionBlob(n: Int, selected: Int): SelectionBlob {
    val blob = remember(n) { SelectionBlob(n) }
    val target = blob.bounds.getOrNull(selected)
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceAnimation.current
    blob.scope = scope
    blob.reduce = reduce
    LaunchedEffect(blob, selected, target) {
        if (target == null || target.width <= 0f) return@LaunchedEffect
        blob.cy = target.center.y
        if (!blob.placed) {
            blob.pill.setNow(this, target.left, target.right)
            blob.pill.h.snapTo(target.height)
            blob.placed = true
        } else {
            blob.pill.moveTo(scope, target.left, target.right, reduce = reduce)
            blob.pill.heightTo(scope, target.height, reduce)
        }
    }
    return blob
}

/**
 * An item's ink, recoloured to [ink] where [blob] covers it: the mock clips an inverted copy of
 * the labels to the blob's path; here the item's own glyphs are repainted (source-in) inside it.
 * [position] is the item's place in the row, read when drawn.
 */
internal fun Modifier.inkUnderBlob(blob: SelectionBlob, position: () -> Rect, ink: Color, reduce: Boolean): Modifier = this
    .graphicsLayer {
        val p = position()
        compositingStrategy = if (blob.covers(p.left, p.right)) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val p = position()
        if (!blob.covers(p.left, p.right)) return@drawWithContent
        val path = blob.outline(reduce)
        translate(-p.left, -p.top) {
            clipPath(path) { drawRect(ink, topLeft = p.topLeft, size = p.size, blendMode = BlendMode.SrcIn) }
        }
    }

/** BitChord's SearchFilterTabs: capsules on surfaceVariant, and the mock's blob flowing to the chosen one in the inverse colours. */
@Composable
fun ChipsRow(chips: List<Chip>, onSelect: (Chip) -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = PAGE_GUTTER), leading: (@Composable () -> Unit)? = null) {
    if (!LocalLiquidMotion.current) {
        // Each chosen chip fills itself, without the travelling blob.
        Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            leading?.invoke()
            chips.forEach { chip -> FilterChip(chip.title, chip.selected) { onSelect(chip) } }
        }
        return
    }
    val selected = chips.indexOfFirst { it.selected }
    val blob = rememberSelectionBlob(chips.size, selected)
    val reduce = LocalReduceAnimation.current
    val fill = MaterialTheme.colorScheme.onBackground
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding)
            .onPlaced { blob.row = it }
            .drawBehind { if (selected >= 0 && blob.placed) drawPath(blob.outline(reduce), fill) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        chips.forEachIndexed { i, chip -> FilterChip(chip.title, chip.selected, blob = blob.takeIf { selected >= 0 }, index = i) { blob.go(i); onSelect(chip) } }
    }
}

/**
 * A page's sections (a channel's Home, Videos, Shorts…) as iOS sets a scrolling segment: plain
 * labels, and the mock's blob behind the chosen one that flows and necks to the next, rather
 * than Material's underline. Scrolls the chosen one into view.
 */
@Composable
fun TabStrip(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (LocalLiquidMotion.current) LiquidTabStrip(titles, selected, onSelect, modifier) else ClassicTabStrip(titles, selected, onSelect, modifier)
}

/** Liquid motion off: the capsule slides, its leading edge ahead of its width, and each label recolours itself. */
@Composable
private fun ClassicTabStrip(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Each title's drawn capsule (left, top, width, height) in the row, from the left edge whatever the direction.
    val bounds = remember(titles) { mutableStateListOf<androidx.compose.ui.geometry.Rect>().apply { repeat(titles.size) { add(androidx.compose.ui.geometry.Rect.Zero) } } }
    val target = bounds.getOrNull(selected) ?: androidx.compose.ui.geometry.Rect.Zero
    // The leading edge leads going right and trails going left, so the capsule stretches on its way.
    val x by animateFloatAsState(target.left, motion(spring(dampingRatio = 0.82f, stiffness = 460f)), label = "tabX")
    val w by animateFloatAsState(target.width, motion(spring(dampingRatio = 0.82f, stiffness = 360f)), label = "tabW")
    val shown by animateFloatAsState(if (selected in titles.indices) 1f else 0f, fade(160), label = "tabShown")
    val ink = MaterialTheme.colorScheme.onBackground
    val scroll = rememberScrollState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inset = PAGE_GUTTER - 4.dp
    LaunchedEffect(selected, target) {
        if (target.width <= 0f) return@LaunchedEffect
        val pad = with(density) { 40.dp.toPx() }
        val gutter = with(density) { inset.toPx() }
        val view = scroll.viewportSize.toFloat()
        // The scroll runs from the start edge: from the right, right to left.
        val content = scroll.maxValue + view
        val start = if (rtl) content - (gutter + target.left + target.width) else gutter + target.left
        when {
            start - pad < scroll.value -> scroll.animateScrollTo((start - pad).toInt().coerceAtLeast(0))
            start + target.width + pad > scroll.value + view -> scroll.animateScrollTo((start + target.width + pad - view).toInt())
        }
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = inset, vertical = 1.dp)
            .drawBehind {
                if (w > 0f && shown > 0f) drawRoundRect(ink.copy(alpha = shown), topLeft = Offset(x, target.top), size = androidx.compose.ui.geometry.Size(w, target.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(target.height / 2))
            },
    ) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            val color by animateColorAsState(if (on) MaterialTheme.colorScheme.background else ink.copy(alpha = 0.72f), fade(180), label = "tabInk")
            Text(
                title, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1,
                modifier = Modifier
                    .clickable(null, GlyphPress) { if (!on) { haptics.play(Haptic.Select); onSelect(i) } }
                    .semantics { this.selected = on; role = Role.Tab }
                    .minTouchTarget()
                    .onGloballyPositioned { c ->
                        val drawn = androidx.compose.ui.geometry.Rect(c.positionInParent(), c.size.toSize())
                        if (i < bounds.size && bounds[i] != drawn) bounds[i] = drawn
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LiquidTabStrip(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val blob = rememberSelectionBlob(titles.size, selected)
    val bounds = blob.bounds
    val target = bounds.getOrNull(selected) ?: Rect.Zero
    val shown by animateFloatAsState(if (selected in titles.indices) 1f else 0f, fade(160), label = "tabShown")
    val reduce = LocalReduceAnimation.current
    val ink = MaterialTheme.colorScheme.onBackground
    val onBlob = MaterialTheme.colorScheme.background
    val scroll = rememberScrollState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inset = PAGE_GUTTER - 4.dp
    LaunchedEffect(selected, target) {
        if (target.width <= 0f) return@LaunchedEffect
        val pad = with(density) { 40.dp.toPx() }
        val gutter = with(density) { inset.toPx() }
        val view = scroll.viewportSize.toFloat()
        val content = scroll.maxValue + view
        val start = if (rtl) content - (gutter + target.left + target.width) else gutter + target.left
        when {
            start - pad < scroll.value -> scroll.animateScrollTo((start - pad).toInt().coerceAtLeast(0))
            start + target.width + pad > scroll.value + view -> scroll.animateScrollTo((start + target.width + pad - view).toInt())
        }
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = inset, vertical = 1.dp)
            .onPlaced { blob.row = it }
            .drawBehind { if (shown > 0f && blob.placed) drawPath(blob.outline(reduce), ink.copy(alpha = shown)) },
    ) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            Text(
                title, style = MaterialTheme.typography.titleSmall, color = ink.copy(alpha = 0.72f), maxLines = 1,
                modifier = Modifier
                    .clickable(null, GlyphPress) { if (!on) { haptics.play(Haptic.Select); blob.go(i); onSelect(i) } }
                    .semantics { this.selected = on; role = Role.Tab }
                    .minTouchTarget()
                    .onPlaced { blob.report(i, it) }
                    .inkUnderBlob(blob, { bounds.getOrNull(i) ?: Rect.Zero }, onBlob, reduce)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * A capsule chip. [role] is Tab for a choice among chips; a chip that acts (removes a filter) is a
 * Button. In a [ChipsRow] the chosen fill is the row's travelling [blob] rather than its own.
 */
@Composable
fun FilterChip(label: String, selected: Boolean, leading: ImageVector? = null, role: Role = Role.Tab, onClick: () -> Unit) =
    FilterChip(label, selected, leading, role, blob = null, index = -1, onClick = onClick)

@Composable
internal fun FilterChip(label: String, selected: Boolean, leading: ImageVector? = null, role: Role = Role.Tab, blob: SelectionBlob?, index: Int, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val reduce = LocalReduceAnimation.current
    val onBlob = MaterialTheme.colorScheme.onBackground
    val inkOnBlob = MaterialTheme.colorScheme.background
    val fill by animateColorAsState(if (selected && blob == null) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant, fade(160), label = "chipFill")
    val text by animateColorAsState(if (selected && blob == null) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground, fade(160), label = "chipText")
    val position = { blob?.bounds?.getOrNull(index) ?: Rect.Zero }
    Row(
        Modifier
            // A capsule that squishes under the finger, as the mock's chips do.
            .clickable(null, TilePress) {
                if (!selected) haptics.play(Haptic.Select)
                onClick()
            }
            .semantics { if (role == Role.Tab) this.selected = selected; this.role = role }
            .minTouchTarget()
            .then(if (blob != null) Modifier.onPlaced { blob.report(index, it) } else Modifier)
            .clip(CircleShape).background(fill)
            .then(
                if (blob == null) Modifier else Modifier
                    .drawBehind {
                        val p = position()
                        if (!blob.covers(p.left, p.right)) return@drawBehind
                        translate(-p.left, -p.top) { drawPath(blob.outline(reduce), onBlob) }
                    }
                    .inkUnderBlob(blob, position, inkOnBlob, reduce),
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = text, maxLines = 1)
    }
}
