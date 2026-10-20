package app.oriel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryboardTest {
    // The shape of a real spec (a 213-second video), with the signatures shortened.
    private val spec = "https://i.ytimg.com/sb/VIDEO/storyboard3_L\$L/\$N.jpg?sqp=abc" +
        "|48#27#100#10#10#0#default#rs\$A" +
        "|80#45#108#10#10#2000#M\$M#rs\$B" +
        "|160#90#108#5#5#2000#M\$M#rs\$C"

    @Test fun parsesLevelsSmallestFirst() {
        val sb = Storyboard.parse(spec, 213)!!
        assertEquals(listOf(48, 80, 160), sb.levels.map { it.width })
        assertEquals(160, sb.best.width)
        assertEquals(5, sb.best.sheetCount)
        assertEquals(1, sb.levels[0].sheetCount)
    }

    @Test fun framesMapToSheetsAndCells() {
        val sb = Storyboard.parse(spec, 213)!!
        // Frame 0: first cell of the first sheet.
        val first = sb.frameAt(0)
        assertEquals("https://i.ytimg.com/sb/VIDEO/storyboard3_L2/M0.jpg?sqp=abc&sigh=rs\$C", first.url)
        assertEquals(0 to 0, first.x to first.y)
        // 61 s at one frame per 2 s is frame 30: sheet 1 (25 per sheet), cell 5 = column 0, row 1.
        val f = sb.frameAt(61_000)
        assertEquals(1, f.sheet)
        assertTrue(f.url.contains("/M1.jpg"))
        assertEquals(0 to 90, f.x to f.y)
        // Past the end clamps to the last frame (107): sheet 4, cell 7 = column 2, row 1.
        val last = sb.frameAt(10_000_000)
        assertEquals(4, last.sheet)
        assertEquals(320 to 90, last.x to last.y)
    }

    @Test fun evenlySpreadLevelUsesTheDuration() {
        val sb = Storyboard.parse(spec, 213)!!
        val default = sb.levels[0]
        // 100 frames over 213 s: 2130 ms apart, so 106.5 s is frame 50 = column 0, row 5.
        val f = sb.frameAt(106_500, default)
        assertEquals("https://i.ytimg.com/sb/VIDEO/storyboard3_L0/default.jpg?sqp=abc&sigh=rs\$A", f.url)
        assertEquals(0 to 5 * 27, f.x to f.y)
    }

    @Test fun rejectsWhatIsntASpec() {
        assertNull(Storyboard.parse(null, 100))
        assertNull(Storyboard.parse("https://x", 100))
        assertNull(Storyboard.parse(spec, null))
        assertNull(Storyboard.parse("https://x|1#2#3", 100))
    }
}
