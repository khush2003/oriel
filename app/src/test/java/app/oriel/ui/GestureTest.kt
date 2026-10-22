package app.oriel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.data.ThemeMode
import app.oriel.ui.components.SwipeToRemove
import app.oriel.ui.components.longPressMenuClickable
import app.oriel.ui.theme.OrielTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Touch gestures on their own, outside the app frame: which gesture a finger's movement belongs to. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = app.oriel.TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class GestureTest {
    @get:Rule
    val rule = createComposeRule()

    /** The swiped row follows every move of the finger, even several between two frames. */
    @Test fun swipeToRemoveFollowsTheFinger() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK) {
                SwipeToRemove({}) { Box(Modifier.fillMaxWidth().height(80.dp).testTag("row")) }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        val slop = rule.onRoot().fetchSemanticsNode().layoutInfo.viewConfiguration.touchSlop
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.8f, 80f))
            repeat(12) { moveBy(Offset(-20f, 0f)) }
        }
        rule.mainClock.advanceTimeBy(32)
        val left = with(rule.density) { rule.onNodeWithTag("row").getBoundsInRoot().left.toPx() }
        assertEquals("row offset", -(240f - slop), left, 2f)
        rule.onRoot().performTouchInput { up() }
    }

    /** Tapping a row whose Remove button is showing closes it, as iOS does, rather than opening the row. */
    @Test fun tapOnOpenSwipeRowClosesIt() {
        val clicks = mutableIntStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK) {
                SwipeToRemove({}) { Box(Modifier.fillMaxWidth().height(80.dp).testTag("row").clickable { clicks.intValue++ }) }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        // Open it: a slow drag past the button's width.
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.8f, 80f))
            repeat(12) { moveBy(Offset(-20f, 0f)); advanceEventTime(16) }
            advanceEventTime(200)
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        val open = with(rule.density) { rule.onNodeWithTag("row").getBoundsInRoot().left.toPx() }
        assertTrue("opened: $open", open < -100f)
        rule.onRoot().performTouchInput { click(Offset(width * 0.3f, 80f)) }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("the row's own tap didn't run", 0, clicks.intValue)
        val closed = with(rule.density) { rule.onNodeWithTag("row").getBoundsInRoot().left.toPx() }
        assertEquals("closed", 0f, closed, 1f)
    }

    /** Once a press-and-hold has opened its menu, moving the finger doesn't scroll the list under it. */
    @Test fun heldRowDoesntScrollTheList() {
        val held = mutableIntStateOf(0)
        lateinit var list: LazyListState
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK) {
                list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(60) { i ->
                        Box(Modifier.fillMaxWidth().height(80.dp).longPressMenuClickable(onClick = {}, onLongClick = { held.intValue++ })) { Text("Row $i") }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onRoot().performTouchInput { down(Offset(width / 2f, 400f)) }
        rule.mainClock.advanceTimeBy(800)
        assertEquals("the hold opened the menu", 1, held.intValue)
        rule.onRoot().performTouchInput {
            repeat(15) { moveBy(Offset(0f, -30f)); advanceEventTime(16) }
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("the list stayed put", 0, list.firstVisibleItemIndex)
        assertEquals("the list stayed put", 0, list.firstVisibleItemScrollOffset)
    }

    /** A tap on a flinging list stops it, and isn't taken as a tap on the row it lands on. */
    @Test fun tapDuringFlingStopsIt() {
        val clicks = mutableIntStateOf(0)
        lateinit var list: LazyListState
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK) {
                list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(400) { i -> Box(Modifier.fillMaxWidth().height(80.dp).clickable { clicks.intValue++ }) { Text("Row $i") } }
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onRoot().performTouchInput { swipe(Offset(width / 2f, height * 0.85f), Offset(width / 2f, height * 0.15f), 80) }
        rule.mainClock.advanceTimeBy(150)
        val during = list.firstVisibleItemIndex
        assertTrue("still flinging when the tap lands", list.isScrollInProgress)
        rule.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        rule.mainClock.advanceTimeBy(100)
        val stoppedAt = list.firstVisibleItemIndex
        rule.mainClock.advanceTimeBy(1_000)
        println("fling: during=$during stoppedAt=$stoppedAt after=${list.firstVisibleItemIndex} clicks=${clicks.intValue}")
        assertEquals("the fling stopped", stoppedAt, list.firstVisibleItemIndex)
        assertEquals("no row was tapped", 0, clicks.intValue)
    }

    /** Starting to scroll the list on an open row closes the row, and the list still scrolls. */
    @Test fun scrollOnOpenSwipeRowClosesItAndScrolls() {
        lateinit var list: LazyListState
        // Read from layout: in a lazy list the semantics bounds don't follow the row's offset.
        var rowLeft = 0f
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK) {
                list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(60) { i ->
                        SwipeToRemove({}, Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().height(80.dp).clickable { }.onGloballyPositioned { if (i == 0) rowLeft = it.positionInRoot().x })
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.8f, 80f))
            repeat(12) { moveBy(Offset(-20f, 0f)); advanceEventTime(16) }
            advanceEventTime(200)
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("opened: $rowLeft", rowLeft < -100f)
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.3f, 120f))
            // Not so far that the row scrolls out of the list.
            repeat(5) { moveBy(Offset(0f, -20f)); advanceEventTime(16) }
            advanceEventTime(200)
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("the list scrolled", list.firstVisibleItemScrollOffset > 0 || list.firstVisibleItemIndex > 0)
        assertEquals("closed", 0f, rowLeft, 1f)
    }

    /** A finger landing on a list bouncing back from its edge catches it there; lifting it lets it finish. */
    @Test fun touchCatchesTheBounce() {
        lateinit var list: LazyListState
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK) {
                list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(60) { i -> Box(Modifier.fillMaxWidth().height(80.dp).testTag("row$i")) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        fun top() = with(rule.density) { rule.onNodeWithTag("row0").getBoundsInRoot().top.toPx() }
        // Pulled down past the top, slowly, and let go.
        rule.onRoot().performTouchInput {
            down(Offset(width / 2f, 300f))
            repeat(20) { moveBy(Offset(0f, 20f)); advanceEventTime(16) }
            advanceEventTime(300)
            up()
        }
        rule.mainClock.advanceTimeBy(48)
        val pulled = top()
        assertTrue("stretched: $pulled", pulled > 20f)
        rule.onRoot().performTouchInput { down(Offset(width / 2f, 600f)) }
        rule.mainClock.advanceTimeBy(16)
        val caught = top()
        rule.mainClock.advanceTimeBy(500)
        assertEquals("held where the finger caught it", caught, top(), 1f)
        assertTrue("and still stretched", caught > 1f)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_500)
        assertEquals("settled once let go", 0f, top(), 1f)
    }
}

