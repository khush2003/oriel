package app.oriel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** iOS's destructive red, shared by every remove and delete. */
val DestructiveRed = Color(0xFFFF453A)

/**
 * iOS's trailing swipe action: drag a row left to show a red [label] button behind it. Let go
 * past the button and it stays open; tap it to remove. A long swipe, past half the row, removes
 * at once with a tick as it crosses the line. Anything else springs closed. Null [onRemove]
 * leaves the row as it is.
 */
@Composable
fun SwipeToRemove(onRemove: (() -> Unit)?, modifier: Modifier = Modifier, label: String = "Remove", content: @Composable () -> Unit) {
    if (onRemove == null) return Box(modifier) { content() }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val action = with(density) { 88.dp.toPx() }
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableIntStateOf(0) }
    val remove by rememberUpdatedState(onRemove)
    val settle = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

    fun commit() {
        haptics.play(Haptic.ToggleOff)
        scope.launch {
            offset.animateTo(-width.toFloat(), spring(dampingRatio = 1f, stiffness = 700f))
            remove()
            offset.snapTo(0f)
        }
    }

    Box(modifier.onSizeChanged { width = it.width }) {
        // The red button, revealed from the right edge as the row slides away.
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = if (offset.value < -1f) 1f else 0f }.background(DestructiveRed).clickable { commit() },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                modifier = Modifier.width(88.dp).padding(horizontal = 8.dp),
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background)
                .draggable(
                    rememberDraggableState { d ->
                        val next = (offset.value + d).coerceIn(-width.toFloat(), 0f)
                        val past = width > 0 && abs(next) > width * 0.5f
                        if (past && armed == 0) { armed = 1; haptics.play(Haptic.Tick) } else if (!past) armed = 0
                        scope.launch { offset.snapTo(next) }
                    },
                    Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val x = offset.value
                        when {
                            width > 0 && (abs(x) > width * 0.5f || velocity < -3000f && abs(x) > action) -> commit()
                            x < -action * 0.6f || velocity < -800f -> offset.animateTo(-action, settle, velocity)
                            else -> offset.animateTo(0f, settle, velocity)
                        }
                        armed = 0
                    },
                ),
        ) { content() }
    }
}
