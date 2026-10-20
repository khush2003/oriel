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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableStateListOf
import app.oriel.ui.theme.motion
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

// ---- headers & text -------------------------------------------------------------------------

/** headlineMedium, padding h16 v10, with an optional subtitle and a "Show all". */
@Composable
fun SectionHeader(title: String, subtitle: String? = null, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.onBackground, onMore: (() -> Unit)? = null, moreLabel: String = "Show all") {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        if (onMore != null) Text(moreLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onMore).padding(4.dp))
    }
}

/** The iOS large title: displayLarge, padding h16 v8, as the first scrolling item. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onBackground, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.displayLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Text that shows [collapsedLines] and a "more" that opens the rest in place. */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = LocalContentColor.current, collapsedLines: Int = 3) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier.animateContentSize(spring(stiffness = 600f)).clickable(enabled = overflows || expanded, indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded }) {
        Text(text, style = style, color = color, maxLines = if (expanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
        if (overflows && !expanded) Text("more", style = style.copy(fontWeight = FontWeight.W600), color = color)
    }
}

// ---- chips ---------------------------------------------------------------------------------

/** BitChord's SearchFilterTabs: corner 12, selected in the inverse colours, everything else on surfaceVariant. */
@Composable
fun ChipsRow(chips: List<Chip>, onSelect: (Chip) -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = PAGE_GUTTER, vertical = 6.dp), leading: (@Composable () -> Unit)? = null) {
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
    val bounds = remember(titles) { mutableStateListOf<Pair<Float, Float>>().apply { repeat(titles.size) { add(0f to 0f) } } }
    val target = bounds.getOrNull(selected) ?: (0f to 0f)
    // The leading edge leads going right and trails going left, so the capsule stretches on its way.
    val x by animateFloatAsState(target.first, spring(dampingRatio = 0.82f, stiffness = 460f), label = "tabX")
    val w by animateFloatAsState(target.second, spring(dampingRatio = 0.82f, stiffness = 360f), label = "tabW")
    val shown by animateFloatAsState(if (selected in titles.indices) 1f else 0f, tween(160), label = "tabShown")
    val ink = MaterialTheme.colorScheme.onBackground
    val scroll = rememberScrollState()
    LaunchedEffect(selected, target) {
        if (target.second <= 0f) return@LaunchedEffect
        val pad = with(density) { 40.dp.toPx() }
        val view = scroll.viewportSize.toFloat()
        when {
            target.first - pad < scroll.value -> scroll.animateScrollTo((target.first - pad).toInt().coerceAtLeast(0))
            target.first + target.second + pad > scroll.value + view -> scroll.animateScrollTo((target.first + target.second + pad - view).toInt())
        }
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = PAGE_GUTTER - 4.dp, vertical = 6.dp)
            .drawBehind {
                if (w > 0f && shown > 0f) drawRoundRect(ink.copy(alpha = shown), topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            },
    ) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            val color by animateColorAsState(if (on) MaterialTheme.colorScheme.background else ink.copy(alpha = 0.72f), tween(180), label = "tabInk")
            Text(
                title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600), color = color, maxLines = 1,
                modifier = Modifier
                    .onGloballyPositioned { c ->
                        val left = c.positionInParent().x
                        val width = c.size.width.toFloat()
                        if (i < bounds.size && bounds[i] != left to width) bounds[i] = left to width
                    }
                    .clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { if (!on) { haptics.play(Haptic.Select); onSelect(i) } }
                    .semantics { this.selected = on; role = Role.Tab }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, leading: ImageVector? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant, tween(160), label = "chipFill")
    val text by animateColorAsState(if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground, tween(160), label = "chipText")
    // A capsule that gives a little under the finger, as iOS's segmented pills do.
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "chipPress")
    Row(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(fill).clickable(press, indication = null) {
            if (!selected) haptics.play(Haptic.Select)
            onClick()
        }.semantics { this.selected = selected; role = Role.Tab }.padding(horizontal = 14.dp, vertical = 7.dp),
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

/**
 * BitChord's ActionPill: filled is the inverse colour, unfilled is a faint
 * veil with a hairline. [height] 50 for page actions (Play all, Shuffle),
 * 36 for the watch page's row of actions.
 */
@Composable
fun ActionPill(
    text: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    height: Dp = 50.dp,
    haptic: Haptic = Haptic.Tap,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    val dark = LocalIsDark.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 900f), label = "pillPress")
    val fill = when {
        filled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.09f else 0.06f)
    }
    val content = tint ?: if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.38f }
            .height(height)
            .clip(CircleShape)
            .background(fill)
            .then(if (filled) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), CircleShape))
            .clickable(interaction, indication = null, enabled = enabled) {
                haptics.play(haptic)
                onClick()
            }
            .padding(horizontal = if (height >= 44.dp) 24.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(if (height >= 44.dp) 18.dp else 19.dp))
        if (icon != null && text != null) Spacer(Modifier.width(if (height >= 44.dp) 8.dp else 6.dp))
        if (text != null) Text(text, style = if (height >= 44.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** A 50dp circle in the unfilled pill's veil; disabled at α0.38. */
@Composable
fun CircleIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 50.dp, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val haptics = rememberHaptics()
    val dark = LocalIsDark.current
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.09f else 0.06f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), CircleShape)
            .clickable(enabled = enabled) {
                haptics.play(Haptic.Tap)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.44f)) }
}

