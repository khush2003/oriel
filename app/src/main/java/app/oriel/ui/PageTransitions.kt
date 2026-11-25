package app.oriel.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import app.oriel.ui.theme.Sp
import androidx.compose.animation.core.Spring
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import app.oriel.ui.navigation.SearchRoute
import app.oriel.ui.navigation.tabRoot

/** A tab's root or Search: moving between these is a tab switch. A short pushed from a feed isn't one. */
private fun NavBackStackEntry.isRoot(): Boolean = tabRoot() != null || destination.hasRoute(SearchRoute::class)

/**
 * How pages push: the new page slides in whole from the right over the old one, which drifts
 * left and dims toward the black behind it rather than fading away. Push and
 * pop share one spring, as UIKit's do; the dim rides the same one so the two finish together.
 */
internal class PushStyle(val spring: androidx.compose.animation.core.FiniteAnimationSpec<IntOffset>, val dimSpring: androidx.compose.animation.core.FiniteAnimationSpec<Float>, val drift: Float, val liquid: Boolean)

/**
 * iOS push: critically damped, the page beneath drifting a third of the way. Kept here rather
 * than in Motion.kt: it's the one IntOffset spring, and only pushes use it.
 */
internal val ClassicPush = PushStyle(
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold),
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f),
    drift = 1f / 3f,
    liquid = false,
)

/** The mock's push: its spring (response 0.5, damping 0.88, a whisker of overshoot), the page beneath drifting 28%. */
internal val LiquidPush = PushStyle(Sp.Push.spec(IntOffset.VisibilityThreshold), Sp.Push.unit, drift = 0.28f, liquid = true)

/** How opaque the page beneath stays over black: about UIKit's dimming in each mode, and the mock's 40% in both. */
internal const val PUSH_DIM_DARK = 0.7f
internal const val PUSH_DIM_LIGHT = 0.88f
internal const val PUSH_DIM_LIQUID = 0.6f

/**
 * The edge swipe's curve. It's seeked by the gesture's progress, so near the start it has to be
 * about linear for the page to stay under the finger; it eases out at the end so a release
 * lands softly instead of stopping dead.
 */
private val SwipeBackEasing = CubicBezierEasing(0.4f, 0.4f, 0.65f, 1f)
private const val SWIPE_BACK_MS = 340

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(tabSwitchEntry: String?) =
    (initialState.isRoot() && targetState.isRoot()) || targetState.id == tabSwitchEntry

/*
 * A tab switch crossfades by fading only the page on top, in for a switch and out for a pop
 * back, with the page beneath kept opaque until it's done: fading both let the black behind
 * show through mid-way.
 */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.push(style: PushStyle, forward: Boolean, tabSwitchEntry: String?, dim: Float): EnterTransition {
    if (isTabSwitch(tabSwitchEntry)) {
        if (!forward) return EnterTransition.None
        // Liquid, a tab switch crossfades as the mock's do, the incoming page growing from 97%.
        return if (style.liquid) fadeIn(Sp.TabFade.unit) + scaleIn(Sp.TabFade.unit, initialScale = 0.97f) else fadeIn(tween(160))
    }
    return if (forward) slideInHorizontally(style.spring) { it }
    else slideInHorizontally(style.spring) { drift(it, style) } + fadeIn(style.dimSpring, initialAlpha = dim)
}

/** The page beneath's offset at full drift, for a page [width] wide. */
private fun drift(width: Int, style: PushStyle): Int = if (style.liquid) -(width * style.drift).toInt() else -width / 3

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pushAway(style: PushStyle, forward: Boolean, tabSwitchEntry: String?, dim: Float): ExitTransition {
    if (isTabSwitch(tabSwitchEntry)) return if (forward) ExitTransition.KeepUntilTransitionsFinished else fadeOut(tween(160))
    return if (forward) slideOutHorizontally(style.spring) { drift(it, style) } + fadeOut(style.dimSpring, targetAlpha = dim)
    else slideOutHorizontally(style.spring) { it }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.swipeBackIn(style: PushStyle, tabSwitchEntry: String?, dim: Float): EnterTransition {
    if (isTabSwitch(tabSwitchEntry)) return EnterTransition.None
    val spec = tween<IntOffset>(SWIPE_BACK_MS, easing = SwipeBackEasing)
    return slideInHorizontally(spec) { drift(it, style) } + fadeIn(tween(SWIPE_BACK_MS, easing = SwipeBackEasing), initialAlpha = dim)
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.swipeBackOut(tabSwitchEntry: String?): ExitTransition {
    if (isTabSwitch(tabSwitchEntry)) return fadeOut(tween(SWIPE_BACK_MS, easing = LinearEasing))
    return slideOutHorizontally(tween(SWIPE_BACK_MS, easing = SwipeBackEasing)) { it }
}
