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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import app.oriel.ui.theme.fade
import app.oriel.ui.theme.SegmentThumbDark
import app.oriel.ui.glass.glassEdgeColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableStateListOf
import app.oriel.ui.theme.motion
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.oriel.core.model.Chip
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.GlyphPress
import app.oriel.ui.theme.TilePress
import app.oriel.ui.theme.TILE_PRESS_SCALE
import app.oriel.ui.theme.pressEffect

// ---- headers & text -------------------------------------------------------------------------

/** headlineMedium, padding h16 v10, with an optional subtitle and a "Show all". */
@Composable
fun SectionHeader(title: String, subtitle: String? = null, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.onBackground, onMore: (() -> Unit)? = null, moreLabel: String = "Show all") {
    // The vertical padding is the title's, so "Show all" can take a 44dp target out of it without growing the row.
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        if (onMore != null) TextButton(moreLabel, onMore, MaterialTheme.typography.titleSmall, MaterialTheme.colorScheme.primary, PaddingValues(4.dp))
    }
}

/** iOS's minimum hit area. */
val MIN_TOUCH = 44.dp

/**
 * Grows the layout, not the drawing, to a [size] square at least, with what follows centred in
 * it at its own size. Put the clickable before it, so the click takes the whole area.
 */
fun Modifier.minTouchTarget(size: Dp = MIN_TOUCH): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val min = size.roundToPx()
    val width = maxOf(placeable.width, min, constraints.minWidth).coerceAtMost(constraints.maxWidth)
    val height = maxOf(placeable.height, min, constraints.minHeight).coerceAtMost(constraints.maxHeight)
    layout(width, height) { placeable.placeRelative((width - placeable.width) / 2, (height - placeable.height) / 2) }
}

/** A text button: a 44dp target, the label dimming under the finger as iOS's do, and a tap. */
@Composable
private fun TextButton(text: String, onClick: () -> Unit, style: TextStyle, color: Color, padding: PaddingValues) {
    val haptics = rememberHaptics()
    Text(
        text, style = style, color = color, maxLines = 1,
        modifier = Modifier
            .clickable(null, GlyphPress, role = Role.Button) { haptics.play(Haptic.Tap); onClick() }
            .minTouchTarget()
            .padding(padding),
    )
}

/** The iOS large title: displayLarge, padding h16 v8, as the first scrolling item. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onBackground, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.displayLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** Text that shows [collapsedLines] and a "more" that opens the rest in place. */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = LocalContentColor.current, collapsedLines: Int = 3) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier.animateContentSize(motion(spring(stiffness = 600f))).clickable(enabled = overflows || expanded, indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded }) {
        Text(text, style = style, color = color, maxLines = if (expanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
        if (overflows && !expanded) Text("more", style = style.copy(fontWeight = FontWeight.W600), color = color)
    }
}

// ---- chips ---------------------------------------------------------------------------------

/** BitChord's SearchFilterTabs: corner 12, selected in the inverse colours, everything else on surfaceVariant. */
@Composable
fun ChipsRow(chips: List<Chip>, onSelect: (Chip) -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = PAGE_GUTTER), leading: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leading?.invoke()
        chips.forEach { chip -> FilterChip(chip.title, chip.selected) { onSelect(chip) } }
    }
}

/**
 * A page's sections (a channel's Home, Videos, Shorts…) as iOS sets a scrolling segment: plain
 * labels, and one capsule behind the chosen one that slides and stretches to the next, rather
 * than Material's underline. Scrolls the chosen one into view.
 */
@Composable
fun TabStrip(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
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

/** A capsule chip. [role] is Tab for a choice among chips; a chip that acts (removes a filter) is a Button. */
@Composable
fun FilterChip(label: String, selected: Boolean, leading: ImageVector? = null, role: Role = Role.Tab, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant, fade(160), label = "chipFill")
    val text by animateColorAsState(if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground, fade(160), label = "chipText")
    Row(
        Modifier
            // A capsule that gives a little under the finger, as iOS's segmented pills do.
            .clickable(null, TilePress) {
                if (!selected) haptics.play(Haptic.Select)
                onClick()
            }
            .semantics { if (role == Role.Tab) this.selected = selected; this.role = role }
            .minTouchTarget()
            .clip(CircleShape).background(fill).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = text, maxLines = 1)
    }
}

// ---- buttons -------------------------------------------------------------------------------

/** An [ActionPill]'s height: Large for page actions (Play, Shuffle), Medium for a state's button, Compact in rows. */
enum class PillSize(val height: Dp) { Large(50.dp), Medium(44.dp), Compact(36.dp) }

/**
 * BitChord's ActionPill: filled is the inverse colour, unfilled is a faint veil with a
 * hairline. At least [size] tall, taller if the text is; a Compact pill still takes a 44dp tap.
 */
