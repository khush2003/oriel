/*
 * Section headers, chips, buttons, the search field, the segmented control,
 * message states and skeletons, adapted from BitChord (GPL-3.0): Common.kt,
 * SearchField.kt, Skeletons.kt, DetailScreen.kt (ActionPill, CircleIconButton)
 * and SettingsSheet.kt (SegmentedControl).
 */
package app.oriel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.ui.theme.LocalReduceAnimation

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
fun SkeletonLine(widthFraction: Float, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    Box(modifier.fillMaxWidth(widthFraction).height(height).clip(RoundedCornerShape(4.dp)).shimmer())
}
