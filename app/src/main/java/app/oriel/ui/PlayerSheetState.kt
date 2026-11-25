package app.oriel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.PlayerSpring
import androidx.compose.animation.core.AnimationEndReason
import app.oriel.ui.theme.VideoOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The player sheet: 0 is the collapsed window, 1 the full watch page. Fullscreen is landscape video only. */
@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    // Bounded: a hard flick lands on the dock or the top instead of carrying past it.
    val progress = Animatable(0f).apply { updateBounds(0f, 1f) }
    var fullscreen by mutableStateOf(false)
    val expanded: Boolean get() = progress.targetValue > 0.5f
    /** Which corner the collapsed picture-in-picture window rests in: 0 top-start, 1 top-end, 2 bottom-start, 3 bottom-end. */
    var pipCorner by mutableIntStateOf(3)
    /** The window's size, pinched bigger or smaller, relative to its default. */
    var pipScale by mutableFloatStateOf(1f)
    /**
     * The mock's `P.q`: a jelly squash of the picture (positive squashes it, as a fraction of its
     * height), kicked when a new video lands in a page already open. Only drawn; Reduce motion doesn't draw it.
     */
    val squash = Animatable(0f)
    /**
     * The flight's overshoot past where it lands, which [progress] (bounded) can't show: above 1
     * at the top, below 0 at the dock, as the mock's `over`. The picture stretches up by it at
     * the top and squashes on landing at the dock. Only drawn.
     */
    val over = Animatable(0f)
    /** The last video opened while the player was showing or about to: where from, for the hand-off. */
    var handoff by mutableStateOf<VideoHandoff?>(null)
    /** Liquid motion is on (the player sets it as it composes): the flight overshoots on the mock's spring. Off, it's the plain one. */
    var liquid = false

    fun expand() {
        scope.launch { fly(1f, 0f) }
    }

    fun collapse() {
        fullscreen = false
        scope.launch { fly(0f, 0f) }
    }

    suspend fun settle(target: Float, velocity: Float) = fly(target, velocity)

    /** A new video in the page already open: the picture wobbles as the mock's does (a jelly kick of 2.2). */
    fun jiggle() {
        scope.launch {
            squash.snapTo(0f)
            squash.animateTo(0f, Sp.Jelly.unit, initialVelocity = 2.2f)
        }
    }

    // The mock's player spring (response 0.5, damping 0.72): it overshoots, and what it would have
    // carried past the bound plays on in [over], on the same spring, from the speed it hit it at.
    private suspend fun fly(target: Float, velocity: Float) {
        if (!liquid) {
            progress.animateTo(target, PlayerSpring, initialVelocity = velocity)
            return
        }
        val result = progress.animateTo(target, Sp.Player.unit, initialVelocity = velocity)
        if (result.endReason == AnimationEndReason.BoundReached) {
            over.snapTo(0f)
            over.animateTo(0f, Sp.Player.unit, initialVelocity = result.endState.velocity)
        }
    }
}

/**
 * A video opened while the player was up (or rising): [origin] is the thumbnail it was tapped
 * on, if any; [fromDock] says the player was collapsed or closed, so the whole player grows out
 * of the thumbnail rather than the thumbnail flying into a page already open; [windowShowing]
 * that a collapsed window was playing, to be absorbed as it does.
 */
data class VideoHandoff(val videoId: String, val origin: VideoOrigin?, val fromDock: Boolean, val windowShowing: Boolean, val at: Long = System.nanoTime())
