package app.oriel.core

import app.oriel.core.model.Channel
import app.oriel.core.model.Playlist
import app.oriel.core.model.Video
import app.oriel.core.parse.Items
import app.oriel.core.parse.Pages
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YouTube answers in the language asked for, so the parsers go by a page's shape, not its
 * English words. The pages here are invented, worded in German and Japanese.
 */
class LocalizedParseTest {
    private fun lockup(rows: String, overlays: String = "") = Json.parseToJsonElement(
        """
        {"lockupViewModel":{"contentId":"AAAAAAAAAAA","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
          "contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://example.org/t.jpg"}]},"overlays":[$overlays]}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"Ein erfundenes Video"},
            "metadata":{"contentMetadataViewModel":{"metadataRows":[$rows]}}}}}}
        """,
    )

    private val channelRow = """{"metadataParts":[{"text":{"content":"Erfundener Kanal","commandRuns":[{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCinvented"}}}}]}}]}"""

    @Test fun lockupViewsAndAgeByPosition() {
        val v = Items.item(lockup("$channelRow,{\"metadataParts\":[{\"text\":{\"content\":\"1234 Aufrufe\"}},{\"text\":{\"content\":\"vor 3 Tagen\"}}]}")) as Video
        assertEquals("Erfundener Kanal", v.channel?.name)
        assertEquals("UCinvented", v.channel?.id)
        assertEquals("1234 Aufrufe", v.viewsText)
        assertEquals("vor 3 Tagen", v.publishedText)
        assertFalse(v.isLive)
        assertFalse(v.isUpcoming)
    }

    @Test fun lockupOnAChannelPageHasNoChannelRow() {
        val v = Items.item(lockup("{\"metadataParts\":[{\"text\":{\"content\":\"5万 回視聴\"}},{\"text\":{\"content\":\"2 日前\"}}]}")) as Video
        assertNull(v.channel)
        assertEquals("5万 回視聴", v.viewsText)
        assertEquals("2 日前", v.publishedText)
    }

    @Test fun lockupLiveByBadgeStyle() {
        val live = """{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"LIVE","badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"}}]}}"""
        val v = Items.item(lockup("$channelRow,{\"metadataParts\":[{\"text\":{\"content\":\"812 Zuschauer\"}}]}", live)) as Video
        assertTrue(v.isLive)
        assertEquals("812 Zuschauer", v.viewsText)
        assertNull(v.publishedText)
    }

    @Test fun lockupUpcomingByEventData() {
        val json = Json.parseToJsonElement(
            """
            {"lockupViewModel":{"contentId":"BBBBBBBBBBB","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
              "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"BBBBBBBBBBB"}}}}},
              "upcomingEventData":{"startTime":"1790000000"},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Bald"},
                "metadata":{"contentMetadataViewModel":{"metadataRows":[$channelRow,{"metadataParts":[{"text":{"content":"Geplant für 21.10.26"}}]}]}}}}}}
            """,
        )
        val v = Items.item(json) as Video
        assertTrue(v.isUpcoming)
        assertEquals("Geplant für 21.10.26", v.publishedText)
        assertNull(v.viewsText)
    }

    @Test fun lockupInEnglishStillReads() {
        val v = Items.item(lockup("$channelRow,{\"metadataParts\":[{\"text\":{\"content\":\"1.2K watching\"}}]}")) as Video
        assertTrue(v.isLive)
        assertEquals("1.2K watching", v.viewsText)
    }

    @Test fun channelRendererCountsByWhereTheHandleIs() {
        val withHandle = Items.item(
            Json.parseToJsonElement(
                """{"channelRenderer":{"channelId":"UCinvented","title":{"simpleText":"Erfundener Kanal"},
                   "subscriberCountText":{"simpleText":"@erfunden"},"videoCountText":{"simpleText":"12.300 Abonnenten"}}}""",
            ),
        ) as Channel
        assertEquals("@erfunden", withHandle.handle)
        assertEquals("12.300 Abonnenten", withHandle.subscribersText)
        assertNull(withHandle.videosText)

        val older = Items.item(
            Json.parseToJsonElement(
                """{"channelRenderer":{"channelId":"UCinvented","title":{"simpleText":"Erfundener Kanal"},
                   "subscriberCountText":{"simpleText":"12.300 Abonnenten"},"videoCountText":{"simpleText":"321 Videos"}}}""",
            ),
        ) as Channel
        assertNull(older.handle)
        assertEquals("12.300 Abonnenten", older.subscribersText)
        assertEquals("321 Videos", older.videosText)
    }

    @Test fun playlistLockupOwnerByLink() {
        fun playlist(owner: String) = Items.item(
            Json.parseToJsonElement(
                """
                {"lockupViewModel":{"contentId":"PLinvented","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                  "metadata":{"lockupMetadataViewModel":{"title":{"content":"Erfundene Liste"},
                    "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[$owner]}]}}}}}}
                """,
            ),
        ) as Playlist
        val linked = playlist("""{"text":{"content":"Erfundener Kanal","commandRuns":[{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCinvented"}}}}]}}""")
        assertEquals("Erfundener Kanal", linked.owner?.name)
        assertEquals("UCinvented", linked.owner?.id)
        assertNull(playlist("""{"text":{"content":"Playlist"}}""").owner)
    }

    @Test fun playlistPageOwnerFromTheLinkedRun() {
        val root = Json.parseToJsonElement(
            """
            {"header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{
              "title":{"dynamicTextViewModel":{"text":{"content":"Erfundene Liste"}}},
              "metadata":{"contentMetadataViewModel":{"metadataRows":[
                {"metadataParts":[{"avatarStack":{"avatarStackViewModel":{"text":{"content":"von Erfundener Kanal",
                  "commandRuns":[{"startIndex":4,"length":16,"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCinvented"}}}}]}}}}]},
                {"metadataParts":[{"text":{"content":"Playlist"}},{"text":{"content":"42 Videos"}}]}
              ]}}}}}}}
            """,
        )
        val page = Pages.playlist("PLinvented", root)
        assertEquals("Erfundener Kanal", page.playlist.owner?.name)
        assertEquals("UCinvented", page.playlist.owner?.id)
        assertEquals(listOf("42 Videos"), page.metadata)
    }

    @Test fun channelHeaderCountsByPosition() {
        val root = Json.parseToJsonElement(
            """
            {"header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{
              "title":{"dynamicTextViewModel":{"text":{"content":"Erfundener Kanal"}}},
              "metadata":{"contentMetadataViewModel":{"metadataRows":[
                {"metadataParts":[{"text":{"content":"@erfunden"}}]},
                {"metadataParts":[{"text":{"content":"12.300 Abonnenten"}},{"text":{"content":"321 Videos"}}]}
              ]}}}}}}}
            """,
        )
        val c = Pages.channel("UCinvented", root).channel
        assertEquals("@erfunden", c.handle)
        assertEquals("12.300 Abonnenten", c.subscribersText)
        assertEquals("321 Videos", c.videosText)
    }
}
