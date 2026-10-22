package app.oriel.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/*
 * iOS's press feedback in place of Material's ripple, in two flavours, picked by LocalLiquidMotion
 * as each press starts.
 *
 * Liquid motion off (the default), three kinds on the same springs (PressSpring in,
 * ReleaseSpring out) so every control in the app answers a finger alike:
 *  - rows wash: a flat highlight over the cell, as UITableView's ([PressHighlight.wash], the
 *    theme's LocalIndication);
 *  - cards and tiles give: they shrink a little under the finger ([CardPress], [TilePress]);
 *  - glyph and text buttons dim, as UIButton's do ([GlyphPress]).
 * A press that turns into a scroll arrives as a Cancel and springs straight back; a quick tap
 * still shows, held for at least [MIN_SHOWN_MS].
 *
 * Liquid motion on, the mock's `pressable` (design/liquid/index.html): a pressed surface dents
 * toward the finger, scaled about the touch point by up to 7% (less along the axis the finger
 * is off-centre on) and nudged 2.5 px toward it, lit by a radial glow under it; while held it
 * stretches after the finger (a rubber band, 22%) and sinks a further 5% as the long press nears; let go, it wobbles like jelly, kicked at 0.9
 * across and −0.63 down on Sp.Jelly. [PressHighlight.strength] scales it all, as the mock's does
 * per kind (cards 0.45, chips 0.7, rows 0.25, buttons 1).
 *
 * Either way a press starts with the clickable's Press, not the finger's down: in a scrolling
 * list that comes a tap-timeout late (at once outside one), so the start of a scroll never
 * squishes what it starts on. A press that becomes a scroll, or whose finger wanders past the
 * touch slop, settles back without the kick. Draw- or layer-only: pressing never recomposes
 * anything. Reduce motion dims instead.
 */

/** How long a tap's feedback is held at the least, so a quick tap still shows. */
private const val MIN_SHOWN_MS = 90L

/** A feed card's give under the finger, liquid motion off. */
const val CARD_PRESS_SCALE = 0.97f

/** A smaller tile's (shelf cards, chips, pills): small things give a little more. */
const val TILE_PRESS_SCALE = 0.95f

/** A glass control swells a touch under the finger, as iOS 26's interactive glass does. */
const val GLASS_PRESS_SCALE = 1.08f

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

/** A row's flat wash, liquid motion off: UITableView's highlight. */
fun rowWash(dark: Boolean): Color = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)

/**
 * An indication. Liquid: the squish at [strength] and a radial [glow] (transparent for none)
 * under the finger. Off: a flat [wash] and the content drawn at [scale]. Both clip to [shape]
 * and draw the content at [dim] alpha at full press.
 */
class PressHighlight(
    internal val glow: Color,
    internal val strength: Float = 1f,
    internal val dim: Float = 1f,
    internal val shape: Shape = RectangleShape,
    internal val wash: Color = Color.Transparent,
    internal val scale: Float = 1f,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = DrawPressNode(interactionSource, this, cancelOnSlop = false)

    override fun equals(other: Any?) = other is PressHighlight && other.glow == glow && other.strength == strength && other.dim == dim && other.shape == shape &&
        other.wash == wash && other.scale == scale

    override fun hashCode() = ((((glow.hashCode() * 31 + strength.hashCode()) * 31 + dim.hashCode()) * 31 + shape.hashCode()) * 31 + wash.hashCode()) * 31 + scale.hashCode()
}

/** Feed cards: the squish (or a slight give), no glow or wash (a wash would fill the gutters round the card). */
val CardPress = PressHighlight(Color.Transparent, strength = CARD_PRESS_STRENGTH, scale = CARD_PRESS_SCALE)

/** Shelf cards, chips and pills. */
val TilePress = PressHighlight(Color.Transparent, strength = TILE_PRESS_STRENGTH, scale = TILE_PRESS_SCALE)

/** Icon and text buttons: the full squish, and the dim iOS gives them. */
val GlyphPress = PressHighlight(Color.Transparent, strength = 1f, dim = GLYPH_PRESS_ALPHA)

/**
 * The same press as a layer around everything after it, for what an indication can't reach:
 * a glass surface, which has to squish (or, liquid motion off, scale by [scale]) with its
 * content. [interactionSource] is the clickable's; the finger itself is watched here, in this
 * modifier's bounds, for the stretch after it.
 */
fun Modifier.pressEffect(interactionSource: InteractionSource, strength: Float = 1f, alpha: Float = 1f, scale: Float = 1f): Modifier =
    this then PressEffectElement(interactionSource, strength, alpha, scale)

/**
 * [highlight] as a modifier of its own rather than a clickable's indication, so it can watch the
 * finger: the liquid squish stretches after it while held, and lets go the moment it wanders
 * past the touch slop. Put before the clickable, whose indication is then null;
 * [interactionSource] is the clickable's, whose Press starts it.
 */
fun Modifier.pressHighlight(interactionSource: InteractionSource, highlight: PressHighlight): Modifier =
    this then PressHighlightElement(interactionSource, highlight)

