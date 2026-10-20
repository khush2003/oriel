package app.oriel.core

import app.oriel.core.parse.Pages
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxTest {
    // The shape of the bell menu's first page, with placeholder content.
    private val response = """
        {"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[{"multiPageMenuNotificationSectionRenderer":{"items":[
          {"notificationRenderer":{"notificationId":"n1","read":false,
            "shortMessage":{"simpleText":"Placeholder Channel uploaded: A placeholder video"},
            "sentTimeText":{"simpleText":"2 hours ago"},
            "thumbnail":{"thumbnails":[{"url":"//example.org/a48.jpg"},{"url":"//example.org/a88.jpg"}]},
            "videoThumbnail":{"thumbnails":[{"url":"https://example.org/v.jpg"}]},
            "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=AAAAAAAAAAA"}},"watchEndpoint":{"videoId":"AAAAAAAAAAA"}}}},
          {"notificationRenderer":{"notificationId":"n2","read":true,
            "shortMessage":{"runs":[{"text":"Someone replied: "},{"text":"placeholder"}]},
            "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/channel/UCplaceholder"}}}}},
          {"continuationItemRenderer":{"continuationEndpoint":{"getNotificationMenuEndpoint":{"ctoken":"NEXT"}}}}
        ]}}]}}}}]}
    """.trimIndent()

    @Test fun parsesInbox() {
        val page = Pages.inbox(Json.parseToJsonElement(response))
        assertEquals(2, page.items.size)
        val first = page.items[0]
        assertEquals("AAAAAAAAAAA", first.videoId)
        assertEquals("https://example.org/a88.jpg", first.avatar)
        assertEquals("2 hours ago", first.sentText)
        assertTrue(!first.read)
        val second = page.items[1]
        assertEquals("Someone replied: placeholder", second.message)
        assertNull(second.videoId)
        assertEquals("/channel/UCplaceholder", second.url)
        assertEquals("NEXT", page.continuation)
    }
}
