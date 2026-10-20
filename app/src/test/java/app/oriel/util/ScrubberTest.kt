package app.oriel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every value here is invented; none is a real cookie, token or address. */
class ScrubberTest {
    private fun scrub(s: String) = Scrubber.scrub(s)

    @Test fun urlQueriesAreCut() {
        assertEquals(
            "HTTP 403 https://rr3---sn-fake.googlevideo.com/videoplayback?…",
            scrub("HTTP 403 https://rr3---sn-fake.googlevideo.com/videoplayback?expire=1&ip=203.0.113.9&sig=AbC"),
        )
    }

    @Test fun aWatchLinkKeepsItsVideoId() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", scrub("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&…", scrub("https://www.youtube.com/watch?v=dQw4w9WgXcQ&pp=fake&t=30"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&…", scrub("https://www.youtube.com/watch?list=PLfake&v=dQw4w9WgXcQ"))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", scrub("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("oriel://video/dQw4w9WgXcQ failed", scrub("oriel://video/dQw4w9WgXcQ failed"))
    }

    @Test fun cookieAndAuthHeadersAreRemoved() {
        assertEquals("Cookie: [removed]", scrub("Cookie: SAPISID=fake.value; __Secure-3PSID=fake.other; LOGIN_INFO=fake:info"))
        assertEquals("authorization: [removed]\nnext line", scrub("authorization: SAPISIDHASH 1700000000_fake.hash\nnext line"))
        assertEquals("headers {Authorization=[removed]", scrub("headers {Authorization=Bearer fake.token, X-Origin=https://www.youtube.com}"))
        assertEquals("X-Goog-Visitor-Id: [removed]", scrub("X-Goog-Visitor-Id: Cgtfake.visitor"))
    }

    @Test fun bareCookiePairsAreRemoved() {
        assertEquals(
            "jar: SAPISID=[removed]; __Secure-1PSIDTS=[removed]; LOGIN_INFO=[removed]; PREF=f6=40000000",
            scrub("jar: SAPISID=fake.sapisid; __Secure-1PSIDTS=fake.ts; LOGIN_INFO=fake:login; PREF=f6=40000000"),
        )
        assertEquals("sent SAPISIDHASH [removed] to YouTube", scrub("sent SAPISIDHASH 1700000000_fake.hash to YouTube"))
    }

    @Test fun emailAddressesAreRemoved() {
        assertEquals("signed in as [email] (2 accounts)", scrub("signed in as some.one+yt@example.com (2 accounts)"))
        assertEquals("[email]", scrub("person@example.org"))
    }

    @Test fun longTokensAreRemoved() {
        val visitor = "Cgt" + "fAkEvIsItOrDaTa0123456789".repeat(3) + "%3D%3D"
        assertEquals("visitorData=[token] then more", scrub("visitorData=$visitor then more"))
        val continuation = "4qmFsgK" + "Q0FFU0FnZ0IlM0Q".repeat(5)
        assertEquals("continuation [token]", scrub("continuation $continuation"))
    }

    @Test fun stackTracesAndIdsSurvive() {
        val trace = """
            java.lang.IllegalStateException: Expected a video, got null for dQw4w9WgXcQ
            	at app.oriel.ui.screens.FeedModel${'$'}reload${'$'}1.invokeSuspend(FeedModel.kt:131)
            	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
            Caused by: java.lang.NullPointerException: java.lang.Object@5e2de80c
        """.trimIndent()
        assertEquals(trace, scrub(trace))
    }

    @Test fun nothingSensitiveSurvivesAMixedLine() {
        val line = "POST https://www.youtube.com/youtubei/v1/player?key=fake&prettyPrint=false Cookie: SAPISID=fake.one from someone@example.com"
        val out = scrub(line)
        assertFalse(out, out.contains("SAPISID=fake"))
        assertFalse(out, out.contains("@example.com"))
        assertFalse(out, out.contains("key=fake"))
        assertTrue(out, out.startsWith("POST https://www.youtube.com/youtubei/v1/player?…"))
    }

    @Test fun scrubbingTwiceChangesNothing() {
        val once = scrub("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3 Cookie: SID=fake.sid someone@example.com " + "A".repeat(80))
        assertEquals(once, scrub(once))
    }
}
