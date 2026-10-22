package app.oriel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    private fun v(s: String) = SemVer.parse(s) ?: error("not SemVer: $s")

    @Test fun precedenceFollowsSemVer() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-beta.2", "1.0.0-beta.10", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "1.10.0", "2.0.0")
        ordered.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", v(a) < v(b)) }
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).map(::v).sorted().map(SemVer::toString))
    }

    @Test fun numericIdentifiersSortBelowWordsAndByValue() {
        assertTrue(v("1.0.0-1") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-beta.9") < v("1.0.0-beta.11"))
        assertTrue(v("1.0.0-beta") < v("1.0.0-beta.1"))
        assertTrue(v("1.0.0-99999999999999999999") < v("1.0.0-100000000000000000000"))
    }

    @Test fun tagsAndBuildMetadata() {
        assertEquals(v("1.0.0-beta.3"), v("v1.0.0-beta.3"))
        assertEquals(0, v("1.0.0+build.5").compareTo(v("1.0.0")))
        assertTrue(v("1.0.0-beta.3").isPreRelease)
        assertFalse(v("1.0.0").isPreRelease)
    }

    @Test fun rejectsWhatIsntStrictSemVer() {
        listOf("", "1", "1.0", "01.0.0", "1.01.0", "1.0.0-", "1.0.0-beta.01", "1.0.0.0", "latest", "1.0.0-be ta", "0.9.01-alpha").forEach {
            assertNull(it, SemVer.parse(it))
        }
    }
}
