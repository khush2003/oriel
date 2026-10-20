package app.oriel.core

import io.ktor.client.request.get
import app.oriel.core.extras.ReturnDislike
import app.oriel.core.extras.SponsorBlock
import app.oriel.core.model.Channel
import app.oriel.core.model.Playlist
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.Video
import app.oriel.core.stream.StreamQuality
import app.oriel.core.stream.StreamRequest
import app.oriel.core.stream.StreamResolver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Hits the real YouTube; runs only with -Plive=true. */
class LiveApiTest {
    private val live = System.getProperty("oriel.live") == "true"
    private val yt by lazy { YouTube().apply { setLocale("US", "en") } }

    private fun liveTest(block: suspend () -> Unit) = runBlocking { if (live) block() }

    private fun Video.line() = "$title | ${channel?.name} | $viewsText | $publishedText | ${durationSec}s live=$isLive short=$isShort"

    @Test fun homeAndTopics() = liveTest {
        val home = yt.home()
        println("home signed-out: message=${home.message} shelves=${home.shelves.size}")
        for (topic in listOf(Topic.MUSIC, Topic.NEWS, Topic.SPORTS, Topic.LIVE)) {
            val feed = yt.topic(topic)
            println("topic $topic: ${feed.shelves.map { "${it.title}(${it.kind},${it.items.size})" }}")
            assertTrue(feed.items.size >= 10, "$topic has items")
        }
        val music = yt.topic(Topic.MUSIC).items.filterIsInstance<Video>()
        music.take(3).forEach { println("  " + it.line()) }
        assertTrue(music.all { it.thumbnail != null })
    }

    @Test fun search() = liveTest {
        val page = yt.search("lofi hip hop")
        val videos = page.feed.items.filterIsInstance<Video>()
        println("search: ${page.feed.shelves.map { "${it.title}(${it.kind},${it.items.size})" }} chips=${page.feed.chips.map { it.title }} filters=${page.filters.size}")
        videos.take(4).forEach { println("  " + it.line()) }
        assertTrue(videos.size >= 10)
        assertTrue(videos.filter { it.durationSec != null }.take(5).all { it.channel?.id != null && it.viewsText != null })
        assertTrue(page.filters.any { it.label == "Videos" && it.params != null })
        val token = assertNotNull(page.feed.continuation)
        val more = yt.searchMore(token)
        println("search page 2: ${more.items.size} items, cont=${more.continuation != null}")
        assertTrue(more.items.size >= 10)

        val channels = yt.search("linus tech tips", page.filters.first { it.label == "Channels" }.params)
        val ch = channels.feed.items.filterIsInstance<Channel>().first()
        println("channel result: ${ch.title} ${ch.handle} ${ch.subscribersText} ${ch.avatar}")
        assertTrue(ch.subscribersText?.contains("subscriber") == true)
        assertTrue(ch.avatar?.startsWith("https:") == true)

        val chip = page.feed.chips.firstOrNull { !it.selected && it.token != null }
        if (chip != null) {
            val reloaded = yt.searchMore(chip.token!!)
            println("chip ${chip.title}: ${reloaded.items.size} items")
            assertTrue(reloaded.items.isNotEmpty())
        }

        val suggestions = yt.suggestions("minecr")
        println("suggestions: $suggestions")
        assertTrue(suggestions.size >= 5 && suggestions.all { it.isNotBlank() })
    }

    @Test fun watchCommentsRelated() = liveTest {
        val page = yt.watch("aV81SqB6HQg")
        println("watch: ${page.video.title} | ${page.video.channel} | views=${page.viewCount} likes=${page.likeCount} date=${page.date} subs=${page.subscribersText}")
        println("  chapters=${page.chapters.map { "${it.title}@${it.startMs}" }}")
        println("  comments=${page.commentsCount} related=${page.related.size} relatedCont=${page.relatedContinuation != null}")
        assertTrue(page.video.title.isNotBlank())
        assertNotNull(page.video.channel?.id)
        assertNotNull(page.likeCount)
        assertTrue(page.description?.isNotBlank() == true)
        assertTrue(page.chapters.size >= 3)
        assertTrue(page.related.size >= 10)

        val comments = yt.comments(assertNotNull(page.commentsToken))
        println("comments: ${comments.countText} sort=${comments.sort.map { it.title }} n=${comments.comments.size}")
        comments.comments.take(2).forEach { println("  ${it.author} · ${it.published} · likes=${it.likes} replies=${it.replyCount}") }
        assertTrue(comments.comments.size >= 10)
        val withReplies = comments.comments.firstOrNull { it.repliesToken != null }
        if (withReplies != null) {
            val replies = yt.comments(withReplies.repliesToken!!)
            println("replies: ${replies.comments.size}")
            assertTrue(replies.comments.isNotEmpty() && replies.comments.all { it.isReply })
        }
        val more = yt.comments(assertNotNull(comments.continuation))
        assertTrue(more.comments.isNotEmpty())

        val related = yt.relatedMore(assertNotNull(page.relatedContinuation))
        println("related page 2: ${related.items.size}")
        assertTrue(related.items.isNotEmpty())
    }

