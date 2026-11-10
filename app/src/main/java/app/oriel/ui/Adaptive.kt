package app.oriel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What a page is made of, and so how it uses a wide window. */
enum class PageWidth {
    /** Video cards and shelves: as many columns as fit, up to a wide cap. */
    Feed,

    /** Rows (playlists, history, library lists): one comfortable column, centred. */
    List,

    /** Grouped settings and account pages: one centred column, as iPad's Settings sets its forms. */
    Form,
}

/**
 * The window's size, which every wide-screen decision comes from. Phones keep the layout they
 * always had, in any orientation (the window of a phone turned sideways is wide but short, and it
 * isn't a tablet); a tablet's layout follows the width of the window the app is in, so split screen
 * and a narrow window fall back to the phone layout without a separate code path.
 */
@Immutable
class WindowLayout(val width: Dp, val height: Dp, val smallestWidth: Dp) {
    /** A tablet or unfolded foldable: the screen is at least 600dp on its short side. */
    val tablet: Boolean get() = smallestWidth >= TABLET_MIN

    /** The window is wide enough to lay pages out for a bigger screen. */
    val wide: Boolean get() = tablet && width >= TABLET_MIN

    val landscape: Boolean get() = width > height

    /** The watch page's two panes: the video and its details beside what to watch next. */
    val splitWatch: Boolean get() = wide && width >= SPLIT_MIN && landscape

    /** The widest a page of this kind gets before it's centred. */
    fun contentWidth(kind: PageWidth): Dp = when {
        !wide -> width
        kind == PageWidth.Feed -> minOf(width, FEED_MAX)
        kind == PageWidth.Form -> minOf(width, FORM_MAX)
        else -> minOf(width, LIST_MAX)
    }

    /** Space on each side that centres a page of this kind. */
    fun inset(kind: PageWidth): Dp = ((width - contentWidth(kind)) / 2).coerceAtLeast(0.dp)

    /** How many cards or rows of this kind sit side by side. */
    fun columns(kind: PageWidth): Int = when {
        !wide -> 1
        kind != PageWidth.Feed -> 1
        width >= 1400.dp -> 4
        width >= 1000.dp -> 3
        else -> 2
    }

    /** Short posters across a grid of Shorts: two on a phone, and two for every video column. */
    fun shortColumns(kind: PageWidth): Int = columns(kind).let { if (it == 1) 2 else it * 2 }

    companion object {
        val TABLET_MIN = 600.dp
        val SPLIT_MIN = 840.dp
        private val FEED_MAX = 1600.dp
        private val LIST_MAX = 760.dp
        private val FORM_MAX = 680.dp
    }
}

/** A phone-sized window until [rememberWindowLayout] says otherwise (tests that skip the root get this). */
val LocalWindowLayout = compositionLocalOf { WindowLayout(360.dp, 800.dp, 360.dp) }

@Composable
fun rememberWindowLayout(): WindowLayout {
    // The window's own size, which follows split screen and resizing, not the display's.
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val smallest = LocalConfiguration.current.smallestScreenWidthDp
    return remember(size, density, smallest) {
        WindowLayout((size.width / density).dp, (size.height / density).dp, smallest.dp)
    }
}

/** The space on each side of the page's list that centres it on a wide window (0 on a phone). */
val LocalPageInset = compositionLocalOf { 0.dp }

/**
 * Lets an item run edge to edge across a centred page (a banner, a header): it's measured as wide
 * as the window and placed over the page's side space.
 */
@Composable
fun Modifier.fullBleed(): Modifier {
    val inset = with(LocalDensity.current) { LocalPageInset.current.roundToPx() }
    if (inset <= 0) return this
    return layout { measurable, c ->
        val width = c.maxWidth + inset * 2
        val placeable = measurable.measure(c.copy(minWidth = width, maxWidth = width))
        layout(c.maxWidth, placeable.height) { placeable.place(-inset, 0) }
    }
}
