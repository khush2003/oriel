package app.oriel.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * iOS's cell highlight in place of Material's ripple: a flat wash over the pressed thing,
 * there at once on touch and fading out after the finger lifts. No circle grows out from
 * the touch point. A tap still shows it, because the wash holds for at least 90 ms.
 */
class PressHighlight(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color)

    override fun equals(other: Any?) = other is PressHighlight && other.color == color
    override fun hashCode() = color.hashCode()

    private class Node(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
        private val alpha = Animatable(0f)
        private var release: Job? = null

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> {
                            release?.cancel()
                            launch { alpha.animateTo(1f, tween(60)) }
                        }
                        is PressInteraction.Release, is PressInteraction.Cancel -> {
                            release = launch {
                                kotlinx.coroutines.delay(90)
                                alpha.animateTo(0f, tween(220))
                            }
                        }
                    }
                }
            }
            coroutineScope.launch {
                androidx.compose.runtime.snapshotFlow { alpha.value }.collect { invalidateDraw() }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val a = alpha.value
            if (a > 0f) drawRect(color.copy(alpha = color.alpha * a))
        }
    }
}
