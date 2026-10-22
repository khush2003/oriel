package app.oriel.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.data.ThemeMode
import app.oriel.ui.components.longPressMenuClickable
import app.oriel.ui.theme.CardPress
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.OrielTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Presses in a scrolling list of video cards, as the feeds have them: a scroll never ends in a
 * click or a menu, a tap opens, a hold opens the menu only while the finger stays put, and the
 * press doesn't show the moment a finger lands to scroll. With liquid motion on and off.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = app.oriel.TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class PressScrollTest {
    @get:Rule
    val rule = createComposeRule()

    private val clicks = mutableIntStateOf(0)
    private val holds = mutableIntStateOf(0)
    private lateinit var list: LazyListState

    private fun cards(liquid: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK, liquidMotion = liquid) {
                CompositionLocalProvider(LocalLiquidMotion provides liquid, LocalIndication provides CardPress) {
                    list = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        items(40) {
                            Box(
                                Modifier.fillMaxWidth().height(240.dp)
                                    .longPressMenuClickable(onClick = { clicks.intValue++ }, onLongClick = { holds.intValue++ })
                                    .padding(16.dp).background(Color(0xFF3060C0)),
                            )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
    }

    private fun swipeOverCards(liquid: Boolean) {
        cards(liquid)
        rule.onRoot().performTouchInput {
            down(Offset(width / 2f, height * 0.7f))
            // A finger that lands and starts moving at once, a little off vertical, as a thumb does.
            repeat(20) { moveBy(Offset(3f, -30f)); advanceEventTime(16) }
            up()
        }
        rule.mainClock.advanceTimeBy(2_000)
        assertTrue("the list scrolled", list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
        assertEquals("no card opened", 0, clicks.intValue)
        assertEquals("no menu opened", 0, holds.intValue)
    }

    @Test fun swipeOverCardsOpensNothing() = swipeOverCards(liquid = false)
    @Test fun swipeOverCardsOpensNothingLiquid() = swipeOverCards(liquid = true)

    private fun tap(liquid: Boolean) {
        cards(liquid)
        rule.onRoot().performTouchInput { down(Offset(width / 2f, 300f)); advanceEventTime(60); up() }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("the tap opened the card", 1, clicks.intValue)
        assertEquals(0, holds.intValue)
    }

    @Test fun tapOpens() = tap(liquid = false)
    @Test fun tapOpensLiquid() = tap(liquid = true)

    private fun hold(liquid: Boolean) {
        cards(liquid)
        rule.onRoot().performTouchInput { down(Offset(width / 2f, 300f)) }
        rule.mainClock.advanceTimeBy(800)
        assertEquals("the hold opened the menu", 1, holds.intValue)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("and didn't open the card", 0, clicks.intValue)
    }

    @Test fun holdOpensMenu() = hold(liquid = false)
    @Test fun holdOpensMenuLiquid() = hold(liquid = true)

    /** A finger that lands, rests and then slides sideways (which the vertical list doesn't take) isn't a hold. */
    private fun holdThenMove(liquid: Boolean) {
        cards(liquid)
        rule.onRoot().performTouchInput {
            down(Offset(width / 2f, 300f))
            advanceEventTime(150)
        }
        rule.mainClock.advanceTimeBy(150)
        rule.onRoot().performTouchInput { repeat(6) { moveBy(Offset(12f, 0f)); advanceEventTime(16) } }
        rule.mainClock.advanceTimeBy(800)
        assertEquals("no menu", 0, holds.intValue)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("no click either", 0, clicks.intValue)
    }

    @Test fun holdThenMoveDoesntOpenMenu() = holdThenMove(liquid = false)
    @Test fun holdThenMoveDoesntOpenMenuLiquid() = holdThenMove(liquid = true)

    /** A sideways swipe across a card in a vertical list: nobody scrolls, and it still isn't a tap. */
    private fun sideways(liquid: Boolean) {
        cards(liquid)
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.2f, 300f))
            repeat(8) { moveBy(Offset(30f, 2f)); advanceEventTime(16) }
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("no card opened", 0, clicks.intValue)
    }

    @Test fun sidewaysSwipeDoesntOpen() = sideways(liquid = false)
    @Test fun sidewaysSwipeDoesntOpenLiquid() = sideways(liquid = true)

    private fun frame(): IntArray {
        val map = rule.onRoot().captureToImage().toPixelMap()
        return IntArray(map.width * map.height) { map[it % map.width, it / map.width].hashCode() }
    }

    /**
     * In a list, the squish waits for the tap timeout: a finger that lands to scroll doesn't
     * squish the card it lands on. Once the finger has stayed, it does.
     */
    @Test fun liquidPressWaitsInAList() {
        cards(liquid = true)
        val before = frame()
        rule.onRoot().performTouchInput { down(Offset(width / 2f, 300f)) }
        rule.mainClock.advanceTimeBy(48)
        assertTrue("nothing squished at the touch", before.contentEquals(frame()))
        rule.mainClock.advanceTimeBy(250)
        assertFalse("squished once held", before.contentEquals(frame()))
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(2_000)
    }

    /** A press whose finger slides past the slop lets go there, without waiting for the up. */
    @Test fun liquidPressLetsGoPastTheSlop() {
        cards(liquid = true)
        val before = frame()
        rule.onRoot().performTouchInput { down(Offset(width * 0.3f, 300f)) }
        rule.mainClock.advanceTimeBy(300)
        assertFalse("squished once held", before.contentEquals(frame()))
        rule.onRoot().performTouchInput { repeat(8) { moveBy(Offset(12f, 0f)); advanceEventTime(16) } }
        rule.mainClock.advanceTimeBy(3_000)
        assertTrue("back at rest with the finger still down", before.contentEquals(frame()))
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0, clicks.intValue)
    }
}
