package app.oriel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page's key, whatever it holds, names a file inside the cache directory. */
class PageDiskCacheKeyTest {
    @Test fun plainKeysKeepTheirNames() {
        assertEquals("home-true.json", PageDiskCache.fileName("home-true"))
        assertEquals("inbox--12345.json", PageDiskCache.fileName("inbox--12345"))
    }

    @Test fun otherKeysAreHashedIntoOneName() {
        for (key in listOf("../../shared_prefs/account", "a/b", "", "x".repeat(300), "channel:UC fake")) {
            val name = PageDiskCache.fileName(key)
            assertTrue(name, name.startsWith("h-") && name.endsWith(".json"))
            assertFalse(name, name.contains('/') || name.contains(".."))
        }
    }
}
