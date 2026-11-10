package app.oriel.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the window's size becomes columns, widths and panes. */
class WindowLayoutTest {
    private fun layout(w: Int, h: Int, smallest: Int = minOf(w, h)) = WindowLayout(w.dp, h.dp, smallest.dp)

    @Test fun phoneKeepsItsLayoutInEveryOrientation() {
        val portrait = layout(412, 915)
        val landscape = layout(915, 412)
        for (l in listOf(portrait, landscape)) {
            assertFalse(l.wide)
            assertFalse(l.splitWatch)
            PageWidth.entries.forEach { kind ->
                assertEquals(1, l.columns(kind))
                assertEquals(0.dp, l.inset(kind))
            }
            assertEquals(2, l.shortColumns(PageWidth.Feed))
        }
    }

    @Test fun feedGainsColumnsWithTheWidth() {
        assertEquals(1, layout(560, 900, smallest = 700).columns(PageWidth.Feed))
        assertEquals(2, layout(600, 960).columns(PageWidth.Feed))
        assertEquals(2, layout(800, 1280).columns(PageWidth.Feed))
        assertEquals(3, layout(1280, 800).columns(PageWidth.Feed))
        assertEquals(4, layout(1600, 1000).columns(PageWidth.Feed))
    }

    @Test fun listsAndFormsStayOneCentredColumn() {
        val tablet = layout(1280, 800)
        assertEquals(1, tablet.columns(PageWidth.List))
        assertEquals(1, tablet.columns(PageWidth.Form))
        assertEquals(760.dp, tablet.contentWidth(PageWidth.List))
        assertEquals(680.dp, tablet.contentWidth(PageWidth.Form))
        assertEquals(300.dp, tablet.inset(PageWidth.Form))
        // Narrower than the cap: nothing to centre.
        assertEquals(0.dp, layout(600, 960).inset(PageWidth.List))
    }

    @Test fun feedIsCappedOnHugeScreens() {
        val huge = layout(2400, 1500)
        assertEquals(1600.dp, huge.contentWidth(PageWidth.Feed))
        assertEquals(400.dp, huge.inset(PageWidth.Feed))
    }

    @Test fun shortsFollowTheVideoColumns() {
        assertEquals(4, layout(800, 1280).shortColumns(PageWidth.Feed))
        assertEquals(6, layout(1280, 800).shortColumns(PageWidth.Feed))
    }

    @Test fun watchPageSplitsOnlyOnWideLandscapeTablets() {
        assertTrue(layout(1280, 800).splitWatch)
        assertFalse("portrait tablet", layout(800, 1280).splitWatch)
        assertTrue("small tablet, landscape", layout(960, 600).splitWatch)
        assertFalse("tablet split to a narrow window", layout(500, 800, smallest = 800).splitWatch)
    }
}
