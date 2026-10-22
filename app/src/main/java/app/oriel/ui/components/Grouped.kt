/*
 * iOS grouped inset lists, adapted from BitChord's SettingsSheet.kt (GPL-3.0):
 * group cards, rows, switches, dividers, footers.
 */
package app.oriel.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.oriel.ui.theme.LiquidPill
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.Sp
import kotlinx.coroutines.launch

/** The grouped card's corner, for anything that should read as one of these cards. */
val GROUP_CORNER = 14.dp
val GroupShape = RoundedCornerShape(GROUP_CORNER)
private val GROUP_INSET = 16.dp
private val ICON_SIZE = 22.dp
private val TEXT_INSET = 52.dp

/** A titled group card: uppercase header, the rows on surfaceVariant, an optional footer. */
@Composable
fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
    /** Space above the group; the first group in a sheet sits closer, under the drag handle. */
    top: Dp = 26.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(header.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = GROUP_INSET + 4.dp, top = top, bottom = 8.dp).semantics { heading() })
        } else Spacer(Modifier.height(top))
        Column(Modifier.padding(horizontal = GROUP_INSET).fillMaxWidth().clip(GroupShape).background(fill), content = content)
        if (footer != null) Text(footer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = GROUP_INSET + 4.dp).padding(top = 8.dp))
    }
}

@Composable
fun RowDivider(inset: Boolean = true) {
    Box(Modifier.padding(start = if (inset) TEXT_INSET else 16.dp).fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
}

/** A row: optional leading icon, title and subtitle, a trailing value and chevron. Disabled at α0.45. */
@Composable
fun SettingsRow(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    toggled: Boolean? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                when {
                    // A switch row reads as a switch, on or off, rather than a button.
                    onClick != null && toggled != null -> Modifier.toggleable(toggled, enabled = enabled, role = Role.Switch) { onClick() }
                    onClick != null -> Modifier.clickable(enabled = enabled) { haptics.play(Haptic.Tap); onClick() }
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (destructive) DestructiveRed else MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (destructive) DestructiveRed else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5)
        }
        if (value != null) {
            // Capped, not weighted: a weight would split the free space and strand the value mid-row.
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 168.dp), textAlign = TextAlign.End)
        }
        trailing?.invoke()
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(OrielIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        }
    }
}

/** A switch row; the track is primary (white on dark, black on light), not iOS green. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null, subtitle: String? = null, enabled: Boolean = true) {
    val haptics = rememberHaptics()
    val change = { on: Boolean -> haptics.play(if (on) Haptic.ToggleOn else Haptic.ToggleOff); onChange(on) }
    SettingsRow(title, icon, subtitle, enabled = enabled, toggled = checked, onClick = { change(!checked) }) {
        IosSwitch(checked, enabled, onChange = if (enabled) change else null)
    }
}

/**
 * iOS's switch: a 51 x 31 capsule, the track filling with primary, and the mock's knob: a blob
 * on two edge springs that widens under the finger (7 dp toward the other side) and stretches
 * along its way across, necking a little. With [onChange] the knob can be pressed, tapped and
 * dragged itself; without, it's drawing only and the row takes the tap.
 */
@Composable
fun IosSwitch(checked: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null) {
    val reduce = LocalReduceAnimation.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    // The mock's geometry, in dp: the knob 27 tall, from 2 to 29 off and from 22 to 49 on.
    val pill = remember { LiquidPill((if (checked) 22f else 2f) * density, (if (checked) 49f else 29f) * density, 27f * density, neckK = 0.1f, squash = 0.15f) }
    val fill = remember { Animatable(if (checked) 1f else 0f) }
    val shown = remember { booleanArrayOf(checked) }
    fun rest(on: Boolean) = pill.moveTo(scope, (if (on) 22f else 2f) * density, (if (on) 49f else 29f) * density, reduce = reduce)
    fun fillTo(on: Boolean) = scope.launch { if (reduce) fill.snapTo(if (on) 1f else 0f) else fill.animateTo(if (on) 1f else 0f, Sp.Snappy.unit) }
    LaunchedEffect(checked) {
        if (shown[0] == checked) return@LaunchedEffect
        shown[0] = checked
        rest(checked)
        fillTo(checked)
    }
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onChange)
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val path = remember { Path() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .drawBehind {
                val t = fill.value.coerceIn(0f, 1f)
                drawRoundRect(lerp(off, on, t), cornerRadius = CornerRadius(size.height / 2))
                pill.path(path, size.height / 2f, reduce, if (rtl) size.width else Float.NaN)
                translate(0f, 1.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.14f)) }
                drawPath(path, if (on == Color.White && t > 0.5f) Color.Black else Color.White)
            }
            .then(
                if (onChange == null) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val was = currentChecked
                        // Pressed, the knob widens toward where it would go.
                        if (was) pill.moveTo(scope, 15f * density, 49f * density, reduce = reduce) else pill.moveTo(scope, 2f * density, 36f * density, reduce = reduce)
                        var dragging = false
                        var x = 0f
                        var moved = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) {
                                c.consume()
                                break
                            }
                            moved += c.positionChange().x
                            if (!dragging && kotlin.math.abs(moved) > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                c.consume()
                                val fx = if (rtl) size.width - c.position.x else c.position.x
                                x = (fx / density).coerceIn(19f, 32f)
                                pill.moveTo(scope, (x - 17f) * density, (x + 17f) * density, reduce = reduce)
                                fillTo(x > 25.5f)
                            }
                        }
                        val now = if (dragging) x > 25.5f else !was
                        shown[0] = now
                        rest(now)
                        fillTo(now)
                        if (now != was) currentOnChange?.invoke(now)
                    }
                },
            ),
    )
}

/**
 * A choice row: label, optional detail, and a check when selected. One of several (a radio
 * button) by default; [multi] makes it one of a set that each toggle (a checkbox). Disabled
 * at α0.45 and not tappable.
 */
@Composable
fun ChoiceRow(label: String, selected: Boolean, detail: String? = null, enabled: Boolean = true, multi: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val tap = { haptics.play(Haptic.Select); onClick() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                if (multi) Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { tap() }
                else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = tap),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(OrielIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
    }
}