private class PressHighlightElement(val source: InteractionSource, val highlight: PressHighlight) : ModifierNodeElement<DrawPressNode>() {
    override fun create() = DrawPressNode(source, highlight, cancelOnSlop = true)

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

/**
 * Both presses' springs, the clickable's interactions that drive them, and (liquid) where the
 * finger is: shared by the indication and the layer.
 */
private abstract class SquishNode(private var source: InteractionSource, var strength: Float, private val cancelOnSlop: Boolean) :
    Modifier.Node(), CompositionLocalConsumerModifierNode, PointerInputModifierNode {
    /** Liquid motion off: the press's 0-to-1. */
    val progress = Animatable(0f)
    private var moving: Job? = null

    // Liquid motion on: the squish.
    val sx = Animatable(1f)
    val sy = Animatable(1f)
    val tx = Animatable(0f)
    val ty = Animatable(0f)
    /** The glow's (and the dim's) 0-to-1. */
    val hl = Animatable(0f)
    /** Held: the surface keeps sinking toward the long press, 0 to 1, so the hold builds up before the menu lifts it. */
    val charge = Animatable(0f)
    private var charging: Job? = null
    /** How much further a held surface sinks: cards and up the full 5%, a row less. */
    val chargeScale get() = 1f - 0.05f * min(1f, strength / CARD_PRESS_STRENGTH) * charge.value
    var origin = Offset.Zero
    var size = Size.Zero
    private var base = floatArrayOf(1f, 1f, 0f, 0f)
    private var down = Offset.Zero
    private var pointerId = -1L
    /** Pressed, and not yet let go, cancelled, or moved off. */
    private var pressing = false
    private var pressedAt = 0L
    private var presses = 0
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

    private val reduce get() = currentValueOf(LocalReduceAnimation)
    private val liquid get() = currentValueOf(LocalLiquidMotion)

    /** Called when a liquid press starts, for kinds that cache something per press (the glow's brush). */
    open fun onPressAt(origin: Offset, size: Size) {}

    private fun collect() {
        collecting?.cancel()
        collecting = coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        pressedAt = System.nanoTime()
                        if (liquid) {
                            press(i.pressPosition)
                        } else {
                            moving?.cancel()
                            moving = launch { toProgress(1f, PressSpring) }
                        }
                    }
                    is PressInteraction.Release -> {
                        val heldMs = (System.nanoTime() - pressedAt) / 1_000_000
                        if (pressing) {
                            // A quick tap (in a list, Press and Release come together) still dents before it wobbles.
                            val press = presses
                            if (heldMs < MIN_SHOWN_MS) launch { delay(MIN_SHOWN_MS - heldMs); if (press == presses && pressing) settle(pop = true) } else settle(pop = true)
                        }
                        if (progress.targetValue != 0f || progress.value != 0f) {
                            moving?.cancel()
                            moving = launch {
                                if (heldMs < MIN_SHOWN_MS) delay(MIN_SHOWN_MS - heldMs)
                                toProgress(0f, ReleaseSpring)
                            }
                        }
                    }
                    // Became a scroll, or left the bounds: no hold, straight back.
                    is PressInteraction.Cancel -> {
                        if (pressing) settle(pop = false)
                        if (progress.targetValue != 0f || progress.value != 0f) {
                            moving?.cancel()
                            moving = launch { toProgress(0f, ReleaseSpring) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun toProgress(target: Float, spec: AnimationSpec<Float>) {
        if (reduce) progress.snapTo(target) else progress.animateTo(target, spec)
    }

    private fun to(a: Animatable<Float, *>, target: Float, spec: AnimationSpec<Float>, velocity: Float? = null) {
        coroutineScope.launch { if (velocity == null) a.animateTo(target, spec) else a.animateTo(target, spec, velocity) }
    }

    private fun press(at: Offset) {
        val w = size.width
        val h = size.height
        val ox = at.x.coerceIn(0f, max(w, 0f))
        val oy = at.y.coerceIn(0f, max(h, 0f))
        origin = Offset(ox, oy)
        down = at
        pressing = true
        presses++
        // The finger that's down is adopted by its first move.
        pointerId = -1L
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
        charging?.cancel()
        charging = coroutineScope.launch {
            // Past a tap's length, sink the rest of the way to the long press.
            delay(MIN_SHOWN_MS)
            val hold = currentValueOf(LocalViewConfiguration).longPressTimeoutMillis - MIN_SHOWN_MS
            charge.animateTo(1f, tween(hold.toInt().coerceAtLeast(100), easing = LinearOutSlowInEasing))
        }
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
    private fun settle(pop: Boolean) {
        pressing = false
        pointerId = -1L
        charging?.cancel()
        charging = coroutineScope.launch { if (reduce) charge.snapTo(0f) else charge.animateTo(0f, Sp.Jelly.unit) }
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
        if (!pressing) return
        if (pointerId == -1L) pointerId = pointerEvent.changes.firstOrNull { it.pressed }?.id?.value ?: return
        val c = pointerEvent.changes.firstOrNull { it.id.value == pointerId } ?: return
        // Let go: the clickable's Release or Cancel says how.
        if (!c.pressed || c.positionChangeIgnoreConsumed() == Offset.Zero) return
        val slop = currentValueOf(LocalViewConfiguration).touchSlop
        when {
            // Taken by a scroll or a drag, or wandered off: back without the kick.
            c.isConsumed -> settle(pop = false)
            cancelOnSlop && (c.position - down).getDistance() > slop -> settle(pop = false)
            else -> follow(c.position)
        }
    }

    override fun onCancelPointerInput() {
        if (pressing) settle(pop = false)
    }
}

private class DrawPressNode(source: InteractionSource, h: PressHighlight, cancelOnSlop: Boolean) : SquishNode(source, h.strength, cancelOnSlop), DrawModifierNode {
    private var glow = h.glow
    private var dim = h.dim
    private var shape = h.shape
    private var wash = h.wash
    private var scale = h.scale

    fun update(h: PressHighlight) {
        if (h.glow == glow && h.strength == strength && h.dim == dim && h.shape == shape && h.wash == wash && h.scale == scale) return
        glow = h.glow
        strength = h.strength
        dim = h.dim
        shape = h.shape
        wash = h.wash
        scale = h.scale
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
        val p = progress.value
        if (p != 0f) return drawClassic(p)
        val g = hl.value
        val sxv = sx.value
        val syv = sy.value
        val txv = tx.value
        val tyv = ty.value
        if (g == 0f && sxv == 1f && syv == 1f && txv == 0f && tyv == 0f && charge.value == 0f) return drawContent()
        val a = 1f + (dim - 1f) * g.coerceIn(0f, 1f)
        withTransform({
            translate(txv, tyv)
            scale(sxv * chargeScale, syv * chargeScale, origin)
        }) {
            this@draw.drawDimmed(a)
            val b = brush
            if (b != null && g > 0f) {
                val alpha = g.coerceIn(0f, 1f)
                if (shape == RectangleShape) drawRect(b, alpha = alpha) else drawOutline(shape.createOutline(size, layoutDirection, this), b, alpha = alpha)
            }
        }
    }

    /** Liquid motion off: the give, dim and wash, as before liquid motion. */
    private fun ContentDrawScope.drawClassic(p: Float) {
        val s = 1f + (scale - 1f) * p
        val a = 1f + (dim - 1f) * p
        scale(s) {
            this@drawClassic.drawDimmed(a)
            if (wash.alpha > 0f) {
                val w = wash.copy(alpha = wash.alpha * p.coerceIn(0f, 1f))
                if (shape == RectangleShape) drawRect(w) else drawOutline(shape.createOutline(size, layoutDirection, this), w)
            }
        }
    }

    private fun ContentDrawScope.drawDimmed(a: Float) {
        if (a < 1f) {
            dimPaint.alpha = a
            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), dimPaint)
            drawContent()
            drawContext.canvas.restore()
        } else {
            drawContent()
        }
    }
}

private class PressEffectElement(val source: InteractionSource, val strength: Float, val alpha: Float, val scale: Float) : ModifierNodeElement<LayerPressNode>() {
    override fun create() = LayerPressNode(source, strength, alpha, scale)

    override fun update(node: LayerPressNode) {
        node.updateSource(source)
        if (node.strength != strength || node.alpha != alpha || node.scale != scale) {
            node.strength = strength
            node.alpha = alpha
            node.scale = scale
            node.invalidatePlacement()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressEffect"
        properties["strength"] = strength
        properties["alpha"] = alpha
        properties["scale"] = scale
    }

    override fun equals(other: Any?) = other is PressEffectElement && other.source == source && other.strength == strength && other.alpha == alpha && other.scale == scale
    override fun hashCode() = ((source.hashCode() * 31 + strength.hashCode()) * 31 + alpha.hashCode()) * 31 + scale.hashCode()
}

private class LayerPressNode(source: InteractionSource, strength: Float, var alpha: Float, var scale: Float) : SquishNode(source, strength, cancelOnSlop = false), LayoutModifierNode {
    // Read in the layer block: the press only updates the layer's properties.
    private val layer: GraphicsLayerScope.() -> Unit = {
        val p = progress.value
        if (p != 0f) {
            val s = 1f + (scale - 1f) * p
            scaleX = s
            scaleY = s
            // The release's give overshoots past rest; alpha can't go past opaque.
            this.alpha = (1f + (this@LayerPressNode.alpha - 1f) * p).coerceIn(0f, 1f)
        } else {
            val w = this@LayerPressNode.size.width
            val h = this@LayerPressNode.size.height
            transformOrigin = if (w > 0f && h > 0f) TransformOrigin(origin.x / w, origin.y / h) else TransformOrigin.Center
            scaleX = sx.value * chargeScale
            scaleY = sy.value * chargeScale
            translationX = tx.value
            translationY = ty.value
            this.alpha = (1f + (this@LayerPressNode.alpha - 1f) * hl.value).coerceIn(0f, 1f)
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        size = Size(placeable.width.toFloat(), placeable.height.toFloat())
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layer) }
    }
}
