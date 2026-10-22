package app.oriel.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** The launch-time check agrees with the client's own, for invented cookies. */
class SignedInCookieTest {
    private val cookies = listOf(
        null,
        "",
        "PREF=hl=en",
        "SAPISID=",
        "SAPISID=fake1; PREF=hl=en",
        " __Secure-3PAPISID=fake2 ;HSID=x",
        "PREF=hl=en; __Secure-1PAPISID=fake3",
        "SAPISID=fa\nke",
    )

    @Test fun agreesWithTheClient() {
        val yt = YouTube()
        for (cookie in cookies) {
            yt.cookie = cookie
            assertEquals(yt.isSignedIn, YouTube.isSignedInCookie(cookie), "for ${cookie?.replace("\n", "\\n")}")
        }
    }
}
