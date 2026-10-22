package app.oriel.ui.player

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalReduceAnimation

/**
 * A tap on a control drawn over video or in the player's pages: no ripple, the control dims
 * while held, as iOS's do, and the haptic plays with the tap. The 44dp target is the caller's
 * to give (a min height or a required size around the visual).
 */
internal fun Modifier.pressClickable(
    onClickLabel: String? = null,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    haptic: Haptic? = Haptic.Tap,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberHaptics()
    graphicsLayer { alpha = if (pressed) 0.6f else 1f }
        .clickable(interaction, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role) {
            haptic?.let(haptics::play)
            onClick()
        }
}

/** [enter], or none under Reduce motion: the content just appears. */
@Composable
@ReadOnlyComposable
internal fun enterMotion(enter: EnterTransition): EnterTransition = if (LocalReduceAnimation.current) EnterTransition.None else enter

/** [exit], or none under Reduce motion: the content just goes. */
@Composable
@ReadOnlyComposable
internal fun exitMotion(exit: ExitTransition): ExitTransition = if (LocalReduceAnimation.current) ExitTransition.None else exit

/**
 * Where a pointer is on the screen, for a drag on something the drag itself moves. Pointer
 * positions come in the moving node's own coordinates, so as it follows the finger each move
 * reads short, and the picture (or panel) fell behind the finger by as much as it had moved.
 */
internal class ScreenPosition {
    internal var coordinates: LayoutCoordinates? = null
    fun of(local: Offset): Offset = coordinates?.takeIf { it.isAttached }?.localToRoot(local) ?: local
}

/** Gives [position] this node's coordinates: put it just before the pointer input it serves. */
internal fun Modifier.screenPosition(position: ScreenPosition): Modifier = onPlaced { position.coordinates = it }
