package app.oriel.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/*
 * Press feedback, ported from the mock's `pressable` (design/liquid/index.html): a pressed
 * surface dents toward the finger, scaled about the touch point by up to 7% (less along the axis
 * the finger is off-centre on) and nudged 2.5 px toward it, lit by a radial glow under it; while
 * held it stretches after the finger (a rubber band, 22%); let go, it wobbles like jelly, kicked
 * at 0.9 across and −0.63 down on Sp.Jelly (response 0.48, damping 0.3). A press that turns into
 * a scroll or a drag settles back without the kick. [strength] scales it all, as the mock's does
 * per kind (cards 0.45, chips 0.7, rows 0.25, buttons 1). Draw- or layer-only: pressing never
 * recomposes anything. Reduce motion dims instead, as the mock does.
 */

/** A feed card's squish, as the mock's cards. */
const val CARD_PRESS_STRENGTH = 0.45f

/** Shelf cards, chips and pills. */
const val TILE_PRESS_STRENGTH = 0.7f

/** A glyph or text button's dim, on top of its squish. */
const val GLYPH_PRESS_ALPHA = 0.35f

/** List rows: a gentle squish under the glow. */
const val ROW_PRESS_STRENGTH = 0.25f

/** The mock's glow under the finger: white at 28% on dark, black at 10% on light. */
fun pressGlow(dark: Boolean): Color = if (dark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.10f)

/**
 * An indication: the squish at [strength], a radial [glow] (transparent for none) under the
 * finger clipped to [shape], and the content at [dim] alpha at full press.
 */
class PressHighlight(
    internal val glow: Color,
    internal val strength: Float = 1f,
    internal val dim: Float = 1f,
    internal val shape: Shape = RectangleShape,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = DrawPressNode(interactionSource, glow, strength, dim, shape)

    override fun equals(other: Any?) = other is PressHighlight && other.glow == glow && other.strength == strength && other.dim == dim && other.shape == shape
    override fun hashCode() = ((glow.hashCode() * 31 + strength.hashCode()) * 31 + dim.hashCode()) * 31 + shape.hashCode()
}

/** Feed cards: the squish, no glow (the mock's cards have none). */
val CardPress = PressHighlight(Color.Transparent, strength = CARD_PRESS_STRENGTH)

/** Shelf cards, chips and pills. */
val TilePress = PressHighlight(Color.Transparent, strength = TILE_PRESS_STRENGTH)

/** Icon and text buttons: the full squish, and the dim iOS gives them. */
val GlyphPress = PressHighlight(Color.Transparent, strength = 1f, dim = GLYPH_PRESS_ALPHA)

/**
 * The same press as a layer around everything after it, for what an indication can't reach:
 * a glass surface, which has to squish with its content. [interactionSource] is the clickable's;
 * the finger itself is watched here, in this modifier's bounds.
 */
fun Modifier.pressEffect(interactionSource: InteractionSource, strength: Float = 1f, alpha: Float = 1f): Modifier =
    this then PressEffectElement(interactionSource, strength, alpha)

/**
 * [highlight] as a modifier of its own rather than a clickable's indication. A clickable's
 * indication sees no pointer events, only its press interactions, and in a scrolling list those
 * come a tap-timeout late (or at the release, for a quick tap): so it can't dent at the finger's
 * down or stretch after it while held, as the mock's `pressable` does. Put before the clickable,
 * whose indication is then null; [interactionSource] is the clickable's, for keys and the like.
 */
fun Modifier.pressHighlight(interactionSource: InteractionSource, highlight: PressHighlight): Modifier =
    this then PressHighlightElement(interactionSource, highlight)

private class PressHighlightElement(val source: InteractionSource, val highlight: PressHighlight) : ModifierNodeElement<DrawPressNode>() {
    override fun create() = DrawPressNode(source, highlight.glow, highlight.strength, highlight.dim, highlight.shape)

    override fun update(node: DrawPressNode) {
        node.updateSource(source)
        node.update(highlight)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressHighlight"
        properties["strength"] = highlight.strength
    }

    override fun equals(other: Any?) = other is PressHighlightElement && other.source == source && other.highlight == highlight
    override fun hashCode() = source.hashCode() * 31 + highlight.hashCode()
}

/** The mock's settle-back threshold for a release still counting as on the surface. */
private const val RELEASE_SLOP_PX = 20f

