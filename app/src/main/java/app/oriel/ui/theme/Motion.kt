/*
 * Motion constants adapted from BitChord (GPL-3.0).
 */
package app.oriel.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/** Damping 0.72, stiffness 320: one soft pass beyond the mark and done, in about a third of a second. */
val GlassSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

/** The context menu's open spring. */
val MenuSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 420f)

/** Sheets and pixel offsets: critically damped, no bounce. Its threshold is half a pixel, so it suits pixel-scale values only. */
val SheetSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 195f, visibilityThreshold = 0.5f)

/**
 * The player sheet's 0-to-1 progress between the mini player and the watch page: critically
 * damped and a little quicker than [SheetSpring], settling in about 0.4 s. Its threshold is set for
 * a 0-to-1 value. ([SheetSpring]'s half a unit ended the flight with 4% of it left, about 60 px
 * on a phone, which then jumped into place.)
 */
val PlayerSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f, visibilityThreshold = 0.0005f)

val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/*
 * Press feedback (PressHighlight.kt): touch-down is quick and critically damped, so the
 * response is there before the finger has finished landing; release is softer and a touch
 * underdamped, so what was pressed settles back with the slight give iOS controls have.
 * Both work on a 0-to-1 press progress, hence the small thresholds.
 */
val PressSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1_400f, visibilityThreshold = 0.001f)
val ReleaseSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 520f, visibilityThreshold = 0.001f)

/** [spec], or an instant snap under "reduce animation". */
@Composable
@ReadOnlyComposable
fun <T> motion(spec: AnimationSpec<T>): AnimationSpec<T> = if (LocalReduceAnimation.current) snap() else spec

/** The same for a finite spec, as enter and exit transitions and content-size animations take. */
@Composable
@ReadOnlyComposable
fun <T> motion(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (LocalReduceAnimation.current) snap() else spec

@Composable
@ReadOnlyComposable
fun <T> fade(ms: Int = 220): FiniteAnimationSpec<T> = if (LocalReduceAnimation.current) snap() else tween(ms)
