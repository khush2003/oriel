/*
 * The iOS capsule scrubber with no thumb, adapted from BitChord's
 * ThinSlider.kt (GPL-3.0), without its DJ-crossfade pulse. Added: a buffered
 * track, chapter gaps, and SponsorBlock segments drawn in place.
 */
package app.oriel.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.core.extras.Segment

/** SponsorBlock's own category colours, so a sponsor reads as one wherever it's seen. */
fun segmentColor(category: String): Color = when (category) {
    "sponsor" -> Color(0xFF00D400)
    "selfpromo" -> Color(0xFFFFFF00)
    "interaction" -> Color(0xFFCC00FF)
    "intro" -> Color(0xFF00FFFF)
    "outro" -> Color(0xFF0202ED)
    "music_offtopic" -> Color(0xFFFF9900)
    "preview" -> Color(0xFF008FD6)
    "filler" -> Color(0xFF7300FF)
    else -> Color.White
}

@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragStateChange: (Boolean) -> Unit = {},
    onValueChangeFinished: (() -> Unit)? = null,
    buffered: Float = 0f,
    loading: Boolean = false,
    durationMs: Long = 0,
    chapterStartsMs: List<Long> = emptyList(),
    segments: List<Segment> = emptyList(),
    idleHeight: Dp = 5.dp,
    activeHeight: Dp = 11.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    bufferedColor: Color = Color.White.copy(alpha = 0.40f),
    inactiveColor: Color = Color.White.copy(alpha = 0.22f),
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf(false) }
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val onDrag by rememberUpdatedState(onDragStateChange)
    // Read in draw only: the capsule swells under the finger without re-measuring anything.
    val grow = animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium), label = "sliderHeight")
    val fillFactor = animateFloatAsState(if (loading && value <= 0f) 0f else 1f, tween(450, easing = FastOutSlowInEasing), label = "fill")
    // The loading sheen runs only while loading, not every frame the controls are up.
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        while (true) { sheen.snapTo(0f); sheen.animateTo(1f, tween(1000, easing = LinearEasing)) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(activeHeight + 22.dp)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        onDrag(true)
                        onChange((down.position.x / size.width).coerceIn(0f, 1f))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) {
                                pointer.consume()
                                break
                            }
                            if (pointer.positionChanged()) {
                                onChange((pointer.position.x / size.width).coerceIn(0f, 1f))
                                pointer.consume()
                            }
                        }
                        dragging = false
                        onDrag(false)
                        onFinished?.invoke()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(activeHeight)) {
            val h = idleHeight.toPx() + (activeHeight.toPx() - idleHeight.toPx()) * grow.value
            val radius = CornerRadius(h / 2f)
            val capsule = Path().apply { addRoundRect(RoundRect(0f, (size.height - h) / 2f, size.width, (size.height + h) / 2f, radius)) }
            translate(top = (size.height - h) / 2f) { clipPath(capsule.apply { translate(Offset(0f, -(size.height - h) / 2f)) }) {
                val size = Size(this.size.width, h)
                drawRect(inactiveColor)
                if (buffered > 0f) drawRect(bufferedColor, size = Size(size.width * buffered.coerceIn(0f, 1f), size.height))
                if (durationMs > 0) segments.forEach { s ->
                    val from = size.width * (s.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    val to = size.width * (s.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    if (to > from) drawRect(segmentColor(s.category).copy(alpha = 0.85f), Offset(from, 0f), Size(to - from, size.height))
                }
                val filled = size.width * value.coerceIn(0f, 1f) * fillFactor.value
                if (filled > 0f) drawRoundRect(activeColor, size = Size(filled.coerceAtLeast(size.height).coerceAtMost(size.width), size.height), cornerRadius = radius)
                if (loading) {
                    val band = size.width * 0.34f
                    val x = -band + (size.width + band) * sheen.value
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, activeColor.copy(alpha = 0.45f), Color.Transparent), startX = x, endX = x + band))
                }
                // Chapters part the capsule with hairline gaps, as YouTube's scrubber does.
                if (durationMs > 0) {
                    val gap = 2.dp.toPx()
                    chapterStartsMs.forEach { start ->
                        if (start <= 0) return@forEach
                        val x = size.width * (start.toFloat() / durationMs)
                        drawRect(Color.Black.copy(alpha = 0.55f), Offset(x - gap / 2, 0f), Size(gap, size.height))
                    }
                }
            } }
        }
    }
}
