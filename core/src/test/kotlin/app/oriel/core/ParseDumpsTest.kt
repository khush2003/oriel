package app.oriel.core

import app.oriel.core.model.Channel
import app.oriel.core.model.Playlist
import app.oriel.core.model.Post
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import app.oriel.core.parse.Pages
import app.oriel.core.parse.Text
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses saved InnerTube responses (see /tmp/ytweb-tools), so parser changes
 * can be checked without the network. Skips when the dumps aren't present.
 */
class ParseDumpsTest {
    private val dir = File(System.getProperty("oriel.dumps") ?: "/tmp/ytweb")
    private fun dump(name: String) = File(dir, "$name.json").takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()) }

    @Test fun text() {
        assertEquals(820, Text.duration("13:40"))
        assertEquals(13091, Text.duration("3:38:11"))
        assertNull(Text.duration("LIVE"))
        assertEquals("https://yt3.ggpht.com/x", Text.absolute("//yt3.ggpht.com/x"))
    }

    @Test fun search() {
        val root = dump("search_lofi") ?: return
        val page = Pages.search(root)
        val videos = page.feed.items.filterIsInstance<Video>()
        println(page.feed.shelves.map { "${it.title}(${it.kind},${it.items.size})" })
        assertTrue(videos.size >= 15)
        assertTrue(page.feed.shelves.any { it.kind == ShelfKind.SHORTS })
        assertNotNull(page.feed.continuation)
        assertTrue(page.filters.size >= 15)
        val plain = videos.first { it.durationSec != null }
        assertNotNull(plain.durationSec); assertNotNull(plain.channel?.id); assertNotNull(plain.viewsText)
    }

    @Test fun searchChannels() {
        val root = dump("search_channels") ?: return
        val ch = Pages.search(root).feed.items.filterIsInstance<Channel>()
        assertTrue(ch.size >= 10)
        assertTrue(ch.first().handle?.startsWith("@") == true)
        assertTrue(ch.first().subscribersText?.contains("subscriber") == true)
    }

    @Test fun topics() {
        for (name in listOf("topic_music", "topic_news", "topic_sports", "topic_live", "topic_learning")) {
            val root = dump(name) ?: continue
            val feed = Pages.feed(root)
            println("$name: ${feed.shelves.map { "${it.title}(${it.kind},${it.items.size})" }}")
            assertTrue(feed.items.size >= 10, name)
            assertTrue(feed.shelves.all { it.title != null }, name)
        }
    }

    @Test fun channel() {
        val root = dump("channel_ltt") ?: return
        val page = Pages.channel("UCXuqSBlHAE6Xw-yeJA0Tunw", root)
        assertEquals("Linus Tech Tips", page.channel.title)
        assertEquals("@LinusTechTips", page.channel.handle)
        assertNotNull(page.banner)
        assertEquals("Home", page.selectedTab)
        assertTrue(page.tabs.size >= 8)
        println(page.feed.shelves.map { "${it.title}(${it.kind},${it.items.size})" })
        assertTrue(page.feed.shelves.any { it.kind == ShelfKind.SHORTS })
        assertTrue(page.feed.items.any { it is Post })
        assertTrue(page.feed.items.any { it is Channel })

        val videos = Pages.feed(dump("ch_videos") ?: return)
        assertEquals(30, videos.items.size)
        assertTrue(videos.chips.map { it.title }.containsAll(listOf("Latest", "Popular")))
        assertNotNull(videos.continuation)
        val shorts = Pages.feed(dump("ch_shorts") ?: return)
        assertEquals(ShelfKind.SHORTS_GRID, shorts.shelves.single().kind)
        val playlists = Pages.feed(dump("ch_playlists") ?: return).items
        assertTrue(playlists.isNotEmpty() && playlists.all { it is Playlist })
        val posts = Pages.feed(dump("ch_posts") ?: return).items.filterIsInstance<Post>()
        assertTrue(posts.isNotEmpty() && posts.any { it.images.isNotEmpty() })
    }

    @Test fun watch() {
        val root = dump("next_ltt") ?: return
        val page = Pages.watch("aV81SqB6HQg", root)
        assertTrue(page.video.title.isNotBlank())
        assertEquals("Linus Tech Tips", page.video.channel?.name)
        assertEquals(10, page.chapters.size)
        assertEquals("1.3K", page.commentsCount)
        assertNotNull(page.commentsToken)
        // Comment sort lives on the watch page's panel; the comments response no longer carries it.
        assertEquals(listOf("Top" to true, "Newest" to false), page.commentSort.map { it.title to it.selected })
        assertEquals(20, page.related.size)
        assertNotNull(page.relatedContinuation)
        assertNotNull(page.likeCount)

        val short = Pages.watch("short", dump("next_short") ?: return)
        assertNotNull(short.video.channel, "collaboration owner falls back to the dialog")
    }

    @Test fun playlist() {
        val root = dump("playlist") ?: return
        val page = Pages.playlist("PLT4ClbJt7VFM", root)
        assertEquals("Datacenter Tours", page.playlist.title)
        assertEquals("Linus Tech Tips", page.playlist.owner?.name)
        assertEquals(3, page.videos.size)
    }

    @Test fun shortsSequence() {
        // 15 entries, 5 of them ads (their endpoints carry adClientParams), which are dropped.
        val batch = Pages.shortsSequence(dump("reel_seq2") ?: return)
        assertEquals(10, batch.ids.size)
        assertNotNull(batch.continuation)
    }

    @Test fun suggestions() {
        val s = Pages.suggestions("""window.google.ac.h(["mine",[["minecraft",0,[512]],["minecraft song",0,[512]]],{"k":1}])""")
        assertEquals(listOf("minecraft", "minecraft song"), s)
    }
}