    @Test fun channelTabs() = liveTest {
        val page = yt.channel("UCXuqSBlHAE6Xw-yeJA0Tunw")
        println("channel: ${page.channel} banner=${page.banner != null}")
        println("  tabs=${page.tabs.map { it.title }}")
        println("  home shelves=${page.feed.shelves.map { "${it.title}(${it.kind},${it.items.size})" }}")
        assertEquals("Linus Tech Tips", page.channel.title)
        assertTrue(page.channel.subscribersText?.contains("subscriber") == true)
        assertTrue(page.tabs.any { it.title == "Videos" })
        assertTrue(page.feed.shelves.size >= 3)

        val videos = yt.channel(page.channel.id, page.tabs.first { it.title == "Videos" }.params)
        val list = videos.feed.items.filterIsInstance<Video>()
        println("videos tab: ${list.size} chips=${videos.feed.chips.map { it.title }}")
        list.take(2).forEach { println("  " + it.line()) }
        assertTrue(list.size >= 20 && list.all { it.durationSec != null || it.isLive || it.isUpcoming })
        val more = yt.more(assertNotNull(videos.feed.continuation))
        println("videos page 2: ${more.items.size}")
        assertTrue(more.items.size >= 20)
        val popular = videos.feed.chips.firstOrNull { it.title == "Popular" }?.token
        if (popular != null) assertTrue(yt.more(popular).items.isNotEmpty())

        val shorts = yt.channel(page.channel.id, page.tabs.first { it.title == "Shorts" }.params)
        println("shorts tab: ${shorts.feed.shelves.map { "${it.kind},${it.items.size}" }}")
        assertEquals(ShelfKind.SHORTS_GRID, shorts.feed.shelves.first().kind)

        val playlists = yt.channel(page.channel.id, page.tabs.first { it.title == "Playlists" }.params)
        val pls = playlists.feed.items.filterIsInstance<Playlist>()
        println("playlists tab: ${pls.size} first=${pls.firstOrNull()}")
        assertTrue(pls.isNotEmpty())

        page.tabs.firstOrNull { it.title == "Posts" }?.let { tab ->
            val posts = yt.channel(page.channel.id, tab.params).feed.items
            println("posts tab: ${posts.size} first=${posts.firstOrNull()?.title?.take(40)}")
            assertTrue(posts.isNotEmpty())
        }
    }

    @Test fun playlist() = liveTest {
        val page = yt.playlist("PLT4ClbJt7VFM")
        println("playlist: ${page.playlist} meta=${page.metadata} videos=${page.videos.size}")
        assertTrue(page.videos.isNotEmpty())
        val big = yt.playlist("PL8mG-RkN2uTw7PhlnAr4pZZz2QubIbujH")
        println("big playlist: ${big.playlist.title} ${big.videos.size} cont=${big.continuation != null}")
        assertTrue(big.videos.size >= 50)
        val more = yt.more(assertNotNull(big.continuation)).items.filterIsInstance<Video>()
        println("big playlist page 2: ${more.size}")
        assertTrue(more.isNotEmpty())
    }

    @Test fun shorts() = liveTest {
        val first = yt.shorts()
        val second = yt.shorts(first.continuation)
        println("shorts: ${first.ids.size} then ${second.ids.size}")
        assertTrue(second.ids.size >= 5)
        val info = yt.shortInfo(second.ids.first())
        println("short info: $info")
        assertNotNull(info.title)
        assertNotNull(info.channel)
    }

    @Test fun streamsAndCaptions() = liveTest {
        val resolver = StreamResolver(yt)
        val t0 = System.currentTimeMillis()
        // From a flagged IP YouTube bot-checks most videos ("Sign in to confirm you're not a bot");
        // this one stays reachable, so it carries the stream checks.
        val v = resolver.resolve(StreamRequest("dQw4w9WgXcQ", StreamQuality.HIGH, videoHeight = 720))
        println("stream: $v in ${System.currentTimeMillis() - t0}ms heights=${v.availableHeights} tracking=${v.playbackUrl != null}")
        assertEquals(720, v.video?.height)
        assertTrue(v.availableHeights.size >= 3)
        assertNotNull(v.playbackUrl)
        val audio = resolver.resolve(StreamRequest("dQw4w9WgXcQ", StreamQuality.HIGH))
        println("audio only: $audio")
        assertTrue(audio.video == null)

        val liveId = yt.topic(Topic.LIVE).items.filterIsInstance<Video>().firstOrNull { it.isLive }?.id
        if (liveId != null) {
            val l = runCatching { resolver.resolve(StreamRequest(liveId, StreamQuality.HIGH, videoHeight = 720)) }
            println("live $liveId: ${l.map { "hls=${it.isHls} live=${it.isLive}" }.getOrElse { "unavailable here: ${it.message}" }}")
            l.getOrNull()?.let { assertTrue(it.isHls) }
        }

        val storyboard = yt.storyboard("dQw4w9WgXcQ")
        println("storyboard: ${storyboard?.levels?.map { "${it.width}x${it.height}/${it.count}" }}")
        assertNotNull(storyboard)
        val frame = storyboard.frameAt(61_000)
        val sheet = yt.http.get(frame.url)
        assertEquals(200, sheet.status.value)
        assertTrue(sheet.headers["Content-Type"].orEmpty().startsWith("image/"))

        val tracks = yt.captionTracks("dQw4w9WgXcQ")
        println("captions: ${tracks.map { "${it.languageCode}${if (it.isAuto) "(auto)" else ""}" }}")
        if (tracks.isNotEmpty()) {
            val cues = yt.captionCues(tracks.first())
            println("cues: ${cues.size}")
            assertTrue(cues.isNotEmpty())
        }
    }

    @Test fun extras() = liveTest {
        val segments = SponsorBlock(yt.http).segments("aV81SqB6HQg")
        println("sponsorblock: ${segments.map { "${it.category}@${it.startMs / 1000}-${it.endMs / 1000}" }}")
        val votes = ReturnDislike(yt.http).votes("aV81SqB6HQg")
        println("ryd: $votes")
        assertNotNull(votes?.dislikes)
    }
}
