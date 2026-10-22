package app.oriel.core.security

import app.oriel.core.YouTube
import app.oriel.core.stream.SegmentIndex
import app.oriel.core.stream.StreamResolver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inputs from the network can't reach outside the app's files, hang a parser, or redirect a fetch. */
class InputHardeningTest {
    @Test fun playerCacheNamesStayInTheirDirectory() {
        val dir = File("/tmp/players")
        assertEquals(File(dir, "0123456789abcdef0123456789abcdef01234567"), StreamResolver.playerFile(dir, "0123456789abcdef0123456789abcdef01234567"))
        for (key in listOf("../library.json", "..", ".", "a/b", "", "x".repeat(200), "/etc/passwd")) {
            assertNull(StreamResolver.playerFile(dir, key), key)
        }
    }

    @Test fun anMp4BoxSizedToWrapDoesntLoopForever() {
        // A 64-bit box size of exactly 2^32 wrapped to a step of 0 as an Int.
        val box = byteArrayOf(0, 0, 0, 1) + "ftyp".toByteArray() + byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + ByteArray(32)
        var result: Any? = "unset"
        val parser = Thread { result = SegmentIndex.parse(box) }.apply { isDaemon = true; start(); join(5_000) }
        assertFalse(parser.isAlive, "parser is still running")
        assertNull(result)
    }

    @Test fun captionsAreFetchedFromYouTubeOnly() {
        assertTrue(YouTube.isYouTubeHttps("https://www.youtube.com/api/timedtext?v=x&lang=en"))
        for (url in listOf(
            "http://www.youtube.com/api/timedtext",
            "https://www.youtube.com.example.org/api/timedtext",
            "https://user" + "@" + "www.youtube.com/api/timedtext", // split so it doesn't read as an email address
            "https://www.youtube.com:8080/api/timedtext",
            "file:///data/data/app.oriel/shared_prefs/account.xml",
            "/api/timedtext",
        )) assertFalse(YouTube.isYouTubeHttps(url), url)
    }
}