/** The squish's springs and where the finger is: shared by the indication and the layer. */
private abstract class SquishNode(private var source: InteractionSource, var strength: Float) :
    Modifier.Node(), CompositionLocalConsumerModifierNode, PointerInputModifierNode {
    val sx = Animatable(1f)
    val sy = Animatable(1f)
    val tx = Animatable(0f)
    val ty = Animatable(0f)
    /** The glow's (and the dim's) 0-to-1. */
    val hl = Animatable(0f)
    var origin = Offset.Zero
    var size = Size.Zero
    private var base = floatArrayOf(1f, 1f, 0f, 0f)
    private var down = Offset.Zero
    private var pointerId = -1L
    /** Pressed, and not yet let go (by a finger or a key). */
    private var pressing = false
    private var collecting: Job? = null

    override fun onAttach() = collect()

    fun updateSource(new: InteractionSource) {
        if (new == source) return
        source = new
        if (isAttached) collect()
    }

    private val reduce get() = currentValueOf(LocalReduceAnimation)

    /** Called when a press starts, for kinds that cache something per press (the glow's brush). */
    open fun onPressAt(origin: Offset, size: Size) {}

    private fun collect() {
        collecting?.cancel()
        collecting = coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    // A key or an accessibility action, or the first touch of a clickable whose
                    // indication is made as it's pressed (it missed the finger's down).
                    is PressInteraction.Press -> if (!pressing) press(i.pressPosition, fromPointer = false)
                    is PressInteraction.Release -> if (pressing && pointerId == -1L) settle(pop = true)
                    is PressInteraction.Cancel -> if (pressing) settle(pop = false)
                }
            }
        }
    }

    private fun to(a: Animatable<Float, *>, target: Float, spec: AnimationSpec<Float>, velocity: Float? = null) {
        coroutineScope.launch { if (velocity == null) a.animateTo(target, spec) else a.animateTo(target, spec, velocity) }
    }

    fun press(at: Offset, fromPointer: Boolean) {
        val w = size.width
        val h = size.height
        val ox = at.x.coerceIn(0f, max(w, 0f))
        val oy = at.y.coerceIn(0f, max(h, 0f))
        origin = Offset(ox, oy)
        down = at
        pressing = true
        if (!fromPointer) pointerId = -1L
        onPressAt(origin, size)
        if (reduce) {
            coroutineScope.launch { hl.snapTo(1f) }
            return
        }
        val nx = if (w > 0f) ox / w * 2f - 1f else 0f
        val ny = if (h > 0f) oy / h * 2f - 1f else 0f
        val k = 0.07f * strength
        base = floatArrayOf(1f - k * (1f - 0.45f * abs(nx)), 1f - k * (1f - 0.45f * abs(ny)), nx * 2.5f * strength, ny * 2.5f * strength)
        to(sx, base[0], Sp.Press.unit)
        to(sy, base[1], Sp.Press.unit)
        to(tx, base[2], Sp.Press.px)
        to(ty, base[3], Sp.Press.px)
        to(hl, 1f, Sp.Press.unit)
    }

    /** While held, the surface stretches after the finger. */
    private fun follow(at: Offset) {
        if (reduce || size.width <= 0f || size.height <= 0f) return
        val dx = at.x - down.x
        val dy = at.y - down.y
        val ex = sign(dx) * rubber(abs(dx), size.width, 0.6f) * 0.22f * strength
        val ey = sign(dy) * rubber(abs(dy), size.height, 0.6f) * 0.22f * strength
        val ax = min(1f, abs(dx) / size.width)
        val ay = min(1f, abs(dy) / size.height)
        to(sx, base[0] + (ax * 0.1f - ay * 0.04f) * strength, Sp.Jelly.unit)
        to(sy, base[1] + (ay * 0.1f - ax * 0.04f) * strength, Sp.Jelly.unit)
        to(tx, base[2] + ex, Sp.Jelly.px)
        to(ty, base[3] + ey, Sp.Jelly.px)
    }

    /** Let go: a jelly wobble seeded with a kick when it was a tap ([pop]), a plain settle when not. */
    fun settle(pop: Boolean) {
        pressing = false
        pointerId = -1L
        if (reduce) {
            coroutineScope.launch { hl.snapTo(0f) }
            coroutineScope.launch { sx.snapTo(1f) }
            coroutineScope.launch { sy.snapTo(1f) }
            coroutineScope.launch { tx.snapTo(0f) }
            coroutineScope.launch { ty.snapTo(0f) }
            return
        }
        val kick = if (pop) 0.9f * strength else 0f
        to(sx, 1f, Sp.Jelly.unit, sx.velocity + kick)
        to(sy, 1f, Sp.Jelly.unit, sy.velocity - kick * 0.7f)
        to(tx, 0f, Sp.Jelly.px)
        to(ty, 0f, Sp.Jelly.px)
        to(hl, 0f, Sp.FadeOut.unit)
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        // After everyone else has had it: a scroll or a drag that took the finger shows as consumed.
        if (pass != PointerEventPass.Final) return
        size = bounds.toSize()
        for (c in pointerEvent.changes) {
            if (c.changedToDownIgnoreConsumed()) {
                if (pointerId == -1L && pointerEvent.changes.count { it.pressed } == 1) {
                    pointerId = c.id.value
                    press(c.position, fromPointer = true)
                }
                continue
            }
            if (!pressing) continue
            // A press whose down this node missed (made as it was pressed) is adopted by its first move.
            if (pointerId == -1L && c.pressed) pointerId = c.id.value
            if (c.id.value != pointerId) continue
            when {
                c.changedToUpIgnoreConsumed() -> {
                    val p = c.position
                    val inside = p.x > -RELEASE_SLOP_PX && p.x < size.width + RELEASE_SLOP_PX && p.y > -RELEASE_SLOP_PX && p.y < size.height + RELEASE_SLOP_PX
                    settle(pop = inside)
                }
                c.positionChangeIgnoreConsumed() != Offset.Zero -> {
                    // Taken by a scroll or a drag: back without the kick.
                    if (c.isConsumed) settle(pop = false) else follow(c.position)
                }
            }
        }
    }

    override fun onCancelPointerInput() {
        if (pressing && pointerId != -1L) settle(pop = false)
    }

}

