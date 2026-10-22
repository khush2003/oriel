package app.oriel.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * iOS's press feedback in place of Material's ripple, in three kinds, all on the same springs
 * (PressSpring in, ReleaseSpring out) so every control in the app answers a finger alike:
 *  - rows wash: a flat highlight over the cell, as UITableView's ([PressHighlight] with a colour,
 *    the theme's LocalIndication);
 *  - cards and tiles give: they shrink a little under the finger ([CardPress], [TilePress]);
 *  - glyph and text buttons dim, as UIButton's do ([GlyphPress]).
 * No circle grows out from the touch point. A press that turns into a scroll arrives as a
 * Cancel and springs straight back; a quick tap still shows, held for at least [MIN_SHOWN_MS].
 * The animation is draw- or layer-only: pressing never recomposes anything.
 */

/** How long a tap's feedback is held at the least, so a quick tap still shows. */
private const val MIN_SHOWN_MS = 90L

/** A feed card's give under the finger. */
const val CARD_PRESS_SCALE = 0.97f

/** A smaller tile's (shelf cards, chips, pills): small things give a little more. */
const val TILE_PRESS_SCALE = 0.95f

/** A glyph or text button's dim. */
const val GLYPH_PRESS_ALPHA = 0.35f

/** A glass control swells a touch under the finger, as iOS 26's interactive glass does. */
const val GLASS_PRESS_SCALE = 1.08f

/**
 * An indication: a [color] wash (transparent for none) over the pressed thing, clipped to
 * [shape], with the content drawn at [scale] and [dim] alpha at full press.
 */
class PressHighlight(
    private val color: Color,
    private val scale: Float = 1f,
    private val dim: Float = 1f,
    private val shape: Shape = RectangleShape,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = DrawPressNode(interactionSource, color, scale, dim, shape)

    override fun equals(other: Any?) = other is PressHighlight && other.color == color && other.scale == scale && other.dim == dim && other.shape == shape
    override fun hashCode() = ((color.hashCode() * 31 + scale.hashCode()) * 31 + dim.hashCode()) * 31 + shape.hashCode()
}

/** Feed cards: a slight give, no wash (a wash would fill the gutters round the card). */
val CardPress = PressHighlight(Color.Transparent, scale = CARD_PRESS_SCALE)

/** Shelf cards, chips and pills. */
val TilePress = PressHighlight(Color.Transparent, scale = TILE_PRESS_SCALE)

/** Icon and text buttons. */
val GlyphPress = PressHighlight(Color.Transparent, dim = GLYPH_PRESS_ALPHA)

/**
 * The same press as a layer around everything after it, for what an indication can't reach:
 * a glass surface, which has to scale with its content, or a thumb that answers another
 * element's press. [interactionSource] is the clickable's.
 */
fun Modifier.pressEffect(interactionSource: InteractionSource, scale: Float = 1f, alpha: Float = 1f): Modifier =
    this then PressEffectElement(interactionSource, scale, alpha)

/** A press's 0-to-1 progress, driven by an interaction source. */
private abstract class PressNode(private var source: InteractionSource) : Modifier.Node(), CompositionLocalConsumerModifierNode {
    protected val progress = Animatable(0f)
    private var collecting: Job? = null

    override fun onAttach() = collect()

    fun updateSource(new: InteractionSource) {
        if (new == source) return
        source = new
        if (isAttached) {
            collect()
            coroutineScope.launch { progress.snapTo(0f) }
        }
    }

    private fun collect() {
        collecting?.cancel()
        collecting = coroutineScope.launch {
            var moving: Job? = null
            var pressedAt = 0L
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        moving?.cancel()
                        pressedAt = System.nanoTime()
                        moving = launch { to(1f, PressSpring) }
                    }
                    is PressInteraction.Release -> {
                        moving?.cancel()
                        moving = launch {
                            val heldMs = (System.nanoTime() - pressedAt) / 1_000_000
                            if (heldMs < MIN_SHOWN_MS) delay(MIN_SHOWN_MS - heldMs)
                            to(0f, ReleaseSpring)
                        }
                    }
                    // Became a scroll, or left the bounds: no hold, straight back.
                    is PressInteraction.Cancel -> {
                        moving?.cancel()
                        moving = launch { to(0f, ReleaseSpring) }
                    }
                }
            }
        }
    }

    private suspend fun to(target: Float, spec: AnimationSpec<Float>) {
        if (currentValueOf(LocalReduceAnimation)) progress.snapTo(target) else progress.animateTo(target, spec)
    }
}

private class DrawPressNode(
    source: InteractionSource,
    private val color: Color,
    private val scale: Float,
    private val dim: Float,
    private val shape: Shape,
) : PressNode(source), DrawModifierNode {
    private val dimPaint = Paint()

    override fun ContentDrawScope.draw() {
        // Read in the draw phase: the press only redraws.
        val p = progress.value
        if (p == 0f) return drawContent()
        val s = 1f + (scale - 1f) * p
        val a = 1f + (dim - 1f) * p
        scale(s) {
            if (a < 1f) {
                dimPaint.alpha = a
                drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), dimPaint)
                this@draw.drawContent()
                drawContext.canvas.restore()
            } else {
                this@draw.drawContent()
            }
            if (color.alpha > 0f) {
                val wash = color.copy(alpha = color.alpha * p.coerceIn(0f, 1f))
                if (shape == RectangleShape) drawRect(wash) else drawOutline(shape.createOutline(size, layoutDirection, this), wash)
            }
        }
    }
}

private class PressEffectElement(val source: InteractionSource, val scale: Float, val alpha: Float) : ModifierNodeElement<LayerPressNode>() {
    override fun create() = LayerPressNode(source, scale, alpha)

    override fun update(node: LayerPressNode) {
        node.updateSource(source)
        if (node.scale != scale || node.alpha != alpha) {
            node.scale = scale
            node.alpha = alpha
            node.invalidatePlacement()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressEffect"
        properties["scale"] = scale
        properties["alpha"] = alpha
    }

    override fun equals(other: Any?) = other is PressEffectElement && other.source == source && other.scale == scale && other.alpha == alpha
    override fun hashCode() = (source.hashCode() * 31 + scale.hashCode()) * 31 + alpha.hashCode()
}

private class LayerPressNode(source: InteractionSource, var scale: Float, var alpha: Float) : PressNode(source), LayoutModifierNode {
    // Read in the layer block: the press only updates the layer's properties.
    private val layer: GraphicsLayerScope.() -> Unit = {
        val p = progress.value
        val s = 1f + (scale - 1f) * p
        scaleX = s
        scaleY = s
        // The release's give overshoots past rest; alpha can't go past opaque.
        this.alpha = (1f + (this@LayerPressNode.alpha - 1f) * p).coerceIn(0f, 1f)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layer) }
    }
}