@Composable
fun ActionPill(
    text: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: PillSize = PillSize.Large,
    haptic: Haptic = Haptic.Tap,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    val dark = LocalIsDark.current
    val fill = when {
        filled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.09f else 0.06f)
    }
    val content = tint ?: if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val large = size != PillSize.Compact
    Row(
        modifier
            .clickable(null, TilePress, enabled = enabled, role = Role.Button) {
                haptics.play(haptic)
                onClick()
            }
            .minTouchTarget()
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .heightIn(min = size.height)
            .clip(CircleShape)
            .background(fill)
            .then(if (filled) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), CircleShape))
            .padding(horizontal = if (large) 24.dp else 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(if (large) 18.dp else 19.dp))
        if (icon != null && text != null) Spacer(Modifier.width(if (large) 8.dp else 6.dp))
        if (text != null) Text(text, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** Subscribe: the filled inverse pill; Subscribed: the quiet veil with a bell. */
@Composable
fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit) {
    ActionPill(
        text = if (subscribed) "Subscribed" else "Subscribe",
        icon = if (subscribed) OrielIcons.Bell else null,
        onClick = onClick,
        filled = !subscribed,
        size = PillSize.Compact,
        haptic = if (subscribed) Haptic.ToggleOff else Haptic.ToggleOn,
    )
}

/** BitChord's Search "Clear" pill and other text buttons: titleMedium in primary, on a 44dp target. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary) {
    TextButton(text, onClick, MaterialTheme.typography.titleMedium, color, PaddingValues(horizontal = 10.dp, vertical = 4.dp))
}

// ---- search field --------------------------------------------------------------------------

/** 46dp on surfaceVariant, corner 11: the magnifier in its 32dp circle, then the text, then a clear button. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search YouTube",
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    /** Shown at the end while the field is empty, where Clear goes once there's text: a microphone. */
    idleTrailing: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    Row(
        modifier.heightIn(min = 46.dp).fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).padding(6.dp)) { Icon(OrielIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(value) }),
                modifier = Modifier.fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .then(if (onFocus != null) Modifier.onFocusChanged { onFocus(it.isFocused) } else Modifier),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(MIN_TOUCH).clickable(null, GlyphPress, onClickLabel = "Clear") { haptics.play(Haptic.Tap); onValueChange("") }, contentAlignment = Alignment.Center) {
                Icon(OrielIcons.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        } else idleTrailing?.invoke()
    }
}

// ---- segmented control ---------------------------------------------------------------------

/**
 * iOS's segmented control: a recessed track, and a raised thumb that slides (with a little
 * stretch) to the chosen segment rather than each segment flipping its own fill. [track],
 * [thumb] and [ink] default to the app's scheme; the watch page passes its artwork colours.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    track: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalIsDark.current) 0.12f else 0.07f),
    thumb: Color = if (LocalIsDark.current) SegmentThumbDark else Color.White,
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    val haptics = rememberHaptics()
    val index = options.indexOf(selected).coerceAtLeast(0)
    val at by animateFloatAsState(index.toFloat(), motion(spring(dampingRatio = 0.78f, stiffness = 520f)), label = "segThumb")
    // The layer's offset doesn't mirror, so right to left the thumb steps the other way.
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    // Each segment's presses: the chosen one's shrinks the thumb, as iOS's does; the others dim their label.
    val presses = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    // At least 34dp, and as tall as the labels at a large font size.
    Box(modifier.fillMaxWidth().heightIn(min = 34.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(9.dp)).background(track).padding(2.dp)) {
        // The thumb, with the soft shadow iOS gives it in light mode.
        Box(
            Modifier
                .fillMaxWidth(1f / options.size.coerceAtLeast(1)).fillMaxHeight()
                .graphicsLayer { translationX = at * size.width * sign }
                .then(presses.getOrNull(index)?.let { Modifier.pressEffect(it, scale = TILE_PRESS_SCALE) } ?: Modifier)
                .shadow(if (LocalIsDark.current) 0.dp else 3.dp, RoundedCornerShape(7.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                .clip(RoundedCornerShape(7.dp))
                .background(thumb),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, option ->
                val on = i == index
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                        .clickable(presses[i], if (on) null else SegmentPress) { if (!on) { haptics.play(Haptic.Select); onSelect(option) } }
                        .semantics { this.selected = on; role = Role.RadioButton },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.W600 else FontWeight.W500), color = ink, maxLines = 1, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

/** An unchosen segment's label dims under the finger, less than a button's: it sits on a track. */
private val SegmentPress = app.oriel.ui.theme.PressHighlight(Color.Transparent, dim = 0.5f)

// ---- states --------------------------------------------------------------------------------

/** Empty and error states: centred bodyLarge, with an optional button. */
@Composable
fun MessageState(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, action: String? = null, onAction: (() -> Unit)? = null, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(14.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(action, null, onAction, filled = true, size = PillSize.Medium)
                if (secondary != null && onSecondary != null) ActionPill(secondary, null, onSecondary, size = PillSize.Medium)
            }
        }
    }
}

@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        ActivityIndicator()
    }
}

