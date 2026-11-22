package app.oriel.core

import app.oriel.core.model.Video
import app.oriel.core.parse.Pages
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistParsingTest {
    @Test fun likedPlaylistPreservesRowOrderRatherThanUploadRecency() {
        val root = Json.parseToJsonElement("""
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
              {"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                {"playlistVideoListRenderer":{"contents":[
                  {"playlistVideoRenderer":{"videoId":"olderUpload","title":{"simpleText":"Older upload"},
                    "publishedTimeText":{"simpleText":"6 years ago"}}},
                  {"playlistVideoRenderer":{"videoId":"newerUpload","title":{"simpleText":"Newer upload"},
                    "publishedTimeText":{"simpleText":"1 day ago"}}},
                  {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}
                ]}}
              ]}}]}}
            }}]}}}
        """.trimIndent())

        val page = Pages.playlist(YouTube.LIKED_VIDEOS, root)
        assertEquals(listOf("olderUpload", "newerUpload"), page.videos.map { it.id })
        assertEquals("next-page", page.continuation)
    }

    @Test fun olderPlaylistContinuationFormatKeepsOrderAndReportsTheNextPage() {
        val root = Json.parseToJsonElement("""
            {"continuationContents":{"playlistVideoListContinuation":{
              "contents":[
                {"playlistVideoRenderer":{"videoId":"nextSecond","title":{"simpleText":"First in this page"}}},
                {"playlistVideoRenderer":{"videoId":"nextFirst","title":{"simpleText":"Second in this page"}}}
              ],
              "continuations":[{"nextContinuationData":{"continuation":"final-page"}}]
            }}}
        """.trimIndent())
        val feed = Pages.continuation(root)
        assertEquals(listOf("nextSecond", "nextFirst"), feed.items.filterIsInstance<Video>().map { it.id })
        assertEquals("final-page", feed.continuation)

        val applied = Pages.continuation(Json.parseToJsonElement("""
            {"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
              {"playlistVideoRenderer":{"videoId":"final","title":{"simpleText":"Last in the playlist"}}}
            ]}}]}
        """.trimIndent()))
        assertEquals(listOf("final"), applied.items.filterIsInstance<Video>().map { it.id })
        assertNull(applied.continuation)
    }
}