private class DrawPressNode(
    source: InteractionSource,
    private var glow: Color,
    strength: Float,
    private var dim: Float,
    private var shape: Shape,
) : SquishNode(source, strength), DrawModifierNode {
    fun update(h: PressHighlight) {
        if (h.glow == glow && h.strength == strength && h.dim == dim && h.shape == shape) return
        glow = h.glow
        strength = h.strength
        dim = h.dim
        shape = h.shape
        invalidateDraw()
    }

    private val dimPaint = Paint()
    private var brush: Brush? = null

    override fun onPressAt(origin: Offset, size: Size) {
        if (glow.alpha <= 0f) return
        // CSS's `radial-gradient(circle at …, glow, transparent 65%)`: the farthest corner sets the radius.
        val far = max(max(hypot(origin.x, origin.y), hypot(size.width - origin.x, origin.y)), max(hypot(origin.x, size.height - origin.y), hypot(size.width - origin.x, size.height - origin.y)))
        brush = Brush.radialGradient(listOf(glow, glow.copy(alpha = 0f)), center = origin, radius = max(1f, far * 0.65f))
    }

    override fun ContentDrawScope.draw() {
        size.let { if (it != this@DrawPressNode.size && it.width > 0f) this@DrawPressNode.size = it }
        // Read in the draw phase: the press only redraws.
        val g = hl.value
        val sxv = sx.value
        val syv = sy.value
        val txv = tx.value
        val tyv = ty.value
        if (g == 0f && sxv == 1f && syv == 1f && txv == 0f && tyv == 0f) return drawContent()
        val a = 1f + (dim - 1f) * g.coerceIn(0f, 1f)
        withTransform({
            translate(txv, tyv)
            scale(sxv, syv, origin)
        }) {
            if (a < 1f) {
                dimPaint.alpha = a
                drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), dimPaint)
                this@draw.drawContent()
                drawContext.canvas.restore()
            } else {
                this@draw.drawContent()
            }
            val b = brush
            if (b != null && g > 0f) {
                val alpha = g.coerceIn(0f, 1f)
                if (shape == RectangleShape) drawRect(b, alpha = alpha) else drawOutline(shape.createOutline(size, layoutDirection, this), b, alpha = alpha)
            }
        }
    }
}

private class PressEffectElement(val source: InteractionSource, val strength: Float, val alpha: Float) : ModifierNodeElement<LayerPressNode>() {
    override fun create() = LayerPressNode(source, strength, alpha)

    override fun update(node: LayerPressNode) {
        node.updateSource(source)
        if (node.strength != strength || node.alpha != alpha) {
            node.strength = strength
            node.alpha = alpha
            node.invalidatePlacement()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressEffect"
        properties["strength"] = strength
        properties["alpha"] = alpha
    }

    override fun equals(other: Any?) = other is PressEffectElement && other.source == source && other.strength == strength && other.alpha == alpha
    override fun hashCode() = (source.hashCode() * 31 + strength.hashCode()) * 31 + alpha.hashCode()
}

private class LayerPressNode(source: InteractionSource, strength: Float, var alpha: Float) : SquishNode(source, strength), LayoutModifierNode {
    // Read in the layer block: the press only updates the layer's properties.
    private val layer: GraphicsLayerScope.() -> Unit = {
        val w = this@LayerPressNode.size.width
        val h = this@LayerPressNode.size.height
        transformOrigin = if (w > 0f && h > 0f) TransformOrigin(origin.x / w, origin.y / h) else TransformOrigin.Center
        scaleX = sx.value
        scaleY = sy.value
        translationX = tx.value
        translationY = ty.value
        this.alpha = (1f + (this@LayerPressNode.alpha - 1f) * hl.value).coerceIn(0f, 1f)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        size = Size(placeable.width.toFloat(), placeable.height.toFloat())
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layer) }
    }
}
