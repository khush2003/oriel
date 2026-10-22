package app.oriel.core.extras

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Community servers' answers are kept only when they make sense. Invented answers. */
class ExtrasParseTest {
    private val all = SponsorBlock.DEFAULT_CATEGORIES

    private fun bucket(vararg segments: String) =
        """[{"videoID":"fakeVideo01","segments":[${segments.joinToString(",")}]},{"videoID":"otherVideo1","segments":[{"segment":[1,2],"category":"sponsor"}]}]"""

    @Test fun picksThisVideosSaneSegments() {
        val body = bucket(
            """{"segment":[30.5,60],"category":"sponsor"}""",
            """{"segment":[5,10],"category":"intro"}""",
        )
        assertEquals(
            listOf(Segment(5_000, 10_000, "intro"), Segment(30_500, 60_000, "sponsor")),
            SponsorBlock.parse(body, "fakeVideo01", all),
        )
    }

    @Test fun dropsSegmentsThatWouldBreakPlayback() {
        val body = bucket(
            """{"segment":[0,1e300],"category":"sponsor"}""",
            """{"segment":[-5,10],"category":"sponsor"}""",
            """{"segment":[50,40],"category":"sponsor"}""",
            """{"segment":[10,10],"category":"sponsor"}""",
            """{"segment":["x",10],"category":"sponsor"}""",
            """{"segment":[10],"category":"sponsor"}""",
            """{"segment":[1,2],"category":"not_asked_for"}""",
            """{"segment":[100,110],"category":"sponsor"}""",
        )
        assertEquals(listOf(Segment(100_000, 110_000, "sponsor")), SponsorBlock.parse(body, "fakeVideo01", all))
    }

    @Test fun capsHowManyAreKept() {
        val many = (0 until 1000).map { """{"segment":[${it * 2},${it * 2 + 1}],"category":"sponsor"}""" }
        assertTrue(SponsorBlock.parse(bucket(*many.toTypedArray()), "fakeVideo01", all).size <= 64)
    }

    @Test fun garbageIsNoSegments() {
        assertEquals(emptyList(), SponsorBlock.parse("not json", "fakeVideo01", all))
        assertEquals(emptyList(), SponsorBlock.parse("""{"videoID":"fakeVideo01"}""", "fakeVideo01", all))
    }

    @Test fun votesAreNeverNegative() {
        assertEquals(ReturnDislike.Votes(10, 2), ReturnDislike.parse("""{"likes":10,"dislikes":2}"""))
        assertEquals(ReturnDislike.Votes(null, null), ReturnDislike.parse("""{"likes":-1,"dislikes":-9}"""))
        assertNull(ReturnDislike.parse("<html>"))
    }
}
