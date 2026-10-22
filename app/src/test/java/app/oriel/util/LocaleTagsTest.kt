package app.oriel.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleTagsTest {
    @Test
    fun languagesTakeYouTubesForms() {
        assertEquals("de", LocaleTags.hl("de-AT"))
        assertEquals("en", LocaleTags.hl("en-US"))
        assertEquals("en-GB", LocaleTags.hl("en-GB"))
        assertEquals("es-419", LocaleTags.hl("es-MX"))
        assertEquals("es", LocaleTags.hl("es-ES"))
        assertEquals("es-US", LocaleTags.hl("es-US"))
        assertEquals("pt", LocaleTags.hl("pt-BR"))
        assertEquals("pt-PT", LocaleTags.hl("pt-PT"))
        assertEquals("zh-TW", LocaleTags.hl("zh-Hant"))
        assertEquals("zh-TW", LocaleTags.hl("zh-Hant-TW"))
        assertEquals("zh-HK", LocaleTags.hl("zh-HK"))
        assertEquals("zh-CN", LocaleTags.hl("zh-Hans"))
        assertEquals("sr-Latn", LocaleTags.hl("sr-Latn-RS"))
        assertEquals("sr", LocaleTags.hl("sr-RS"))
        assertEquals("iw", LocaleTags.hl("he-IL"))
        assertEquals("en", LocaleTags.hl(""))
    }

    @Test
    fun countriesAreTwoLetters() {
        assertEquals("DE", LocaleTags.gl("DE"))
        assertEquals("FR", LocaleTags.gl("fr"))
        assertEquals("US", LocaleTags.gl("419"))
        assertEquals("US", LocaleTags.gl(""))
        assertEquals("US", LocaleTags.gl(null))
    }
}
