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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import app.oriel.ui.theme.SegmentThumbDark
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import app.oriel.ui.theme.motion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.LiquidPill
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.TILE_PRESS_SCALE
import app.oriel.ui.theme.pressEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

// ---- segmented control ---------------------------------------------------------------------

/**
 * iOS's segmented control: a recessed track, and the mock's thumb, a blob on two edge springs
 * that stretches and necks toward the chosen segment and catches up with overshoot. Drag along
 * it to drag the thumb (rubber-banding past the ends); it lands where the fling carries it.
 * [track], [thumb] and [ink] default to the app's scheme; the watch page passes its artwork colours.
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
    if (LocalLiquidMotion.current) LiquidSegmentedControl(options, selected, label, onSelect, modifier, track, thumb, ink)
    else ClassicSegmentedControl(options, selected, label, onSelect, modifier, track, thumb, ink)
}

/** Liquid motion off: the raised thumb slides (with a little stretch) to the chosen segment. */
@Composable
private fun <T> ClassicSegmentedControl(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier, track: Color, thumb: Color, ink: Color) {
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

@Composable
private fun <T> LiquidSegmentedControl(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier, track: Color, thumb: Color, ink: Color) {
    val haptics = rememberHaptics()
    val index = options.indexOf(selected).coerceAtLeast(0)
    val n = options.size.coerceAtLeast(1)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dark = LocalIsDark.current
    val reduce = LocalReduceAnimation.current
    var inner by remember { mutableStateOf(IntSize.Zero) }
    val sw = inner.width / n.toFloat()
    val pill = remember { LiquidPill(0f, 0f, 0f, neckK = 0.12f) }
    val placed = remember { booleanArrayOf(false) }
    val throwV = remember { floatArrayOf(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(index, sw, inner.height) {
        if (sw <= 0f) return@LaunchedEffect
        val l = index * sw
        if (!placed[0]) {
            pill.setNow(this, l, l + sw)
            pill.h.snapTo(inner.height.toFloat())
            placed[0] = true
        } else {
            pill.moveTo(scope, l, l + sw, throwV[0].takeIf { it != 0f }, reduce)
            throwV[0] = 0f
        }
    }
    val currentStep by rememberUpdatedState(sw)
    val currentIndex by rememberUpdatedState(index)
    val currentReduce by rememberUpdatedState(reduce)
    val currentOptions by rememberUpdatedState(options)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val path = remember { Path() }
    // The unchosen segments' presses dim their label.
    val presses = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    Box(
        modifier.fillMaxWidth().heightIn(min = 34.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(9.dp)).background(track).padding(2.dp)
            .onSizeChanged { inner = it }
            .drawBehind {
                if (!placed[0]) return@drawBehind
                pill.path(path, size.height / 2f, reduce, if (rtl) size.width else Float.NaN)
                // The soft shadow iOS gives the thumb in light mode.
                if (!dark) translate(0f, 1.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.10f)) }
                drawPath(path, thumb)
            }
            .pointerInput(n) {
                slotDrag(pill, scope, if (rtl) -1f else 1f, 5.dp.toPx(), { currentStep }, { currentStep }, { currentIndex }, { currentIndex }, { currentReduce }, n, haptics, throwV) { i ->
                    haptics.play(Haptic.Select)
                    currentOptions.getOrNull(i)?.let(currentOnSelect)
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, option ->
                val on = i == index
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                        .clickable(presses[i], if (on) null else SegmentPress) {
                            if (!on) {
                                haptics.play(Haptic.Select)
                                // The thumb sets off on the tap itself, not when the choice comes back down.
                                if (sw > 0f && placed[0]) pill.moveTo(scope, i * sw, i * sw + sw, reduce = reduce)
                                onSelect(option)
                            }
                        }
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
private val SegmentPress = app.oriel.ui.theme.PressHighlight(Color.Transparent, strength = 0f, dim = 0.5f)
