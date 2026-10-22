package app.oriel.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailsTest {
    @Test fun avatarsAreResized() {
        assertEquals("https://yt3.ggpht.com/abc=s96-c-k-c0x00ffffff-no-rj", Thumbnails.sized("https://yt3.ggpht.com/abc=s176-c-k-c0x00ffffff-no-rj", 96))
        assertEquals("https://lh3.googleusercontent.com/x=w120-h120-l90-rj", Thumbnails.sized("https://lh3.googleusercontent.com/x=w544-h544-l90-rj", 120))
    }

    @Test fun smallVideoFramesUseTheSmallRendition() {
        val signed = "https://i.ytimg.com/vi/aV81SqB6HQg/hq720.jpg?sqp=-oaymwEcCNAFEJQDSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CLD"
        assertEquals("https://i.ytimg.com/vi/aV81SqB6HQg/mqdefault.jpg", Thumbnails.sized(signed, 320))
        assertEquals(signed, Thumbnails.sized(signed, 1080))
    }

    @Test fun imageUrlsGoOverHttps() {
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", Thumbnails.secure("http://i.ytimg.com/vi/x/hq.jpg"))
        assertEquals("https://yt3.ggpht.com/a=s88", Thumbnails.secure("//yt3.ggpht.com/a=s88"))
        assertEquals("https://yt3.ggpht.com/a=s88", Thumbnails.secure("HTTP://yt3.ggpht.com/a=s88"))
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", Thumbnails.secure("https://i.ytimg.com/vi/x/hq.jpg"))
    }

    @Test fun formatHelpers() {
        assertEquals("1.2K", Format.compact(1234))
        assertEquals("446", Format.compact(446))
        assertEquals("16M", Format.compact(16_206_000))
        assertEquals(3 * 86_400L, Format.ageSeconds("3 days ago"))
        assertEquals(2 * 604_800L, Format.ageSeconds("Streamed 2 weeks ago"))
        assertEquals(null, Format.ageSeconds("Premieres Oct 21"))
    }
}
