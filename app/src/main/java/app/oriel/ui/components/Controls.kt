/*
 * Section headers, chips, buttons, the search field, the segmented control,
 * message states and skeletons, adapted from BitChord (GPL-3.0): Common.kt,
 * SearchField.kt, Skeletons.kt, DetailScreen.kt (ActionPill, CircleIconButton)
 * and SettingsSheet.kt (SegmentedControl).
 */
package app.oriel.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.heightIn
import app.oriel.ui.theme.fade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.GlyphPress
import app.oriel.ui.theme.TilePress
import app.oriel.ui.theme.LiquidPill
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.Sp
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

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
internal fun TextButton(text: String, onClick: () -> Unit, style: TextStyle, color: Color, padding: PaddingValues) {
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

@Composable
fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit) = SubscribePill(subscribed, onClick)

/**
 * Subscribe or Subscribed as an [ActionPill]. With liquid motion the inverse fill is a drop
 * ([LiquidSubscribePill]): subscribing drains it into the middle, its two edges on their own
 * springs so it necks on the way and pinches off; unsubscribing wells it back up from there,
 * past full and back.
 */
@Composable
fun SubscribePill(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: PillSize = PillSize.Compact) {
    val haptic = if (subscribed) Haptic.ToggleOff else Haptic.ToggleOn
    if (!LocalLiquidMotion.current) {
        ActionPill(if (subscribed) "Subscribed" else "Subscribe", if (subscribed) OrielIcons.Bell else null, onClick, modifier, filled = !subscribed, size = size, haptic = haptic)
        return
    }
    LiquidSubscribePill(subscribed, onClick, modifier, size, haptic)
}

@Composable
private fun LiquidSubscribePill(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier, size: PillSize, haptic: Haptic) {
    val haptics = rememberHaptics()
    val dark = LocalIsDark.current
    val veil = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.09f else 0.06f)
    val drop = MaterialTheme.colorScheme.primary
    val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val content by animateColorAsState(if (subscribed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary, fade(180), label = "subInk")
    val scope = rememberCoroutineScope()
    val pill = remember { LiquidPill(0f, 0f, 0f, neckK = 0.22f, squash = 0.2f) }
    val filled = remember { Animatable(if (subscribed) 0f else 1f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val path = remember { Path() }
    val placed = remember { booleanArrayOf(false) }
    LaunchedEffect(subscribed, box) {
        val w = box.width.toFloat()
        val h = box.height.toFloat()
        if (w <= 0f || h <= 0f) return@LaunchedEffect
        if (!placed[0]) {
            placed[0] = true
            if (subscribed) pill.setNow(this, w / 2f, w / 2f) else pill.setNow(this, 0f, w)
            pill.h.snapTo(if (subscribed) 0f else h)
            return@LaunchedEffect
        }
        if (subscribed) {
            // Drains toward the middle, the leading edge first, and pinches off.
            pill.moveTo(scope, w * 0.5f, w * 0.5f)
            launch { filled.animateTo(0f, Sp.Snappy.unit) }
            pill.h.animateTo(0f, Sp.Bouncy.px)
        } else {
            // Wells up from the middle as a drop and spreads past its ends before it settles.
            if (pill.h.value <= 0.5f) {
                pill.setNow(this, w * 0.5f - h * 0.3f, w * 0.5f + h * 0.3f)
                pill.h.snapTo(h * 0.6f)
            }
            pill.moveTo(scope, 0f, w)
            launch { filled.animateTo(1f, Sp.Snappy.unit) }
            pill.heightTo(scope, h)
        }
    }
    val large = size != PillSize.Compact
    Row(
        modifier
            .clickable(null, TilePress, role = Role.Button) {
                haptics.play(haptic)
                onClick()
            }
            .minTouchTarget()
            .heightIn(min = size.height)
            .clip(CircleShape)
            .background(veil)
            .onSizeChanged { box = it }
            .drawBehind {
                val h = this.size.height
                drawRoundRect(hairline.copy(alpha = hairline.alpha * (1f - filled.value).coerceIn(0f, 1f)), cornerRadius = CornerRadius(h / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                if (pill.h.value <= 0.5f) return@drawBehind
                pill.path(path, h / 2f)
                drawPath(path, drop)
            }
            .padding(horizontal = if (large) 24.dp else 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (subscribed) {
            Icon(OrielIcons.Bell, null, tint = content, modifier = Modifier.size(if (large) 18.dp else 19.dp))
            Spacer(Modifier.width(if (large) 8.dp else 6.dp))
        }
        Text(if (subscribed) "Subscribed" else "Subscribe", style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
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
    shape: Shape = RoundedCornerShape(11.dp),
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val haptics = rememberHaptics()
    Row(
        modifier.heightIn(min = 46.dp).fillMaxWidth().clip(shape).background(fill).padding(start = 8.dp, end = 8.dp),
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
                cursorBrush = SolidColor(app.oriel.ui.theme.accent()),
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

// ---- states --------------------------------------------------------------------------------

/** Empty and error states: centred bodyLarge under its icon in a soft disc, with an optional button. */
@Composable
fun MessageState(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, action: String? = null, onAction: (() -> Unit)? = null, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(18.dp))
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