/**
 * iOS's activity indicator: eight rounded spokes, the lit one stepping round once a second
 * and the rest trailing off. Drawn in one layer; only the draw phase ticks.
 */
@Composable
fun ActivityIndicator(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val reduce = LocalReduceAnimation.current
    val turn = if (reduce) null else rememberInfiniteTransition(label = "spin").animateFloat(0f, 8f, infiniteRepeatable(tween(960, easing = LinearEasing)), label = "spin")
    Canvas(modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        drawActivitySpokes(color, step = turn?.value?.toInt() ?: 0)
    }
}

/**
 * The activity indicator's eight spokes: the lit one at [step], the rest trailing off. [shown]
 * fewer than eight draws only the first ones, evenly grey, as iOS's pull to refresh fills in
 * spoke by spoke while it's being pulled.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawActivitySpokes(color: Color, step: Int, shown: Int = 8) {
    val w = this.size.minDimension
    val stroke = w * 0.095f
    for (i in 0 until shown.coerceIn(0, 8)) {
        val a = if (shown < 8) 0.5f else (1f - ((step - i + 8) % 8) / 8f).coerceAtLeast(0.22f)
        // From twelve o'clock, clockwise.
        rotate(i * 45f) {
            drawLine(color.copy(alpha = color.alpha * a), Offset(w / 2, w * 0.1f), Offset(w / 2, w * 0.33f), stroke, cap = StrokeCap.Round)
        }
    }
}

// ---- skeletons -----------------------------------------------------------------------------

/**
 * A highlight band half the screen wide sweeping left to right every [SHIMMER_PERIOD_MS], over
 * surfaceVariant. Every placeholder on screen shares the one band, timed off the frame clock and
 * placed in window coordinates, so a page of skeletons shimmers as one sheet rather than each
 * bone on its own clock. Draw-only: it never recomposes.
 */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceVariant
    if (LocalReduceAnimation.current) return@composed background(base)
    background(base).then(ShimmerElement(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)))
}

private const val SHIMMER_PERIOD_MS = 1400L

private data class ShimmerElement(val highlight: Color) : androidx.compose.ui.node.ModifierNodeElement<ShimmerNode>() {
    override fun create() = ShimmerNode(highlight)
    override fun update(node: ShimmerNode) { node.highlight = highlight }
    override fun androidx.compose.ui.platform.InspectorInfo.inspectableProperties() {
        name = "shimmer"
        properties["highlight"] = highlight
    }
}

private class ShimmerNode(var highlight: Color) : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode, androidx.compose.ui.node.GlobalPositionAwareModifierNode {
    private var phase = 0f
    private var left = 0f
    private var span = 0f

    override fun onAttach() {
        coroutineScope.launch {
            while (true) {
                androidx.compose.runtime.withFrameMillis { t -> phase = (t % SHIMMER_PERIOD_MS) / SHIMMER_PERIOD_MS.toFloat() }
                invalidateDraw()
            }
        }
    }

    override fun onGloballyPositioned(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        left = coordinates.positionInWindow().x
        span = coordinates.findRootCoordinates().size.width.toFloat()
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        drawContent()
        val width = if (span > 0f) span else size.width
        val band = width * 0.5f
        val x = -band + (width + band * 2) * phase - left
        if (x + band / 2 < 0f || x - band / 2 > size.width) return
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, highlight, Color.Transparent), startX = x - band / 2, endX = x + band / 2))
    }
}

private val TitleWidths = floatArrayOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val SubtitleWidths = floatArrayOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

@Composable
fun VideoCardSkeleton(index: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(CARD_CORNER)).shimmer())
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(start = 2.dp)) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(TitleWidths[index % 5] + 0.2f).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth(SubtitleWidths[index % 5] + 0.15f).height(11.dp).clip(RoundedCornerShape(4.dp)).shimmer())
            }
        }
    }
}

@Composable
fun VideoRowSkeleton(index: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(ROW_CORNER)).shimmer())
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(TitleWidths[index % 5] + 0.2f).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmer())
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth(SubtitleWidths[index % 5] + 0.2f).height(11.dp).clip(RoundedCornerShape(4.dp)).shimmer())
        }
    }
}

@Composable
fun SkeletonLine(widthFraction: Float, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(widthFraction).height(height).clip(RoundedCornerShape(4.dp)).shimmer())
}

// ---- notice ----------------------------------------------------------------------------------

/**
 * BitChord's QueueActionNotice: a short-lived glass banner over the bars. Announced politely as
 * it arrives. On light, where the card is the page's own white, a soft shadow sets it off.
 */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    val dark = LocalIsDark.current
    Row(
        modifier
            .padding(horizontal = BAR_GUTTER)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .shadow(if (dark) 0.dp else 12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, glassEdgeColor(), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Spacer(Modifier.width(14.dp))
            TextButton(action, onAction, MaterialTheme.typography.titleSmall, MaterialTheme.colorScheme.primary, PaddingValues(4.dp))
        }
    }
}