/** Subscribe: the filled inverse pill; Subscribed: the quiet veil with a bell. */
@Composable
fun SubscribeButton(subscribed: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    ActionPill(
        text = if (subscribed) "Subscribed" else "Subscribe",
        icon = if (subscribed) OrielIcons.Bell else null,
        onClick = onClick,
        filled = !subscribed,
        height = if (compact) 34.dp else 38.dp,
        haptic = if (subscribed) Haptic.ToggleOff else Haptic.ToggleOn,
    )
}

/** BitChord's Search "Clear" pill and other text buttons: titleMedium in primary. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp))
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
    Row(
        modifier.height(46.dp).fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 8.dp, end = 8.dp),
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
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Clear") { onValueChange("") }, contentAlignment = Alignment.Center) {
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
    thumb: Color = if (LocalIsDark.current) Color(0xFF636366) else Color.White,
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    val haptics = rememberHaptics()
    val index = options.indexOf(selected).coerceAtLeast(0)
    val at by animateFloatAsState(index.toFloat(), spring(dampingRatio = 0.78f, stiffness = 520f), label = "segThumb")
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp)).background(track).padding(2.dp)) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        // The thumb, with the soft shadow iOS gives it in light mode.
        Box(
            Modifier
                .graphicsLayer { translationX = at * segment.toPx() }
                .width(segment).fillMaxHeight()
                .shadow(if (LocalIsDark.current) 0.dp else 3.dp, RoundedCornerShape(7.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                .clip(RoundedCornerShape(7.dp))
                .background(thumb),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, option ->
                val on = i == index
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { if (!on) { haptics.play(Haptic.Select); onSelect(option) } }
                        .semantics { this.selected = on; role = Role.RadioButton },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.W600 else FontWeight.W500), color = ink, maxLines = 1)
                }
            }
        }
    }
}

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
                ActionPill(action, null, onAction, filled = true, height = 44.dp)
                if (secondary != null && onSecondary != null) ActionPill(secondary, null, onSecondary, height = 44.dp)
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

/** A determinate capsule bar: a soft track and an animated fill, as iOS's progress view. */
@Composable
fun CapsuleProgress(progress: () -> Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val shown by animateFloatAsState(progress(), motion(spring(stiffness = Spring.StiffnessLow)), label = "progress")
    Canvas(modifier) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(color, size = androidx.compose.ui.geometry.Size(size.width * shown.coerceIn(0f, 1f), size.height), cornerRadius = r)
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
    Canvas(modifier.size(size)) {
        val step = turn?.value?.toInt() ?: 0
        val w = this.size.minDimension
        val stroke = w * 0.095f
        for (i in 0 until 8) {
            val age = (step - i + 8) % 8
            val a = (1f - age / 8f).coerceAtLeast(0.22f)
            rotate(i * 45f) {
                drawLine(color.copy(alpha = color.alpha * a), Offset(w / 2, w * 0.1f), Offset(w / 2, w * 0.33f), stroke, cap = StrokeCap.Round)
            }
        }
    }
}

// ---- skeletons -----------------------------------------------------------------------------

/** A highlight band half the width wide sweeping left to right every 1400ms, over surfaceVariant. */
fun Modifier.shimmer(): Modifier = composed {
    if (LocalReduceAnimation.current) return@composed background(MaterialTheme.colorScheme.surfaceVariant)
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "shimmerT")
    background(base).drawWithContent {
        drawContent()
        val band = size.width * 0.5f
        val x = -band + (size.width + band * 2) * t
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

/** BitChord's QueueActionNotice: a short-lived glass banner over the bars. */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.padding(horizontal = BAR_GUTTER).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(0.5.dp, app.oriel.ui.glass.GLASS_EDGE_COLOR, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Spacer(Modifier.width(14.dp))
            Text(action, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(4.dp))
        }
    }
}

@Composable
fun ProvideContentColor(color: Color, content: @Composable () -> Unit) = CompositionLocalProvider(LocalContentColor provides color, content = content)

