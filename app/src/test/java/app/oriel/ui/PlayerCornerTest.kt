package app.oriel.ui

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.oriel.ui.player.fullscreenPullCorner
import app.oriel.ui.theme.SheetSpring
import app.oriel.ui.theme.Sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCornerTest {
    private val density = Density(3.5f)
    private val maxCorner = 20f * density.density

    /** Samples the real spring, including the frames before it settles, at more than 120 Hz. */
    private fun checkFrames(animation: TargetBasedAnimation<Float, AnimationVector1D>): Float {
        var minimumPull = 0f
        for (time in 0L..animation.durationNanos step 4_000_000L) {
            val pull = animation.getValueFromNanos(time)
            minimumPull = minOf(minimumPull, pull)
            val extra = fullscreenPullCorner(pull, maxCorner)
            assertTrue("pull $pull at $time ns produced radius $extra", extra in 0f..maxCorner)
            // Fullscreen/the watch page has no base corner; a collapsing page has a positive one.
            for (base in listOf(0f, 7f * density.density, 14f * density.density)) {
                RoundedCornerShape(base + extra).createOutline(Size(1440f, 3120f), LayoutDirection.Ltr, density)
            }
        }
        assertEquals(0f, fullscreenPullCorner(animation.getValueFromNanos(animation.durationNanos), maxCorner), 0f)
        return minimumPull
    }

    @Test fun upwardFullscreenFlickFromZeroHasValidCorners() {
        // Upward dragging displays zero pull, but release still carries the finger's negative speed.
        val animation = TargetBasedAnimation(SheetSpring, Float.VectorConverter, 0f, 0f, -1200f)
        val pull = animation.getValueFromNanos(16_000_000L)
        // Reproduce the shipping formula's exception with an actual first-frame spring value.
        assertThrows(IllegalArgumentException::class.java) {
            RoundedCornerShape((pull / 6f).coerceAtMost(maxCorner))
                .createOutline(Size(1440f, 3120f), LayoutDirection.Ltr, density)
        }
        assertTrue("even the critically damped spring crosses zero", checkFrames(animation) < -1f)
    }

    @Test fun downwardPullReversedBeforeReleaseHasValidCorners() {
        val animation = TargetBasedAnimation(SheetSpring, Float.VectorConverter, 40f, 0f, -2400f)
        assertTrue("the returning finger carries the picture past rest", checkFrames(animation) < -1f)
    }

    @Test fun cancelledLiquidPullKeepsItsBounceButNotNegativeCorners() {
        // Cancellation (for example a pinch taking over) releases with zero velocity.
        val animation = TargetBasedAnimation(Sp.Bouncy.px, Float.VectorConverter, 180f, 0f, 0f)
        assertTrue("the intended liquid wobble is still present", checkFrames(animation) < -1f)
    }

    @Test fun interruptedAndRepeatedReturnsHaveValidCorners() {
        var animation = TargetBasedAnimation(Sp.Bouncy.px, Float.VectorConverter, 140f, 0f, 700f)
        repeat(4) {
            checkFrames(animation)
            // Retarget while moving, then simulate the next finger taking the picture and releasing up.
            val interruptedAt = 80_000_000L
            animation = TargetBasedAnimation(
                SheetSpring, Float.VectorConverter,
                animation.getValueFromNanos(interruptedAt), 0f,
                animation.getVelocityVectorFromNanos(interruptedAt).value,
            )
            checkFrames(animation)
            animation = TargetBasedAnimation(SheetSpring, Float.VectorConverter, 24f, 0f, -4000f)
        }
        assertTrue(checkFrames(animation) < -1f)
    }

    @Test fun positivePullAndUpperLimitAreUnchanged() {
        assertEquals(0f, fullscreenPullCorner(-30f, maxCorner), 0f)
        assertEquals(0f, fullscreenPullCorner(0f, maxCorner), 0f)
        assertEquals(10f, fullscreenPullCorner(60f, maxCorner), 0f)
        assertEquals(maxCorner, fullscreenPullCorner(maxCorner * 6f, maxCorner), 0f)
        assertEquals(maxCorner, fullscreenPullCorner(2000f, maxCorner), 0f)
    }
}
