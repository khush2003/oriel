package app.oriel.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The session's headers reach YouTube's and Google's hosts over HTTPS, and nowhere else. Invented values. */
class CredentialGuardTest {
    private fun signed(url: String) = Request.Builder().url(url)
        .header("Cookie", "SAPISID=fake1; PREF=hl=en")
        .header("Authorization", "SAPISIDHASH 1_fake")
        .header("X-Goog-AuthUser", "0")
        .header("User-Agent", "test")
        .build()

    @Test fun keepsCredentialsForYouTubeAndGoogle() {
        for (url in listOf(
            "https://www.youtube.com/youtubei/v1/browse",
            "https://music.youtube.com/youtubei/v1/player",
            "https://rr3---sn-fake.googlevideo.com/videoplayback?id=1",
            "https://suggestqueries-clients6.youtube.com/complete/search",
            "https://accounts.google.com/x",
        )) {
            val request = signed(url)
            assertSame(request, CredentialGuard.guard(request), url)
        }
    }

    @Test fun stripsCredentialsEverywhereElse() {
        for (url in listOf(
            "https://sponsor.ajay.app/api/skipSegments/abcd",
            "https://returnyoutubedislikeapi.com/votes?videoId=x",
            "https://cdn.jsdelivr.net/gh/x/y@z/a.json",
            "https://i.ytimg.com.attacker.example/vi/x/hq.jpg",
            "https://notyoutube.com/",
            "https://youtube.com.example.org/",
            // Not HTTPS, or not the HTTPS port: anyone on the path could read it.
            "http://www.youtube.com/youtubei/v1/browse",
            "https://www.youtube.com:8443/",
        )) {
            val guarded = CredentialGuard.guard(signed(url))
            assertNull(guarded.header("Cookie"), url)
            assertNull(guarded.header("Authorization"), url)
            assertNull(guarded.header("X-Goog-AuthUser"), url)
            assertEquals("test", guarded.header("User-Agent"), url)
        }
    }

    @Test fun trustIsByWholeDomainLabels() {
        assertTrue(CredentialGuard.isTrusted("https://youtube.com/".toHttpUrl()))
        assertTrue(CredentialGuard.isTrusted("https://WWW.YouTube.com/".toHttpUrl()))
        assertFalse(CredentialGuard.isTrusted("https://evilyoutube.com/".toHttpUrl()))
        assertFalse(CredentialGuard.isTrusted("https://googlevideo.com.evil.example/".toHttpUrl()))
    }
}
