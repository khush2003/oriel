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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import app.oriel.ui.theme.GlassSpring
import app.oriel.ui.theme.motion
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
    SettingsRow(title, icon, subtitle, enabled = enabled, toggled = checked, onClick = { haptics.play(if (!checked) Haptic.ToggleOn else Haptic.ToggleOff); onChange(!checked) }) {
        IosSwitch(checked, enabled)
    }
}

/**
 * iOS's switch: a 51 x 31 capsule whose white knob springs across, the track filling with
 * primary as it goes. The row takes the tap, so this is drawing only.
 */
@Composable
fun IosSwitch(checked: Boolean, enabled: Boolean = true) {
    val t by animateFloatAsState(if (checked) 1f else 0f, motion(GlassSpring), label = "switch")
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .drawBehind {
                val r = size.height / 2
                drawRoundRect(lerp(off, on, t.coerceIn(0f, 1f)), cornerRadius = CornerRadius(r))
                val inset = 2.dp.toPx()
                val knob = size.height - inset * 2
                val x = inset + (size.width - knob - inset * 2) * t
                drawCircle(Color.Black.copy(alpha = 0.14f), knob / 2 + 0.5.dp.toPx(), Offset(x + knob / 2, size.height / 2 + 1.dp.toPx()))
                drawCircle(if (on == Color.White && t > 0.5f) Color.Black else Color.White, knob / 2, Offset(x + knob / 2, size.height / 2))
            },
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
