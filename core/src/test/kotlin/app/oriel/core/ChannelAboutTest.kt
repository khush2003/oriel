package app.oriel.core

import app.oriel.core.parse.Pages
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelAboutTest {
    // The shape of the About panel's continuation response, with placeholder content.
    private val response = """
        {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[{"aboutChannelRenderer":{"metadata":{"aboutChannelViewModel":{
          "description":"Placeholder channel about making things.\n",
          "country":"Atlantis",
          "subscriberCountText":"1.2K subscribers",
          "viewCountText":"34,567 views",
          "joinedDateText":{"content":"Joined Jan 2, 2020"},
          "videoCountText":"89 videos",
          "displayCanonicalChannelUrl":"www.youtube.com/@placeholder",
          "links":[
            {"channelExternalLinkViewModel":{"title":{"content":"Site"},"link":{"content":"example.org/home","commandRuns":[{"onTap":{"innertubeCommand":{"urlEndpoint":{"url":"https://www.youtube.com/redirect?event=channel_description&redir_token=x&q=https%3A%2F%2Fexample.org%2Fhome%3Fa%3D1"}}}}]},"favicon":{"sources":[{"url":"https://example.org/16.png"},{"url":"https://example.org/32.png"}]}}},
            {"channelExternalLinkViewModel":{"title":{"content":"Shop"},"link":{"content":"example.net"}}}
          ]
        }}}}]}}]}
    """.trimIndent()

    @Test fun parsesAboutPanel() {
        val about = Pages.channelAbout(Json.parseToJsonElement(response))
        assertEquals("Placeholder channel about making things.", about.description)
        assertEquals("Atlantis", about.country)
        assertEquals("Joined Jan 2, 2020", about.joinedText)
        assertEquals("34,567 views", about.viewsText)
        assertEquals("www.youtube.com/@placeholder", about.url)
        assertEquals(2, about.links.size)
        // The redirect wrapper is undone, so the link opens where it says.
        assertEquals("https://example.org/home?a=1", about.links[0].url)
        assertEquals("https://example.org/32.png", about.links[0].favicon)
        assertEquals("https://example.net", about.links[1].url)
    }
}
